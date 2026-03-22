package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.TradeLogEntity;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.core.risk.RiskCheckResult;
import com.cryptoautotrader.core.risk.RiskConfig;
import com.cryptoautotrader.core.risk.RiskEngine;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.AccountResponse;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 주문 실행 엔진 — 6단계 상태 머신 기반 주문 처리
 *
 * 상태 전이:
 *   PENDING → SUBMITTED → FILLED
 *                       → PARTIAL_FILLED → FILLED
 *                       → CANCELLED
 *            → FAILED
 *
 * 주요 기능:
 * - 리스크 체크 후 거래소 주문 제출
 * - 중복 주문 방지 (동일 코인+방향의 활성 주문 거부)
 * - 5초 간격 활성 주문 상태 폴링
 * - SUBMITTED 상태 5분 경과 시 자동 취소
 * - 모든 상태 변경 시 trade_log 기록
 */
@Service
@Slf4j
public class OrderExecutionEngine {

    private static final Duration ORDER_TIMEOUT = Duration.ofMinutes(5);
    private static final List<String> ACTIVE_STATES = List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED");

    private final OrderRepository orderRepository;
    private final PositionRepository positionRepository;
    private final TradeLogRepository tradeLogRepository;
    private final RiskManagementService riskManagementService;
    private final ObjectMapper objectMapper;

    // exchange-adapter 모듈에서 빈 등록 전이면 null 허용
    @Autowired(required = false)
    private UpbitRestClient upbitRestClient;

    @Autowired(required = false)
    private UpbitOrderClient upbitOrderClient;

    public OrderExecutionEngine(OrderRepository orderRepository,
                                 PositionRepository positionRepository,
                                 TradeLogRepository tradeLogRepository,
                                 RiskManagementService riskManagementService,
                                 ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.positionRepository = positionRepository;
        this.tradeLogRepository = tradeLogRepository;
        this.riskManagementService = riskManagementService;
        this.objectMapper = objectMapper;
    }

    /**
     * 주문 제출 — PENDING 상태로 생성 후 리스크 체크 및 거래소 주문
     * @Async 는 void 또는 Future<T> 반환만 지원 (Spring Boot 3.x)
     */
    @Transactional
    @Async("orderExecutor")
    public void submitOrder(OrderRequest request) {
        log.info("주문 제출 요청: {} {} {} 수량={}", request.getCoinPair(), request.getSide(),
                request.getOrderType(), request.getQuantity());

        // 1. 중복 주문 방지 — 세션 주문은 세션 단위로, 비세션 주문은 전역으로 체크
        boolean duplicateExists = request.getSessionId() != null
                ? orderRepository.existsBySessionIdAndCoinPairAndSideAndStateIn(
                        request.getSessionId(), request.getCoinPair(), request.getSide(), ACTIVE_STATES)
                : orderRepository.existsByCoinPairAndSideAndStateIn(
                        request.getCoinPair(), request.getSide(), ACTIVE_STATES);
        if (duplicateExists) {
            log.warn("중복 주문 거부 (sessionId={}, {} {}): 이미 활성 주문이 있습니다",
                    request.getSessionId(), request.getCoinPair(), request.getSide());
            return;
        }

        // 2. 주문 엔티티 생성 (PENDING)
        OrderEntity order = OrderEntity.builder()
                .coinPair(request.getCoinPair())
                .side(request.getSide())
                .orderType(request.getOrderType())
                .price(request.getPrice())
                .quantity(request.getQuantity())
                .state("PENDING")
                .signalReason(request.getReason())
                .sessionId(request.getSessionId())
                .positionId(request.getPositionId())
                .build();
        order = orderRepository.save(order);
        recordTradeLog(order.getId(), "STATE_CHANGE", null, "PENDING", "주문 생성");

        // 3. 리스크 체크
        RiskCheckResult riskResult = riskManagementService.checkRisk();
        if (!riskResult.isApproved()) {
            log.warn("리스크 체크 거부 (orderId={}): {}", order.getId(), riskResult.getReason());
            transitionState(order, "FAILED", riskResult.getReason());
            return;
        }

        // 4. 거래소 주문 제출
        if (upbitOrderClient == null) {
            log.warn("UpbitOrderClient 미등록 — 거래소 연동 불가 (orderId={})", order.getId());
            transitionState(order, "FAILED", "거래소 클라이언트 미등록");
            return;
        }

        try {
            OrderResponse exchangeResponse = submitToExchange(order);
            order.setExchangeOrderId(exchangeResponse.getUuid());
            order.setSubmittedAt(Instant.now());
            log.info("거래소 주문 제출 완료 (orderId={}, exchangeId={}, initialState={})",
                    order.getId(), exchangeResponse.getUuid(), exchangeResponse.getState());

            // 주문 생성 응답에서 이미 최종 상태인 경우 즉시 처리 (폴러 대기 불필요)
            String initialState = mapExchangeState(exchangeResponse.getState());
            if ("FILLED".equals(initialState)) {
                if (exchangeResponse.getExecutedVolume() != null) {
                    order.setFilledQuantity(exchangeResponse.getExecutedVolume());
                }
                order.setFilledAt(Instant.now());
                transitionState(order, "FILLED", null);
                processFilledOrder(order);
            } else {
                transitionState(order, "SUBMITTED", null);
                // CANCELLED이지만 부분 체결된 경우도 즉시 처리
                if ("CANCELLED".equals(initialState)
                        && "BUY".equalsIgnoreCase(order.getSide())
                        && exchangeResponse.getExecutedVolume() != null
                        && exchangeResponse.getExecutedVolume().compareTo(BigDecimal.ZERO) > 0) {
                    log.warn("주문 생성 응답에서 부분 체결 후 취소 감지 (orderId={}, executed_volume={})",
                            order.getId(), exchangeResponse.getExecutedVolume());
                    order.setFilledQuantity(exchangeResponse.getExecutedVolume());
                    order.setFilledAt(Instant.now());
                    transitionState(order, "FILLED", null);
                    processFilledOrder(order);
                }
            }
        } catch (Exception e) {
            log.error("거래소 주문 제출 실패 (orderId={}): {}", order.getId(), e.getMessage(), e);
            transitionState(order, "FAILED", "거래소 주문 실패: " + e.getMessage());
        }
    }

