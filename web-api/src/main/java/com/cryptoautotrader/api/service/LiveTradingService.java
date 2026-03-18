package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.LiveTradingStartRequest;
import com.cryptoautotrader.api.exception.SessionNotFoundException;
import com.cryptoautotrader.api.exception.SessionStateException;
import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.dto.TradingStatusResponse;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.repository.StrategyConfigRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.util.TimeframeUtils;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.core.selector.CompositeStrategy;
import com.cryptoautotrader.core.selector.StrategySelector;
import com.cryptoautotrader.core.selector.WeightedStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실전 매매 서비스 -- 다중 세션 지원
 * - 각 세션: 특정 종목 + 전략 + 타임프레임 + 투자금액 조합
 * - 최대 5개 동시 세션
 * - 세션별 시작/정지/비상정지
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiveTradingService {

    private static final int MAX_CONCURRENT_SESSIONS = 5;
    private static final int CANDLE_LOOKBACK = 100;
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal INVEST_RATIO = new BigDecimal("0.80");
    private static final List<String> ACTIVE_ORDER_STATES =
            List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED");

    /** COMPOSITE 전략 사용 세션별 MarketRegimeDetector (Hysteresis 상태 유지) */
    private final Map<Long, MarketRegimeDetector> sessionDetectors = new ConcurrentHashMap<>();

    private final LiveTradingSessionRepository sessionRepository;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final StrategyConfigRepository strategyConfigRepository;
    private final MarketDataCacheRepository candleDataRepository;
    private final OrderExecutionEngine orderExecutionEngine;
    private final PositionService positionService;
    private final ExchangeHealthMonitor exchangeHealthMonitor;
    private final TelegramNotificationService telegramService;
    private final StrategyLogRepository strategyLogRepository;

    // -- 거래소 DOWN 이벤트 수신 -- 모든 세션 비상 정지 ----------

    @EventListener
    public void onExchangeDown(ExchangeDownEvent event) {
        log.error("거래소 DOWN 이벤트 수신 -- 모든 실전매매 세션을 비상 정지합니다.");
        emergencyStopAll();
    }

    // -- 세션 생성 -----------------------------------------------

    /**
     * 새 매매 세션 생성 (아직 시작하지 않음 -- status=STOPPED)
     */
    @Transactional
    public LiveTradingSessionEntity createSession(LiveTradingStartRequest req) {
        long runningCount = sessionRepository.countByStatus("RUNNING");
        if (runningCount >= MAX_CONCURRENT_SESSIONS) {
            throw new SessionStateException(
                    "최대 " + MAX_CONCURRENT_SESSIONS + "개의 동시 매매 세션만 가능합니다. "
                            + "현재 " + runningCount + "개 실행 중.");
        }

        // 전략 유효성 검증 (COMPOSITE는 StrategyRegistry 외부에서 처리)
        if (!"COMPOSITE".equals(req.getStrategyType())) {
            try {
                StrategyRegistry.get(req.getStrategyType());
            } catch (Exception e) {
                throw new IllegalArgumentException("지원하지 않는 전략입니다: " + req.getStrategyType());
            }
        }

        // TEST_TIMED: 코인/타임프레임/원금 강제 고정
        if ("TEST_TIMED".equals(req.getStrategyType())) {
            req.setCoinPair("KRW-ETH");
            req.setTimeframe("M1");
            req.setInitialCapital(BigDecimal.valueOf(10000));
        }

        BigDecimal stopLoss = req.getStopLossPct() != null
                ? req.getStopLossPct() : new BigDecimal("5.0");

        LiveTradingSessionEntity session = LiveTradingSessionEntity.builder()
                .strategyType(req.getStrategyType())
                .coinPair(req.getCoinPair())
                .timeframe(req.getTimeframe())
                .initialCapital(req.getInitialCapital())
                .availableKrw(req.getInitialCapital())
                .totalAssetKrw(req.getInitialCapital())
                .status("CREATED")
                .strategyParams(req.getStrategyParams() != null
                        ? req.getStrategyParams() : Collections.emptyMap())
                .stopLossPct(stopLoss)
                .build();

        session = sessionRepository.save(session);
        log.info("실전매매 세션 생성: id={} {} {} {} 초기자본={}",
                session.getId(), req.getStrategyType(), req.getCoinPair(),
                req.getTimeframe(), req.getInitialCapital());
        return session;
    }

    // -- 세션 시작 -----------------------------------------------

    /**
     * 세션 시작 -- STOPPED 상태의 세션을 RUNNING으로 전환
     */
    @Transactional
    public LiveTradingSessionEntity startSession(Long sessionId) {
        LiveTradingSessionEntity session = getSessionOrThrow(sessionId);

        if ("RUNNING".equals(session.getStatus())) {
            throw new SessionStateException("세션이 이미 실행 중입니다: id=" + sessionId);
        }

        // 동시 실행 세션 수 제한 확인
        long runningCount = sessionRepository.countByStatus("RUNNING");
        if (runningCount >= MAX_CONCURRENT_SESSIONS) {
            throw new SessionStateException(
                    "최대 " + MAX_CONCURRENT_SESSIONS + "개의 동시 매매 세션만 가능합니다.");
        }

        // 거래소 상태 확인
        if (exchangeHealthMonitor != null && "DOWN".equals(exchangeHealthMonitor.getStatus())) {
            throw new SessionStateException("거래소 연결이 DOWN 상태입니다. 연결 복구 후 시작하세요.");
        }

        session.setStatus("RUNNING");
        session.setStartedAt(Instant.now());
        session.setStoppedAt(null);
        session = sessionRepository.save(session);

        log.info("실전매매 세션 시작: id={} {} {} {}",
                sessionId, session.getStrategyType(), session.getCoinPair(), session.getTimeframe());
        telegramService.notifySessionStarted(
                sessionId, session.getStrategyType(), session.getCoinPair(),
                session.getTimeframe(), session.getInitialCapital().longValue());
        return session;
    }

    // -- 세션 정지 -----------------------------------------------

    /**
     * 세션 정지 -- 해당 세션의 열린 포지션을 청산하고 STOPPED로 전환
     */
    @Transactional
    public LiveTradingSessionEntity stopSession(Long sessionId) {
        LiveTradingSessionEntity session = getSessionOrThrow(sessionId);

        if (!"RUNNING".equals(session.getStatus())) {
            throw new SessionStateException("세션이 실행 중이 아닙니다: id=" + sessionId);
        }

        // 해당 세션의 열린 포지션 청산
        closeSessionPositions(session, "세션 정지 -- 포지션 청산");

        session.setStatus("STOPPED");
        session.setStoppedAt(Instant.now());
        sessionDetectors.remove(sessionId);
        session = sessionRepository.save(session);

        log.info("실전매매 세션 정지: id={} 최종 자산: {} KRW",
                sessionId, session.getTotalAssetKrw());

        double returnPct = session.getTotalAssetKrw()
                .subtract(session.getInitialCapital())
                .divide(session.getInitialCapital(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
        telegramService.notifySessionStopped(
                sessionId, session.getCoinPair(), returnPct,
                session.getTotalAssetKrw().longValue(), false);
        return session;
    }

    // -- 세션 비상 정지 -------------------------------------------

    /**
     * 특정 세션 비상 정지 -- 활성 주문 취소 + 포지션 시장가 청산
     */
    @Transactional
    public LiveTradingSessionEntity emergencyStopSession(Long sessionId) {
        LiveTradingSessionEntity session = getSessionOrThrow(sessionId);

        log.error("실전매매 세션 비상 정지: id={}", sessionId);

        // 해당 세션의 활성 주문 취소
        cancelSessionActiveOrders(sessionId);

        // 해당 세션의 열린 포지션 시장가 청산
        closeSessionPositions(session, "비상 정지 -- 강제 시장가 청산");

        session.setStatus("EMERGENCY_STOPPED");
        session.setStoppedAt(Instant.now());
        sessionDetectors.remove(sessionId);
        session = sessionRepository.save(session);

        log.error("실전매매 세션 비상 정지 완료: id={}", sessionId);

        double returnPct = session.getTotalAssetKrw()
                .subtract(session.getInitialCapital())
                .divide(session.getInitialCapital(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
        telegramService.notifySessionStopped(
                sessionId, session.getCoinPair(), returnPct,
                session.getTotalAssetKrw().longValue(), true);
        return session;
    }

    /**
     * 전체 비상 정지 -- 모든 RUNNING 세션을 비상 정지
     */
    @Transactional
    public void emergencyStopAll() {
        log.error("전체 비상 정지 실행!");

        // 모든 활성 주문 취소
        int cancelledOrders = orderExecutionEngine.cancelAllActiveOrders();
        log.info("전체 비상 정지: {}건 주문 취소", cancelledOrders);

        List<LiveTradingSessionEntity> runningSessions =
                sessionRepository.findByStatus("RUNNING");

        for (LiveTradingSessionEntity session : runningSessions) {
            try {
                closeSessionPositions(session, "전체 비상 정지 -- 강제 시장가 청산");
                session.setStatus("EMERGENCY_STOPPED");
                session.setStoppedAt(Instant.now());
                sessionRepository.save(session);
            } catch (Exception e) {
                log.error("세션 비상 정지 실패 (id={}): {}", session.getId(), e.getMessage());
            }
        }

        log.error("전체 비상 정지 완료: {}개 세션 정지", runningSessions.size());
    }

    // -- 세션 삭제 -----------------------------------------------

    /**
     * 세션 삭제 -- STOPPED 또는 EMERGENCY_STOPPED 상태만 삭제 가능
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        LiveTradingSessionEntity session = getSessionOrThrow(sessionId);
        if ("RUNNING".equals(session.getStatus())) {
            throw new SessionStateException("실행 중인 세션은 삭제할 수 없습니다. 먼저 정지하세요.");
        }

        // OPEN 포지션이 남아 있으면 강제 종료 (세션 정지 후 남은 orphan 포지션 정리)
        List<PositionEntity> openPositions = positionRepository.findBySessionIdAndStatus(sessionId, "OPEN");
        for (PositionEntity pos : openPositions) {
            pos.setStatus("CLOSED");
            pos.setClosedAt(Instant.now());
            positionRepository.save(pos);
            log.warn("세션 삭제 시 미청산 포지션 강제 종료: posId={} {} (sessionId={})",
                    pos.getId(), pos.getCoinPair(), sessionId);
        }

        // 관련 주문/포지션의 session_id를 null로 설정 (이력 보존)
        List<PositionEntity> positions = positionRepository.findBySessionId(sessionId);
        positions.forEach(pos -> {
            pos.setSessionId(null);
            positionRepository.save(pos);
        });

        List<OrderEntity> orders = orderRepository
                .findBySessionIdOrderByCreatedAtDesc(sessionId, Pageable.unpaged()).getContent();
        orders.forEach(order -> {
            order.setSessionId(null);
            orderRepository.save(order);
        });

        sessionRepository.deleteById(sessionId);
        sessionDetectors.remove(sessionId);
        log.info("실전매매 세션 삭제 완료: id={}", sessionId);
    }

    // -- 세션 조회 -----------------------------------------------

    /**
     * 세션 상세 조회
     */
    @Transactional(readOnly = true)
    public LiveTradingSessionEntity getSession(Long sessionId) {
        return getSessionOrThrow(sessionId);
    }

    /**
     * 전체 세션 목록 (최신순)
     */
    @Transactional(readOnly = true)
    public List<LiveTradingSessionEntity> listSessions() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 특정 세션의 포지션 목록
     */
    @Transactional(readOnly = true)
    public List<PositionEntity> getSessionPositions(Long sessionId) {
        getSessionOrThrow(sessionId); // 존재 확인
        return positionRepository.findBySessionId(sessionId);
    }

    /**
     * 특정 세션의 주문 내역 (페이징)
     */
    @Transactional(readOnly = true)
    public Page<OrderEntity> getSessionOrders(Long sessionId, Pageable pageable) {
        getSessionOrThrow(sessionId); // 존재 확인
        return orderRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
    }

    // -- 전체 상태 요약 -------------------------------------------

    /**
     * 전체 매매 상태 요약
     */
    public TradingStatusResponse getGlobalStatus() {
        long runningCount = sessionRepository.countByStatus("RUNNING");
        long totalCount = sessionRepository.count();
        int openPositionCount = (int) positionRepository.countBySessionIdIsNotNullAndStatus("OPEN");
        int activeOrderCount = (int) orderRepository.countBySessionIdIsNotNullAndStateIn(ACTIVE_ORDER_STATES);
        BigDecimal totalPnl = positionService.getTotalPnl();
        String exchangeHealth = exchangeHealthMonitor != null
                ? exchangeHealthMonitor.getStatus() : "UNKNOWN";

        // 전체 상태 결정: RUNNING 세션이 있으면 RUNNING, 없으면 STOPPED
        String globalStatus = runningCount > 0 ? "RUNNING" : "STOPPED";

        return TradingStatusResponse.builder()
                .status(globalStatus)
                .openPositions(openPositionCount)
                .activeOrders(activeOrderCount)
                .totalPnl(totalPnl)
                .startedAt(null) // 다중 세션에서는 개별 세션의 startedAt 참조
                .exchangeHealth(exchangeHealth)
                .runningSessions((int) runningCount)
                .totalSessions((int) totalCount)
                .build();
    }

    /**
     * 현재 매매 활성 여부 -- RUNNING 세션이 하나라도 있으면 true
     */
    public boolean isTradingActive() {
        return sessionRepository.countByStatus("RUNNING") > 0;
    }

    // -- 스케줄: RUNNING 세션 순회하며 전략 실행 (60초 간격) -------

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void executeStrategies() {
        List<LiveTradingSessionEntity> runningSessions =
                sessionRepository.findByStatus("RUNNING");

        if (runningSessions.isEmpty()) {
            return;
        }

        for (LiveTradingSessionEntity session : runningSessions) {
            try {
                evaluateAndExecuteSession(session);
            } catch (Exception e) {
                log.error("세션 전략 실행 오류 (sessionId={}, {}): {}",
                        session.getId(), session.getStrategyType(), e.getMessage(), e);
            }
        }
    }

    // -- 내부: 세션별 전략 평가 및 주문 실행 ----------------------

    private void evaluateAndExecuteSession(LiveTradingSessionEntity session) {
        String coinPair = session.getCoinPair();
        String timeframe = session.getTimeframe();
        String strategyType = session.getStrategyType();
        Long sessionId = session.getId();

        List<Candle> candles = fetchRecentCandles(coinPair, timeframe);
        if (candles.size() < 10) {
            log.warn("캔들 부족: {} {} {}건 (sessionId={})",
                    coinPair, timeframe, candles.size(), sessionId);
            return;
        }

        // 전략 신호 평가
        StrategySignal signal;
        MarketRegime regime = null;
        if ("COMPOSITE".equals(strategyType)) {
            MarketRegimeDetector detector = sessionDetectors.computeIfAbsent(
                    sessionId, id -> new MarketRegimeDetector());
            regime = detector.detect(candles);
            List<WeightedStrategy> weighted = StrategySelector.select(regime);
            signal = new CompositeStrategy(weighted).evaluate(candles, Collections.emptyMap());
            log.info("실전매매 COMPOSITE 신호 (sessionId={}): regime={} {} → {} ({})",
                    sessionId, regime, coinPair, signal.getAction(), signal.getReason());
        } else {
            Map<String, Object> params = new java.util.HashMap<>(
                    session.getStrategyParams() != null ? session.getStrategyParams() : Collections.emptyMap());
            if (session.getStartedAt() != null) {
                params.put("sessionStartedAt", session.getStartedAt().toEpochMilli());
            }
            signal = StrategyRegistry.get(strategyType).evaluate(candles, params);
            log.debug("세션 전략 신호 (sessionId={}): {} {} -> {} ({})",
                    sessionId, strategyType, coinPair, signal.getAction(), signal.getReason());
        }

        // 전략 로그 DB 저장
        try {
            StrategyLogEntity logEntity = StrategyLogEntity.builder()
                    .strategyName(strategyType)
                    .coinPair(coinPair)
                    .signal(signal.getAction().name())
                    .reason(signal.getReason())
                    .marketRegime(regime != null ? regime.name() : null)
                    .sessionType("LIVE")
                    .sessionId(sessionId)
                    .build();
            strategyLogRepository.save(logEntity);
        } catch (Exception e) {
            log.warn("전략 로그 저장 실패: {}", e.getMessage());
        }

        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();
        Optional<PositionEntity> openPos = positionRepository
                .findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "OPEN");

        // 손절 확인
        if (openPos.isPresent()) {
            PositionEntity pos = openPos.get();
            BigDecimal pnlPct = currentPrice.subtract(pos.getAvgPrice())
                    .divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            BigDecimal stopLoss = session.getStopLossPct().negate();
            if (pnlPct.compareTo(stopLoss) <= 0) {
                log.warn("손절 발동 (sessionId={}): {} 손익률={}% (한도={}%)",
                        sessionId, coinPair, pnlPct, stopLoss);
                telegramService.notifyStopLoss(coinPair, pnlPct.doubleValue(), sessionId);
                executeSessionSell(session, pos, currentPrice,
                        "손절 발동 -- 손익률 " + pnlPct + "%");
                return;
            }
        }

        switch (signal.getAction()) {
            case BUY -> {
                if (openPos.isEmpty()) {
                    executeSessionBuy(session, coinPair, currentPrice,
                            String.format("전략 신호: %s -- %s", strategyType, signal.getReason()));
                }
            }
            case SELL -> {
                openPos.ifPresent(pos -> executeSessionSell(session, pos, currentPrice,
                        String.format("전략 신호: %s -- %s", strategyType, signal.getReason())));
            }
            default -> { /* HOLD */ }
        }

        // 미실현 손익 업데이트
        updateSessionUnrealizedPnl(session, coinPair, currentPrice);
    }

    private void executeSessionBuy(LiveTradingSessionEntity session,
                                    String coinPair, BigDecimal price, String reason) {
        // 사전 검증: 이미 이 세션에 활성 BUY 주문이 있으면 스킵 (orphan 포지션 방지)
        boolean hasPendingBuy = orderRepository.existsBySessionIdAndCoinPairAndSideAndStateIn(
                session.getId(), coinPair, "BUY", ACTIVE_ORDER_STATES);
        if (hasPendingBuy) {
            log.warn("매수 스킵: 세션({})에 이미 활성 BUY 주문이 있습니다 ({})", session.getId(), coinPair);
            return;
        }

        BigDecimal investAmount = session.getAvailableKrw().multiply(INVEST_RATIO);
        if (investAmount.compareTo(BigDecimal.valueOf(5000)) < 0) {
            log.warn("매수 불가: 가용 자금 부족 ({}) sessionId={}",
                    session.getAvailableKrw(), session.getId());
            return;
        }

        BigDecimal quantity = investAmount.divide(price, 8, RoundingMode.DOWN);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // 포지션 생성 (세션 연결)
        // size=0 으로 초기화: 주문 체결(FILLED) 후 handleBuyFill()에서 실제 체결 수량으로 갱신됨
        // 체결 전 size=0 이므로 updateSessionUnrealizedPnl()에서 totalAssetKrw가 가격에 따라 변동하지 않음
        PositionEntity pos = PositionEntity.builder()
                .coinPair(coinPair)
                .side("BUY")
                .entryPrice(price)
                .avgPrice(price)
                .size(BigDecimal.ZERO)
                .status("OPEN")
                .sessionId(session.getId())
                .build();
        pos = positionRepository.save(pos);

        // 주문 제출 — sessionId/positionId를 request에 미리 설정 (@Async 리턴값 의존 회피)
        // 시장가 매수는 Upbit price 타입: quantity 필드에 KRW 금액(investAmount)을 전달해야 함
        OrderRequest order = new OrderRequest();
        order.setCoinPair(coinPair);
        order.setSide("BUY");
        order.setOrderType("MARKET");
        order.setQuantity(investAmount);
        order.setReason(reason);
        order.setSessionId(session.getId());
        order.setPositionId(pos.getId());
        orderExecutionEngine.submitOrder(order);

        // 세션 잔고 차감
        session.setAvailableKrw(session.getAvailableKrw().subtract(investAmount));
        sessionRepository.save(session);

        log.info("실전 매수 주문 (sessionId={}): {} {}개 @ {} 사유: {}",
                session.getId(), coinPair, quantity, price, reason);
    }

    private void executeSessionSell(LiveTradingSessionEntity session,
                                     PositionEntity pos, BigDecimal currentPrice,
                                     String reason) {
        // 매도 수량 검증 — position.size=0 이면 매수 체결 미감지 상태이므로 스킵
        if (pos.getSize().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("매도 건너뜀: position.size={} (sessionId={}, posId={}). 매수 체결 미감지 — 다음 틱에 재시도됩니다.",
                    pos.getSize(), session.getId(), pos.getId());
            return;
        }

        // 주문 제출 — sessionId/positionId를 request에 미리 설정 (@Async 리턴값 의존 회피)
        OrderRequest order = new OrderRequest();
        order.setCoinPair(pos.getCoinPair());
        order.setSide("SELL");
        order.setOrderType("MARKET");
        order.setQuantity(pos.getSize());
        order.setReason(reason);
        order.setSessionId(session.getId());
        order.setPositionId(pos.getId());
        orderExecutionEngine.submitOrder(order);

        // 매도 금액을 세션 잔고에 복원 (수수료 고려)
        BigDecimal proceeds = pos.getSize().multiply(currentPrice);
        BigDecimal fee = proceeds.multiply(FEE_RATE);
        BigDecimal netProceeds = proceeds.subtract(fee);

        BigDecimal costBasis = pos.getSize().multiply(pos.getAvgPrice());
        BigDecimal realizedPnl = netProceeds.subtract(costBasis);

        pos.setRealizedPnl(realizedPnl);
        pos.setUnrealizedPnl(BigDecimal.ZERO);
        pos.setStatus("CLOSED");
        pos.setClosedAt(Instant.now());
        positionRepository.save(pos);

        session.setAvailableKrw(session.getAvailableKrw().add(netProceeds));
        // 총자산 = 기존 총자산 - 매도 수수료 (포지션→KRW 전환은 가치 중립, 다른 오픈 포지션 가치 유지)
        session.setTotalAssetKrw(session.getTotalAssetKrw().subtract(fee));
        sessionRepository.save(session);

        log.info("실전 매도 주문 (sessionId={}): {} {}개 @ {} 손익: {} KRW",
                session.getId(), pos.getCoinPair(), pos.getSize(), currentPrice, realizedPnl);
    }

    private void updateSessionUnrealizedPnl(LiveTradingSessionEntity session,
                                              String coinPair, BigDecimal currentPrice) {
        positionRepository.findBySessionIdAndCoinPairAndStatus(
                session.getId(), coinPair, "OPEN").ifPresent(pos -> {
            BigDecimal unrealized = currentPrice.subtract(pos.getAvgPrice())
                    .multiply(pos.getSize());
            pos.setUnrealizedPnl(unrealized);
            positionRepository.save(pos);

            // 세션 총자산 업데이트
            BigDecimal posValue = pos.getSize().multiply(currentPrice);
            session.setTotalAssetKrw(session.getAvailableKrw().add(posValue));
            sessionRepository.save(session);
        });
    }

    // -- 내부: 세션 포지션 청산 ----------------------------------

    private void closeSessionPositions(LiveTradingSessionEntity session, String reason) {
        List<PositionEntity> openPositions =
                positionRepository.findBySessionIdAndStatus(session.getId(), "OPEN");

        for (PositionEntity pos : openPositions) {
            try {
                OrderRequest sellOrder = new OrderRequest();
                sellOrder.setCoinPair(pos.getCoinPair());
                sellOrder.setSide("SELL");
                sellOrder.setOrderType("MARKET");
                sellOrder.setQuantity(pos.getSize());
                sellOrder.setReason(reason);
                sellOrder.setSessionId(session.getId());
                sellOrder.setPositionId(pos.getId());
                orderExecutionEngine.submitOrder(sellOrder);

                log.info("세션 포지션 청산 주문: sessionId={} {} 수량={}",
                        session.getId(), pos.getCoinPair(), pos.getSize());
            } catch (Exception e) {
                log.error("세션 포지션 청산 실패 (sessionId={}, posId={}): {}",
                        session.getId(), pos.getId(), e.getMessage());
            }
        }
    }

    private void cancelSessionActiveOrders(Long sessionId) {
        List<OrderEntity> activeOrders = orderRepository
                .findBySessionIdAndStateIn(sessionId, ACTIVE_ORDER_STATES);
        for (OrderEntity order : activeOrders) {
            try {
                orderExecutionEngine.cancelOrder(order.getId());
                log.info("세션 주문 취소: sessionId={} orderId={}", sessionId, order.getId());
            } catch (Exception e) {
                log.error("세션 주문 취소 실패 (sessionId={}, orderId={}): {}",
                        sessionId, order.getId(), e.getMessage());
            }
        }
    }

    // -- 내부: 유틸 -----------------------------------------------

    @Transactional(readOnly = true)
    public List<MarketDataCacheEntity> getChartCandles(Long sessionId) {
        LiveTradingSessionEntity session = getSessionOrThrow(sessionId);
        Instant from = session.getStartedAt() != null ? session.getStartedAt() : Instant.now().minus(7, ChronoUnit.DAYS);
        Instant to = session.getStoppedAt() != null ? session.getStoppedAt() : Instant.now();
        return candleDataRepository.findCandles(session.getCoinPair(), session.getTimeframe(), from, to);
    }

    @Transactional(readOnly = true)
    public List<OrderEntity> getAllSessionOrders(Long sessionId) {
        getSessionOrThrow(sessionId);
        return orderRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, Pageable.unpaged()).getContent();
    }

    private LiveTradingSessionEntity getSessionOrThrow(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    private List<Candle> fetchRecentCandles(String coinPair, String timeframe) {
        Instant to = Instant.now();
        Instant from = to.minus(CANDLE_LOOKBACK * TimeframeUtils.toMinutes(timeframe), ChronoUnit.MINUTES);

        return candleDataRepository.findCandles(coinPair, timeframe, from, to).stream()
                .map(c -> Candle.builder()
                        .time(c.getTime()).open(c.getOpen()).high(c.getHigh())
                        .low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                        .build())
                .toList();
    }

}
