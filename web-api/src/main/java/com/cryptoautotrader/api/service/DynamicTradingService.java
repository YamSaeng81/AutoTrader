package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.StrategyTypeEnabledRepository;
import com.cryptoautotrader.api.util.TimeframeUtils;
import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.core.selector.BlackSwanGuard;
import com.cryptoautotrader.core.selector.BtcMarketGuard;
import com.cryptoautotrader.core.selector.Ema200RegimeGate;
import com.cryptoautotrader.core.selector.RangeRegimeGate;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 동적 멀티코인 세션 서비스.
 *
 * <h3>동작 흐름</h3>
 * <pre>
 * [SCANNING]
 *   매 60초: 워치리스트 코인들에 전략 평가 → BUY 신호 첫 번째 코인 매수
 *   → scanState = POSITION_MONITORING, currentCoinPair = 매수 코인
 *
 * [POSITION_MONITORING]
 *   매 60초: currentCoinPair만 평가 → SL/TP/SELL 신호 시 매도
 *   → scanState = SCANNING, currentCoinPair = null
 * </pre>
 */
@Service
@Slf4j
public class DynamicTradingService {

    // HEIKIN_ASHI_STOCH 등 EMA(200) 기반 전략은 최소 201개 닫힌 캔들이 필요하다. 200개만 가져오면
    // closedCandleSlice()가 미마감 캔들을 하나 더 잘라내 최대 199개만 남아 구조적으로 절대 신호를
    // 낼 수 없었다(2026-07-01 실전 로그 분석 — 해당 전략 세션 100% "데이터 부족"). 라이브 매매
    // (LiveTradingService.CANDLE_LOOKBACK)·백테스트(BacktestEngine.MAX_LOOKBACK)와 동일하게
    // 500으로 맞춰 백테스트·실거래 신호 괴리를 줄인다.
    private static final int CANDLE_LOOKBACK = 500;
    private static final List<String> ACTIVE_ORDER_STATES = List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED");
    private static final long MIN_HOLD_MINUTES = 180;
    private static final BigDecimal MIN_PNL_PCT_FOR_SELL = new BigDecimal("0.30");
    private static final BigDecimal LOSS_ESCAPE_THRESHOLD = new BigDecimal("-1.00");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    /**
     * CLOSING 상태 진입 시각 — 이 시간 초과 시 reconcileClosingPositions()에서 OPEN 롤백.
     * OrderExecutionEngine.ORDER_TIMEOUT(5분)보다 반드시 길어야 한다 — LiveTradingService와
     * 동일한 race 방지 이유 (2026-07-02 감사 D-5).
     */
    private static final long CLOSING_TIMEOUT_MINUTES = 8;
    private static final String SESSION_KIND = "DYNAMIC";

    private final DynamicSessionRepository dynamicSessionRepo;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final WatchlistFilterService watchlistFilterService;
    private final OrderExecutionEngine orderExecutionEngine;
    private final TelegramNotificationService telegramService;
    private final ObjectMapper objectMapper;
    private final DynamicSessionBalanceUpdater balanceUpdater;
    private final StrategyLogRepository strategyLogRepository;
    private final WsSubscriptionManager wsSubscriptionManager;
    private final StrategyLiveStatusRegistry strategyLiveStatusRegistry;
    private final StrategyTypeEnabledRepository strategyTypeEnabledRepository;

    @Autowired(required = false)
    private UpbitRestClient upbitRestClient;

    /** 코인별 마지막 실시간(WS) SL/TP 점검 시각 — 5초 throttle */
    private final Map<String, Long> rtCheckLastMs = new ConcurrentHashMap<>();
    private static final long RT_CHECK_INTERVAL_MS = 5_000;

    /**
     * self-invocation 문제 해결용 — tick()이 @Scheduled(비-프록시 경유)로 직접 호출되면
     * processTick() 내부의 @Transactional이 Spring 프록시를 우회해 무시된다.
     * @Lazy 자기 참조로 프록시를 경유시켜 트랜잭션이 실제로 적용되도록 한다.
     */
    @Lazy
    @Autowired
    private DynamicTradingService self;

    /** 세션+코인 조합별 stateful 전략 인스턴스 (MarketRegimeDetector 상태 격리) */
    private final Map<String, Strategy> strategyInstances = new ConcurrentHashMap<>();

    /** 세션+코인 조합별 마지막으로 평가한 닫힌 캔들 시각 */
    private final Map<String, Instant> lastEvaluatedCandle = new ConcurrentHashMap<>();

    public DynamicTradingService(DynamicSessionRepository dynamicSessionRepo,
                                  PositionRepository positionRepository,
                                  OrderRepository orderRepository,
                                  WatchlistFilterService watchlistFilterService,
                                  OrderExecutionEngine orderExecutionEngine,
                                  TelegramNotificationService telegramService,
                                  ObjectMapper objectMapper,
                                  DynamicSessionBalanceUpdater balanceUpdater,
                                  StrategyLogRepository strategyLogRepository,
                                  WsSubscriptionManager wsSubscriptionManager,
                                  StrategyLiveStatusRegistry strategyLiveStatusRegistry,
                                  StrategyTypeEnabledRepository strategyTypeEnabledRepository) {
        this.dynamicSessionRepo   = dynamicSessionRepo;
        this.positionRepository   = positionRepository;
        this.orderRepository      = orderRepository;
        this.watchlistFilterService = watchlistFilterService;
        this.orderExecutionEngine = orderExecutionEngine;
        this.telegramService      = telegramService;
        this.objectMapper         = objectMapper;
        this.balanceUpdater       = balanceUpdater;
        this.strategyLogRepository = strategyLogRepository;
        this.wsSubscriptionManager = wsSubscriptionManager;
        this.strategyLiveStatusRegistry = strategyLiveStatusRegistry;
        this.strategyTypeEnabledRepository = strategyTypeEnabledRepository;
    }

    // ── 세션 생성 ──────────────────────────────────────────────────

    @Transactional
    public DynamicSessionEntity createSession(DynamicSessionRequest req) {
        StrategyRegistry.get(req.getStrategyType()); // 유효성 검증

        // 비활성 전략 차단 — strategy_type_enabled에서 꺼진 전략은 세션 생성 거부.
        // (UI 드롭다운 필터만으로는 select 표시/상태 불일치 등으로 우회될 수 있어 서버에서 강제)
        boolean typeEnabled = strategyTypeEnabledRepository.findById(req.getStrategyType())
                .map(e -> Boolean.TRUE.equals(e.getIsActive()))
                .orElse(true);   // 테이블에 없으면 기본 활성 (StrategyController와 동일 규칙)
        if (!typeEnabled) {
            throw new IllegalArgumentException(String.format(
                    "전략 '%s'은(는) 비활성화되어 세션을 생성할 수 없습니다. 전략 관리에서 활성화 후 사용하세요.",
                    req.getStrategyType()));
        }

        // 전략 거버넌스 검증 — BLOCKED/DEPRECATED 전략은 동적 세션 생성도 차단한다.
        // 기존에는 이 검사가 라이브 세션에도 동적 세션에도 강제되지 않아, 두 경로 모두
        // StrategyLiveStatusRegistry 라벨을 우회해 BLOCKED 전략으로 실돈 세션 생성이 가능했다.
        if (strategyLiveStatusRegistry.isBlocked(req.getStrategyType())) {
            StrategyLiveStatusRegistry.StatusEntry status = strategyLiveStatusRegistry.getStatus(req.getStrategyType());
            throw new IllegalArgumentException(String.format(
                    "전략 '%s'은(는) 동적 세션 생성이 차단되었습니다 (%s): %s",
                    req.getStrategyType(), status.readiness(), status.reason()));
        }

        BigDecimal investRatio = normalizeRatio(req.getInvestRatio(), new BigDecimal("0.80"));
        BigDecimal stopLoss    = req.getStopLossPct() != null ? req.getStopLossPct() : new BigDecimal("5.0");

        DynamicSessionEntity session = DynamicSessionEntity.builder()
                .strategyType(req.getStrategyType())
                .timeframe(req.getTimeframe())
                .initialCapital(req.getInitialCapital())
                .availableKrw(req.getInitialCapital())
                .totalAssetKrw(req.getInitialCapital())
                .investRatio(investRatio)
                .stopLossPct(stopLoss)
                .status("CREATED")
                .scanState("SCANNING")
                .maxCandidateSize(req.getMaxCandidateSize() != null ? req.getMaxCandidateSize() : 30)
                .targetWatchSize(req.getTargetWatchSize() != null ? req.getTargetWatchSize() : 10)
                .minAtrPct(req.getMinAtrPct() != null ? req.getMinAtrPct() : new BigDecimal("0.5"))
                .maxSpreadPct(req.getMaxSpreadPct() != null ? req.getMaxSpreadPct() : new BigDecimal("0.1"))
                .watchlistRefreshMin(req.getWatchlistRefreshMin() != null ? req.getWatchlistRefreshMin() : 60)
                .build();

        session = dynamicSessionRepo.save(session);
        log.info("[Dynamic] 세션 생성: id={} strategy={} timeframe={} capital={}",
                session.getId(), session.getStrategyType(), session.getTimeframe(), session.getInitialCapital());
        return session;
    }