    /**
     * 주문 상태 동기화 — 거래소에서 최신 상태를 조회하여 업데이트
     */
    @Transactional
    public void checkOrderStatus(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: id=" + orderId));

        if (!ACTIVE_STATES.contains(order.getState())) {
            return; // 이미 최종 상태
        }

        if (upbitRestClient == null || order.getExchangeOrderId() == null) {
            return;
        }

        try {
            OrderResponse exchangeStatus = queryExchangeOrder(order.getExchangeOrderId());
            syncOrderState(order, exchangeStatus);
        } catch (Exception e) {
            log.error("주문 상태 조회 실패 (orderId={}): {}", orderId, e.getMessage());
        }
    }

    /**
     * 주문 취소 요청
     */
    @Transactional
    public OrderEntity cancelOrder(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: id=" + orderId));

        String currentState = order.getState();
        if (!"PENDING".equals(currentState) && !"SUBMITTED".equals(currentState)
                && !"PARTIAL_FILLED".equals(currentState)) {
            throw new IllegalStateException(
                    String.format("취소 불가 상태: %s (orderId=%d)", currentState, orderId));
        }

        // 거래소에 취소 요청
        if (upbitOrderClient != null && order.getExchangeOrderId() != null) {
            try {
                cancelOnExchange(order.getExchangeOrderId());
            } catch (Exception e) {
                log.error("거래소 주문 취소 실패 (orderId={}): {}", orderId, e.getMessage());
                // 거래소 취소 실패해도 로컬 상태는 CANCELLED로 전이
            }
        }

        order.setCancelledAt(Instant.now());
        transitionState(order, "CANCELLED", "사용자 취소 요청");
        log.info("주문 취소 완료: orderId={}", orderId);
        return order;
    }

    /**
     * 모든 활성 주문 취소 (비상 정지용)
     */
    @Transactional
    public int cancelAllActiveOrders() {
        List<OrderEntity> activeOrders = orderRepository.findByStateIn(ACTIVE_STATES);
        int cancelledCount = 0;

        for (OrderEntity order : activeOrders) {
            try {
                cancelOrder(order.getId());
                cancelledCount++;
            } catch (Exception e) {
                log.error("활성 주문 취소 실패 (orderId={}): {}", order.getId(), e.getMessage());
            }
        }

        log.info("활성 주문 일괄 취소: {}건 취소됨", cancelledCount);
        return cancelledCount;
    }

    /**
     * 주문 ID로 상세 조회
     */
    @Transactional(readOnly = true)
    public Optional<OrderEntity> getOrder(Long orderId) {
        return orderRepository.findById(orderId);
    }

