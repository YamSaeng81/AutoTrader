package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.dto.PerformanceSummaryResponse;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.core.risk.ExitRuleConfig;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.api.util.TradingConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cryptoautotrader.api.dto.MultiStrategyPaperRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingService {

    /**
     * 동시 실행 가능한 모의투자 세션 수.
     *
     * <p>2026-08-06 20 → 120으로 상향. 목적은 <b>코인 N개 × 전략 M개 격자 실험</b>이다
     * (예: 10코인 × 10전략 = 100세션). 실자본 매매가 시장 대비 알파 음수인 상태에서,
     * 실전 진입 빈도(6일 8거래)로는 통계적 유의성에 영원히 도달하지 못한다는 판단에 따른 것.</p>
     *
     * <p>세션 수가 늘어도 캔들 조회는 {@code (coinPair, timeframe)} 조합 수만큼만 발생한다
     * (아래 틱 단위 캐시). 100세션이 10코인을 쓰면 조회는 11회(코인 10 + BTC 가드 1)다.</p>
     */
    private static final int MAX_CONCURRENT_SESSIONS = 120;
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    // 백테스트(BacktestEngine.MAX_LOOKBACK)·실거래(LiveTradingService.CANDLE_LOOKBACK)와 동일하게
    // 맞춰야 페이퍼 승격 판단이 실거래 신호와 같은 조건에서 검증된다.
    private static final int CANDLE_LOOKBACK = TradingConstants.CANDLE_LOOKBACK;

    // ── LIVE 정렬 상수 (2026-08-06) ───────────────────────────────────────────
    // 아래 3개는 LiveTradingService의 동일 이름 상수와 값이 반드시 같아야 한다.
    // 하나라도 어긋나면 "페이퍼에서 검증하고 실전에 올린다"는 절차가 다시 성립하지 않는다.

    /** 전략 SELL 최소 보유시간(분) — 진입 직후 동가 청산 패턴 차단. SL/TP는 이 게이트와 무관하게 항상 동작. */
    private static final long MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT = 180;
    /** 본전 청산 차단 하한(%) — 이 미만 수익에서의 전략 SELL은 무시한다. */
    private static final BigDecimal MIN_PNL_PCT_FOR_SIGNAL_EXIT = new BigDecimal("0.30");
    /**
     * 손실 탈출 임계(%) — 이보다 더 잃고 있으면 본전 청산 차단을 풀어 전략 SELL을 허용한다.
     * 2026-08-18: −1.00 → −0.30. 단일 출처는 {@link ExitRuleConfig} (근거는 그 필드 javadoc).
     */
    private static final BigDecimal LOSS_ESCAPE_THRESHOLD =
            ExitRuleConfig.defaults().getLossEscapeThresholdPct();

    /** 업비트 최소 주문금액(KRW) — 실거래에서 못 넣는 주문을 페이퍼가 체결하면 모집단이 어긋난다. */
    private static final BigDecimal MIN_ORDER_KRW = new BigDecimal("5000");

    /**
     * 체결 슬리피지(0.001 = 0.1%) — 매수는 불리하게 높게, 매도는 불리하게 낮게 체결시킨다.
     *
     * <p>기존 페이퍼는 캔들 종가에 <b>정확히</b> 체결시켜 실거래에 없는 이점을 누렸다.
     * 백테스트(`BacktestEngine`)가 쓰는 0.1%와 같은 값으로 맞춰 세 엔진(백테스트·페이퍼·실전)의
     * 체결 가정을 통일한다. 실측 슬리피지는 LIVE BTC 기준 0.1% 수준이었다.</p>
     */
    private static final BigDecimal SLIPPAGE_PCT = TradingConstants.PAPER_SLIPPAGE_PCT;

    /** Stateful 전략 세션별 인스턴스 (COMPOSITE, COMPOSITE_MOMENTUM 등 상태 보유 전략) */
    private final Map<Long, com.cryptoautotrader.strategy.Strategy> sessionStatefulStrategies = new ConcurrentHashMap<>();

    /**
     * 세션별 마지막으로 평가한 <b>닫힌</b> 캔들 시각 — LIVE의 {@code lastEvaluatedClosedCandle}와 동일 목적.
     *
     * <p>이게 없으면 60초 스케줄이 같은 미완성 캔들을 반복 평가한다. 실전은 캔들이 닫힐 때
     * 1회만 평가하는데 페이퍼가 같은 캔들을 60번 다시 보면서 그때그때 변하는 종가로 판단하면,
     * 실전에 존재하지 않는 미세한 정보 이점이 생겨 성과가 부풀려진다.</p>
     */
    private final Map<Long, Instant> lastEvaluatedClosedCandle = new ConcurrentHashMap<>();

    private final VirtualBalanceRepository balanceRepo;
    private final PaperPositionRepository positionRepo;
    private final PaperOrderRepository orderRepo;
    private final MarketDataCacheRepository marketDataCacheRepo;
    private final TelegramNotificationService telegramService;
    private final RulesetRegistry rulesetRegistry;
    private final StrategyLogRepository strategyLogRepo;
    private final RiskManagementService riskManagementService;
    private final StrategyEnablementGate strategyEnablementGate;

    /** DB에서 ExitRuleChecker를 동적 로드하는 헬퍼 */
    private com.cryptoautotrader.core.risk.ExitRuleChecker exitChecker() {
        return riskManagementService.getExitRuleChecker();
    }

    // exchange-adapter 모듈 Bean이 없을 때 null 허용 (테스트/개발 환경 대비)
    @Autowired(required = false)
    private UpbitRestClient upbitRestClient;

    // ── 공개 API ──────────────────────────────────────────────

    @Transactional
    public VirtualBalanceEntity start(PaperTradingStartRequest req) {
        long runningCount = balanceRepo.countByStatus("RUNNING");
        if (runningCount >= MAX_CONCURRENT_SESSIONS) {
            throw new IllegalStateException("최대 " + MAX_CONCURRENT_SESSIONS + "개의 동시 모의투자만 가능합니다.");
        }
        return createSession(req);
    }

    /**
     * 동일 조건(코인/타임프레임/투자금)으로 여러 전략을 한 번에 모의투자 등록.
     * 세션 한도 초과 여부를 일괄 사전 검증한 뒤 각 전략마다 독립 세션을 생성한다.
     */
    @Transactional
    public List<VirtualBalanceEntity> startMulti(MultiStrategyPaperRequest req) {
        int count = req.getStrategyTypes().size();
        long running = balanceRepo.countByStatus("RUNNING");
        if (running + count > MAX_CONCURRENT_SESSIONS) {
            throw new IllegalStateException(
                    "세션 한도 초과: 현재 " + running + "개 실행 중, " + count + "개 추가 시 최대 "
                            + MAX_CONCURRENT_SESSIONS + "개 초과합니다.");
        }
        List<VirtualBalanceEntity> sessions = new ArrayList<>();
        for (String strategyType : req.getStrategyTypes()) {
            PaperTradingStartRequest single = new PaperTradingStartRequest();
            single.setStrategyType(strategyType);
            single.setCoinPair(req.getCoinPair());
            single.setTimeframe(req.getTimeframe());
            single.setInitialCapital(req.getInitialCapital());
            single.setEnableTelegram(req.isEnableTelegram());
            sessions.add(createSession(single));
        }
        log.info("다중 전략 모의투자 {} 세션 생성: {} {} {}",
                count, req.getCoinPair(), req.getTimeframe(), req.getStrategyTypes());
        return sessions;
    }

    private VirtualBalanceEntity createSession(PaperTradingStartRequest req) {
        // 비활성 전략 차단 (2026-08-18) — start/startMulti 양쪽이 여기를 거치므로 한 곳에서 막는다.
        // 페이퍼에도 거는 이유: 페이퍼에서 죽은 전략을 페이퍼로 다시 돌릴 이유가 없다.
        strategyEnablementGate.assertEnabled(req.getStrategyType());

        VirtualBalanceEntity session = VirtualBalanceEntity.builder()
                .totalKrw(req.getInitialCapital())
                .availableKrw(req.getInitialCapital())
                .initialCapital(req.getInitialCapital())
                .strategyName(req.getStrategyType())
                .coinPair(req.getCoinPair())
                .timeframe(req.getTimeframe())
                .status("RUNNING")
                .startedAt(Instant.now())
                .telegramEnabled(req.isEnableTelegram())
                // LIVE 세션과 동일 조건으로 돌리기 위한 설정 (V66) — 미지정이면 risk_config 기본값으로 폴백
                .stopLossPct(req.getStopLossPct())
                .investRatio(req.getInvestRatio())
                .maxHoldHours(req.getMaxHoldHours() != null
                        ? req.getMaxHoldHours()
                        : LiveTradingSessionEntity.DEFAULT_MAX_HOLD_HOURS)
                .build();

        log.info("모의투자 세션 시작: {} {} {} 초기자본={}",
                req.getStrategyType(), req.getCoinPair(), req.getTimeframe(), req.getInitialCapital());
        VirtualBalanceEntity saved = balanceRepo.save(session);

        if (req.isEnableTelegram()) {
            telegramService.notifyPaperSessionStarted(
                    saved.getId(), req.getStrategyType(), req.getCoinPair(),
                    req.getTimeframe(), req.getInitialCapital());
        }
        return saved;
    }

    @Transactional
    public VirtualBalanceEntity stop(Long sessionId) {
        VirtualBalanceEntity session = getSession(sessionId);
        if (!"RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("모의투자가 실행 중이 아닙니다.");
        }

        List<PaperPositionEntity> openPositions = positionRepo.findBySessionIdAndStatus(sessionId, "OPEN");
        openPositions.forEach(pos -> {
            BigDecimal currentPrice = fetchCurrentPrice(pos.getCoinPair());
            closePosition(pos, currentPrice, session, "모의투자 중단 - 강제 청산");
        });

        session.setStatus("STOPPED");
        session.setStoppedAt(Instant.now());
        sessionStatefulStrategies.remove(sessionId);

        log.info("모의투자 세션 중단 (id={}). 최종 자산: {} KRW", sessionId, session.getTotalKrw());
        VirtualBalanceEntity stopped = balanceRepo.save(session);

        if (Boolean.TRUE.equals(session.getTelegramEnabled())) {
            BigDecimal initial = session.getInitialCapital() != null ? session.getInitialCapital() : session.getTotalKrw();
            double returnPct = initial.compareTo(BigDecimal.ZERO) > 0
                    ? stopped.getTotalKrw().subtract(initial)
                              .divide(initial, 4, RoundingMode.HALF_UP)
                              .multiply(BigDecimal.valueOf(100)).doubleValue()
                    : 0;
            telegramService.notifyPaperSessionStopped(
                    sessionId, session.getStrategyName(), session.getCoinPair(),
                    stopped.getTotalKrw(), returnPct);
        }
        return stopped;
    }

    @Transactional
    public List<VirtualBalanceEntity> stopAll() {
        List<VirtualBalanceEntity> runningSessions = balanceRepo.findAllByOrderByIdDesc().stream()
                .filter(s -> "RUNNING".equals(s.getStatus()))
                .toList();
        List<VirtualBalanceEntity> stopped = new ArrayList<>();
        for (VirtualBalanceEntity session : runningSessions) {
            try {
                stopped.add(stop(session.getId()));
            } catch (Exception e) {
                log.warn("세션 일괄 정지 중 오류 (id={}): {}", session.getId(), e.getMessage());
            }
        }
        log.info("모의투자 일괄 정지 완료. 정지된 세션 수: {}", stopped.size());
        return stopped;
    }

    @Transactional(readOnly = true)
    public VirtualBalanceEntity getSessionBalance(Long sessionId) {
        return getSession(sessionId);
    }

    @Transactional(readOnly = true)
    public List<VirtualBalanceEntity> listSessions() {
        return balanceRepo.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<MarketDataCacheEntity> getChartCandles(Long sessionId) {
        VirtualBalanceEntity session = getSession(sessionId);
        Instant from = session.getStartedAt() != null ? session.getStartedAt() : Instant.now().minus(7, ChronoUnit.DAYS);
        Instant to = session.getStoppedAt() != null ? session.getStoppedAt() : Instant.now();
        return marketDataCacheRepo.findCandles(session.getCoinPair(), session.getTimeframe(), from, to);
    }

    @Transactional(readOnly = true)
    public List<PaperOrderEntity> getAllOrders(Long sessionId) {
        return orderRepo.findBySessionIdOrderByCreatedAtDesc(sessionId, Pageable.unpaged()).getContent();
    }

    @Transactional(readOnly = true)
    public List<PaperPositionEntity> getOpenPositions(Long sessionId) {
        return positionRepo.findBySessionIdAndStatus(sessionId, "OPEN");
    }

    @Transactional(readOnly = true)
    public List<PaperPositionEntity> getAllPositions(Long sessionId) {
        return positionRepo.findBySessionId(sessionId);
    }

    @Transactional(readOnly = true)
    public Page<PaperOrderEntity> getOrders(Long sessionId, Pageable pageable) {
        return orderRepo.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
    }

    /**
     * 모의투자 세션 이력 단건 삭제.
     * 진행 중인 세션(RUNNING)은 삭제 불가 → IllegalStateException.
     * 존재하지 않는 세션 → IllegalArgumentException.
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        VirtualBalanceEntity session = balanceRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: id=" + sessionId));
        if ("RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("진행 중인 세션은 삭제할 수 없습니다. 먼저 중단하세요.");
        }
        orderRepo.deleteBySessionId(sessionId);
        positionRepo.deleteBySessionId(sessionId);
        balanceRepo.deleteById(sessionId);
        sessionStatefulStrategies.remove(sessionId);
        log.info("모의투자 세션 이력 삭제 완료: id={}", sessionId);
    }

    /**
     * 모의투자 세션 이력 다건 삭제.
     * 진행 중인 세션이 포함되어 있으면 해당 세션은 건너뛰고 완료/중단 세션만 삭제한다.
     */
    @Transactional
    public void bulkDeleteSessions(List<Long> ids) {
        List<Long> deletableIds = balanceRepo.findAllById(ids).stream()
                .filter(s -> !"RUNNING".equals(s.getStatus()))
                .map(VirtualBalanceEntity::getId)
                .toList();
        if (deletableIds.isEmpty()) {
            log.info("다건 삭제 대상 없음 (모두 RUNNING 이거나 존재하지 않는 ID): ids={}", ids);
            return;
        }
        orderRepo.deleteBySessionIdIn(deletableIds);
        positionRepo.deleteBySessionIdIn(deletableIds);
        balanceRepo.deleteAllByIdInBatch(deletableIds);
        log.info("모의투자 세션 이력 다건 삭제 완료: ids={}", deletableIds);
    }

    // ── 성과 요약 ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PerformanceSummaryResponse getOverallPerformance() {
        List<VirtualBalanceEntity> sessions = balanceRepo.findAllByOrderByIdDesc();

        BigDecimal totalRealizedPnl = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        BigDecimal totalInitialCapital = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        int totalTrades = 0;
        int totalWins = 0;

        List<PerformanceSummaryResponse.SessionPerformance> sessionPerfs = new java.util.ArrayList<>();

        for (VirtualBalanceEntity session : sessions) {
            List<PaperPositionEntity> positions = positionRepo.findBySessionId(session.getId());
            List<PaperPositionEntity> closed = positions.stream().filter(p -> "CLOSED".equals(p.getStatus())).toList();
            List<PaperPositionEntity> open   = positions.stream().filter(p -> "OPEN".equals(p.getStatus())).toList();

            BigDecimal sessionUnrealized = open.stream()
                    .map(p -> p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sessionRealized = session.getRealizedPnl() != null ? session.getRealizedPnl() : BigDecimal.ZERO;
            BigDecimal sessionFee = session.getTotalFee() != null ? session.getTotalFee() : BigDecimal.ZERO;
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

            RiskMetrics sessionRisk = computeRiskMetrics(closed, session.getInitialCapital());

            sessionPerfs.add(PerformanceSummaryResponse.SessionPerformance.builder()
                    .sessionId(session.getId())
                    .strategyType(session.getStrategyName())
                    .coinPair(session.getCoinPair())
                    .timeframe(session.getTimeframe())
                    .status(session.getStatus())
                    .initialCapital(session.getInitialCapital())
                    .currentAsset(session.getTotalKrw())
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
                    .mddPct(sessionRisk.mddPct)
                    .sharpeRatio(sessionRisk.sharpeRatio)
                    .sortinoRatio(sessionRisk.sortinoRatio)
                    .winLossRatio(sessionRisk.winLossRatio)
                    .avgProfitPct(sessionRisk.avgProfitPct)
                    .avgLossPct(sessionRisk.avgLossPct)
                    .maxConsecutiveLoss(sessionRisk.maxConsecutiveLoss)
                    .build());

            totalRealizedPnl = totalRealizedPnl.add(sessionRealized);
            totalUnrealizedPnl = totalUnrealizedPnl.add(sessionUnrealized);
            totalInitialCapital = totalInitialCapital.add(session.getInitialCapital());
            totalFee = totalFee.add(sessionFee);
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

        // 전체 포트폴리오 리스크 지표: 모든 세션 거래를 합산
        List<PaperPositionEntity> allClosed = sessions.stream()
                .flatMap(s -> positionRepo.findBySessionId(s.getId()).stream())
                .filter(p -> "CLOSED".equals(p.getStatus()))
                .toList();
        RiskMetrics overallRisk = computeRiskMetrics(allClosed, totalInitialCapital);

        return PerformanceSummaryResponse.builder()
                .totalRealizedPnl(totalRealizedPnl)
                .totalUnrealizedPnl(totalUnrealizedPnl)
                .totalPnl(totalPnl)
                .totalInitialCapital(totalInitialCapital)
                .returnRatePct(returnRate)
                .totalFee(totalFee)
                .totalTrades(totalTrades)
                .winCount(totalWins)
                .lossCount(totalTrades - totalWins)
                .winRatePct(winRatePct)
                .mddPct(overallRisk.mddPct)
                .sharpeRatio(overallRisk.sharpeRatio)
                .sortinoRatio(overallRisk.sortinoRatio)
                .calmarRatio(overallRisk.calmarRatio)
                .winLossRatio(overallRisk.winLossRatio)
                .avgProfitPct(overallRisk.avgProfitPct)
                .avgLossPct(overallRisk.avgLossPct)
                .maxConsecutiveLoss(overallRisk.maxConsecutiveLoss)
                .sessions(sessionPerfs)
                .build();
    }

    // ── 리스크 지표 계산 ──────────────────────────────────────────────────────────

    private static class RiskMetrics {
        BigDecimal mddPct;
        BigDecimal sharpeRatio;
        BigDecimal sortinoRatio;
        BigDecimal calmarRatio;
        BigDecimal winLossRatio;
        BigDecimal avgProfitPct;
        BigDecimal avgLossPct;
        int maxConsecutiveLoss;
    }

    /**
     * 종료된 포지션 목록으로 MDD·Sharpe·Sortino·승패비 등 리스크 지표를 계산한다.
     * 3건 미만이면 통계적으로 의미 없으므로 null 반환.
     */
    private RiskMetrics computeRiskMetrics(List<PaperPositionEntity> closed, BigDecimal initialCapital) {
        RiskMetrics m = new RiskMetrics();
        if (closed.isEmpty()) return m;

        // closedAt 오름차순 정렬 (null이면 제외)
        List<PaperPositionEntity> sorted = closed.stream()
                .filter(p -> p.getClosedAt() != null && p.getRealizedPnl() != null)
                .sorted(java.util.Comparator.comparing(PaperPositionEntity::getClosedAt))
                .toList();
        if (sorted.isEmpty()) return m;

        // 거래별 수익률 (%)
        List<Double> returns = new ArrayList<>();
        List<Double> profits = new ArrayList<>();
        List<Double> losses  = new ArrayList<>();
        for (PaperPositionEntity pos : sorted) {
            if (pos.getEntryPrice() == null || pos.getSize() == null) continue;
            BigDecimal invested = pos.getEntryPrice().multiply(pos.getSize());
            if (invested.compareTo(BigDecimal.ZERO) <= 0) continue;
            double ret = pos.getRealizedPnl()
                    .divide(invested, 8, RoundingMode.HALF_UP)
                    .doubleValue() * 100.0;
            returns.add(ret);
            if (ret > 0) profits.add(ret);
            else if (ret < 0) losses.add(Math.abs(ret));
        }

        // MDD: 누적 자산 곡선으로 계산
        double equity = initialCapital.doubleValue();
        double peak   = equity;
        double minDD  = 0.0;
        for (PaperPositionEntity pos : sorted) {
            equity += pos.getRealizedPnl().doubleValue();
            if (equity > peak) peak = equity;
            if (peak > 0) {
                double dd = (equity - peak) / peak * 100.0;
                if (dd < minDD) minDD = dd;
            }
        }
        m.mddPct = BigDecimal.valueOf(minDD).setScale(2, RoundingMode.HALF_UP);

        // Sharpe·Sortino: 3건 이상부터 계산
        if (returns.size() >= 3) {
            double avg = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double std = stdDevPopulation(returns, avg);
            if (std > 0) {
                m.sharpeRatio = BigDecimal.valueOf(avg / std).setScale(4, RoundingMode.HALF_UP);
            }
            double downStd = losses.isEmpty() ? 0.0 : stdDevPopulation(
                    losses.stream().map(v -> -v).toList(), 0.0);
            if (downStd > 0) {
                m.sortinoRatio = BigDecimal.valueOf(avg / downStd).setScale(4, RoundingMode.HALF_UP);
            }
        }

        // Calmar = 평균수익률 / |MDD|
        if (m.sharpeRatio != null && minDD < 0) {
            double avgRet = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            m.calmarRatio = BigDecimal.valueOf(avgRet / Math.abs(minDD)).setScale(4, RoundingMode.HALF_UP);
        }

        // 승패비·평균 수익/손실
        if (!profits.isEmpty() && !losses.isEmpty()) {
            double avgProfit = profits.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double avgLoss   = losses.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            m.avgProfitPct   = BigDecimal.valueOf(avgProfit).setScale(2, RoundingMode.HALF_UP);
            m.avgLossPct     = BigDecimal.valueOf(-avgLoss).setScale(2, RoundingMode.HALF_UP);
            if (avgLoss > 0) {
                m.winLossRatio = BigDecimal.valueOf(avgProfit / avgLoss).setScale(4, RoundingMode.HALF_UP);
            }
        }

        // 최대 연속 손실
        int maxCons = 0, cons = 0;
        for (PaperPositionEntity pos : sorted) {
            if (pos.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0) {
                maxCons = Math.max(maxCons, ++cons);
            } else {
                cons = 0;
            }
        }
        m.maxConsecutiveLoss = maxCons;
        return m;
    }

    private double stdDevPopulation(List<Double> values, double mean) {
        if (values.size() < 2) return 0.0;
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / values.size();
        return Math.sqrt(variance);
    }

    // ── 스케줄: MarketDataSyncService 실행(0s) 후 35초 뒤 전략 실행 ──

    @Scheduled(fixedDelay = 60_000, initialDelay = 35_000)
    public void runStrategy() {
        List<VirtualBalanceEntity> runningSessions = balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING");
        if (runningSessions.isEmpty()) return;

        // 이번 틱 동안만 유효한 캔들 캐시 — 격자 실험(코인 N × 전략 M)에서 같은 (코인,타임프레임)을
        // 세션마다 다시 조회하는 낭비를 없앤다. 100세션이 10코인을 쓰면 500행 쿼리가 200회 → 11회
        // (코인 10 + BTC 가드 1)로 줄어든다. 틱마다 새로 만들므로 stale 데이터 위험은 없다.
        Map<String, List<Candle>> tickCandleCache = new java.util.HashMap<>();

        for (VirtualBalanceEntity session : runningSessions) {
            try {
                runSessionStrategy(session, tickCandleCache);
            } catch (Exception e) {
                log.error("모의투자 전략 실행 오류 (sessionId={}): {}", session.getId(), e.getMessage(), e);
            }
        }
    }

    /** 틱 캐시를 경유한 캔들 조회 — 같은 (코인, 타임프레임)은 틱당 1회만 DB를 친다. */
    private List<Candle> candlesFor(String coinPair, String timeframe,
                                     Map<String, List<Candle>> tickCandleCache) {
        return tickCandleCache.computeIfAbsent(
                coinPair + ":" + timeframe, k -> fetchRecentCandles(coinPair, timeframe));
    }

    // ── 내부 메서드 ───────────────────────────────────────────

    /**
     * 세션 1회 평가 — <b>{@code LiveTradingService.processSessionTick}과 동일한 순서·게이트</b>로 구성한다.
     *
     * <p><b>2026-08-06 LIVE 정렬</b>: 이전 구현은 게이트가 하나도 없이 신호가 나오는 대로 매매했다.
     * 실전 로그에서 "BUY 신호 86건 전량 진입 게이트 차단"처럼 대부분의 BUY가 걸러지는데도
     * 페이퍼는 그걸 전부 체결해, 두 엔진의 <b>거래 모집단 자체가 달랐다</b>. 그 상태의 페이퍼 성적은
     * 실전 예측에 쓸 수 없다. 아래 순서·상수는 LIVE와 1:1로 맞춰야 하며, 한쪽만 바꾸면 안 된다.</p>
     *
     * <p><b>의도적으로 적용하지 않는 LIVE 로직</b>(자본 배정 게이트라 페이퍼의 목적과 상충):
     * {@code StrategyLiveStatusRegistry.isBlocked}, {@code WalkForwardValidationGate},
     * {@code §8 cross-session 잔고 가드}. 페이퍼는 <b>미검증 전략을 검증하기 위한</b> 도구이므로
     * "검증되지 않아 차단" 규칙을 적용하면 존재 이유가 사라진다.</p>
     */
    private void runSessionStrategy(VirtualBalanceEntity session,
                                     Map<String, List<Candle>> tickCandleCache) {
        Long sessionId = session.getId();
        String coinPair = session.getCoinPair();
        String timeframe = session.getTimeframe();
        String strategyName = session.getStrategyName();

        List<Candle> candles = candlesFor(coinPair, timeframe, tickCandleCache);
        if (candles.size() < 10) {
            log.warn("모의투자 캔들 부족: {} {}건 (sessionId={})", coinPair, candles.size(), sessionId);
            return;
        }

        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();

        // 전략 평가 전 시장 레짐 선감지 — RANGE 게이트 판정에 사용.
        // detectRaw() 사용 이유는 LIVE와 동일(매번 새 detector라 hysteresis가 항상 RANGE로 오판됨).
        MarketRegime preEvalRegime = null;
        if (candles.size() >= MarketRegimeDetector.MIN_CANDLE_COUNT) {
            try {
                preEvalRegime = new MarketRegimeDetector().detectRaw(candles);
            } catch (Exception e) {
                log.warn("레짐 선감지 실패 (sessionId={}): {}", sessionId, e.getMessage());
            }
        }

        // 안전장치 2종은 닫힌 캔들 게이팅과 무관하게 매 tick 최신 캔들로 평가한다 (LIVE 동일).
        BlackSwanGuard.Result blackSwanGuard = BlackSwanGuard.check(candles);
        List<Candle> btcCandles = "KRW-BTC".equals(coinPair)
                ? candles : candlesFor("KRW-BTC", timeframe, tickCandleCache);
        BtcMarketGuard.Result btcMarketGuard = BtcMarketGuard.check(btcCandles);

        // ── 닫힌 캔들 게이팅 ──────────────────────────────────────────────────
        long periodMin = TimeframeUtils.toMinutes(timeframe);
        Instant lastCandleTime = candles.get(candles.size() - 1).getTime();
        boolean lastCandleClosed = !lastCandleTime.plus(periodMin, ChronoUnit.MINUTES).isAfter(Instant.now());
        List<Candle> evalCandles = (lastCandleClosed || candles.size() < 2)
                ? candles : candles.subList(0, candles.size() - 1);
        Instant closedCandleTime = evalCandles.get(evalCandles.size() - 1).getTime();
        Instant prevEvaluated = lastEvaluatedClosedCandle.get(sessionId);
        boolean newClosedCandle = prevEvaluated == null || closedCandleTime.isAfter(prevEvaluated);

        StrategySignal signal;
        StrategyLogEntity savedSignalLog = null;
        if (!newClosedCandle) {
            // 이미 평가한 닫힌 캔들 — 전략 평가 스킵. 손절/익절/타임스톱 감시는 아래에서 계속된다.
            signal = StrategySignal.hold("닫힌 캔들 미갱신 — 전략 평가 스킵");
        } else {
            lastEvaluatedClosedCandle.put(sessionId, closedCandleTime);

            com.cryptoautotrader.strategy.Strategy strategyInstance =
                    StrategyRegistry.isStateful(strategyName)
                            ? sessionStatefulStrategies.computeIfAbsent(sessionId,
                                    id -> StrategyRegistry.createNew(strategyName))
                            : StrategyRegistry.get(strategyName);

            Map<String, Object> params = new java.util.HashMap<>();
            params.put("coinPair", coinPair);
            if (session.getStartedAt() != null) {
                params.put("sessionStartedAt", session.getStartedAt().toEpochMilli());
            }
            signal = strategyInstance.evaluate(evalCandles, params);
            log.info("모의투자 신호 (sessionId={}): {} {} → {} ({})",
                    sessionId, strategyName, coinPair, signal.getAction(), signal.getReason());

            // ── 진입 게이트 4종 (LIVE와 동일 순서) ──────────────────────────
            boolean ema200Pass = Ema200RegimeGate.isExempt(strategyName)
                    || Ema200RegimeGate.allowsBuy(evalCandles, coinPair);
            if (signal.getAction() == StrategySignal.Action.BUY && !ema200Pass) {
                signal = StrategySignal.hold("EMA200 레짐 필터 — 현재가 EMA200 이하");
            }

            if (preEvalRegime == MarketRegime.RANGE
                    && signal.getAction() == StrategySignal.Action.BUY
                    && RangeRegimeGate.isBlocked(strategyName)) {
                signal = StrategySignal.hold("RANGE 레짐 — 추세 추종 전략 횡보장 신규 진입 차단");
            }

            if (signal.getAction() == StrategySignal.Action.BUY && blackSwanGuard.triggered()) {
                signal = StrategySignal.hold("BLACK_SWAN_GUARD 발동 — " + blackSwanGuard.reason());
            }

            if (signal.getAction() == StrategySignal.Action.BUY && btcMarketGuard.triggered()) {
                signal = StrategySignal.hold("BTC_MARKET_GUARD 발동 — " + btcMarketGuard.reason());
            }

            // 전략 로그 DB 저장
            try {
                BigDecimal conf = (signal.getAction() != StrategySignal.Action.HOLD)
                        ? signal.getStrength().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                        : null;
                StrategyLogEntity logEntity = StrategyLogEntity.builder()
                        .rulesetHash(rulesetRegistry.hashFor(session))
                        .strategyName(strategyName)
                        .coinPair(coinPair)
                        .signal(signal.getAction().name())
                        .reason(signal.getReason())
                        .marketRegime(preEvalRegime != null ? preEvalRegime.name() : null)
                        .sessionType("PAPER")
                        .sessionId(sessionId)
                        // signalPrice 누락 시 SignalQualityService 사후 평가(4h/24h)에서 영구 제외된다
                        // — 2026-07-15 운영 DB 분석: PAPER 로그 24,820건 전량 signal_price NULL의 원인.
                        .signalPrice(currentPrice)
                        .confidenceScore(conf)
                        .build();
                savedSignalLog = strategyLogRepo.save(logEntity);
            } catch (Exception e) {
                log.warn("전략 로그 저장 실패: {}", e.getMessage());
            }
        }

        Optional<PaperPositionEntity> openPos = positionRepo
                .findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "OPEN");

        // ── 손절/익절/타임스톱 (전략 신호보다 우선) ──────────────────────────
        if (openPos.isPresent()) {
            PaperPositionEntity pos = openPos.get();

            // 트레일링 스탑 갱신 (ExitRuleChecker 공통 로직)
            if (pos.getStopLossPrice() != null && pos.getTakeProfitPrice() != null && pos.getEntryPrice() != null) {
                var updatedLevels = exitChecker().updateTrailingStops(
                        currentPrice, currentPrice, pos.getEntryPrice(),
                        pos.getStopLossPrice(), pos.getTakeProfitPrice());
                if (updatedLevels.getStopLossPrice().compareTo(pos.getStopLossPrice()) != 0
                        || updatedLevels.getTakeProfitPrice().compareTo(pos.getTakeProfitPrice()) != 0) {
                    pos.setStopLossPrice(updatedLevels.getStopLossPrice());
                    pos.setTakeProfitPrice(updatedLevels.getTakeProfitPrice());
                    positionRepo.save(pos);
                }
            }

            var exitCheck = exitChecker().checkPriceExit(
                    currentPrice, pos.getStopLossPrice(), pos.getTakeProfitPrice());
            if (exitCheck.isShouldExit()) {
                log.warn("모의투자 {} (sessionId={}): {} 현재가={}",
                        exitCheck.getReason(), sessionId, coinPair, currentPrice);
                closePosition(pos, currentPrice, session, exitCheck.getReason());
                return;
            }

            // time stop — LIVE와 동일하게 ExitRuleCalculator로 판정. 기본 비활성(0)이라
            // 명시적으로 켠 세션에만 영향을 준다.
            if (ExitRuleCalculator.shouldTimeStop(session.getMaxHoldHours(), pos.getOpenedAt(), Instant.now())) {
                long heldHours = Duration.between(pos.getOpenedAt(), Instant.now()).toHours();
                log.warn("모의투자 시간 초과 청산 (sessionId={}): {} 보유 {}h ≥ {}h",
                        sessionId, coinPair, heldHours, session.getMaxHoldHours());
                closePosition(pos, currentPrice, session, String.format(
                        "시간 초과 청산 — 보유 %d시간 ≥ %d시간", heldHours, session.getMaxHoldHours()));
                return;
            }
        }

        final StrategySignal finalSignal = signal;
        switch (signal.getAction()) {
            case BUY -> {
                if (openPos.isEmpty()) {
                    boolean bought = executeBuy(sessionId, coinPair, currentPrice, session, finalSignal, evalCandles);
                    saveSignalQuality(savedSignalLog, bought, bought ? null : "매수 실행 실패(최소주문/잔고 미달)");
                } else {
                    saveSignalQuality(savedSignalLog, false, "이미 포지션 보유 중");
                }
            }
            case SELL -> {
                if (openPos.isPresent()) {
                    PaperPositionEntity pos = openPos.get();
                    long heldMinutes = pos.getOpenedAt() != null
                            ? Duration.between(pos.getOpenedAt(), Instant.now()).toMinutes()
                            : Long.MAX_VALUE;
                    BigDecimal heldPnlPct = (pos.getAvgPrice() != null
                            && pos.getAvgPrice().compareTo(BigDecimal.ZERO) > 0)
                            ? currentPrice.subtract(pos.getAvgPrice())
                                    .divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                                    .setScale(3, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    if (heldMinutes < MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT) {
                        String blockReason = String.format(
                                "최소 보유시간 미달: %d분 < %d분 (pnl=%s%%, 전략 SELL 차단, SL/TP는 유효)",
                                heldMinutes, MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT, heldPnlPct.toPlainString());
                        log.info("모의투자 SELL 차단 (sessionId={}): {} {}", sessionId, coinPair, blockReason);
                        saveSignalQuality(savedSignalLog, false, blockReason);
                    } else if (heldPnlPct.compareTo(MIN_PNL_PCT_FOR_SIGNAL_EXIT) < 0
                            && heldPnlPct.compareTo(LOSS_ESCAPE_THRESHOLD) >= 0) {
                        String blockReason = String.format(
                                "본전 청산 차단: pnl=%s%% < +%s%% (전략 SELL 무시, SL/TP/트레일링은 유효)",
                                heldPnlPct.toPlainString(), MIN_PNL_PCT_FOR_SIGNAL_EXIT.toPlainString());
                        log.info("모의투자 SELL 차단 (sessionId={}): {} {}", sessionId, coinPair, blockReason);
                        saveSignalQuality(savedSignalLog, false, blockReason);
                    } else {
                        closePosition(pos, currentPrice, session, String.format(
                                "전략 신호: %s -- %s (pnl=%s%%)",
                                strategyName, finalSignal.getReason(), heldPnlPct.toPlainString()));
                        saveSignalQuality(savedSignalLog, true, null);
                    }
                } else {
                    saveSignalQuality(savedSignalLog, false, "청산할 포지션 없음");
                }
            }
            default -> { /* HOLD — 신호 품질 추적 불필요 */ }
        }

        updateUnrealizedPnl(sessionId, coinPair, currentPrice, session);
    }

    /** 신호 품질(실행 여부/차단 사유) 기록 — LIVE의 동명 메서드와 동일 목적. */
    private void saveSignalQuality(StrategyLogEntity logEntity, boolean wasExecuted, String blockedReason) {
        if (logEntity == null) return;
        try {
            logEntity.setWasExecuted(wasExecuted);
            logEntity.setBlockedReason(blockedReason);
            strategyLogRepo.save(logEntity);
        } catch (Exception e) {
            log.warn("신호 품질 로그 업데이트 실패: {}", e.getMessage());
        }
    }

    /**
     * 모의 매수 — LIVE {@code executeSessionBuy}와 동일한 투자금 산정·SL/TP 공식·최소주문 제약을 쓴다.
     *
     * @param evalCandles ATR 기반 손절폭 산정용 (닫힌 캔들 기준 — LIVE와 동일)
     * @return 실제로 체결됐으면 true (신호 품질 로그의 wasExecuted에 그대로 반영)
     */
    private boolean executeBuy(Long sessionId, String coinPair, BigDecimal signalPrice,
                                VirtualBalanceEntity session, StrategySignal signal,
                                List<Candle> evalCandles) {
        // 투자금: LIVE와 동일하게 availableKrw × investRatio (세션값 우선, 없으면 risk_config 기본값)
        BigDecimal ratio = session.getInvestRatio() != null
                ? session.getInvestRatio() : exitChecker().getConfig().getInvestRatio();
        BigDecimal investAmount = session.getAvailableKrw().multiply(ratio)
                .setScale(2, RoundingMode.DOWN);

        // 업비트 최소 주문금액 — 실거래에서 못 넣는 주문은 페이퍼도 넣지 않는다.
        if (investAmount.compareTo(MIN_ORDER_KRW) < 0) {
            log.warn("모의투자 매수 불가: 최소주문 미달 (투자가능 {}원 < {}원) sessionId={}",
                    investAmount, MIN_ORDER_KRW, sessionId);
            return false;
        }

        // 체결 슬리피지 — 매수는 신호가보다 불리하게(높게) 체결된다.
        BigDecimal price = signalPrice.multiply(BigDecimal.ONE.add(SLIPPAGE_PCT))
                .setScale(8, RoundingMode.HALF_UP);

        BigDecimal fee = investAmount.multiply(FEE_RATE);
        BigDecimal netAmount = investAmount.subtract(fee);
        BigDecimal quantity = netAmount.divide(price, 8, RoundingMode.DOWN);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        // avgPrice = 수수료 포함 실제 취득단가 (investAmount / quantity)
        // 이렇게 해야 closePosition 에서 costBasis = investAmount 가 되어 정확한 실현손익 계산
        BigDecimal avgPriceWithFee = investAmount.divide(quantity, 8, RoundingMode.HALF_UP);

        // ── SL/TP: ATR 기반 (LIVE·DYNAMIC과 ExitRuleCalculator 공유) ──────────
        // 기존에는 ExitRuleChecker의 고정 % 공식을 써서 실전과 손절폭이 달랐다.
        BigDecimal slPct = ExitRuleCalculator.resolveStopLossPct(
                session.getStopLossPct(), evalCandles, price);
        BigDecimal atrStopLossPrice = price.multiply(BigDecimal.ONE.subtract(
                        slPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                .setScale(8, RoundingMode.HALF_DOWN);

        // 전략 제안 SL은 존중하되 더 넓은 쪽을 채택 (타이트한 제안은 휩쏘로 이어진다 — LIVE 동일)
        BigDecimal stopLossPrice = (signal != null && signal.getSuggestedStopLoss() != null)
                ? signal.getSuggestedStopLoss().min(atrStopLossPrice)
                : atrStopLossPrice;

        BigDecimal takeProfitPrice = ExitRuleCalculator.resolveTakeProfitPrice(
                price, stopLossPrice, signal != null ? signal.getSuggestedTakeProfit() : null);

        String rulesetHash = rulesetRegistry.hashFor(session);
        PaperPositionEntity pos = PaperPositionEntity.builder()
                .rulesetHash(rulesetHash)
                .sessionId(sessionId)
                .coinPair(coinPair)
                .side("BUY")
                .entryPrice(price)
                .avgPrice(avgPriceWithFee)
                .size(quantity)
                .positionFee(fee)
                .status("OPEN")
                .stopLossPrice(stopLossPrice)
                .takeProfitPrice(takeProfitPrice)
                .build();
        pos = positionRepo.save(pos);

        PaperOrderEntity order = PaperOrderEntity.builder()
                .sessionId(sessionId)
                .positionId(pos.getId())
                .coinPair(coinPair)
                .side("BUY")
                .orderType("MARKET")
                .price(price)
                .quantity(quantity)
                .filledQuantity(quantity)
                .state("FILLED")
                .exchangeOrderId("PAPER-" + pos.getId())
                .signalReason(signal.getReason())
                .filledAt(Instant.now())
                .build();
        orderRepo.save(order);

        session.setAvailableKrw(session.getAvailableKrw().subtract(investAmount));
        session.setTotalFee(session.getTotalFee().add(fee));
        balanceRepo.save(session);

        log.info("모의 매수 체결 (sessionId={}): {} {}개 @ {} SL={} TP={} (수수료: {})",
                sessionId, coinPair, quantity, price, stopLossPrice, takeProfitPrice, fee);

        if (Boolean.TRUE.equals(session.getTelegramEnabled())) {
            telegramService.bufferTradeEvent(
                    "[모의투자] 세션#" + sessionId, coinPair, "BUY",
                    price, quantity, fee, null, signal.getReason());
        }
        return true;
    }

    private void closePosition(PaperPositionEntity pos, BigDecimal signalPrice,
                               VirtualBalanceEntity session, String reason) {
        // 체결 슬리피지 — 매도는 신호가보다 불리하게(낮게) 체결된다 (매수 반대 방향).
        BigDecimal currentPrice = signalPrice.multiply(BigDecimal.ONE.subtract(SLIPPAGE_PCT))
                .setScale(8, RoundingMode.HALF_DOWN);

        BigDecimal proceeds = pos.getSize().multiply(currentPrice);
        BigDecimal fee = proceeds.multiply(FEE_RATE);
        BigDecimal netProceeds = proceeds.subtract(fee);

        BigDecimal costBasis = pos.getSize().multiply(pos.getAvgPrice());
        BigDecimal realizedPnl = netProceeds.subtract(costBasis);

        pos.setRealizedPnl(realizedPnl);
        pos.setUnrealizedPnl(BigDecimal.ZERO);
        pos.setPositionFee(pos.getPositionFee() != null
                ? pos.getPositionFee().add(fee) : fee);
        pos.setStatus("CLOSED");
        pos.setClosedAt(Instant.now());
        positionRepo.save(pos);

        PaperOrderEntity order = PaperOrderEntity.builder()
                .sessionId(pos.getSessionId())
                .positionId(pos.getId())
                .coinPair(pos.getCoinPair())
                .side("SELL")
                .orderType("MARKET")
                .price(currentPrice)
                .quantity(pos.getSize())
                .filledQuantity(pos.getSize())
                .state("FILLED")
                .exchangeOrderId("PAPER-SELL-" + pos.getId())
                .signalReason(reason)
                .filledAt(Instant.now())
                .build();
        orderRepo.save(order);

        session.setAvailableKrw(session.getAvailableKrw().add(netProceeds));
        session.setTotalKrw(session.getAvailableKrw());
        session.setRealizedPnl(session.getRealizedPnl().add(realizedPnl));
        session.setTotalFee(session.getTotalFee().add(fee));
        balanceRepo.save(session);

        log.info("모의 매도 체결 (sessionId={}): {} {}개 @ {} 손익: {} KRW",
                pos.getSessionId(), pos.getCoinPair(), pos.getSize(), currentPrice, realizedPnl);

        if (Boolean.TRUE.equals(session.getTelegramEnabled())) {
            telegramService.bufferTradeEvent(
                    "[모의투자] 세션#" + pos.getSessionId(), pos.getCoinPair(), "SELL",
                    currentPrice, pos.getSize(), fee, realizedPnl, reason);
        }
    }

    private void updateUnrealizedPnl(Long sessionId, String coinPair, BigDecimal currentPrice,
                                      VirtualBalanceEntity session) {
        // 현재 코인 포지션 미실현손익 갱신
        positionRepo.findBySessionIdAndCoinPairAndStatus(sessionId, coinPair, "OPEN").ifPresent(pos -> {
            BigDecimal unrealized = currentPrice.subtract(pos.getAvgPrice()).multiply(pos.getSize());
            pos.setUnrealizedPnl(unrealized);
            positionRepo.save(pos);
        });

        // totalKrw = 가용 KRW + 세션 내 모든 오픈 포지션 평가금액 합산
        // (다중 코인 지원 시에도 정확한 총자산 계산)
        BigDecimal openPositionsValue = positionRepo.findBySessionIdAndStatus(sessionId, "OPEN")
                .stream()
                .map(pos -> {
                    BigDecimal price = pos.getCoinPair().equals(coinPair)
                            ? currentPrice
                            : fetchCurrentPrice(pos.getCoinPair());
                    return pos.getSize().multiply(price);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // executeBuy/closePosition이 session을 저장했을 수 있으므로 최신 버전으로 재조회
        VirtualBalanceEntity freshSession = balanceRepo.findById(sessionId).orElse(session);
        freshSession.setTotalKrw(freshSession.getAvailableKrw().add(openPositionsValue));
        balanceRepo.save(freshSession);
    }

    private VirtualBalanceEntity getSession(Long sessionId) {
        return balanceRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: id=" + sessionId));
    }

    private List<Candle> fetchRecentCandles(String coinPair, String timeframe) {
        Instant to = Instant.now();
        Instant from = to.minus(CANDLE_LOOKBACK * TimeframeUtils.toMinutes(timeframe), ChronoUnit.MINUTES);

        // MarketDataSyncService 가 미리 DB에 저장한 캔들만 사용
        return marketDataCacheRepo.findCandles(coinPair, timeframe, from, to).stream()
                .map(c -> Candle.builder()
                        .time(c.getTime()).open(c.getOpen()).high(c.getHigh())
                        .low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                        .build())
                .toList();
    }

    private BigDecimal fetchCurrentPrice(String coinPair) {
        // DB에서 가장 최근 M1 캔들 조회
        Instant now = Instant.now();
        Instant from = now.minus(5, ChronoUnit.MINUTES);
        List<MarketDataCacheEntity> recent = marketDataCacheRepo.findCandles(coinPair, "M1", from, now);
        if (!recent.isEmpty()) {
            return recent.get(recent.size() - 1).getClose();
        }
        // M1 캔들이 없으면 주입된 UpbitRestClient 통해 폴백 조회
        if (upbitRestClient != null) {
            try {
                UpbitCandleCollector collector = new UpbitCandleCollector(upbitRestClient);
                List<Candle> candles = collector.fetchCandles(coinPair, "M1", from, now);
                if (!candles.isEmpty()) {
                    return candles.get(candles.size() - 1).getClose();
                }
            } catch (Exception e) {
                log.warn("현재가 조회 실패: {}", e.getMessage());
            }
        }
        return BigDecimal.ZERO;
    }


}