    // ── 세션 시작 ──────────────────────────────────────────────────

    @Transactional
    public DynamicSessionEntity startSession(Long sessionId) {
        DynamicSessionEntity session = getOrThrow(sessionId);
        if ("RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("이미 실행 중입니다: id=" + sessionId);
        }
        session.setStatus("RUNNING");
        session.setStartedAt(Instant.now());
        session.setStoppedAt(null);
        session = dynamicSessionRepo.save(session);
        log.info("[Dynamic] 세션 시작: id={} {} {}", sessionId, session.getStrategyType(), session.getTimeframe());
        telegramService.notifySessionStarted(sessionId, session.getStrategyType(),
                "멀티코인-동적", session.getTimeframe(), session.getInitialCapital().longValue());
        refreshWsSubscription();
        return session;
    }

    // ── 세션 정지 ──────────────────────────────────────────────────

    @Transactional
    public DynamicSessionEntity stopSession(Long sessionId) {
        DynamicSessionEntity session = getOrThrow(sessionId);
        if (!"RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("실행 중이 아닙니다: id=" + sessionId);
        }
        closeOpenPositions(session, "세션 정지 — 포지션 청산");
        session.setStatus("STOPPED");
        session.setStoppedAt(Instant.now());
        clearSessionState(sessionId);
        session = dynamicSessionRepo.save(session);
        log.info("[Dynamic] 세션 정지: id={}", sessionId);
        refreshWsSubscription();
        return session;
    }

    // ── 세션 비상 정지 ─────────────────────────────────────────────

    @Transactional
    public DynamicSessionEntity emergencyStop(Long sessionId) {
        DynamicSessionEntity session = getOrThrow(sessionId);
        closeOpenPositions(session, "비상 정지 — 강제 청산");
        session.setStatus("EMERGENCY_STOPPED");
        session.setStoppedAt(Instant.now());
        clearSessionState(sessionId);
        session = dynamicSessionRepo.save(session);
        log.error("[Dynamic] 세션 비상 정지 완료: id={}", sessionId);
        refreshWsSubscription();
        return session;
    }

    // ── 세션 삭제 (soft) ───────────────────────────────────────────

    @Transactional
    public void deleteSession(Long sessionId) {
        DynamicSessionEntity session = getOrThrow(sessionId);
        if ("RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("실행 중인 세션은 삭제할 수 없습니다. 먼저 정지하세요.");
        }

        // 정지 시 청산이 누락된 orphan OPEN 포지션 정리 (LiveTradingService.deleteSession과 동일 정책)
        List<PositionEntity> openPositions =
                positionRepository.findBySessionKindAndSessionIdAndStatus(SESSION_KIND, sessionId, "OPEN");
        for (PositionEntity pos : openPositions) {
            pos.setStatus("CLOSED");
            pos.setClosedAt(Instant.now());
            positionRepository.save(pos);
            log.warn("[Dynamic] 세션 삭제 시 미청산 포지션 강제 종료: posId={} {} (sessionId={})",
                    pos.getId(), pos.getCoinPair(), sessionId);
        }

        // soft-delete — 행/링크 보존, 상태만 DELETED (전략로그/주문로그 조회 유지)
        session.setStatus("DELETED");
        if (session.getStoppedAt() == null) session.setStoppedAt(Instant.now());
        dynamicSessionRepo.save(session);
        clearSessionState(sessionId);
        log.info("[Dynamic] 세션 삭제(soft) 완료: id={} → status=DELETED", sessionId);
    }