    /**
     * 전체 주문 내역 조회 (페이징)
     */
    @Transactional(readOnly = true)
    public Page<OrderEntity> getOrders(Pageable pageable) {
        return orderRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * 활성 주문 목록 조회
     */
    @Transactional(readOnly = true)
    public List<OrderEntity> getActiveOrders() {
        return orderRepository.findByStateIn(ACTIVE_STATES);
    }

    // ── 스케줄: 활성 주문 상태 폴링 (5초 간격) ────────────────────

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollActiveOrders() {
        List<OrderEntity> activeOrders = orderRepository.findByStateIn(ACTIVE_STATES);
        if (activeOrders.isEmpty()) {
            return;
        }

        for (OrderEntity order : activeOrders) {
            // 타임아웃 체크: SUBMITTED 상태에서 5분 경과 시 자동 취소
            if ("SUBMITTED".equals(order.getState()) && order.getSubmittedAt() != null) {
                Duration elapsed = Duration.between(order.getSubmittedAt(), Instant.now());
                if (elapsed.compareTo(ORDER_TIMEOUT) > 0) {
                    log.warn("주문 타임아웃 (orderId={}, {}분 경과)", order.getId(), elapsed.toMinutes());
                    try {
                        cancelOrder(order.getId());
                        recordTradeLog(order.getId(), "TIMEOUT", "SUBMITTED", "CANCELLED",
                                "제출 후 5분 경과 자동 취소");
                    } catch (Exception e) {
                        log.error("타임아웃 취소 실패 (orderId={}): {}", order.getId(), e.getMessage());
                    }
                    continue;
                }
            }

            // 거래소 상태 동기화
            checkOrderStatus(order.getId());
        }
    }

    // ── 체결 처리 ──────────────────────────────────────────────

    /**
     * 체결 완료 시 포지션 업데이트
     */
    @Transactional
    public void processFilledOrder(OrderEntity order) {
        log.info("체결 처리 시작 (orderId={}, {} {} {})", order.getId(),
                order.getCoinPair(), order.getSide(), order.getFilledQuantity());

        if ("BUY".equalsIgnoreCase(order.getSide())) {
            handleBuyFill(order);
        } else if ("SELL".equalsIgnoreCase(order.getSide())) {
            handleSellFill(order);
        }
    }

    private void handleBuyFill(OrderEntity order) {
        BigDecimal filledQty = order.getFilledQuantity();
        if (filledQty == null || filledQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("BUY 체결 처리 건너뜀: filledQty={} (orderId={})", filledQty, order.getId());
            return;
        }

        // 평균 체결 단가 계산
        // - MARKET(price 타입) 매수: Upbit 응답 price = 원래 KRW 총액, executed_volume = 체결 코인 수량
        //   → avgFillPrice = KRW 총액(quantity) / 체결 코인 수량
        // - LIMIT 매수: order.getPrice() = 지정 단가
        BigDecimal avgFillPrice;
        if ("MARKET".equalsIgnoreCase(order.getOrderType())) {
            avgFillPrice = order.getQuantity().divide(filledQty, 8, RoundingMode.HALF_UP);
        } else {
            avgFillPrice = order.getPrice();
        }

        // positionId가 있으면 해당 포지션을 직접 조회 (세션 데이터 혼재 방지)
        Optional<PositionEntity> existingPos = order.getPositionId() != null
                ? positionRepository.findById(order.getPositionId())
                : positionRepository.findByCoinPairAndStatus(order.getCoinPair(), "OPEN");

        if (existingPos.isPresent()) {
            // 기존 포지션에 체결가/수량 반영 (평균 단가 갱신)
            PositionEntity pos = existingPos.get();
            BigDecimal oldTotal = pos.getAvgPrice().multiply(pos.getSize());
            BigDecimal newTotal = avgFillPrice.multiply(filledQty);
            BigDecimal totalSize = pos.getSize().add(filledQty);
            BigDecimal newAvgPrice = oldTotal.add(newTotal)
                    .divide(totalSize, 8, RoundingMode.HALF_UP);
            pos.setAvgPrice(newAvgPrice);
            pos.setSize(totalSize);
            positionRepository.save(pos);
            log.info("BUY 체결 반영: posId={}, 평균단가={}, 수량={}", pos.getId(), pos.getAvgPrice(), pos.getSize());
        } else {
            // positionId 없는 비세션 주문만 새 포지션 생성
            PositionEntity pos = PositionEntity.builder()
                    .coinPair(order.getCoinPair())
                    .side("LONG")
                    .entryPrice(avgFillPrice)
                    .avgPrice(avgFillPrice)
                    .size(filledQty)
                    .status("OPEN")
                    .build();
            pos = positionRepository.save(pos);
            order.setPositionId(pos.getId());
            orderRepository.save(order);
            log.info("새 포지션 생성: posId={}, {} @ {}", pos.getId(), order.getCoinPair(), avgFillPrice);
        }
    }

    private void handleSellFill(OrderEntity order) {
        // 세션 연결 매도 주문은 LiveTradingService.reconcileClosingPositions()에서 처리
        // (KRW 복원·손익 확정·수수료 기록까지 실제 체결가 기반으로 일괄 처리)
        if (order.getSessionId() != null) {
            log.debug("세션 매도 주문 체결 — reconcileClosingPositions에서 처리됨 (orderId={}, sessionId={})",
                    order.getId(), order.getSessionId());
            return;
        }

        // positionId가 있으면 해당 포지션을 직접 조회 (세션 데이터 혼재 방지)
        Optional<PositionEntity> openPos = order.getPositionId() != null
                ? positionRepository.findById(order.getPositionId())
                : positionRepository.findByCoinPairAndStatus(order.getCoinPair(), "OPEN");

        if (openPos.isEmpty()) {
            log.warn("매도 체결이지만 열린 포지션 없음: {} (orderId={})", order.getCoinPair(), order.getId());
            return;
        }

        PositionEntity pos = openPos.get();
        BigDecimal soldQuantity = order.getFilledQuantity();
        if (soldQuantity == null || soldQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("SELL 체결 처리 건너뜀: filledQty={} (orderId={})", soldQuantity, order.getId());
            return;
        }
        BigDecimal remainingSize = pos.getSize().subtract(soldQuantity);

        // 실현 손익 계산 — order.getPrice()는 syncOrderState에서 세팅됨
        // market 매도: executedFunds/executedVolume 으로 역산한 단가
        // 단가 미확보 시 pos.getAvgPrice()로 대체 (PnL = 0으로 처리)
        BigDecimal fillPrice = order.getPrice() != null ? order.getPrice() : pos.getAvgPrice();
        BigDecimal realizedPnl = fillPrice.subtract(pos.getAvgPrice()).multiply(soldQuantity);
        pos.setRealizedPnl(pos.getRealizedPnl().add(realizedPnl));

        if (remainingSize.compareTo(BigDecimal.ZERO) <= 0) {
            // 전량 매도 → 포지션 종료
            pos.setSize(BigDecimal.ZERO);
            pos.setUnrealizedPnl(BigDecimal.ZERO);
            pos.setStatus("CLOSED");
            pos.setClosedAt(Instant.now());
            log.info("포지션 종료: posId={}, 실현손익={}", pos.getId(), realizedPnl);
        } else {
            // 부분 매도
            pos.setSize(remainingSize);
            log.info("부분 매도: posId={}, 잔여수량={}, 실현손익={}", pos.getId(), remainingSize, realizedPnl);
        }

        positionRepository.save(pos);
        order.setPositionId(pos.getId());
        orderRepository.save(order);

        // 체결 로그에 실현 손익 기록
        recordTradeLog(order.getId(), "FILL", null, null,
                String.format("{\"realizedPnl\": %s, \"fillPrice\": %s, \"fillQuantity\": %s}",
                        realizedPnl.toPlainString(), fillPrice.toPlainString(),
                        soldQuantity.toPlainString()));
    }

    // ── 거래소 연동 (UpbitRestClient 래핑) ─────────────────────

    private OrderResponse submitToExchange(OrderEntity order) throws Exception {
        // BUY:  Upbit bid + price 타입 (총 KRW 금액 = quantity 필드에 KRW 금액이 설정된 경우)
        //       또는 bid + market 타입 (수량 기반 시장가)
        // SELL: Upbit ask + market 타입 (코인 수량)
        // LIMIT: bid/ask + limit 타입 (지정가)
        String upbitSide = "BUY".equalsIgnoreCase(order.getSide()) ? "bid" : "ask";
        String upbitOrderType;
        BigDecimal volume;
        BigDecimal price;

        if ("MARKET".equalsIgnoreCase(order.getOrderType())) {
            if ("bid".equals(upbitSide)) {
                // 시장가 매수: price 타입 — quantity 필드를 KRW 총액으로 사용
                upbitOrderType = "price";
                volume = null;
                price = order.getQuantity();
            } else {
                // 시장가 매도: market 타입 — Upbit 실제 잔고 기준으로 수량 결정
                // 이유: price-type 매수 체결량(executed_volume)은 코인별 매도 허용 단위를 초과할 수 있음.
                //       Upbit 잔고(account.balance)는 항상 해당 코인의 유효 단위로 반환되므로
                //       invalid_volume_ask 없이 전량 매도 가능.
                upbitOrderType = "market";
                price = null;
                volume = resolveAskVolume(order);
            }
        } else {
            // 지정가
            upbitOrderType = "limit";
            volume = order.getQuantity();
            price = order.getPrice();
        }

        return upbitOrderClient.createOrder(order.getCoinPair(), upbitSide, volume, price, upbitOrderType);
    }

    /**
     * 시장가 매도 수량 결정 — Upbit 실제 잔고 기준
     *
     * Upbit price-type 매수의 executed_volume은 코인별 매도 허용 단위를 초과할 수 있다.
     * (예: 8000 ÷ 3,437,000 = 0.00232761181... → 8자리 초과 또는 단위 불일치)
     * 계좌 잔고(account.balance)는 Upbit이 직접 반환하는 값이므로 항상 유효한 단위이다.
     * 포지션 수량과 잔고 중 작은 값을 사용해 과매도를 방지한다.
     */
    private BigDecimal resolveAskVolume(OrderEntity order) {
        BigDecimal positionVolume = order.getQuantity();
        String coinPair = order.getCoinPair();
        int dashIdx = coinPair.indexOf('-');
        String currency = dashIdx >= 0 ? coinPair.substring(dashIdx + 1) : coinPair;  // "KRW-ETH" → "ETH"

        try {
            List<AccountResponse> accounts = upbitOrderClient.getAccounts();
            Optional<AccountResponse> account = accounts.stream()
                    .filter(a -> currency.equals(a.getCurrency()))
                    .findFirst();

            if (account.isPresent()) {
                BigDecimal freeBalance = account.get().getBalance() != null
                        ? account.get().getBalance() : BigDecimal.ZERO;
                BigDecimal lockedBalance = account.get().getLocked() != null
                        ? account.get().getLocked() : BigDecimal.ZERO;
                BigDecimal totalHeld = freeBalance.add(lockedBalance);

                if (freeBalance.compareTo(BigDecimal.ZERO) <= 0 && lockedBalance.compareTo(BigDecimal.ZERO) > 0) {
                    // ETH가 pending 주문에 잠겨있는 경우 — 중복 매도 시도 차단
                    log.warn("매도 불가: {} 잔고 전량 잠김 (locked={}, free={}). 기존 pending 주문 확인 필요.",
                            currency, lockedBalance, freeBalance);
                    throw new IllegalStateException(
                            currency + " 잔고가 전량 주문 잠금 상태입니다 (locked=" + lockedBalance + "). 중복 매도 방지.");
                }

                if (freeBalance.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal volume = freeBalance.min(positionVolume);
                    log.info("매도 수량 결정: 포지션={}, 가용잔고={}, 잠금잔고={}, 사용={} ({})",
                            positionVolume, freeBalance, lockedBalance, volume, currency);
                    return volume;
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("업비트 잔고 조회 실패, 포지션 수량 사용 ({}): {}", currency, e.getMessage());
        }

        // 폴백: setScale(8) 으로 소수점 정리
        return positionVolume.setScale(8, RoundingMode.DOWN);
    }

    private OrderResponse queryExchangeOrder(String exchangeOrderId) throws Exception {
        return upbitOrderClient.getOrder(exchangeOrderId);
    }

    private void cancelOnExchange(String exchangeOrderId) throws Exception {
        upbitOrderClient.cancelOrder(exchangeOrderId);
    }

    // ── 상태 동기화 ────────────────────────────────────────────

    private void syncOrderState(OrderEntity order, OrderResponse exchangeStatus) {
        String newState = mapExchangeState(exchangeStatus.getState());
        String currentState = order.getState();

        if (currentState.equals(newState)) {
            return; // 변경 없음
        }

        // 체결 수량 업데이트
        if (exchangeStatus.getExecutedVolume() != null) {
            order.setFilledQuantity(exchangeStatus.getExecutedVolume());
        }

        if ("FILLED".equals(newState)) {
            order.setFilledAt(Instant.now());
            // market 타입 매도: price 필드는 null이고 executed_funds(KRW 총수령)로 단가 역산
            // price 타입 매수:  price 필드 = 원래 KRW 총액(order.getQuantity()와 동일) → 덮어쓰지 않음
            // limit 타입:       price 필드 = 지정 단가 → 그대로 사용
            if ("market".equalsIgnoreCase(exchangeStatus.getOrdType())
                    && "ask".equalsIgnoreCase(exchangeStatus.getSide())
                    && exchangeStatus.getExecutedFunds() != null
                    && exchangeStatus.getExecutedVolume() != null
                    && exchangeStatus.getExecutedVolume().compareTo(BigDecimal.ZERO) > 0) {
                // 시장가 매도 평균 단가 = 수령 KRW / 체결 코인 수량
                BigDecimal avgSellPrice = exchangeStatus.getExecutedFunds()
                        .divide(exchangeStatus.getExecutedVolume(), 8, RoundingMode.HALF_UP);
                order.setPrice(avgSellPrice);
            } else if ("limit".equalsIgnoreCase(exchangeStatus.getOrdType())
                    && exchangeStatus.getPrice() != null) {
                order.setPrice(exchangeStatus.getPrice());
            }
            // price 타입 매수는 order.getPrice() 그대로 유지 (= 원래 KRW 총액, handleBuyFill 에서 quantity/filledQty 로 재계산)
        }

        // CANCELLED지만 일부 체결된 경우 (price-type 시장가 매수의 부분 체결 후 취소)
        // Upbit: state=cancel + executed_volume>0 → 실제로 코인을 매수한 것이므로 체결 처리
        boolean partialFill = "CANCELLED".equals(newState)
                && "BUY".equalsIgnoreCase(order.getSide())
                && order.getFilledQuantity() != null
                && order.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0;

        if (partialFill) {
            log.warn("부분 체결 후 취소 감지 (orderId={}, executed_volume={}): CANCELLED → FILLED 처리",
                    order.getId(), order.getFilledQuantity());
            order.setFilledAt(Instant.now());
            transitionState(order, "FILLED", null);
            processFilledOrder(order);
            return;
        }

        transitionState(order, newState, null);

        // 체결 완료 시 포지션 처리
        if ("FILLED".equals(newState)) {
            processFilledOrder(order);
        }
    }

    /**
     * Upbit 주문 상태를 내부 상태로 매핑
     * Upbit: wait, watch, done, cancel
     * 내부: PENDING, SUBMITTED, PARTIAL_FILLED, FILLED, CANCELLED, FAILED
     */
    private String mapExchangeState(String upbitState) {
        if (upbitState == null) return "PENDING";
        return switch (upbitState.toLowerCase()) {
            case "wait", "watch" -> "SUBMITTED";
            case "done" -> "FILLED";
            case "cancel" -> "CANCELLED";
            default -> "PENDING";
        };
    }

    // ── 상태 전이 + 로그 ──────────────────────────────────────

    private void transitionState(OrderEntity order, String newState, String failedReason) {
        String oldState = order.getState();
        order.setState(newState);

        if ("FAILED".equals(newState) && failedReason != null) {
            order.setFailedReason(failedReason);
        }

        orderRepository.save(order);
        recordTradeLog(order.getId(), "STATE_CHANGE", oldState, newState, failedReason);

        log.info("주문 상태 전이 (orderId={}): {} → {}{}", order.getId(), oldState, newState,
                failedReason != null ? " (" + failedReason + ")" : "");
    }

    private void recordTradeLog(Long orderId, String eventType, String oldState,
                                 String newState, String detail) {
        TradeLogEntity logEntry = TradeLogEntity.builder()
                .orderId(orderId)
                .eventType(eventType)
                .oldState(oldState)
                .newState(newState)
                .detailJson(toJsonDetail(detail))
                .build();
        tradeLogRepository.save(logEntry);
    }

    /**
     * detail_json 컬럼은 JSONB 타입이므로 plain string은 JSON으로 감싸야 한다.
     * '{' 또는 '['로 시작하면 이미 JSON으로 간주하고 그대로 사용한다.
     */
    private String toJsonDetail(String detail) {
        if (detail == null) return null;
        String trimmed = detail.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            return objectMapper.writeValueAsString(Map.of("message", trimmed));
        } catch (Exception e) {
            return "{\"message\":\"" + trimmed.replace("\"", "'") + "\"}";
        }
    }
}
