package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.LiveTradingStartRequest;
import com.cryptoautotrader.api.dto.MultiStrategyLiveTradingRequest;
import com.cryptoautotrader.api.dto.PerformanceSummaryResponse;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cryptoautotrader.core.risk.RiskCheckResult;

import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

    private static final int MAX_CONCURRENT_SESSIONS = 10;
    private static final int CANDLE_LOOKBACK = 100;
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal INVEST_RATIO = new BigDecimal("0.80");

    /** 백테스트 기준 구조적 손실 전략 — 실전매매 세션 생성 차단 */
    private static final List<String> BLOCKED_LIVE_STRATEGIES = List.of("STOCHASTIC_RSI", "MACD");
    private static final List<String> ACTIVE_ORDER_STATES =
            List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED");

    /** StatefulStrategy(COMPOSITE/Grid 등) 세션별 독립 인스턴스 — 다중 세션 간 상태 오염 방지 */
    private final Map<Long, com.cryptoautotrader.strategy.Strategy> sessionStatefulStrategies = new ConcurrentHashMap<>();

    /** 낙폭 경고 쿨다운: 세션별 마지막 DRAWDOWN_WARNING 전송 시각 (30분 쿨다운) */
    private final Map<Long, Instant> lastDrawdownWarning = new ConcurrentHashMap<>();
    private static final long DRAWDOWN_WARNING_COOLDOWN_MIN = 30;

    /** WebSocket 실시간 손절 — 코인별 마지막 체크 시각 (5초 throttle) */
    private final Map<String, Long> rtStopLossLastCheckMs = new ConcurrentHashMap<>();
    private static final long RT_STOPLOSS_CHECK_INTERVAL_MS = 5_000;

    /** 급등/급락 감지 — 코인별 가격 이력 (최근 60초) */
    private final Map<String, Deque<PriceSnapshot>> priceHistory = new ConcurrentHashMap<>();
    /** 급등/급락 감지 시 단축 throttle (1초) */
    private final Map<String, Long> spikeCheckLastMs = new ConcurrentHashMap<>();
    private static final long SPIKE_WINDOW_MS        = 30_000;   // 감지 윈도우 30초
    private static final long SPIKE_HISTORY_TTL_MS   = 60_000;   // 버퍼 보존 60초
    private static final long SPIKE_CHECK_INTERVAL_MS = 1_000;   // 급등락 시 1초 throttle
    private static final BigDecimal SPIKE_DOWN_THRESHOLD  = new BigDecimal("-1.5");  // -1.5%/30s
    private static final BigDecimal SPIKE_UP_THRESHOLD    = new BigDecimal("2.0");   // +2.0%/30s
    private static final BigDecimal SL_TIGHTEN_MARGIN     = new BigDecimal("0.003"); // 현재가 0.3% 아래로 SL 조임
    private static final BigDecimal TRAILING_STOP_MARGIN  = new BigDecimal("0.005"); // 현재가 0.5% 아래로 TP 갱신

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
    private final RiskManagementService riskManagementService;
    private final ApplicationEventPublisher eventPublisher;

    /** 호가창 조회용 (선택적 의존성 — exchange-adapter 빈이 없을 경우 null) */
    @Autowired(required = false)
    private UpbitRestClient upbitRestClient;

    /** WebSocket 클라이언트 (선택적 — exchange-adapter 빈이 없을 경우 null) */
    @Autowired(required = false)
    private UpbitWebSocketClient wsClient;

    // -- 거래소 DOWN 이벤트 수신 -- 모든 세션 비상 정지 ----------

    @EventListener
    public void onExchangeDown(ExchangeDownEvent event) {
        log.error("거래소 DOWN 이벤트 수신 -- 모든 실전매매 세션을 비상 정지합니다.");
        telegramService.notifyExchangeDown(event.getReason());
        emergencyStopAll();
    }

    // -- 세션 생성 -----------------------------------------------

    /**
     * 새 매매 세션 생성 (아직 시작하지 않음 -- status=STOPPED)
     * UI 버튼 중복 클릭 등 동시 요청 시 세션이 중복 생성되지 않도록 synchronized
     */
    @Transactional
    public synchronized LiveTradingSessionEntity createSession(LiveTradingStartRequest req) {
        long runningCount = sessionRepository.countByStatus("RUNNING");
        if (runningCount >= MAX_CONCURRENT_SESSIONS) {
            throw new SessionStateException(
                    "최대 " + MAX_CONCURRENT_SESSIONS + "개의 동시 매매 세션만 가능합니다. "
                            + "현재 " + runningCount + "개 실행 중.");
        }

        // 백테스트 기준 구조적 손실 전략 차단
        if (BLOCKED_LIVE_STRATEGIES.contains(req.getStrategyType())) {
            throw new IllegalArgumentException(
                    req.getStrategyType() + " 전략은 백테스트 기준 구조적 손실이 확인되어 실전매매가 차단됩니다. "
                    + "전략을 개선한 후 이용하세요. (백테스트 결과: STOCHASTIC_RSI BTC -70.4%/-67.6%, MACD BTC -58.8%/-57.6%)");
        }

        // 전략 유효성 검증
        try {
            StrategyRegistry.get(req.getStrategyType());
        } catch (Exception e) {
            throw new IllegalArgumentException("지원하지 않는 전략입니다: " + req.getStrategyType());
        }

        // TEST_TIMED: 코인/타임프레임/원금 강제 고정
        if ("TEST_TIMED".equals(req.getStrategyType())) {
            req.setCoinPair("KRW-ETH");
            req.setTimeframe("M1");
            req.setInitialCapital(BigDecimal.valueOf(10000));
        }

        BigDecimal stopLoss = req.getStopLossPct() != null
                ? req.getStopLossPct() : new BigDecimal("5.0");
        BigDecimal rawRatio = req.getInvestRatio();
        // 프론트엔드가 1~100 정수(예: 80)로 보내는 경우 0~1 범위로 변환
        if (rawRatio != null && rawRatio.compareTo(BigDecimal.ONE) > 0) {
            rawRatio = rawRatio.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        }
        BigDecimal investRatio = rawRatio != null
                ? rawRatio.max(new BigDecimal("0.01")).min(BigDecimal.ONE)
                : new BigDecimal("0.8000");

        LiveTradingSessionEntity session = LiveTradingSessionEntity.builder()
                .strategyType(req.getStrategyType())
                .coinPair(req.getCoinPair())
                .timeframe(req.getTimeframe())
                .initialCapital(req.getInitialCapital())
                .availableKrw(req.getInitialCapital())
                .totalAssetKrw(req.getInitialCapital())
                .investRatio(investRatio)
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

    // -- 다중 세션 일괄 생성 ----------------------------------------

    /**
     * 동일 조건(코인/타임프레임/투자금)으로 여러 전략을 한 번에 세션 등록 (CREATED 상태).
     * 현재 running 수 + 추가 수가 최대 한도를 초과하면 거부한다.
     */
    @Transactional
    public List<LiveTradingSessionEntity> createMultipleSessions(MultiStrategyLiveTradingRequest req) {
        int count = req.getStrategyTypes().size();
        long runningCount = sessionRepository.countByStatus("RUNNING");
        if (runningCount + count > MAX_CONCURRENT_SESSIONS) {
            throw new SessionStateException(
                    "세션 한도 초과: 현재 " + runningCount + "개 실행 중, " + count + "개 추가 시 최대 "
                            + MAX_CONCURRENT_SESSIONS + "개 초과합니다.");
        }

        List<LiveTradingSessionEntity> sessions = new ArrayList<>();
        for (String strategyType : req.getStrategyTypes()) {
            LiveTradingStartRequest single = new LiveTradingStartRequest();
            single.setStrategyType(strategyType);
            single.setCoinPair(req.getCoinPair());
            single.setTimeframe(req.getTimeframe());
            single.setInitialCapital(req.getInitialCapital());
            single.setStopLossPct(req.getStopLossPct());
            single.setInvestRatio(req.getInvestRatio());
            sessions.add(createSession(single));
        }
        log.info("다중 전략 실전매매 {} 세션 생성: {} {} {}",
                count, req.getCoinPair(), req.getTimeframe(), req.getStrategyTypes());
        return sessions;
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
        refreshWsSubscription();
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
        sessionStatefulStrategies.remove(sessionId);
        lastDrawdownWarning.remove(sessionId);
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
        refreshWsSubscription();
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
        sessionStatefulStrategies.remove(sessionId);
        lastDrawdownWarning.remove(sessionId);
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
        refreshWsSubscription();
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
                double returnPct = session.getTotalAssetKrw()
                        .subtract(session.getInitialCapital())
                        .divide(session.getInitialCapital(), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
                telegramService.notifySessionStopped(
                        session.getId(), session.getCoinPair(), returnPct,
                        session.getTotalAssetKrw().longValue(), true);
            } catch (Exception e) {
                log.error("세션 비상 정지 실패 (id={}): {}", session.getId(), e.getMessage());
            }
        }

        log.error("전체 비상 정지 완료: {}개 세션 정지", runningSessions.size());
        refreshWsSubscription();
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
        sessionStatefulStrategies.remove(sessionId);
        lastDrawdownWarning.remove(sessionId);
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
        Long sessionId = session.getId();
        // DB에서 최신 상태 재확인 — stopSession()/emergencyStop() 동시 호출 race condition 방지
        // (session 재할당 금지: lambda 참조에서 effectively-final 위반 방지)
        boolean stillRunning = sessionRepository.findById(sessionId)
                .map(s -> "RUNNING".equals(s.getStatus()))
                .orElse(false);
        if (!stillRunning) {
            log.debug("세션 상태 변경 감지 — 평가 스킵 (sessionId={})", sessionId);
            return;
        }

        // ── MDD 피크 자본 갱신 ────────────────────────────────
        BigDecimal currentTotal = session.getTotalAssetKrw();
        if (session.getMddPeakCapital() == null
                || currentTotal.compareTo(session.getMddPeakCapital()) > 0) {
            session.setMddPeakCapital(currentTotal);
            sessionRepository.save(session);
        }

        // ── 서킷 브레이커 체크 ────────────────────────────────
        CircuitBreakerResult cbResult = riskManagementService.checkCircuitBreaker(session);
        if (cbResult.isTriggered()) {
            log.error("서킷 브레이커 발동 (sessionId={}): {}", sessionId, cbResult.getReason());
            session.setCircuitBreakerTriggeredAt(Instant.now());
            session.setCircuitBreakerReason(cbResult.getReason());
            sessionRepository.save(session);
            emergencyStopSession(sessionId);
            return;
        }

        String coinPair = session.getCoinPair();
        String timeframe = session.getTimeframe();
        String strategyType = session.getStrategyType();

        List<Candle> candles = fetchRecentCandles(coinPair, timeframe);
        if (candles.size() < 10) {
            log.warn("캔들 부족: {} {} {}건 (sessionId={})",
                    coinPair, timeframe, candles.size(), sessionId);
            return;
        }

        // 전략 신호 평가
        Map<String, Object> params = new java.util.HashMap<>(
                session.getStrategyParams() != null ? session.getStrategyParams() : Collections.emptyMap());
        params.put("coinPair", coinPair);
        if (session.getStartedAt() != null) {
            params.put("sessionStartedAt", session.getStartedAt().toEpochMilli());
        }
        // ORDERBOOK_IMBALANCE 전략: REST API로 실시간 호가창 주입 (캔들 근사 대신 실값 사용)
        if ("ORDERBOOK_IMBALANCE".equals(strategyType) && upbitRestClient != null) {
            try {
                List<Map<String, Object>> orderbook = upbitRestClient.getOrderbook(coinPair);
                if (!orderbook.isEmpty()) {
                    Map<String, Object> ob = orderbook.get(0);
                    params.put("bidVolume", ob.get("total_bid_size"));
                    params.put("askVolume", ob.get("total_ask_size"));
                }
            } catch (Exception e) {
                log.warn("호가창 조회 실패, 캔들 근사 방식으로 대체 (sessionId={}): {}", sessionId, e.getMessage());
            }
        }
        com.cryptoautotrader.strategy.Strategy strategyInstance =
                StrategyRegistry.isStateful(strategyType)
                        ? sessionStatefulStrategies.computeIfAbsent(sessionId,
                                id -> StrategyRegistry.createNew(strategyType))
                        : StrategyRegistry.get(strategyType);
        StrategySignal signal = strategyInstance.evaluate(candles, params);
        log.debug("세션 전략 신호 (sessionId={}): {} {} -> {} ({})",
                sessionId, strategyType, coinPair, signal.getAction(), signal.getReason());

        // 전략 로그 DB 저장
        try {
            StrategyLogEntity logEntity = StrategyLogEntity.builder()
                    .strategyName(strategyType)
                    .coinPair(coinPair)
                    .signal(signal.getAction().name())
                    .reason(signal.getReason())
                    .marketRegime(null)
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

        // ── 익절/손절 체크 (전략 신호보다 우선) ──────────────────
        if (openPos.isPresent()) {
            PositionEntity pos = openPos.get();
            BigDecimal pnlPct = currentPrice.subtract(pos.getAvgPrice())
                    .divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            BigDecimal rawStopLoss = session.getStopLossPct() != null
                    ? session.getStopLossPct() : new BigDecimal("5.0");

            // 익절 체크: 저장된 takeProfitPrice 도달 시 청산
            if (pos.getTakeProfitPrice() != null
                    && currentPrice.compareTo(pos.getTakeProfitPrice()) >= 0) {
                log.info("익절 발동 (sessionId={}): {} 현재가={} 익절가={} 손익률={}%",
                        sessionId, coinPair, currentPrice, pos.getTakeProfitPrice(), pnlPct);
                executeSessionSell(session, pos, currentPrice,
                        "익절 발동 — 현재가 " + currentPrice + " ≥ 익절가 " + pos.getTakeProfitPrice());
                return;
            }

            // 낙폭 경고: 손절 한도의 50% 이상 손실이고 아직 손절 미도달 시 (30분 쿨다운)
            BigDecimal stopLossNeg = rawStopLoss.negate();
            BigDecimal warningThreshold = stopLossNeg.multiply(new BigDecimal("0.5"));
            if (pnlPct.compareTo(warningThreshold) <= 0 && pnlPct.compareTo(stopLossNeg) > 0) {
                Instant lastWarn = lastDrawdownWarning.get(sessionId);
                boolean cooldownPassed = lastWarn == null ||
                        Duration.between(lastWarn, Instant.now()).toMinutes() >= DRAWDOWN_WARNING_COOLDOWN_MIN;
                if (cooldownPassed) {
                    telegramService.notifyDrawdownWarning(
                            sessionId, coinPair, pnlPct.doubleValue(), rawStopLoss.doubleValue());
                    lastDrawdownWarning.put(sessionId, Instant.now());
                }
            }

            // 손절 체크: 저장된 stopLossPrice 우선, 없으면 세션 stopLossPct % 비교 (기존 포지션 하위 호환)
            boolean slTriggered = (pos.getStopLossPrice() != null)
                    ? currentPrice.compareTo(pos.getStopLossPrice()) <= 0
                    : pnlPct.compareTo(stopLossNeg) <= 0;
            if (slTriggered) {
                log.warn("손절 발동 (sessionId={}): {} 현재가={} 손익률={}% (손절가={}/한도={}%)",
                        sessionId, coinPair, currentPrice, pnlPct,
                        pos.getStopLossPrice() != null ? pos.getStopLossPrice() : "pct",
                        rawStopLoss);
                telegramService.notifyStopLoss(coinPair, pnlPct.doubleValue(), sessionId);
                executeSessionSell(session, pos, currentPrice,
                        "손절 발동 -- 손익률 " + pnlPct + "%");
                return;
            }
        }

        final StrategySignal finalSignal = signal;
        switch (signal.getAction()) {
            case BUY -> {
                boolean hasClosingPos = positionRepository
                        .findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "CLOSING").isPresent();
                if (openPos.isEmpty() && !hasClosingPos) {
                    RiskCheckResult riskResult = riskManagementService.checkRisk();
                    if (!riskResult.isApproved()) {
                        log.warn("리스크 한도 초과로 매수 차단 (sessionId={}): {}", sessionId, riskResult.getReason());
                        return;
                    }
                    executeSessionBuy(session, coinPair, currentPrice,
                            String.format("전략 신호: %s -- %s", strategyType, finalSignal.getReason()),
                            finalSignal);
                }
            }
            case SELL -> {
                openPos.ifPresent(pos -> executeSessionSell(session, pos, currentPrice,
                        String.format("전략 신호: %s -- %s", strategyType, finalSignal.getReason())));
            }
            default -> { /* HOLD */ }
        }

        // 미실현 손익 업데이트
        updateSessionUnrealizedPnl(session, coinPair, currentPrice);
    }

    private void executeSessionBuy(LiveTradingSessionEntity session,
                                    String coinPair, BigDecimal price, String reason,
                                    StrategySignal signal) {
        // 사전 검증: 이미 이 세션에 활성 BUY 주문이 있으면 스킵 (orphan 포지션 방지)
        boolean hasPendingBuy = orderRepository.existsBySessionIdAndCoinPairAndSideAndStateIn(
                session.getId(), coinPair, "BUY", ACTIVE_ORDER_STATES);
        if (hasPendingBuy) {
            log.warn("매수 스킵: 세션({})에 이미 활성 BUY 주문이 있습니다 ({})", session.getId(), coinPair);
            return;
        }

        BigDecimal ratio = session.getInvestRatio() != null ? session.getInvestRatio() : INVEST_RATIO;
        BigDecimal baseAmount = session.getAvailableKrw().multiply(ratio);
        BigDecimal investAmount = session.getMaxInvestment() != null
                ? baseAmount.min(session.getMaxInvestment())
                : baseAmount;
        if (investAmount.compareTo(BigDecimal.valueOf(5000)) < 0) {
            log.warn("매수 불가: 가용 자금 부족 ({}) sessionId={}",
                    session.getAvailableKrw(), session.getId());
            return;
        }

        BigDecimal quantity = investAmount.divide(price, 8, RoundingMode.DOWN);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // SL/TP 계산: 전략 제시값 우선, 없으면 세션 stopLossPct 기반 기본값 적용
        BigDecimal slPct = (session.getStopLossPct() != null)
                ? session.getStopLossPct()
                : new BigDecimal("5.0");
        BigDecimal stopLossPrice = (signal != null && signal.getSuggestedStopLoss() != null)
                ? signal.getSuggestedStopLoss()
                : price.multiply(BigDecimal.ONE.subtract(slPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                        .setScale(8, RoundingMode.HALF_DOWN);
        BigDecimal takeProfitPrice = (signal != null && signal.getSuggestedTakeProfit() != null)
                ? signal.getSuggestedTakeProfit()
                : price.multiply(BigDecimal.ONE.add(slPct.multiply(new BigDecimal("2")).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                        .setScale(8, RoundingMode.HALF_UP);

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
                .stopLossPrice(stopLossPrice)
                .takeProfitPrice(takeProfitPrice)
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
        // 매도 수량 검증 — position.size=null or 0 이면 매수 체결 미감지 상태
        if (pos.getSize() == null || pos.getSize().compareTo(BigDecimal.ZERO) <= 0) {
            // 매수 주문이 취소/실패됐는지 확인
            Optional<OrderEntity> cancelledBuy = orderRepository
                    .findByPositionIdOrderByCreatedAtDesc(pos.getId())
                    .stream()
                    .filter(o -> "BUY".equalsIgnoreCase(o.getSide()))
                    .filter(o -> "CANCELLED".equals(o.getState()) || "FAILED".equals(o.getState()))
                    .findFirst();

            if (cancelledBuy.isPresent()) {
                // 매수 취소 확정 — 포지션 종료 + 차감됐던 KRW 복원
                OrderEntity buyOrder = cancelledBuy.get();
                log.warn("매수 취소/실패 확인 — 포지션 종료 및 KRW 복원 (posId={}, sessionId={}, orderId={}, 복원금액={})",
                        pos.getId(), session.getId(), buyOrder.getId(), buyOrder.getQuantity());
                session.setAvailableKrw(session.getAvailableKrw().add(buyOrder.getQuantity()));
                sessionRepository.save(session);
                pos.setStatus("CLOSED");
                pos.setClosedAt(Instant.now());
                positionRepository.save(pos);
                return;
            }

            // 아직 주문 체결 대기 중 — 다음 틱 재시도
            log.warn("매도 건너뜀: position.size={} (sessionId={}, posId={}). 매수 체결 미감지 — 다음 틱에 재시도됩니다.",
                    pos.getSize(), session.getId(), pos.getId());
            return;
        }

        // 포지션 CLOSING 표시 — 중복 매도 신호 및 새 매수 진입 차단
        pos.setStatus("CLOSING");
        pos.setClosingAt(Instant.now());
        positionRepository.save(pos);

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

        // KRW 복원·손익 확정은 reconcileClosingPositions()에서 실제 체결가 기반으로 처리
        log.info("실전 매도 주문 제출 (sessionId={}): {} {}개 (CLOSING 상태, 체결 대기)",
                session.getId(), pos.getCoinPair(), pos.getSize());
    }

    private void updateSessionUnrealizedPnl(LiveTradingSessionEntity session,
                                              String coinPair, BigDecimal currentPrice) {
        positionRepository.findBySessionIdAndCoinPairAndStatus(
                session.getId(), coinPair, "OPEN").ifPresent(pos -> {
            BigDecimal unrealized = currentPrice.subtract(pos.getAvgPrice())
                    .multiply(pos.getSize());
            pos.setUnrealizedPnl(unrealized);
            positionRepository.save(pos);

            // 세션 총자산 업데이트 (size=0이면 매수 미체결 상태 — totalAssetKrw 갱신 보류)
            if (pos.getSize().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal posValue = pos.getSize().multiply(currentPrice);
                session.setTotalAssetKrw(session.getAvailableKrw().add(posValue));
                sessionRepository.save(session);
            }
        });
    }

    // -- 내부: 세션 포지션 청산 ----------------------------------

    private void closeSessionPositions(LiveTradingSessionEntity session, String reason) {
        List<PositionEntity> openPositions =
                positionRepository.findBySessionIdAndStatus(session.getId(), "OPEN");

        for (PositionEntity pos : openPositions) {
            try {
                // size=0 포지션: 매수 체결 미완료 상태 — KRW만 복원하고 종료 (SELL 주문 불필요)
                if (pos.getSize().compareTo(BigDecimal.ZERO) <= 0) {
                    List<OrderEntity> failedBuy = orderRepository
                            .findByPositionIdOrderByCreatedAtDesc(pos.getId())
                            .stream()
                            .filter(o -> "BUY".equalsIgnoreCase(o.getSide()))
                            .filter(o -> "CANCELLED".equals(o.getState()) || "FAILED".equals(o.getState()))
                            .findFirst()
                            .stream().toList();
                    if (!failedBuy.isEmpty() && failedBuy.get(0).getQuantity() != null) {
                        session.setAvailableKrw(
                                session.getAvailableKrw().add(failedBuy.get(0).getQuantity()));
                        sessionRepository.save(session);
                        log.warn("세션 종료 시 미체결 매수 포지션 KRW 복원 (posId={}, sessionId={}, 복원={})",
                                pos.getId(), session.getId(), failedBuy.get(0).getQuantity());
                    }
                    pos.setStatus("CLOSED");
                    pos.setClosedAt(Instant.now());
                    positionRepository.save(pos);
                    continue;
                }
                pos.setStatus("CLOSING");
                pos.setClosingAt(Instant.now());
                positionRepository.save(pos);

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

    // -- 성과 요약 -----------------------------------------------

    @Transactional(readOnly = true)
    public PerformanceSummaryResponse getPerformanceSummary() {
        List<LiveTradingSessionEntity> sessions = sessionRepository.findAll();

        // 세션 수와 무관하게 단 1회 쿼리로 전체 포지션 로드 (N+1 방지)
        List<Long> sessionIds = sessions.stream().map(LiveTradingSessionEntity::getId).toList();
        Map<Long, List<PositionEntity>> positionsBySession = sessionIds.isEmpty()
                ? Map.of()
                : positionRepository.findBySessionIdIn(sessionIds).stream()
                        .collect(Collectors.groupingBy(PositionEntity::getSessionId));

        BigDecimal totalRealizedPnl = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        BigDecimal totalInitialCapital = BigDecimal.ZERO;
        BigDecimal totalFeeAccum = BigDecimal.ZERO;
        int totalTrades = 0;
        int totalWins = 0;

        List<PerformanceSummaryResponse.SessionPerformance> sessionPerfs = new ArrayList<>();

        for (LiveTradingSessionEntity session : sessions) {
            List<PositionEntity> positions = positionsBySession.getOrDefault(session.getId(), List.of());
            List<PositionEntity> closed = positions.stream().filter(p -> "CLOSED".equals(p.getStatus())).toList();
            List<PositionEntity> open   = positions.stream().filter(p -> "OPEN".equals(p.getStatus())).toList();

            BigDecimal sessionRealized = closed.stream()
                    .map(p -> p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sessionUnrealized = open.stream()
                    .map(p -> p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sessionFee = closed.stream()
                    .map(p -> p.getPositionFee() != null ? p.getPositionFee() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int wins = (int) closed.stream()
                    .filter(p -> p.getRealizedPnl() != null && p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
                    .count();

            BigDecimal sessionPnl = sessionRealized.add(sessionUnrealized);
            BigDecimal sessionReturn = session.getInitialCapital().compareTo(BigDecimal.ZERO) > 0
                    ? sessionPnl.divide(session.getInitialCapital(), 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal sessionWinRate = closed.isEmpty() ? BigDecimal.ZERO
                    : new BigDecimal(wins).divide(new BigDecimal(closed.size()), 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP);

            sessionPerfs.add(PerformanceSummaryResponse.SessionPerformance.builder()
                    .sessionId(session.getId())
                    .strategyType(session.getStrategyType())
                    .coinPair(session.getCoinPair())
                    .timeframe(session.getTimeframe())
                    .status(session.getStatus())
                    .initialCapital(session.getInitialCapital())
                    .currentAsset(session.getTotalAssetKrw())
                    .realizedPnl(sessionRealized)
                    .unrealizedPnl(sessionUnrealized)
                    .totalPnl(sessionPnl)
                    .returnRatePct(sessionReturn)
                    .totalFee(sessionFee)
                    .totalTrades(closed.size())
                    .winCount(wins)
                    .winRatePct(sessionWinRate)
                    .startedAt(session.getStartedAt() != null ? session.getStartedAt().toString() : null)
                    .stoppedAt(session.getStoppedAt() != null ? session.getStoppedAt().toString() : null)
                    .build());

            totalRealizedPnl = totalRealizedPnl.add(sessionRealized);
            totalUnrealizedPnl = totalUnrealizedPnl.add(sessionUnrealized);
            totalInitialCapital = totalInitialCapital.add(session.getInitialCapital());
            totalFeeAccum = totalFeeAccum.add(sessionFee);
            totalTrades += closed.size();
            totalWins += wins;
        }

        BigDecimal totalPnl = totalRealizedPnl.add(totalUnrealizedPnl);
        BigDecimal returnRate = totalInitialCapital.compareTo(BigDecimal.ZERO) > 0
                ? totalPnl.divide(totalInitialCapital, 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal winRatePct = totalTrades > 0
                ? new BigDecimal(totalWins).divide(new BigDecimal(totalTrades), 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return PerformanceSummaryResponse.builder()
                .totalRealizedPnl(totalRealizedPnl)
                .totalUnrealizedPnl(totalUnrealizedPnl)
                .totalPnl(totalPnl)
                .totalInitialCapital(totalInitialCapital)
                .returnRatePct(returnRate)
                .totalFee(totalFeeAccum)
                .totalTrades(totalTrades)
                .winCount(totalWins)
                .lossCount(totalTrades - totalWins)
                .winRatePct(winRatePct)
                .sessions(sessionPerfs)
                .build();
    }

    // -- 서버 시작 복구 --------------------------------------------------

    /**
     * 서버 재시작 복구 — 미처리 주문 정리 + 고아 포지션 KRW 복원
     *
     * 처리 대상:
     * 1) PENDING + exchangeOrderId=null → FAILED (거래소에 제출되지 못한 주문)
     * 2) OPEN + size=0 포지션 중 활성 매수 주문이 없고 CANCELLED/FAILED 매수가 확정된 것
     *    → 포지션 CLOSED + 세션 KRW 복원
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void reconcileOnStartup() {
        log.info("서버 시작 복구: 미처리 주문 및 고아 포지션 정리 시작");
        try {
            reconcileInternal();
        } catch (Exception e) {
            log.error("서버 시작 복구 중 오류 발생 (WebSocket 연결은 계속 진행): {}", e.getMessage(), e);
        } finally {
            initWebSocket();
        }
    }

    private void reconcileInternal() {

        // 1. 거래소에 제출되지 못한 PENDING 주문 → FAILED
        List<OrderEntity> stuckPending = orderRepository.findByStateIn(List.of("PENDING"))
                .stream()
                .filter(o -> o.getExchangeOrderId() == null)
                .toList();
        for (OrderEntity order : stuckPending) {
            order.setState("FAILED");
            orderRepository.save(order);
            log.warn("재시작 복구: 미제출 PENDING 주문 → FAILED (orderId={})", order.getId());
        }

        // 2. size=0 OPEN 포지션 — 활성 매수 없고 CANCELLED/FAILED 매수 있으면 종료 + KRW 복원
        List<PositionEntity> orphanPositions = positionRepository.findByStatus("OPEN")
                .stream()
                .filter(pos -> pos.getSize().compareTo(BigDecimal.ZERO) <= 0)
                .toList();
        int recoveredCount = 0;
        for (PositionEntity pos : orphanPositions) {
            List<OrderEntity> buyOrders = orderRepository
                    .findByPositionIdOrderByCreatedAtDesc(pos.getId())
                    .stream()
                    .filter(o -> "BUY".equalsIgnoreCase(o.getSide()))
                    .toList();

            boolean hasCancelledBuy = buyOrders.stream()
                    .anyMatch(o -> "CANCELLED".equals(o.getState()) || "FAILED".equals(o.getState()));
            boolean hasActiveBuy = buyOrders.stream()
                    .anyMatch(o -> ACTIVE_ORDER_STATES.contains(o.getState()));

            if (hasCancelledBuy && !hasActiveBuy) {
                pos.setStatus("CLOSED");
                pos.setClosedAt(Instant.now());
                positionRepository.save(pos);

                if (pos.getSessionId() != null) {
                    sessionRepository.findById(pos.getSessionId()).ifPresent(session -> {
                        buyOrders.stream()
                                .filter(o -> "CANCELLED".equals(o.getState()) || "FAILED".equals(o.getState()))
                                .findFirst()
                                .ifPresent(buyOrder -> {
                                    if (buyOrder.getQuantity() != null) {
                                        session.setAvailableKrw(
                                                session.getAvailableKrw().add(buyOrder.getQuantity()));
                                        sessionRepository.save(session);
                                    }
                                });
                        log.warn("재시작 복구: 고아 포지션 종료 + KRW 복원 (posId={}, sessionId={})",
                                pos.getId(), pos.getSessionId());
                    });
                }
                recoveredCount++;
            }
        }

        log.info("서버 시작 복구 완료: FAILED 처리 {}건, 고아 포지션 복구 {}건 / 검사 {}건",
                stuckPending.size(), recoveredCount, orphanPositions.size());

        // 3. 재시작 전 CLOSING 상태로 남은 포지션 — 연결된 SELL 주문 기반으로 확정/롤백
        List<PositionEntity> closingPositions = positionRepository.findByStatus("CLOSING");
        for (PositionEntity pos : closingPositions) {
            List<OrderEntity> sellOrders = orderRepository
                    .findByPositionIdOrderByCreatedAtDesc(pos.getId())
                    .stream()
                    .filter(o -> "SELL".equalsIgnoreCase(o.getSide()))
                    .toList();
            if (sellOrders.isEmpty()) {
                pos.setStatus("OPEN");
                positionRepository.save(pos);
                log.warn("재시작 복구: CLOSING 포지션에 연결된 SELL 주문 없음 — OPEN 롤백 (posId={})", pos.getId());
            } else {
                OrderEntity latestSell = sellOrders.get(0);
                if ("FILLED".equals(latestSell.getState())) {
                    finalizeSellPosition(pos, latestSell);
                } else if ("FAILED".equals(latestSell.getState()) || "CANCELLED".equals(latestSell.getState())) {
                    pos.setStatus("OPEN");
                    positionRepository.save(pos);
                    log.warn("재시작 복구: CLOSING 포지션 OPEN 롤백 (posId={}, orderState={})",
                            pos.getId(), latestSell.getState());
                }
                // PENDING/SUBMITTED → 이후 reconcileClosingPositions에서 처리
            }
        }

    }

    /** WebSocket 리스너 등록 및 구독 연결 — reconcileOnStartup의 finally에서 항상 호출 */
    private void initWebSocket() {
        if (wsClient != null) {
            wsClient.addTickerListener(ticker ->
                eventPublisher.publishEvent(
                    new RealtimePriceEvent(ticker.getCode(), ticker.getTradePrice())));
            wsClient.setConnectionStateListener(exchangeHealthMonitor::setWebSocketConnected);
            log.info("WebSocket 실시간 시세 리스너 등록 완료");
        }
        refreshWsSubscription();
    }

    /** CLOSING 포지션 타임아웃 — 이 시간 초과 시 OPEN 롤백 */
    private static final long CLOSING_TIMEOUT_MINUTES = 5;

    /**
     * CLOSING 포지션 처리 — 매도 주문 체결/실패에 따라 청산 확정 또는 롤백 (5초 주기)
     *
     * executeSessionSell()은 포지션을 CLOSING으로만 표시하고 비동기 주문 제출.
     * 이 메서드가 실제 체결 결과를 확인해 처리한다:
     * - FILLED → 실제 체결가 기반 손익/수수료 확정 + 세션 KRW 복원
     * - FAILED / CANCELLED → OPEN 롤백 (다음 틱에서 재시도)
     * - 5분 초과 미체결 → OPEN 롤백 (좀비 포지션 방지 — BUY 신호 영구 차단 방어)
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void reconcileClosingPositions() {
        List<PositionEntity> closingPositions = positionRepository.findByStatus("CLOSING");
        if (closingPositions.isEmpty()) return;

        for (PositionEntity pos : closingPositions) {
            List<OrderEntity> sellOrders = orderRepository
                    .findByPositionIdOrderByCreatedAtDesc(pos.getId())
                    .stream()
                    .filter(o -> "SELL".equalsIgnoreCase(o.getSide()))
                    .toList();

            if (sellOrders.isEmpty()) {
                log.warn("CLOSING 포지션에 SELL 주문 없음 — OPEN 롤백 (posId={})", pos.getId());
                pos.setStatus("OPEN");
                pos.setClosingAt(null);
                positionRepository.save(pos);
                continue;
            }

            OrderEntity latestSell = sellOrders.get(0);
            switch (latestSell.getState()) {
                case "FILLED" -> finalizeSellPosition(pos, latestSell);
                case "FAILED", "CANCELLED" -> {
                    log.warn("매도 주문 {} — 포지션 OPEN 롤백 (orderId={}, posId={}, sessionId={})",
                            latestSell.getState(), latestSell.getId(), pos.getId(), pos.getSessionId());
                    pos.setStatus("OPEN");
                    pos.setClosingAt(null);
                    positionRepository.save(pos);
                }
                default -> {
                    // PENDING/SUBMITTED/PARTIAL_FILLED — 체결 대기
                    // 단, closingAt 기준 5분 초과 시 좀비 포지션 방지를 위해 OPEN 롤백
                    Instant closingAt = pos.getClosingAt();
                    if (closingAt != null &&
                            Duration.between(closingAt, Instant.now()).toMinutes() >= CLOSING_TIMEOUT_MINUTES) {
                        log.warn("CLOSING 타임아웃 ({}분 초과) — OPEN 롤백 (posId={}, sessionId={}, orderId={}, state={})",
                                CLOSING_TIMEOUT_MINUTES, pos.getId(), pos.getSessionId(),
                                latestSell.getId(), latestSell.getState());
                        pos.setStatus("OPEN");
                        pos.setClosingAt(null);
                        positionRepository.save(pos);
                    }
                }
            }
        }
    }

    /**
     * 매도 주문 체결 확정 — 실제 체결가 기반 손익/수수료 계산 + 세션 KRW 복원
     * 멱등성 보장: 이미 CLOSED인 포지션은 중복 처리하지 않음
     */
    private void finalizeSellPosition(PositionEntity pos, OrderEntity filledOrder) {
        // 멱등성 guard — reconcileOnStartup() + reconcileClosingPositions() 동시 호출 방어
        if ("CLOSED".equals(pos.getStatus())) {
            log.debug("finalizeSellPosition 스킵: 이미 CLOSED (posId={})", pos.getId());
            return;
        }
        BigDecimal fillPrice = filledOrder.getPrice() != null ? filledOrder.getPrice() : pos.getAvgPrice();
        BigDecimal soldQty = filledOrder.getFilledQuantity() != null
                ? filledOrder.getFilledQuantity() : pos.getSize();

        BigDecimal proceeds = soldQty.multiply(fillPrice);
        BigDecimal fee = proceeds.multiply(FEE_RATE);
        BigDecimal netProceeds = proceeds.subtract(fee);
        BigDecimal realizedPnl = netProceeds.subtract(soldQty.multiply(pos.getAvgPrice()));

        pos.setRealizedPnl(realizedPnl);
        pos.setPositionFee(fee);
        pos.setUnrealizedPnl(BigDecimal.ZERO);
        pos.setStatus("CLOSED");
        pos.setClosedAt(Instant.now());
        positionRepository.save(pos);

        if (pos.getSessionId() != null) {
            sessionRepository.findById(pos.getSessionId()).ifPresent(session -> {
                session.setAvailableKrw(session.getAvailableKrw().add(netProceeds));
                session.setTotalAssetKrw(session.getTotalAssetKrw().subtract(fee));
                sessionRepository.save(session);
                log.info("매도 체결 확정 (sessionId={}, posId={}): {} {}개 @ {} 손익={} 수수료={}",
                        session.getId(), pos.getId(), pos.getCoinPair(),
                        soldQty, fillPrice, realizedPnl, fee);
                telegramService.bufferTradeEvent(
                        "세션#" + session.getId(), pos.getCoinPair(), "SELL",
                        fillPrice, soldQty, fee, realizedPnl, "전략 매도");
            });
        }
    }

    private LiveTradingSessionEntity getSessionOrThrow(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    /**
     * WebSocket 실시간 시세 이벤트 핸들러 — 코인별 5초 throttle 손절 체크
     *
     * WS 콜백 스레드(upbit-ws-scheduler)에서 직접 DB 접근하면 ping/pong 스케줄링이 지연될 수 있으므로
     * ApplicationEventPublisher → @Async("marketDataExecutor") 패턴으로 디커플링.
     */
    @EventListener
    @Async("marketDataExecutor")
    @Transactional
    public void onRealtimePriceEvent(RealtimePriceEvent event) {
        String coinCode = event.getCoinCode();
        BigDecimal price = event.getPrice();
        long now = System.currentTimeMillis();

        // 1. 가격 이력 업데이트 (throttle 전 — 항상 기록)
        updatePriceHistory(coinCode, price, now);

        // 2. 급등/급락 감지
        BigDecimal spikeRate = calcSpikeRate(coinCode, now);
        boolean spikeDown = spikeRate.compareTo(SPIKE_DOWN_THRESHOLD) <= 0;
        boolean spikeUp   = spikeRate.compareTo(SPIKE_UP_THRESHOLD) >= 0;

        // 3. throttle — 급등락 시 1초, 평상시 5초
        if (spikeDown || spikeUp) {
            Long lastSpike = spikeCheckLastMs.get(coinCode);
            if (lastSpike != null && now - lastSpike < SPIKE_CHECK_INTERVAL_MS) return;
            spikeCheckLastMs.put(coinCode, now);
            log.info("급{}락 감지: {} {}%/30s",
                    spikeDown ? "하" : "상", coinCode, spikeRate.setScale(2, RoundingMode.HALF_UP));
        } else {
            Long lastMs = rtStopLossLastCheckMs.get(coinCode);
            if (lastMs != null && now - lastMs < RT_STOPLOSS_CHECK_INTERVAL_MS) return;
        }
        rtStopLossLastCheckMs.put(coinCode, now);

        List<LiveTradingSessionEntity> sessions = sessionRepository.findByStatus("RUNNING");
        for (LiveTradingSessionEntity session : sessions) {
            if (!coinCode.equals(session.getCoinPair())) continue;

            Optional<PositionEntity> openPos = positionRepository
                    .findBySessionIdAndCoinPairAndStatus(session.getId(), coinCode, "OPEN");
            if (openPos.isEmpty()) continue;

            PositionEntity pos = openPos.get();
            if (pos.getAvgPrice() == null || pos.getAvgPrice().compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal pnlPct = price.subtract(pos.getAvgPrice())
                    .divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            BigDecimal stopLossPct = session.getStopLossPct() != null
                    ? session.getStopLossPct() : new BigDecimal("5.0");

            // 기존 손절 체크 (stopLossPrice 절대가 우선, 없으면 stopLossPct %)
            boolean slTriggered = (pos.getStopLossPrice() != null)
                    ? price.compareTo(pos.getStopLossPrice()) <= 0
                    : pnlPct.compareTo(stopLossPct.negate()) <= 0;

            if (slTriggered) {
                log.warn("실시간 손절 발동 (WS): sessionId={}, {}, 손익={}%",
                        session.getId(), coinCode, pnlPct);
                telegramService.notifyStopLoss(coinCode, pnlPct.doubleValue(), session.getId());
                executeSessionSell(session, pos, price, "실시간 손절(WS) — 손익률 " + pnlPct + "%");
                continue;
            }

            // 급락 처리 — 손실 중인 포지션 SL 조임 (단방향 ratchet, 한번 조이면 완화 안 됨)
            if (spikeDown && pnlPct.compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal newSl = price.multiply(BigDecimal.ONE.subtract(SL_TIGHTEN_MARGIN))
                        .setScale(8, RoundingMode.HALF_DOWN);
                BigDecimal currentSl = pos.getStopLossPrice();
                if (currentSl == null || newSl.compareTo(currentSl) > 0) {
                    pos.setStopLossPrice(newSl);
                    positionRepository.save(pos);
                    log.info("급락 SL 조임: sessionId={}, {} SL {} → {}",
                            session.getId(), coinCode, currentSl, newSl);
                }
            }

            // 급등 처리 — 수익 중인 포지션 TP 트레일링 (단방향 ratchet, 고점 추적)
            if (spikeUp && pnlPct.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal newTp = price.multiply(BigDecimal.ONE.subtract(TRAILING_STOP_MARGIN))
                        .setScale(8, RoundingMode.HALF_UP);
                BigDecimal currentTp = pos.getTakeProfitPrice();
                if (currentTp == null || newTp.compareTo(currentTp) > 0) {
                    pos.setTakeProfitPrice(newTp);
                    positionRepository.save(pos);
                    log.info("급등 TP 트레일링: sessionId={}, {} TP {} → {}",
                            session.getId(), coinCode, currentTp, newTp);
                }
            }
        }
    }

    private void updatePriceHistory(String coin, BigDecimal price, long nowMs) {
        Deque<PriceSnapshot> history = priceHistory.computeIfAbsent(coin, k -> new ArrayDeque<>());
        history.addLast(new PriceSnapshot(nowMs, price));
        // TTL 초과 항목 제거
        while (!history.isEmpty() && nowMs - history.peekFirst().timestampMs > SPIKE_HISTORY_TTL_MS) {
            history.pollFirst();
        }
    }

    private BigDecimal calcSpikeRate(String coin, long nowMs) {
        Deque<PriceSnapshot> history = priceHistory.get(coin);
        if (history == null || history.size() < 2) return BigDecimal.ZERO;

        PriceSnapshot latest = history.peekLast();
        PriceSnapshot windowStart = null;
        for (PriceSnapshot snap : history) {
            if (nowMs - snap.timestampMs <= SPIKE_WINDOW_MS) {
                windowStart = snap;
                break;
            }
        }
        if (windowStart == null || windowStart == latest) return BigDecimal.ZERO;
        if (windowStart.price.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        return latest.price.subtract(windowStart.price)
                .divide(windowStart.price, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * RUNNING 세션의 구독 코인 목록에 맞게 WebSocket 구독을 갱신한다.
     * 세션 시작/정지/비상정지 및 서버 재시작 시 호출된다.
     */
    private void refreshWsSubscription() {
        if (wsClient == null) return;
        List<String> coins = sessionRepository.findByStatus("RUNNING").stream()
                .map(LiveTradingSessionEntity::getCoinPair)
                .distinct()
                .collect(Collectors.toList());
        if (coins.isEmpty()) {
            wsClient.disconnect();
            log.info("WebSocket 구독 해제 (실행 중인 세션 없음)");
        } else {
            wsClient.connect(coins);
            log.info("WebSocket 구독 갱신: {}", coins);
        }
    }

    private static final class PriceSnapshot {
        final long timestampMs;
        final BigDecimal price;
        PriceSnapshot(long ts, BigDecimal p) { this.timestampMs = ts; this.price = p; }
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