    // ── 조회 ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DynamicSessionEntity> listSessions() {
        // DELETED 세션은 목록에서 제외 (로그 조회용 getSessionIndex()에는 유지)
        return dynamicSessionRepo.findAllByOrderByCreatedAtDesc().stream()
                .filter(s -> !"DELETED".equals(s.getStatus()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DynamicSessionEntity getSession(Long sessionId) {
        return getOrThrow(sessionId);
    }

    /**
     * 세션 인덱스 — 전략로그/주문로그 선택 UI용. {@code TradingController.sessionIndex()}가
     * 라이브 세션 인덱스에 이 목록을 합쳐 반환한다. dynamic_session 은 hard/soft delete가 없으므로
     * 테이블 전체를 그대로 반환한다.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSessionIndex() {
        return dynamicSessionRepo.findAllByOrderByCreatedAtDesc().stream()
                .map(s -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("sessionId", s.getId());
                    m.put("strategyType", s.getStrategyType());
                    m.put("coinPair", s.getCurrentCoinPair() != null ? s.getCurrentCoinPair() : "멀티코인");
                    m.put("status", s.getStatus());
                    m.put("sessionType", SESSION_KIND);
                    return m;
                })
                .toList();
    }

    // ── 스케줄: 60초마다 실행 ─────────────────────────────────────

    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void tick() {
        List<DynamicSessionEntity> running = dynamicSessionRepo.findByStatus("RUNNING");
        if (running.isEmpty()) return;

        for (DynamicSessionEntity session : running) {
            try {
                // self 프록시를 경유해야 @Transactional이 실제로 적용된다 (self-invocation 우회 방지)
                self.processTick(session);
            } catch (Exception e) {
                log.error("[Dynamic] 세션 tick 오류 (id={}): {}", session.getId(), e.getMessage(), e);
            }
        }
    }

    // ── 내부: tick 분기 ────────────────────────────────────────────

    @Transactional
    public void processTick(DynamicSessionEntity session) {
        Long sid = session.getId();
        // tick()에서 넘어온 엔티티는 트랜잭션 밖에서 읽은 스냅샷 — 그 사이 WS 매도 등으로
        // scanState가 바뀌었을 수 있으므로 최신 상태를 다시 읽어 분기한다.
        DynamicSessionEntity fresh = dynamicSessionRepo.findById(sid).orElse(null);
        if (fresh == null || !"RUNNING".equals(fresh.getStatus())) return;

        if ("SCANNING".equals(fresh.getScanState())) {
            processScanningTick(fresh);
        } else {
            processMonitoringTick(fresh);
        }
    }

    /**
     * SCANNING 단계에서 BUY 신호를 낸 코인 후보 — 전체 워치리스트 평가 후 최고 강도 신호를
     * 선택하기 위해 즉시 실행하지 않고 임시 보관한다.
     */
    // package-private (private 아님) — DynamicScanSelectionTest에서 선택 로직 단위 테스트를 위해 필요
    record BuyCandidate(String coinPair, List<Candle> evalCandles,
                         StrategySignal signal, StrategyLogEntity signalLog) {}

    /**
     * BUY 후보 중 신호 강도(strength)가 가장 높은 것을 선택한다. 동률이면 먼저 평가된(워치리스트
     * 순서상 앞선) 코인을 유지한다(스트림 max()는 첫 최댓값을 보존).
     */
    static BuyCandidate pickBestBuyCandidate(List<BuyCandidate> candidates) {
        return candidates.stream()
                .max(java.util.Comparator.comparing(c -> c.signal().getStrength()))
                .orElseThrow();
    }

    /**
     * SCANNING: 워치리스트 전체 코인을 평가한 뒤, BUY 신호를 낸 코인 중 신호 강도(strength)가
     * 가장 높은 코인 하나에만 진입한다.
     *
     * <p>이전에는 워치리스트를 거래대금 내림차순으로 순회하다 첫 BUY 신호에서 즉시 진입해,
     * 실제로는 신호 품질이 아니라 "거래대금 순위"가 진입 코인을 결정하고 있었다
     * (2026-07-02 종합분석 DM-1). 전체 평가 후 최고 confidence 선택으로 개선.</p>
     */
    @Transactional
    public void processScanningTick(DynamicSessionEntity session) {
        Long sid = session.getId();
        List<String> watchlist = resolveWatchlist(session);

        if (watchlist.isEmpty()) {
            // 진단용 — 워치리스트가 비는 것은 ATR/스프레드 필터가 너무 빡빡하거나 거래소 응답
            // 문제일 수 있다. 어떤 설정으로 비었는지 바로 알 수 있도록 INFO 로 남긴다.
            log.info("[Dynamic] 워치리스트 비어 있음 — 이번 틱 스킵 (id={}, maxCandidate={} target={} "
                            + "minAtrPct={}% maxSpreadPct={} timeframe={})",
                    sid, session.getMaxCandidateSize(), session.getTargetWatchSize(),
                    session.getMinAtrPct(), session.getMaxSpreadPct(), session.getTimeframe());
            return;
        }

        log.info("[Dynamic] SCANNING 시작: id={} 감시목록({})={}", sid, watchlist.size(), watchlist);

        // 진단용 게이트 차단 집계 — 매수가 막힐 때 어느 단계에서 막히는지 한눈에 보기 위함
        int insufficientCandles = 0;
        int staleCandle = 0;
        int holdCount = 0;
        int sellCount = 0;
        int ema200Blocked = 0;
        int rangeBlocked = 0;
        int blackSwanBlocked = 0;
        int btcMarketGuardBlocked = 0;
        List<BuyCandidate> buyCandidates = new java.util.ArrayList<>();

        // BTC_MARKET_GUARD — 워치리스트 전체가 같은 timeframe을 쓰므로 틱당 한 번만 조회한다
        // (2026-07-02 codex 분석 §6, BTC 1시간 -1.5% 급락 시 코인 무관 신규 진입 차단).
        List<Candle> btcCandles = fetchCandles("KRW-BTC", session.getTimeframe());
        BtcMarketGuard.Result btcMarketGuard = BtcMarketGuard.check(
                closedCandleSlice(btcCandles, session.getTimeframe()));

        for (String coinPair : watchlist) {
            List<Candle> candles = fetchCandles(coinPair, session.getTimeframe());
            if (candles.size() < 15) {
                insufficientCandles++;
                log.debug("[Dynamic] 캔들 부족 스킵: {} ({}개)", coinPair, candles.size());
                continue;
            }

            List<Candle> evalCandles = closedCandleSlice(candles, session.getTimeframe());
            String candleKey = sid + ":" + coinPair;
            Instant closedTime = evalCandles.get(evalCandles.size() - 1).getTime();
            Instant prevEval = lastEvaluatedCandle.get(candleKey);
            if (prevEval != null && !closedTime.isAfter(prevEval)) {
                staleCandle++;
                log.debug("[Dynamic] 닫힌 캔들 미갱신 스킵: {}", coinPair);
                continue;
            }
            lastEvaluatedCandle.put(candleKey, closedTime);

            Strategy strategy = resolveStrategy(sid, coinPair, session.getStrategyType());
            StrategySignal signal = strategy.evaluate(evalCandles, Map.of("coinPair", coinPair));
            BigDecimal evalPrice = evalCandles.get(evalCandles.size() - 1).getClose();

            if (signal.getAction() == StrategySignal.Action.BUY
                    && !Ema200RegimeGate.allowsBuy(evalCandles, coinPair)) {
                ema200Blocked++;
                log.info("[Dynamic] EMA200 BUY 차단: {} (id={})", coinPair, sid);
                signal = StrategySignal.hold("EMA200 레짐 필터 — 현재가 EMA200 이하");
            }

            if (signal.getAction() == StrategySignal.Action.BUY
                    && RangeRegimeGate.isBlocked(session.getStrategyType())) {
                try {
                    MarketRegime regime = new MarketRegimeDetector().detectRaw(evalCandles);
                    if (regime == MarketRegime.RANGE) {
                        rangeBlocked++;
                        log.info("[Dynamic] RANGE 레짐 BUY 차단: {} (id={})", coinPair, sid);
                        signal = StrategySignal.hold("RANGE 레짐 — 추세 추종 전략 횡보장 신규 진입 차단");
                    }
                } catch (Exception ignored) {}
            }

            // BLACK_SWAN_GUARD: 코인별 서킷 브레이커 — 1시간 내 -5% 급락 또는 거래량 5배 급증 시
            // 신규 진입 차단 (2026-04-30 로드맵 ⭐⭐⭐, 2026-07-02 구현).
            if (signal.getAction() == StrategySignal.Action.BUY) {
                BlackSwanGuard.Result guard = BlackSwanGuard.check(evalCandles);
                if (guard.triggered()) {
                    blackSwanBlocked++;
                    log.warn("[Dynamic] BLACK_SWAN_GUARD 발동 — BUY 차단: {} (id={}): {}",
                            coinPair, sid, guard.reason());
                    signal = StrategySignal.hold("BLACK_SWAN_GUARD 발동 — " + guard.reason());
                }
            }

            // BTC_MARKET_GUARD: BTC 1시간 -1.5% 급락 시 코인 무관 전체 신규 진입 차단.
            if (signal.getAction() == StrategySignal.Action.BUY && btcMarketGuard.triggered()) {
                btcMarketGuardBlocked++;
                log.warn("[Dynamic] BTC_MARKET_GUARD 발동 — BUY 차단: {} (id={}): {}",
                        coinPair, sid, btcMarketGuard.reason());
                signal = StrategySignal.hold("BTC_MARKET_GUARD 발동 — " + btcMarketGuard.reason());
            }

            // 코인별 평가 결과 로그 (BUY/SELL은 INFO, HOLD는 DEBUG) — 전략로그 페이지 노출용으로 DB에도 저장
            if (signal.getAction() != StrategySignal.Action.HOLD) {
                log.info("[Dynamic] 평가결과: {} → {} ({})", coinPair, signal.getAction(), signal.getReason());
            } else {
                holdCount++;
                log.debug("[Dynamic] 평가결과: {} → HOLD ({})", coinPair, signal.getReason());
            }
            if (signal.getAction() == StrategySignal.Action.SELL) sellCount++;

            StrategyLogEntity signalLog = saveStrategyLog(sid, session.getStrategyType(), coinPair, signal, evalPrice);

            if (signal.getAction() == StrategySignal.Action.BUY) {
                buyCandidates.add(new BuyCandidate(coinPair, evalCandles, signal, signalLog));
            }
        }

        if (buyCandidates.isEmpty()) {
            log.info("[Dynamic] SCANNING 완료: 진입 조건 없음 (id={}, 감시 {}개) — "
                            + "HOLD={} SELL={} EMA200차단={} RANGE차단={} 블랙스완차단={} BTC급락차단={} 캔들부족={} 캔들미갱신={}",
                    sid, watchlist.size(), holdCount, sellCount, ema200Blocked, rangeBlocked,
                    blackSwanBlocked, btcMarketGuardBlocked, insufficientCandles, staleCandle);
            return;
        }

        // 전체 워치리스트 평가 완료 — BUY 후보 중 신호 강도(strength)가 가장 높은 코인 하나만 진입.
        BuyCandidate best = pickBestBuyCandidate(buyCandidates);

        log.info("[Dynamic] BUY 신호 진입: {} {} strength={} (id={}, 후보 {}개 중 최고)",
                best.coinPair(), best.signal().getReason(), best.signal().getStrength(), sid, buyCandidates.size());
        String blockedReason = executeBuy(session, best.coinPair(), best.evalCandles(), best.signal());
        updateSignalQuality(best.signalLog(), blockedReason == null, blockedReason);

        for (BuyCandidate other : buyCandidates) {
            if (other == best) continue;
            String reason = String.format("다른 코인 신호가 더 강함 — %s(strength=%s) 선택, 본 신호(strength=%s) 미선택",
                    best.coinPair(), best.signal().getStrength(), other.signal().getStrength());
            updateSignalQuality(other.signalLog(), false, reason);
        }
    }

    /** POSITION_MONITORING: 보유 코인만 평가 → SL/TP/SELL 처리 */
    @Transactional
    public void processMonitoringTick(DynamicSessionEntity session) {
        Long sid = session.getId();
        String coinPair = session.getCurrentCoinPair();

        if (coinPair == null) {
            transitionToScanning(sid);
            return;
        }

        List<Candle> candles = fetchCandles(coinPair, session.getTimeframe());
        if (candles.isEmpty()) return;

        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();

        Optional<PositionEntity> posOpt = positionRepository
                .findBySessionKindAndSessionIdAndCoinPairAndStatus(SESSION_KIND, sid, coinPair, "OPEN");

        if (posOpt.isEmpty()) {
            log.info("[Dynamic] 포지션 없음 — SCANNING 복귀 (id={}, {})", sid, coinPair);
            transitionToScanning(sid);
            return;
        }

        PositionEntity pos = posOpt.get();

        // MDD 피크 갱신
        updateMddPeak(session);

        BigDecimal pnlPct = currentPrice.subtract(pos.getAvgPrice())
                .divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // BLACK_SWAN_GUARD 발동 시 보유 포지션 SL 강화 — 단방향 ratchet(더 타이트한 방향으로만
        // 조임). 신규 진입 차단(SCANNING)만으로는 이미 보유 중인 포지션을 방어하지 못하므로 함께 적용.
        BlackSwanGuard.Result blackSwanGuard = BlackSwanGuard.check(candles);
        if (blackSwanGuard.triggered()) {
            BigDecimal tightenedSl = currentPrice.multiply(
                    BigDecimal.ONE.subtract(BlackSwanGuard.tightenedSlMargin(candles)))
                    .setScale(8, RoundingMode.HALF_DOWN);
            BigDecimal existingSl = pos.getStopLossPrice();
            if (existingSl == null || tightenedSl.compareTo(existingSl) > 0) {
                pos.setStopLossPrice(tightenedSl);
                positionRepository.save(pos);
                log.warn("[Dynamic] BLACK_SWAN_GUARD SL 강화 (id={}, {}): SL {} → {} ({})",
                        sid, coinPair, existingSl, tightenedSl, blackSwanGuard.reason());
                telegramService.sendCustomNotification(String.format(
                        "[BlackSwanGuard] SL 강화 — 동적세션#%d %s: SL %s → %s (%s)",
                        sid, coinPair, existingSl, tightenedSl, blackSwanGuard.reason()));
            }
        }

        // 익절
        if (pos.getTakeProfitPrice() != null
                && currentPrice.compareTo(pos.getTakeProfitPrice()) >= 0) {
            log.info("[Dynamic] 익절: {} @ {} pnl={}% (id={})", coinPair, currentPrice, pnlPct, sid);
            executeSell(session, pos, currentPrice,
                    "익절 — 현재가 " + currentPrice + " ≥ " + pos.getTakeProfitPrice());
            return;
        }

        // 손절
        BigDecimal slNeg = session.getStopLossPct().negate();
        boolean slTriggered = pos.getStopLossPrice() != null
                ? currentPrice.compareTo(pos.getStopLossPrice()) <= 0
                : pnlPct.compareTo(slNeg) <= 0;
        if (slTriggered) {
            log.warn("[Dynamic] 손절: {} @ {} pnl={}% (id={})", coinPair, currentPrice, pnlPct, sid);
            telegramService.notifyStopLoss(coinPair, pnlPct.doubleValue(), sid);
            executeSell(session, pos, currentPrice, "손절 — pnl " + pnlPct + "%");
            return;
        }

        // 닫힌 캔들 게이팅 후 전략 SELL 평가
        List<Candle> evalCandles = closedCandleSlice(candles, session.getTimeframe());
        String candleKey = sid + ":" + coinPair;
        Instant closedTime = evalCandles.get(evalCandles.size() - 1).getTime();
        Instant prevEval = lastEvaluatedCandle.get(candleKey);
        if (prevEval != null && !closedTime.isAfter(prevEval)) return;
        lastEvaluatedCandle.put(candleKey, closedTime);

        Strategy strategy = resolveStrategy(sid, coinPair, session.getStrategyType());
        StrategySignal signal = strategy.evaluate(evalCandles, Map.of("coinPair", coinPair));
        StrategyLogEntity signalLog = saveStrategyLog(sid, session.getStrategyType(), coinPair, signal, currentPrice);

        if (signal.getAction() == StrategySignal.Action.SELL) {
            long heldMin = pos.getOpenedAt() != null
                    ? Duration.between(pos.getOpenedAt(), Instant.now()).toMinutes() : Long.MAX_VALUE;
            if (heldMin < MIN_HOLD_MINUTES) {
                String blockReason = String.format("보유시간 미달: %d분 < %d분", heldMin, MIN_HOLD_MINUTES);
                log.debug("[Dynamic] SELL 차단: {} ({})", blockReason, coinPair);
                updateSignalQuality(signalLog, false, blockReason);
                return;
            }
            if (pnlPct.compareTo(MIN_PNL_PCT_FOR_SELL) < 0
                    && pnlPct.compareTo(LOSS_ESCAPE_THRESHOLD) >= 0) {
                String blockReason = String.format("본전 근처 pnl=%s%%", pnlPct);
                log.debug("[Dynamic] SELL 차단: {} ({})", blockReason, coinPair);
                updateSignalQuality(signalLog, false, blockReason);
                return;
            }
            executeSell(session, pos, currentPrice,
                    String.format("전략 SELL — %s (pnl=%s%%)", signal.getReason(), pnlPct));
            updateSignalQuality(signalLog, true, null);
        }
    }

    // ── 내부: 매수 실행 ────────────────────────────────────────────

    /** @return {@code null}이면 매수 주문 제출 성공, 아니면 차단 사유 (신호품질 로그용) */
    @Transactional
    public String executeBuy(DynamicSessionEntity session, String coinPair,
                            List<Candle> evalCandles, StrategySignal signal) {
        Long sid = session.getId();
        BigDecimal currentPrice = evalCandles.get(evalCandles.size() - 1).getClose();

        BigDecimal investAmount = session.getAvailableKrw().multiply(session.getInvestRatio());
        if (investAmount.compareTo(BigDecimal.valueOf(5000)) < 0) {
            log.warn("[Dynamic] 매수 불가: 가용 KRW 부족 (id={})", sid);
            return String.format("가용 KRW 부족: 투자가능 %s원 < 최소 5,000원", investAmount.setScale(0, RoundingMode.DOWN));
        }

        boolean hasPendingBuy = orderRepository.existsBySessionKindAndSessionIdAndCoinPairAndSideAndStateIn(
                SESSION_KIND, sid, coinPair, "BUY", ACTIVE_ORDER_STATES);
        if (hasPendingBuy) return "미체결 BUY 주문 존재 — 중복 매수 차단";

        // SL / TP 계산 (전략 제안값 우선, 없으면 stopLossPct × 2배 기본)
        BigDecimal slPct = session.getStopLossPct();
        BigDecimal stopLossPrice = signal.getSuggestedStopLoss() != null
                ? signal.getSuggestedStopLoss()
                : currentPrice.multiply(BigDecimal.ONE.subtract(
                        slPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                        .setScale(8, RoundingMode.HALF_DOWN);
        BigDecimal takeProfitPrice = signal.getSuggestedTakeProfit() != null
                ? signal.getSuggestedTakeProfit()
                : currentPrice.multiply(BigDecimal.ONE.add(
                        slPct.multiply(new BigDecimal("2")).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                        .setScale(8, RoundingMode.HALF_UP);

        PositionEntity pos = PositionEntity.builder()
                .coinPair(coinPair)
                .side("BUY")
                .entryPrice(currentPrice)
                .avgPrice(currentPrice)
                .size(BigDecimal.ZERO)
                .investedKrw(investAmount)
                .status("OPEN")
                .sessionId(sid)
                .sessionKind(SESSION_KIND)
                .stopLossPrice(stopLossPrice)
                .takeProfitPrice(takeProfitPrice)
                .build();
        pos = positionRepository.save(pos);
        Long posId = pos.getId();

        OrderRequest order = new OrderRequest();
        order.setCoinPair(coinPair);
        order.setSide("BUY");
        order.setOrderType("MARKET");
        order.setQuantity(investAmount);
        order.setReason("동적 세션 BUY — " + signal.getReason());
        order.setSessionId(sid);
        order.setSessionKind(SESSION_KIND);
        order.setPositionId(posId);
        orderExecutionEngine.submitOrder(order);

        // KRW 차감 및 상태 전환 — 낙관적 락 + 재시도 (reconcile 스케줄러와의 동시 쓰기 race 차단)
        balanceUpdater.apply(sid, s -> {
            s.setAvailableKrw(s.getAvailableKrw().subtract(investAmount));
            s.setScanState("POSITION_MONITORING");
            s.setCurrentCoinPair(coinPair);
            s.setCurrentPositionId(posId);
        });

        log.info("[Dynamic] 매수: id={} {} amount={} SL={} TP={}",
                sid, coinPair, investAmount, stopLossPrice, takeProfitPrice);

        // 실시간(WS) 손절/익절 감시 대상에 즉시 반영 — 폴링(60초) 대기 없이 다음 tick 전에도 방어
        refreshWsSubscription();
        return null;
    }

    // ── 내부: 매도 실행 ────────────────────────────────────────────

    @Transactional
    public void executeSell(DynamicSessionEntity session, PositionEntity pos,
                             BigDecimal currentPrice, String reason) {
        Long sid = session.getId();

        if (pos.getSize() == null || pos.getSize().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[Dynamic] 매도 건너뜀: size=0 (posId={}, id={})", pos.getId(), sid);
            return;
        }

        // 원자적 CLOSING 전환 — WS 실시간 SL/TP와 60초 tick이 동시에 같은 포지션을 팔려는
        // race에서 한쪽만 매도 주문을 제출하도록 보장 (시장가 이중 매도 방지)
        int marked = positionRepository.markClosingIfOpen(pos.getId(), Instant.now());
        if (marked == 0) {
            log.debug("[Dynamic] 매도 건너뜀: 이미 CLOSING/CLOSED (posId={}, id={})", pos.getId(), sid);
            return;
        }

        OrderRequest order = new OrderRequest();
        order.setCoinPair(pos.getCoinPair());
        order.setSide("SELL");
        order.setOrderType("MARKET");
        order.setQuantity(pos.getSize());
        order.setReason(reason);
        order.setSessionId(sid);
        order.setSessionKind(SESSION_KIND);
        order.setPositionId(pos.getId());
        orderExecutionEngine.submitOrder(order);

        // KRW 복원은 reconcile 에서 처리 — 여기서는 상태만 전환
        transitionToScanning(sid);
        log.info("[Dynamic] 매도 주문: id={} {} size={}", sid, pos.getCoinPair(), pos.getSize());
    }

    // ── 내부: 워치리스트 관리 ─────────────────────────────────────

    @Transactional
    public List<String> resolveWatchlist(DynamicSessionEntity session) {
        boolean needsRefresh = session.getWatchlistRefreshedAt() == null
                || Duration.between(session.getWatchlistRefreshedAt(), Instant.now()).toMinutes()
                        >= session.getWatchlistRefreshMin();

        if (!needsRefresh && session.getWatchlistJson() != null) {
            return parseWatchlistJson(session.getWatchlistJson());
        }

        log.info("[Dynamic] 워치리스트 갱신 (id={})", session.getId());
        List<String> fresh = watchlistFilterService.buildWatchlist(
                session.getMaxCandidateSize(),
                session.getTargetWatchSize(),
                session.getMinAtrPct(),
                session.getMaxSpreadPct(),
                session.getTimeframe());

        try {
            DynamicSessionEntity toUpdate = getOrThrow(session.getId());
            toUpdate.setWatchlistJson(objectMapper.writeValueAsString(fresh));
            toUpdate.setWatchlistRefreshedAt(Instant.now());
            dynamicSessionRepo.save(toUpdate);
        } catch (Exception e) {
            log.warn("[Dynamic] 워치리스트 저장 실패: {}", e.getMessage());
        }

        return fresh;
    }

    private List<String> parseWatchlistJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── 내부: 전략 인스턴스 ────────────────────────────────────────

    private Strategy resolveStrategy(Long sessionId, String coinPair, String strategyType) {
        if (!StrategyRegistry.isStateful(strategyType)) {
            return StrategyRegistry.get(strategyType);
        }
        String key = sessionId + ":" + coinPair;
        return strategyInstances.computeIfAbsent(key, k -> StrategyRegistry.createNew(strategyType));
    }

    // ── 내부: 전략 로그 (전략로그 페이지 노출용) ──────────────────

    /**
     * 평가된 신호를 {@code strategy_log} 테이블에 저장한다 — {@code sessionType="DYNAMIC"}.
     * 라이브 매매({@link LiveTradingService})와 동일하게 모든 평가(HOLD 포함)를 저장해야
     * 전략로그 화면과 신호 품질 통계(/api/v1/logs/signal-stats)에서 동적 세션도 보인다.
     * 이전까지는 application log 에만 남고 DB에는 전혀 기록되지 않아 전략로그 화면이 비어 있었다.
     */
    private StrategyLogEntity saveStrategyLog(Long sessionId, String strategyName, String coinPair,
                                               StrategySignal signal, BigDecimal signalPrice) {
        try {
            BigDecimal conf = (signal.getAction() != StrategySignal.Action.HOLD && signal.getStrength() != null)
                    ? signal.getStrength().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                    : null;
            StrategyLogEntity entity = StrategyLogEntity.builder()
                    .strategyName(strategyName)
                    .coinPair(coinPair)
                    .signal(signal.getAction().name())
                    .reason(signal.getReason())
                    .sessionType(SESSION_KIND)
                    .sessionId(sessionId)
                    .signalPrice(signalPrice)
                    .confidenceScore(conf)
                    .build();
            return strategyLogRepository.save(entity);
        } catch (Exception e) {
            log.warn("[Dynamic] 전략 로그 저장 실패: {}", e.getMessage());
            return null;
        }
    }

    /** 신호 품질 로그 실행 결과 업데이트 (null-safe) */
    private void updateSignalQuality(StrategyLogEntity logEntity, boolean wasExecuted, String blockedReason) {
        if (logEntity == null) return;
        try {
            logEntity.setWasExecuted(wasExecuted);
            logEntity.setBlockedReason(blockedReason);
            strategyLogRepository.save(logEntity);
        } catch (Exception e) {
            log.warn("[Dynamic] 신호 품질 로그 업데이트 실패: {}", e.getMessage());
        }
    }

    // ── 내부: 캔들 조회 ────────────────────────────────────────────

    private List<Candle> fetchCandles(String coinPair, String timeframe) {
        if (upbitRestClient == null) return List.of();
        try {
            Instant to = Instant.now();
            Instant from = to.minus(CANDLE_LOOKBACK * TimeframeUtils.toMinutes(timeframe), ChronoUnit.MINUTES);
            return new UpbitCandleCollector(upbitRestClient).fetchCandles(coinPair, timeframe, from, to);
        } catch (Exception e) {
            log.warn("[Dynamic] 캔들 조회 실패 ({} {}): {}", coinPair, timeframe, e.getMessage());
            return List.of();
        }
    }

    private List<Candle> closedCandleSlice(List<Candle> candles, String timeframe) {
        if (candles.size() < 2) return candles;
        long periodMin = TimeframeUtils.toMinutes(timeframe);
        Instant lastTime = candles.get(candles.size() - 1).getTime();
        boolean closed = !lastTime.plus(periodMin, ChronoUnit.MINUTES).isAfter(Instant.now());
        return closed ? candles : candles.subList(0, candles.size() - 1);
    }

    // ── 내부: 상태 전환 ────────────────────────────────────────────

    public void transitionToScanning(Long sessionId) {
        balanceUpdater.apply(sessionId, s -> {
            s.setScanState("SCANNING");
            s.setCurrentCoinPair(null);
            s.setCurrentPositionId(null);
            s.setWatchlistRefreshedAt(null); // 다음 스캔에서 즉시 재필터링
        });
        log.info("[Dynamic] SCANNING 복귀 (id={})", sessionId);
        refreshWsSubscription();
    }

    private void updateMddPeak(DynamicSessionEntity session) {
        if (session.getMddPeakCapital() == null
                || session.getTotalAssetKrw().compareTo(session.getMddPeakCapital()) > 0) {
            // 넘어온 엔티티를 직접 save()하면 reconcile(5초)과의 @Version 충돌로
            // 같은 tick의 후속 SL/TP 검사까지 통째로 실패할 수 있다 — 낙관적 락 재시도 경유
            balanceUpdater.apply(session.getId(), s -> {
                if (s.getMddPeakCapital() == null
                        || s.getTotalAssetKrw().compareTo(s.getMddPeakCapital()) > 0) {
                    s.setMddPeakCapital(s.getTotalAssetKrw());
                }
            });
        }
    }

    // ── 내부: 청산 / 정리 ──────────────────────────────────────────

    private void closeOpenPositions(DynamicSessionEntity session, String reason) {
        List<PositionEntity> opens = positionRepository
                .findBySessionKindAndSessionIdAndStatus(SESSION_KIND, session.getId(), "OPEN");
        for (PositionEntity pos : opens) {
            if (pos.getSize() == null || pos.getSize().compareTo(BigDecimal.ZERO) <= 0) {
                pos.setStatus("CLOSED");
                pos.setClosedAt(Instant.now());
                positionRepository.save(pos);
                continue;
            }
            // WS 실시간 SL/TP 매도와의 race 방지 — 이미 CLOSING이면 매도 주문 중복 제출 스킵
            if (positionRepository.markClosingIfOpen(pos.getId(), Instant.now()) == 0) {
                continue;
            }

            OrderRequest order = new OrderRequest();
            order.setCoinPair(pos.getCoinPair());
            order.setSide("SELL");
            order.setOrderType("MARKET");
            order.setQuantity(pos.getSize());
            order.setReason(reason);
            order.setSessionId(session.getId());
            order.setSessionKind(SESSION_KIND);
            order.setPositionId(pos.getId());
            orderExecutionEngine.submitOrder(order);
        }
    }

    // ── 스케줄: CLOSING 포지션 정리 (5초 주기) ──────────────────────

    /**
     * CLOSING 상태의 동적 포지션을 SELL 주문 체결/실패 결과에 따라 확정/롤백한다.
     *
     * <p>{@link #executeSell}은 포지션을 CLOSING으로만 표시하고 비동기 주문을 제출한 뒤
     * 즉시 SCANNING으로 전환한다. 실제 KRW 복원과 손익 확정은 이 스케줄러가 전담한다.
     * 이 처리가 없으면 동적 세션은 매도할 때마다 {@code availableKrw}가 복원되지 않아
     * 몇 차례 매매 후 투자 가능 금액이 5,000원 미만으로 줄어 영구적으로 매수가 멈춘다
     * (2026-07-01 동적 멀티코인 로직 분석 — session_kind 컬럼 부재로 라이브 reconcile이
     * 동적 세션 KRW를 복원하지 못하던 근본 원인).</p>
     */
    @Scheduled(fixedDelay = 5_000)
    @Transactional
    public void reconcileDynamicClosingPositions() {
        List<PositionEntity> closingPositions =
                positionRepository.findBySessionKindAndStatus(SESSION_KIND, "CLOSING");
        if (closingPositions.isEmpty()) return;

        for (PositionEntity pos : closingPositions) {
            List<OrderEntity> sellOrders = orderRepository
                    .findByPositionIdOrderByCreatedAtDesc(pos.getId())
                    .stream()
                    .filter(o -> "SELL".equalsIgnoreCase(o.getSide()))
                    .toList();

            if (sellOrders.isEmpty()) {
                log.warn("[Dynamic] CLOSING 포지션에 SELL 주문 없음 — OPEN 롤백 (posId={})", pos.getId());
                pos.setStatus("OPEN");
                pos.setClosingAt(null);
                positionRepository.save(pos);
                reattachRolledBackPosition(pos);
                continue;
            }

            OrderEntity latestSell = sellOrders.get(0);
            switch (latestSell.getState()) {
                case "FILLED" -> finalizeDynamicSell(pos, latestSell);
                case "FAILED", "CANCELLED" -> {
                    log.warn("[Dynamic] 매도 주문 {} — 포지션 OPEN 롤백 (orderId={}, posId={}, sessionId={})",
                            latestSell.getState(), latestSell.getId(), pos.getId(), pos.getSessionId());
                    pos.setStatus("OPEN");
                    pos.setClosingAt(null);
                    positionRepository.save(pos);
                    reattachRolledBackPosition(pos);
                }
                default -> {
                    Instant closingAt = pos.getClosingAt();
                    if (closingAt != null
                            && Duration.between(closingAt, Instant.now()).toMinutes() >= CLOSING_TIMEOUT_MINUTES) {
                        log.warn("[Dynamic] CLOSING 타임아웃 ({}분 초과) — OPEN 롤백 (posId={}, sessionId={})",
                                CLOSING_TIMEOUT_MINUTES, pos.getId(), pos.getSessionId());
                        pos.setStatus("OPEN");
                        pos.setClosingAt(null);
                        positionRepository.save(pos);
                        reattachRolledBackPosition(pos);
                    }
                }
            }
        }
    }

    /**
     * 매도 실패/타임아웃으로 OPEN 롤백된 포지션을 세션 감시 대상에 재결속한다.
     *
     * <p>{@link #executeSell}은 매도 주문 제출과 동시에 세션을 SCANNING으로 전환하므로,
     * 매도가 FAILED/CANCELLED로 끝나 포지션이 OPEN으로 돌아와도 세션은 이미 다른 곳을
     * 보고 있다. 이 재결속이 없으면 롤백된 포지션은 SL/TP 감시·매도 재시도 없이 영구
     * 방치되고(WS 구독에서도 빠짐), 세션이 남은 KRW로 두 번째 코인을 사버릴 수도 있다.</p>
     */
    private void reattachRolledBackPosition(PositionEntity pos) {
        Long sessionId = pos.getSessionId();
        if (sessionId == null) return;

        DynamicSessionEntity session = dynamicSessionRepo.findById(sessionId).orElse(null);
        if (session == null) return;

        if (!"RUNNING".equals(session.getStatus())) {
            log.error("[Dynamic] 매도 롤백 포지션 재결속 불가 — 세션 비가동 (posId={}, sessionId={}, status={}). 수동 조치 필요",
                    pos.getId(), sessionId, session.getStatus());
            telegramService.sendCustomNotification(String.format(
                    "⚠️ [동적#%d] 매도 실패 포지션 방치 위험: %s (posId=%d) — 세션이 %s 상태라 자동 재결속 불가. 수동 청산 필요",
                    sessionId, pos.getCoinPair(), pos.getId(), session.getStatus()));
            return;
        }

        if (pos.getCoinPair().equals(session.getCurrentCoinPair())) {
            return; // 이미 이 코인을 감시 중 — 다음 tick에서 SL/TP·매도 재시도됨
        }

        if ("SCANNING".equals(session.getScanState()) && session.getCurrentCoinPair() == null) {
            balanceUpdater.apply(sessionId, s -> {
                s.setScanState("POSITION_MONITORING");
                s.setCurrentCoinPair(pos.getCoinPair());
                s.setCurrentPositionId(pos.getId());
            });
            refreshWsSubscription();
            log.warn("[Dynamic] 매도 롤백 포지션 재결속: {} → POSITION_MONITORING 복귀 (posId={}, sessionId={})",
                    pos.getCoinPair(), pos.getId(), sessionId);
        } else {
            // 세션이 이미 다른 코인을 매수한 경우 — 단일 포지션 상태 머신으로는 자동 복구 불가
            log.error("[Dynamic] 매도 롤백 포지션 재결속 불가 — 세션이 다른 코인 감시 중 (posId={}, coin={}, sessionId={}, current={})",
                    pos.getId(), pos.getCoinPair(), sessionId, session.getCurrentCoinPair());
            telegramService.sendCustomNotification(String.format(
                    "🚨 [동적#%d] 매도 실패 포지션 방치: %s (posId=%d) — 세션은 이미 %s 감시 중이라 자동 재결속 불가. 수동 청산 필요",
                    sessionId, pos.getCoinPair(), pos.getId(), session.getCurrentCoinPair()));
        }
    }

    /**
     * 매도 체결 확정 — 실제 체결가 기반 손익/수수료 계산 + 동적 세션 KRW 복원.
     * 멱등성 보장: 이미 CLOSED인 포지션은 중복 처리하지 않음.
     */
    private void finalizeDynamicSell(PositionEntity pos, OrderEntity filledOrder) {
        if ("CLOSED".equals(pos.getStatus())) {
            log.debug("[Dynamic] finalizeDynamicSell 스킵: 이미 CLOSED (posId={})", pos.getId());
            return;
        }
        // 체결가 미확정 시 보류 — 다음 reconcile(5초)에서 재시도 (가짜 본전 방지)
        BigDecimal fillPrice = filledOrder.getPrice();
        if (fillPrice == null) {
            log.warn("[Dynamic] 매도 체결가 미확정 — finalize 보류, 다음 reconcile 재시도 (posId={}, orderId={})",
                    pos.getId(), filledOrder.getId());
            return;
        }
        BigDecimal soldQty = filledOrder.getFilledQuantity() != null
                ? filledOrder.getFilledQuantity() : pos.getSize();

        BigDecimal proceeds = soldQty.multiply(fillPrice);
        BigDecimal fee = proceeds.multiply(FEE_RATE);
        BigDecimal netProceeds = proceeds.subtract(fee);
        BigDecimal realizedPnl = netProceeds.subtract(soldQty.multiply(pos.getAvgPrice()));

        // 부분 체결 후 취소(D-3) — 판 수량이 전체보다 적으면 잔여분은 여전히 보유 중이므로
        // 전체 CLOSED 대신 잔여 수량만 남기고 OPEN 유지한다 (LiveTradingService.finalizeSellPosition 동일 원칙).
        boolean isPartial = soldQty.compareTo(pos.getSize()) < 0;
        if (isPartial) {
            pos.setSize(pos.getSize().subtract(soldQty));
            pos.setRealizedPnl(pos.getRealizedPnl().add(realizedPnl));
            pos.setPositionFee(pos.getPositionFee().add(fee));
            pos.setStatus("OPEN");
            pos.setClosingAt(null);
        } else {
            pos.setRealizedPnl(realizedPnl);
            pos.setPositionFee(fee);
            pos.setUnrealizedPnl(BigDecimal.ZERO);
            pos.setStatus("CLOSED");
            pos.setClosedAt(Instant.now());
        }
        positionRepository.save(pos);

        if (pos.getSessionId() != null) {
            Long sessionId = pos.getSessionId();
            // 같은 코인만이 아니라 세션 전체 노출을 확인 — 이전 매도 정산 지연 중 세션이 다른
            // 코인을 이미 매수했을 수 있고, 그때 totalAssetKrw=availableKrw로 덮으면 그 코인의
            // 평가액이 총자산에서 통째로 사라진다.
            final Long finalizedPosId = pos.getId();
            boolean hasRemainingExposure = isPartial || positionRepository
                    .findBySessionKindAndSessionId(SESSION_KIND, sessionId).stream()
                    .anyMatch(p -> !p.getId().equals(finalizedPosId)
                            && ("OPEN".equals(p.getStatus()) || "CLOSING".equals(p.getStatus())));

            balanceUpdater.apply(sessionId, s -> {
                BigDecimal newAvailableKrw = s.getAvailableKrw().add(netProceeds);
                s.setAvailableKrw(newAvailableKrw);
                if (!hasRemainingExposure) {
                    s.setTotalAssetKrw(newAvailableKrw);
                } else {
                    s.setTotalAssetKrw(s.getTotalAssetKrw().subtract(fee));
                }
            });
            if (isPartial) {
                // executeSell()이 매도 제출과 동시에 세션을 이미 SCANNING으로 돌려놓았으므로,
                // 팔리지 않은 잔여분을 계속 감시하도록 재결속한다 (전체 롤백과 동일 원칙).
                reattachRolledBackPosition(pos);
            }
            log.info("[Dynamic] 매도 체결 확정 (sessionId={}, posId={}, partial={}): {} {}개 @ {} 손익={} 수수료={}",
                    sessionId, pos.getId(), isPartial, pos.getCoinPair(), soldQty, fillPrice, realizedPnl, fee);
            telegramService.bufferTradeEvent(
                    "동적#" + sessionId, pos.getCoinPair(), "SELL",
                    fillPrice, soldQty, fee, realizedPnl, "동적 세션 매도");
        }
    }

    // ── 스케줄: 고아 매수 포지션 정리 (30초 주기) ───────────────────

    /**
     * OPEN + size=0 포지션 중 BUY 주문이 FAILED/CANCELLED로 확정된 경우를 정리한다.
     *
     * <p>이 처리가 없으면 거래소 오류 등으로 BUY가 실패했을 때 포지션이 size=0 OPEN 상태로
     * 영구 고착되고, {@link #executeSell}의 size&le;0 가드 때문에 세션이 POSITION_MONITORING에
     * 멈춰 다시는 스캔/매수하지 않는다.</p>
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void reconcileDynamicOrphanBuyPositions() {
        List<PositionEntity> orphanPositions = positionRepository
                .findBySessionKindAndStatus(SESSION_KIND, "OPEN")
                .stream()
                .filter(pos -> pos.getSize() != null && pos.getSize().compareTo(BigDecimal.ZERO) <= 0)
                .toList();
        if (orphanPositions.isEmpty()) return;

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
                // 원자적 CLOSE — 동시 실행 시 이중 KRW 복원 방지
                int closed = positionRepository.closeIfOpen(pos.getId(), Instant.now());
                if (closed == 0) {
                    log.debug("[Dynamic] 고아 포지션 이미 정리됨, KRW 복원 스킵 (posId={})", pos.getId());
                    continue;
                }

                if (pos.getSessionId() != null) {
                    Long sessionId = pos.getSessionId();
                    BigDecimal toRestore = buyOrders.stream()
                            .filter(o -> "CANCELLED".equals(o.getState()) || "FAILED".equals(o.getState()))
                            .findFirst()
                            .map(OrderEntity::getQuantity)
                            .orElse(pos.getInvestedKrw());
                    if (toRestore != null) {
                        final BigDecimal restoreAmount = toRestore;
                        balanceUpdater.apply(sessionId, s -> s.setAvailableKrw(s.getAvailableKrw().add(restoreAmount)));
                        log.info("[Dynamic] 고아 포지션 정리: KRW 복원 (posId={}, sessionId={}, 복원금액={})",
                                pos.getId(), sessionId, restoreAmount);
                    }
                    // 세션이 POSITION_MONITORING에 고착되지 않도록 SCANNING 복귀
                    transitionToScanning(sessionId);
                }
                log.warn("[Dynamic] 고아 포지션 정리 완료 (posId={}, coinPair={})", pos.getId(), pos.getCoinPair());

            } else if (!hasActiveBuy && buyOrders.isEmpty() && pos.getSessionId() != null) {
                // 예외 경로: 주문 엔티티가 아예 없는 경우 (async 스레드 DB 오류 등)
                boolean isOldEnough = pos.getOpenedAt() != null
                        && Duration.between(pos.getOpenedAt(), Instant.now()).toMinutes() >= 5;
                if (isOldEnough) {
                    int closed = positionRepository.closeIfOpen(pos.getId(), Instant.now());
                    if (closed == 0) continue;

                    Long sessionId = pos.getSessionId();
                    if (pos.getInvestedKrw() != null) {
                        final BigDecimal investedKrw = pos.getInvestedKrw();
                        balanceUpdater.apply(sessionId, s -> s.setAvailableKrw(s.getAvailableKrw().add(investedKrw)));
                        log.warn("[Dynamic] 고아 포지션 정리(주문 없음): KRW 복원 (posId={}, sessionId={}, 복원금액={})",
                                pos.getId(), sessionId, investedKrw);
                    } else {
                        log.error("[Dynamic] 고아 포지션 정리 실패: investedKrw 없음 — 수동 확인 필요 (posId={})", pos.getId());
                    }
                    transitionToScanning(sessionId);
                }
            }
        }
    }

    // ── 실시간(WS) 손절/익절 ────────────────────────────────────────

    /**
     * 현재 RUNNING 중인 동적 세션들의 보유 코인(POSITION_MONITORING 상태) 목록을 다시 계산해
     * {@link WsSubscriptionManager}에 반영한다. LIVE 세션과 구독을 공유하므로 직접
     * {@code UpbitWebSocketClient}를 호출하지 않는다.
     *
     * <p>매수/매도/세션 시작·정지 시점에 호출해 지연 없이 구독을 갱신한다 — 60초 폴링(tick)을
     * 기다리면 그사이 실시간 손절/익절 보호가 비는 구간이 생긴다.</p>
     */
    private void refreshWsSubscription() {
        List<String> coins = dynamicSessionRepo.findByStatus("RUNNING").stream()
                .filter(s -> "POSITION_MONITORING".equals(s.getScanState()))
                .map(DynamicSessionEntity::getCurrentCoinPair)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        wsSubscriptionManager.updateSource(SESSION_KIND, coins);
    }

    /**
     * WebSocket 실시간 시세 이벤트 핸들러 — 동적 세션 보유 포지션의 손절/익절을 폴링(60초)보다
     * 즉시 반응하도록 처리한다. 라이브 매매의 급등락 SL/TP 트레일링(ratchet)은 이번 범위에
     * 포함하지 않고, 기본 SL/TP 트리거만 실시간화한다.
     */
    @EventListener
    @Async("marketDataExecutor")
    @Transactional
    public void onRealtimePriceEvent(RealtimePriceEvent event) {
        try {
            doOnRealtimePriceEvent(event);
        } catch (Exception e) {
            log.error("[Dynamic] 실시간 시세 이벤트 처리 오류 — coinCode={}, price={}",
                    event.getCoinCode(), event.getPrice(), e);
        }
    }

    private void doOnRealtimePriceEvent(RealtimePriceEvent event) {
        String coinCode = event.getCoinCode();
        BigDecimal price = event.getPrice();
        long now = System.currentTimeMillis();

        Long lastMs = rtCheckLastMs.get(coinCode);
        if (lastMs != null && now - lastMs < RT_CHECK_INTERVAL_MS) return;
        rtCheckLastMs.put(coinCode, now);

        List<DynamicSessionEntity> sessions = dynamicSessionRepo.findByStatus("RUNNING");
        for (DynamicSessionEntity session : sessions) {
            if (!coinCode.equals(session.getCurrentCoinPair())) continue;

            Optional<PositionEntity> openPos = positionRepository
                    .findBySessionKindAndSessionIdAndCoinPairAndStatus(SESSION_KIND, session.getId(), coinCode, "OPEN");
            if (openPos.isEmpty()) continue;

            PositionEntity pos = openPos.get();
            if (pos.getAvgPrice() == null || pos.getAvgPrice().compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal pnlPct = price.subtract(pos.getAvgPrice())
                    .divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            // 익절
            if (pos.getTakeProfitPrice() != null && price.compareTo(pos.getTakeProfitPrice()) >= 0) {
                log.info("[Dynamic] 실시간 익절(WS): {} @ {} pnl={}% (id={})",
                        coinCode, price, pnlPct, session.getId());
                executeSell(session, pos, price,
                        "실시간 익절(WS) — 현재가 " + price + " ≥ " + pos.getTakeProfitPrice());
                continue;
            }

            // 손절
            BigDecimal slNeg = session.getStopLossPct().negate();
            boolean slTriggered = pos.getStopLossPrice() != null
                    ? price.compareTo(pos.getStopLossPrice()) <= 0
                    : pnlPct.compareTo(slNeg) <= 0;
            if (slTriggered) {
                log.warn("[Dynamic] 실시간 손절(WS): {} @ {} pnl={}% (id={})",
                        coinCode, price, pnlPct, session.getId());
                telegramService.notifyStopLoss(coinCode, pnlPct.doubleValue(), session.getId());
                executeSell(session, pos, price, "실시간 손절(WS) — pnl " + pnlPct + "%");
            }
        }
    }

    private void clearSessionState(Long sessionId) {
        strategyInstances.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + ":"));
        lastEvaluatedCandle.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + ":"));
    }

    private DynamicSessionEntity getOrThrow(Long sessionId) {
        return dynamicSessionRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("동적 세션 없음: id=" + sessionId));
    }

    private static BigDecimal normalizeRatio(BigDecimal raw, BigDecimal defaultVal) {
        if (raw == null) return defaultVal;
        if (raw.compareTo(BigDecimal.ONE) > 0) {
            raw = raw.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        }
        return raw.max(new BigDecimal("0.01")).min(BigDecimal.ONE);
    }
}
