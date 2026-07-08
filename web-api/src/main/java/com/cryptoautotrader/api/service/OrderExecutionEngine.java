package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.TradeLogEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    /** Upbit 매수 수수료율 — LiveTradingService/DynamicTradingService의 FEE_RATE와 동일 (0.05%) */
    private static final BigDecimal BUY_FEE_RATE = new BigDecimal("0.0005");

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

    @Autowired(required = false)
    private TelegramNotificationService telegramService;

    @Autowired(required = false)
    private LiveTradingSessionRepository sessionRepository;

    /** 동적 세션 KRW 복원용 (선택적 — DynamicTradingService 미사용 환경에서는 null) */
    @Autowired(required = false)
    private com.cryptoautotrader.api.repository.DynamicSessionRepository dynamicSessionRepository;

    @Autowired(required = false)
    private DynamicSessionBalanceUpdater dynamicBalanceUpdater;

    /** §10 — Upbit API rate limit 대응 */
    @Autowired
    private UpbitApiRateLimiter rateLimiter;

    /** §16 — Micrometer 메트릭 (Prometheus export) */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /** §14 — 실전/백테스트 drift 트래커 (BUY 체결 시 신호가 vs 체결가 기록) */
    @Autowired(required = false)
    private ExecutionDriftTracker executionDriftTracker;

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

        // 세션 종류 — live_trading_session/dynamic_session은 별도 BIGSERIAL 이라 sessionId만으로는
        // 구분 불가. 호출자(LiveTradingService/DynamicTradingService)가 명시하지 않으면 LIVE로 간주.
        String sessionKind = request.getSessionKind() != null ? request.getSessionKind() : "LIVE";

        // 1. 중복 주문 방지 — 세션 주문은 세션종류+세션 단위로, 비세션 주문은 전역으로 체크
        //    (kind 미구분 시 live/dynamic 세션이 같은 sessionId를 가지면 서로를 오차단할 수 있었음 — 2026-07-02)
        boolean duplicateExists = request.getSessionId() != null
                ? orderRepository.existsBySessionKindAndSessionIdAndCoinPairAndSideAndStateIn(
                        sessionKind, request.getSessionId(), request.getCoinPair(), request.getSide(), ACTIVE_STATES)
                : orderRepository.existsByCoinPairAndSideAndStateIn(
                        request.getCoinPair(), request.getSide(), ACTIVE_STATES);
        if (duplicateExists) {
            log.warn("중복 주문 거부 (sessionKind={}, sessionId={}, {} {}): 이미 활성 주문이 있습니다",
                    sessionKind, request.getSessionId(), request.getCoinPair(), request.getSide());
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
                .signalPrice(request.getSignalPrice())
                .sessionId(request.getSessionId())
                .sessionKind(sessionKind)
                .positionId(request.getPositionId())
                .build();
        order = orderRepository.save(order);
        recordTradeLog(order.getId(), "STATE_CHANGE", null, "PENDING", "주문 생성");

        // 3. 리스크 체크
        // - SELL: 손절 등 청산이 막히면 손실 확대 → 항상 허용
        // - 세션 BUY (positionId != null): LiveTradingService에서 이미 체크 + 포지션/KRW 처리 완료 → 재체크 생략
        // - 비세션 BUY (positionId == null): 리스크 체크 필요
        boolean needsRiskCheck = "BUY".equalsIgnoreCase(request.getSide())
                && request.getPositionId() == null;
        if (needsRiskCheck) {
            RiskCheckResult riskResult = riskManagementService.checkRisk();
            if (!riskResult.isApproved()) {
                log.warn("리스크 체크 거부 (orderId={}): {}", order.getId(), riskResult.getReason());
                transitionState(order, "FAILED", riskResult.getReason());
                return;
            }
        }

        // 4. 거래소 주문 제출
        if (upbitOrderClient == null) {
            log.warn("UpbitOrderClient 미등록 — 거래소 연동 불가 (orderId={})", order.getId());
            transitionState(order, "FAILED", "거래소 클라이언트 미등록");
            return;
        }

        try {
            UpbitOrderClient.ExchangeResult exchangeResult = submitToExchange(order);
            OrderResponse exchangeResponse = exchangeResult.response();
            order.setExchangeOrderId(exchangeResponse.getUuid());
            order.setSubmittedAt(Instant.now());
            order.setResponseJson(exchangeResult.rawBody());
            log.info("거래소 주문 제출 완료 (orderId={}, exchangeId={}, initialState={})",
                    order.getId(), exchangeResponse.getUuid(), exchangeResponse.getState());

            // 주문 생성 응답에서 이미 최종 상태인 경우 즉시 처리 (폴러 대기 불필요)
            String initialState = mapExchangeState(exchangeResponse.getState());
            if ("FILLED".equals(initialState)) {
                if (exchangeResponse.getExecutedVolume() != null) {
                    order.setFilledQuantity(exchangeResponse.getExecutedVolume());
                }
                // 체결가 확정 시도 — 시장가 매도는 executed_funds/executed_volume 로 평균 단가 산출
                applyFillPrice(order, exchangeResponse);
                // ⚠️ 시장가 매도가 즉시 done 으로 떠도 trades(executed_funds)가 미정산이면 체결가 null.
                //    이대로 FILLED 확정하면 finalizeSellPosition 이 avgPrice 로 대체 → realizedPnl 이
                //    항상 -매도수수료로 기록되는 "가짜 본전" 버그(2026-05-31 분석: 포지션 146건).
                //    체결가 미확보 SELL 은 SUBMITTED 로 두어 5초 폴러가 정산 후 재조회하게 한다.
                if ("SELL".equalsIgnoreCase(order.getSide()) && order.getPrice() == null) {
                    log.warn("매도 즉시 체결이나 체결가 미정산 — SUBMITTED 유지, 폴러 재조회 (orderId={}, uuid={})",
                            order.getId(), exchangeResponse.getUuid());
                    transitionState(order, "SUBMITTED", null);
                } else {
                    order.setFilledAt(Instant.now());
                    transitionState(order, "FILLED", null);
                    processFilledOrder(order);
                }
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
                // (B) 취소 실패는 '이미 체결되어 취소 불가'일 수 있다 — 거래소 상태를 재확인해
                //     실제 체결됐다면 CANCELLED 로 박지 말고 체결 처리한다 (체결을 취소로 오기록 방지).
                try {
                    OrderResponse latest = queryExchangeOrder(order.getExchangeOrderId());
                    boolean executed = latest.getExecutedVolume() != null
                            && latest.getExecutedVolume().compareTo(BigDecimal.ZERO) > 0;
                    if ("FILLED".equals(mapExchangeState(latest.getState())) || executed) {
                        log.warn("취소 실패 + 거래소 체결 확인 — CANCELLED 대신 체결 처리 (orderId={}, state={}, executed={})",
                                orderId, latest.getState(), latest.getExecutedVolume());
                        syncOrderState(order, latest);
                        return order;
                    }
                } catch (Exception ex) {
                    log.warn("취소 실패 후 상태 재조회도 실패 (orderId={}): {} — CANCELLED 전이",
                            orderId, ex.getMessage());
                }
                // 그 외(미체결 확인 또는 재조회 실패): 로컬 상태는 CANCELLED 로 전이
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
     * 전체 주문 내역 조회 (페이징) — 세션/날짜 필터 선택 적용
     */
    @Transactional(readOnly = true)
    public Page<OrderEntity> getOrders(Pageable pageable, List<Long> sessionIds, Instant dateFrom, Instant dateTo) {
        boolean hasSession = sessionIds != null && !sessionIds.isEmpty();
        boolean hasDate    = dateFrom != null && dateTo != null;

        if (hasSession && hasDate) {
            return orderRepository.findBySessionIdInAndCreatedAtBetweenOrderByCreatedAtDesc(sessionIds, dateFrom, dateTo, pageable);
        } else if (hasSession) {
            return orderRepository.findBySessionIdInOrderByCreatedAtDesc(sessionIds, pageable);
        } else if (hasDate) {
            return orderRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(dateFrom, dateTo, pageable);
        } else {
            return orderRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
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
                    // (A) 취소 직전 거래소 최신 상태 재확인 — 이미 체결됐다면 취소가 아니라 체결 처리.
                    //     시장가 매도가 거래소에서 done 됐는데 취소로 오기록 → 포지션 OPEN 롤백 →
                    //     무한 재매도 + 잔고 불일치로 이어지던 버그의 근본 차단선.
                    if (order.getExchangeOrderId() != null) {
                        try {
                            OrderResponse latest = queryExchangeOrder(order.getExchangeOrderId());
                            if ("FILLED".equals(mapExchangeState(latest.getState()))) {
                                log.warn("타임아웃 직전 체결 감지 — 취소 대신 체결 동기화 (orderId={}, state={}, executed={})",
                                        order.getId(), latest.getState(), latest.getExecutedVolume());
                                syncOrderState(order, latest);
                                continue;
                            }
                        } catch (Exception e) {
                            log.warn("타임아웃 전 상태 재조회 실패 (orderId={}): {} — 취소 진행",
                                    order.getId(), e.getMessage());
                        }
                    }
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
        // - MARKET(price 타입) 매수: executedFunds(실제 체결에 사용된 KRW) / executed_volume 이 정확한 평균단가.
        //   부분 체결 후 취소된 경우 order.getQuantity()(원래 주문 KRW 총액)로 나누면 실제보다 낮은 체결량
        //   대비 총액이 부풀려져 평균단가가 실제의 배수로 부풀려진다 → 즉시 허위 손절 유발(2026-07-02 감사).
        //   executedFunds 미확보(구주문 등) 시에만 quantity로 폴백.
        // - LIMIT 매수: order.getPrice() = 지정 단가
        BigDecimal avgFillPrice;
        if ("MARKET".equalsIgnoreCase(order.getOrderType())) {
            BigDecimal fundsUsed = order.getExecutedFunds() != null
                    ? order.getExecutedFunds() : order.getQuantity();
            avgFillPrice = fundsUsed.divide(filledQty, 8, RoundingMode.HALF_UP);
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
            // 매수 수수료 명시 계상 — 기존에는 positionFee가 매도 수수료만 반영해 대시보드
            // "총 수수료" 합계가 실제의 절반 수준으로 과소 표시됐다 (2026-07-02 감사 D-4).
            // 세션 KRW 자체는 investAmount(quantity)를 매수 시점에 이미 전액 차감했고 Upbit가
            // 그 안에서 수수료를 제하고 코인을 지급하므로 잔고 드리프트는 없음 — 이건 순수 리포팅 보강.
            BigDecimal buyFee = newTotal.multiply(BUY_FEE_RATE);
            BigDecimal accumulatedFee = pos.getPositionFee() != null ? pos.getPositionFee() : BigDecimal.ZERO;
            pos.setPositionFee(accumulatedFee.add(buyFee));
            positionRepository.save(pos);
            log.info("BUY 체결 반영: posId={}, 평균단가={}, 수량={}", pos.getId(), pos.getAvgPrice(), pos.getSize());

            // 부분 체결 후 취소 시 미사용 KRW 복원
            // executedFunds(실제 사용 KRW) < quantity(원래 차감 KRW) 이면 차액을 session에 반환.
            // sessionKind로 LIVE/DYNAMIC 세션 테이블을 명확히 구분한다 — 미구분 시 두 세션 테이블이
            // 같은 sessionId를 가질 경우 엉뚱한 세션의 잔고를 복원할 수 있었다 (2026-07-02).
            if (order.getSessionId() != null
                    && order.getExecutedFunds() != null
                    && order.getQuantity() != null
                    && order.getExecutedFunds().compareTo(order.getQuantity()) < 0) {
                BigDecimal unusedKrw = order.getQuantity().subtract(order.getExecutedFunds());
                if ("DYNAMIC".equals(order.getSessionKind())) {
                    if (dynamicBalanceUpdater != null) {
                        dynamicBalanceUpdater.apply(order.getSessionId(),
                                s -> s.setAvailableKrw(s.getAvailableKrw().add(unusedKrw)));
                        log.info("부분 체결 후 미사용 KRW 복원 (동적, orderId={}, sessionId={}, 복원={})",
                                order.getId(), order.getSessionId(), unusedKrw);
                    }
                } else if (sessionRepository != null) {
                    sessionRepository.findById(order.getSessionId()).ifPresent(session -> {
                        session.setAvailableKrw(session.getAvailableKrw().add(unusedKrw));
                        sessionRepository.save(session);
                        log.info("부분 체결 후 미사용 KRW 복원 (orderId={}, sessionId={}, 복원={})",
                                order.getId(), order.getSessionId(), unusedKrw);
                    });
                }
            }

            if (order.getSessionId() != null && telegramService != null) {
                telegramService.bufferTradeEvent(
                        "세션#" + order.getSessionId(), order.getCoinPair(), "BUY",
                        avgFillPrice, filledQty, buyFee, null, order.getSignalReason());
            }

            // §14 drift 기록 — 신호 시점 가격(order.signalPrice) vs 실제 평균 체결가.
            // signalPrice 미보존 주문(V54 이전 생성분 등)은 record()가 생략 처리한다.
            if (order.getSessionId() != null && executionDriftTracker != null
                    && !"DYNAMIC".equals(order.getSessionKind()) && sessionRepository != null) {
                sessionRepository.findById(order.getSessionId()).ifPresent(s ->
                        executionDriftTracker.record(
                                order.getSessionId(), order.getCoinPair(), s.getStrategyType(),
                                "BUY", order.getSignalPrice(), avgFillPrice, Instant.now()));
            }
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
        // market 매도: executedFunds/executedVolume 으로 역산한 단가 (gross 금액 기준)
        // 단가 미확보 시 pos.getAvgPrice()로 대체 (PnL = 0으로 처리)
        BigDecimal fillPrice = order.getPrice() != null ? order.getPrice() : pos.getAvgPrice();
        BigDecimal grossProceeds = fillPrice.multiply(soldQuantity);
        BigDecimal sellFee = grossProceeds.multiply(new BigDecimal("0.0005"));
        BigDecimal realizedPnl = grossProceeds.subtract(sellFee)
                .subtract(soldQuantity.multiply(pos.getAvgPrice()));
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

    private UpbitOrderClient.ExchangeResult submitToExchange(OrderEntity order) throws Exception {
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

        // §10 rate limit — permit 확보 후 Upbit API 호출
        if (!rateLimiter.acquire()) {
            throw new RuntimeException("Upbit API rate limit 대기 타임아웃 — 주문 제출 재시도 필요");
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
        // §10 rate limit — permit 확보 후 Upbit API 호출
        if (!rateLimiter.acquire()) {
            log.warn("Upbit API rate limit 대기 타임아웃 — 취소 재시도 필요 (orderId={})", exchangeOrderId);
        }
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
            // 체결가 확정 — 시장가 매도는 executed_funds/executed_volume 로 평균 단가 산출
            applyFillPrice(order, exchangeStatus);
            // ⚠️ 체결가 미확보 SELL 은 FILLED 확정 보류 — 상태를 SUBMITTED 로 유지(여기서 return)하여
            //    다음 폴링(5초)에서 trades 정산 후 재시도한다. avgPrice 대체로 인한 "가짜 본전" 방지.
            if ("SELL".equalsIgnoreCase(order.getSide()) && order.getPrice() == null) {
                log.warn("매도 체결(done)이나 체결가 미정산 — FILLED 보류, 폴러 재조회 (orderId={}, uuid={})",
                        order.getId(), exchangeStatus.getUuid());
                return;
            }
            order.setFilledAt(Instant.now());
        }

        // CANCELLED지만 일부 체결된 경우 (부분 체결 후 취소) — BUY/SELL 공통.
        // Upbit: state=cancel + executed_volume>0 → 실제로 체결된 수량이 있으므로 체결 처리해야 한다.
        // SELL 쪽을 누락하면 절반만 팔린 매도가 CANCELLED로만 기록되어 포지션이 전체 수량으로
        // OPEN 롤백되고, 이미 팔린 절반의 실현손익은 어디에도 기록되지 않는 정합성 구멍이 생긴다
        // (2026-07-02 감사 D-3).
        boolean partialFill = "CANCELLED".equals(newState)
                && order.getFilledQuantity() != null
                && order.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0;

        if (partialFill) {
            log.warn("부분 체결 후 취소 감지 (orderId={}, side={}, executed_volume={}, executed_funds={}): CANCELLED → FILLED 처리",
                    order.getId(), order.getSide(), order.getFilledQuantity(), exchangeStatus.getExecutedFunds());
            // 실제 사용된 KRW 저장 — handleBuyFill()에서 미사용 KRW 복원에 사용
            BigDecimal executedFunds = exchangeStatus.resolveExecutedFunds();
            if (executedFunds != null) {
                order.setExecutedFunds(executedFunds);
            }
            // SELL: 체결가가 없으면 finalizeSellPosition/finalizeDynamicSell이 처리 불가 — 여기서 확정 시도.
            if ("SELL".equalsIgnoreCase(order.getSide()) && order.getPrice() == null) {
                applyFillPrice(order, exchangeStatus);
                if (order.getPrice() == null) {
                    log.warn("부분 체결 매도 취소 — 체결가 미확보로 FILLED 보류, 폴러 재조회 (orderId={})", order.getId());
                    return;
                }
            }
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
     * 체결가(평균 단가)를 주문에 반영한다. 즉시 체결 경로와 폴러 동기화 경로가 공통 사용.
     *
     * <ul>
     *   <li>매도(ask): 실제 평균 체결가 = executed_funds / executed_volume.
     *       정산 전이라 executed_funds 가 없으면 price 를 건드리지 않는다(null 유지) →
     *       호출자가 FILLED 확정을 보류하고 재조회하도록 한다.
     *       limit 매도이면서 funds 미정산 시에는 지정가(price)로 대체.</li>
     *   <li>지정가 매수(limit/bid): 거래소 지정 단가(price).</li>
     *   <li>시장가 매수(price/bid): order.price = 원래 KRW 총액 유지 (handleBuyFill 에서 재계산).</li>
     * </ul>
     */
    private void applyFillPrice(OrderEntity order, OrderResponse resp) {
        // executed_funds 가 최상위에 없으면 trades[] 합산으로 산출 (시장가 매도 정산 신뢰 소스)
        BigDecimal executedFunds = resp.resolveExecutedFunds();
        boolean hasFunds = executedFunds != null
                && resp.getExecutedVolume() != null
                && resp.getExecutedVolume().compareTo(BigDecimal.ZERO) > 0;
        if ("ask".equalsIgnoreCase(resp.getSide())) {
            if (hasFunds) {
                order.setPrice(executedFunds
                        .divide(resp.getExecutedVolume(), 8, RoundingMode.HALF_UP));
            } else if ("limit".equalsIgnoreCase(resp.getOrdType()) && resp.getPrice() != null) {
                order.setPrice(resp.getPrice());
            }
            // market 매도 + funds 미정산 → price 는 null 유지 (호출자가 재조회)
        } else if ("limit".equalsIgnoreCase(resp.getOrdType()) && resp.getPrice() != null) {
            order.setPrice(resp.getPrice());
        }
        // 시장가 매수(price/bid)는 order.price 그대로 유지
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

        // §16 — 주문 상태 전이 카운터 (Prometheus: order_state_transition_total{from,to})
        if (meterRegistry != null) {
            Counter.builder("order.state.transition")
                    .tag("from", oldState != null ? oldState : "UNKNOWN")
                    .tag("to", newState)
                    .description("주문 상태 전이 횟수")
                    .register(meterRegistry)
                    .increment();
        }

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
