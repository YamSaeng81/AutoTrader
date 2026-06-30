package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.util.TimeframeUtils;
import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
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

    private static final int CANDLE_LOOKBACK = 200;
    private static final List<String> ACTIVE_ORDER_STATES = List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED");
    private static final long MIN_HOLD_MINUTES = 180;
    private static final BigDecimal MIN_PNL_PCT_FOR_SELL = new BigDecimal("0.30");
    private static final BigDecimal LOSS_ESCAPE_THRESHOLD = new BigDecimal("-1.00");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    /** CLOSING 상태 진입 시각 — 이 시간 초과 시 reconcileClosingPositions()에서 OPEN 롤백 */
    private static final long CLOSING_TIMEOUT_MINUTES = 5;
    private static final String SESSION_KIND = "DYNAMIC";

    private final DynamicSessionRepository dynamicSessionRepo;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final WatchlistFilterService watchlistFilterService;
    private final OrderExecutionEngine orderExecutionEngine;
    private final TelegramNotificationService telegramService;
    private final ObjectMapper objectMapper;
    private final DynamicSessionBalanceUpdater balanceUpdater;

    @Autowired(required = false)
    private UpbitRestClient upbitRestClient;

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
                                  DynamicSessionBalanceUpdater balanceUpdater) {
        this.dynamicSessionRepo   = dynamicSessionRepo;
        this.positionRepository   = positionRepository;
        this.orderRepository      = orderRepository;
        this.watchlistFilterService = watchlistFilterService;
        this.orderExecutionEngine = orderExecutionEngine;
        this.telegramService      = telegramService;
        this.objectMapper         = objectMapper;
        this.balanceUpdater       = balanceUpdater;
    }

    // ── 세션 생성 ──────────────────────────────────────────────────

    @Transactional
    public DynamicSessionEntity createSession(DynamicSessionRequest req) {
        StrategyRegistry.get(req.getStrategyType()); // 유효성 검증

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
        return session;
    }

    // ── 조회 ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DynamicSessionEntity> listSessions() {
        return dynamicSessionRepo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public DynamicSessionEntity getSession(Long sessionId) {
        return getOrThrow(sessionId);
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
        boolean stillRunning = dynamicSessionRepo.findById(sid)
                .map(s -> "RUNNING".equals(s.getStatus())).orElse(false);
        if (!stillRunning) return;

        if ("SCANNING".equals(session.getScanState())) {
            processScanningTick(session);
        } else {
            processMonitoringTick(session);
        }
    }

    /** SCANNING: 워치리스트 코인들에 전략 평가 → 첫 BUY 진입 */
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

            // 코인별 평가 결과 로그 (BUY/SELL은 INFO, HOLD는 DEBUG)
            if (signal.getAction() != StrategySignal.Action.HOLD) {
                log.info("[Dynamic] 평가결과: {} → {} ({})", coinPair, signal.getAction(), signal.getReason());
            } else {
                holdCount++;
                log.debug("[Dynamic] 평가결과: {} → HOLD ({})", coinPair, signal.getReason());
            }
            if (signal.getAction() == StrategySignal.Action.SELL) sellCount++;

            if (signal.getAction() == StrategySignal.Action.BUY
                    && !Ema200RegimeGate.allowsBuy(evalCandles, coinPair)) {
                ema200Blocked++;
                log.info("[Dynamic] EMA200 BUY 차단: {} (id={})", coinPair, sid);
                continue;
            }

            if (signal.getAction() == StrategySignal.Action.BUY
                    && RangeRegimeGate.isBlocked(session.getStrategyType())) {
                try {
                    MarketRegime regime = new MarketRegimeDetector().detectRaw(evalCandles);
                    if (regime == MarketRegime.RANGE) {
                        rangeBlocked++;
                        log.info("[Dynamic] RANGE 레짐 BUY 차단: {} (id={})", coinPair, sid);
                        continue;
                    }
                } catch (Exception ignored) {}
            }

            if (signal.getAction() == StrategySignal.Action.BUY) {
                log.info("[Dynamic] BUY 신호 진입: {} {} (id={})", coinPair, signal.getReason(), sid);
                executeBuy(session, coinPair, evalCandles, signal);
                return;
            }
        }

        log.info("[Dynamic] SCANNING 완료: 진입 조건 없음 (id={}, 감시 {}개) — "
                        + "HOLD={} SELL={} EMA200차단={} RANGE차단={} 캔들부족={} 캔들미갱신={}",
                sid, watchlist.size(), holdCount, sellCount, ema200Blocked, rangeBlocked,
                insufficientCandles, staleCandle);
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
                .findBySessionIdAndCoinPairAndStatus(sid, coinPair, "OPEN");

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

        if (signal.getAction() == StrategySignal.Action.SELL) {
            long heldMin = pos.getOpenedAt() != null
                    ? Duration.between(pos.getOpenedAt(), Instant.now()).toMinutes() : Long.MAX_VALUE;
            if (heldMin < MIN_HOLD_MINUTES) {
                log.debug("[Dynamic] SELL 차단: 보유시간 미달 {}분 ({})", heldMin, coinPair);
                return;
            }
            if (pnlPct.compareTo(MIN_PNL_PCT_FOR_SELL) < 0
                    && pnlPct.compareTo(LOSS_ESCAPE_THRESHOLD) >= 0) {
                log.debug("[Dynamic] SELL 차단: 본전 근처 pnl={}% ({})", pnlPct, coinPair);
                return;
            }
            executeSell(session, pos, currentPrice,
                    String.format("전략 SELL — %s (pnl=%s%%)", signal.getReason(), pnlPct));
        }
    }

    // ── 내부: 매수 실행 ────────────────────────────────────────────

    @Transactional
    public void executeBuy(DynamicSessionEntity session, String coinPair,
                            List<Candle> evalCandles, StrategySignal signal) {
        Long sid = session.getId();
        BigDecimal currentPrice = evalCandles.get(evalCandles.size() - 1).getClose();

        BigDecimal investAmount = session.getAvailableKrw().multiply(session.getInvestRatio());
        if (investAmount.compareTo(BigDecimal.valueOf(5000)) < 0) {
            log.warn("[Dynamic] 매수 불가: 가용 KRW 부족 (id={})", sid);
            return;
        }

        boolean hasPendingBuy = orderRepository.existsBySessionIdAndCoinPairAndSideAndStateIn(
                sid, coinPair, "BUY", ACTIVE_ORDER_STATES);
        if (hasPendingBuy) return;

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

        pos.setStatus("CLOSING");
        pos.setClosingAt(Instant.now());
        positionRepository.save(pos);

        OrderRequest order = new OrderRequest();
        order.setCoinPair(pos.getCoinPair());
        order.setSide("SELL");
        order.setOrderType("MARKET");
        order.setQuantity(pos.getSize());
        order.setReason(reason);
        order.setSessionId(sid);
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
    }

    private void updateMddPeak(DynamicSessionEntity session) {
        if (session.getMddPeakCapital() == null
                || session.getTotalAssetKrw().compareTo(session.getMddPeakCapital()) > 0) {
            session.setMddPeakCapital(session.getTotalAssetKrw());
            dynamicSessionRepo.save(session);
        }
    }

    // ── 내부: 청산 / 정리 ──────────────────────────────────────────

    private void closeOpenPositions(DynamicSessionEntity session, String reason) {
        List<PositionEntity> opens = positionRepository.findBySessionIdAndStatus(session.getId(), "OPEN");
        for (PositionEntity pos : opens) {
            if (pos.getSize() == null || pos.getSize().compareTo(BigDecimal.ZERO) <= 0) {
                pos.setStatus("CLOSED");
                pos.setClosedAt(Instant.now());
                positionRepository.save(pos);
                continue;
            }
            pos.setStatus("CLOSING");
            pos.setClosingAt(Instant.now());
            positionRepository.save(pos);

            OrderRequest order = new OrderRequest();
            order.setCoinPair(pos.getCoinPair());
            order.setSide("SELL");
            order.setOrderType("MARKET");
            order.setQuantity(pos.getSize());
            order.setReason(reason);
            order.setSessionId(session.getId());
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
                    }
                }
            }
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

        pos.setRealizedPnl(realizedPnl);
        pos.setPositionFee(fee);
        pos.setUnrealizedPnl(BigDecimal.ZERO);
        pos.setStatus("CLOSED");
        pos.setClosedAt(Instant.now());
        positionRepository.save(pos);

        if (pos.getSessionId() != null) {
            Long sessionId = pos.getSessionId();
            boolean hasOpenPosition = positionRepository
                    .findBySessionIdAndCoinPairAndStatus(sessionId, pos.getCoinPair(), "OPEN")
                    .isPresent();

            balanceUpdater.apply(sessionId, s -> {
                BigDecimal newAvailableKrw = s.getAvailableKrw().add(netProceeds);
                s.setAvailableKrw(newAvailableKrw);
                if (!hasOpenPosition) {
                    s.setTotalAssetKrw(newAvailableKrw);
                } else {
                    s.setTotalAssetKrw(s.getTotalAssetKrw().subtract(fee));
                }
            });
            log.info("[Dynamic] 매도 체결 확정 (sessionId={}, posId={}): {} {}개 @ {} 손익={} 수수료={}",
                    sessionId, pos.getId(), pos.getCoinPair(), soldQty, fillPrice, realizedPnl, fee);
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
