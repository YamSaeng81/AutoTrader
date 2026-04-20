package com.cryptoautotrader.api.report;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.RegimeChangeLogEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.util.TradingConstants;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.RegimeChangeLogRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 12시간 구간 로그 집계 서비스.
 * strategy_log + position + regime_change_log를 분석해 {@link AnalysisReport}를 생성한다.
 */
@Service
@RequiredArgsConstructor
public class LogAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(LogAnalyzerService.class);

    private final StrategyLogRepository strategyLogRepo;
    private final PositionRepository positionRepo;
    private final RegimeChangeLogRepository regimeChangeLogRepo;
    private final LiveTradingSessionRepository sessionRepo;
    private final UpbitRestClient upbitRestClient;

    /**
     * 지정 구간의 분석 보고서를 생성한다 (전체 — 실전+모의 합산).
     * DB에서 기간 필터링하여 메모리 부하를 최소화한다.
     */
    public AnalysisReport analyze(Instant from, Instant to) {
        return analyze(from, to, null);
    }

    /**
     * 지정 구간의 분석 보고서를 생성한다.
     *
     * @param sessionType "REAL" | "PAPER" | null(전체)
     */
    public AnalysisReport analyze(Instant from, Instant to, String sessionType) {
        log.info("[LogAnalyzer] 분석 시작 — {} ~ {} ({})",
                from, to, sessionType != null ? sessionType : "ALL");

        List<StrategyLogEntity> periodLogs = sessionType != null
                ? strategyLogRepo.findByPeriodAndSessionType(sessionType, from, to)
                : strategyLogRepo.findByPeriod(from, to);
        List<PositionEntity> closedPos = sessionType != null
                ? positionRepo.findClosedByPeriodAndSessionType(sessionType, from, to)
                : positionRepo.findClosedByPeriod(from, to);

        SignalStats    signals      = buildSignalStats(periodLogs);
        PositionStats  positions    = buildPositionStats(closedPos);
        RegimeStats    regime       = buildRegimeStats(from, to);
        int            openPos      = buildOpenPositionCount();
        int            streak       = buildConsecutiveLosses();
        Map<String, AnalysisReport.RegimeSignalQuality> regimeSignalStats  = buildRegimeSignalStats(periodLogs);
        List<AnalysisReport.HourlySignalQuality>         hourlySignalStats  = buildHourlySignalStats(periodLogs);
        AnalysisReport.StrategyCorrelationStats          correlationStats   = buildCorrelationStats(periodLogs);

        // 실행 중 세션 기반 추가 컨텍스트
        List<LiveTradingSessionEntity> runningSessions = buildRunningSessions();
        List<AnalysisReport.ActiveSessionInfo> activeSessions = buildActiveSessions(runningSessions);
        Map<String, BigDecimal> coinPriceChanges = buildCoinPriceChanges(runningSessions, from);
        Map<String, AnalysisReport.CoinPositionStat> coinPositionStats = buildCoinPositionStats(closedPos);

        // BTC/ETH 가격은 coinPriceChanges에 포함되지 않을 수 있으므로 별도 보장
        BigDecimal btcChange = coinPriceChanges.computeIfAbsent("KRW-BTC", k -> fetchPriceChange(k, from));
        BigDecimal ethChange = coinPriceChanges.computeIfAbsent("KRW-ETH", k -> fetchPriceChange(k, from));

        log.info("[LogAnalyzer] 집계 완료 — 신호 {}건 (실행 {}/차단 {}), 포지션 {}건, 레짐 {}, BTC {}%, 활성세션 {}개",
                signals.total, signals.executed, signals.blocked, positions.closedCount,
                regime.currentRegime, btcChange, activeSessions.size());

        return AnalysisReport.builder()
                .periodStart(from)
                .periodEnd(to)
                // 신호
                .totalSignals(signals.total)
                .buySignals(signals.buy)
                .sellSignals(signals.sell)
                .holdSignals(signals.hold)
                .executedSignals(signals.executed)
                .blockedSignals(signals.blocked)
                .blockReasons(signals.blockReasons)
                .strategyStats(signals.strategyStats)
                .accuracy4h(signals.accuracy4h)
                .accuracy24h(signals.accuracy24h)
                .avgReturn4h(signals.avgReturn4h)
                .avgReturn24h(signals.avgReturn24h)
                // 포지션
                .closedPositions(positions.closedCount)
                .totalRealizedPnl(positions.totalPnl)
                .winCount(positions.winCount)
                .lossCount(positions.lossCount)
                .winRate(positions.winRate)
                // 레짐
                .currentRegime(regime.currentRegime)
                .regimeTransitions(regime.transitions)
                // 시장 가격 맥락
                .btcPriceChange12h(btcChange)
                .ethPriceChange12h(ethChange)
                // 포지션 현황
                .openPositionCount(openPos)
                .consecutiveLosses(streak)
                // 실행 중 세션 현황
                .activeSessions(activeSessions)
                .coinPriceChanges(coinPriceChanges)
                .coinPositionStats(coinPositionStats)
                // 레짐별/시간대별 신호 품질 및 전략 간 상관관계
                .regimeSignalStats(regimeSignalStats)
                .hourlySignalStats(hourlySignalStats)
                .correlationStats(correlationStats)
                .build();
    }

    // ── 신호 통계 ─────────────────────────────────────────────────────────────

    private SignalStats buildSignalStats(List<StrategyLogEntity> logs) {
        SignalStats s = new SignalStats();
        s.total      = logs.size();
        s.blockReasons = new HashMap<>();
        Map<String, List<StrategyLogEntity>> byStrategy = new HashMap<>();

        // 1회 순회로 buy/sell/hold/executed/blocked/blockReasons/strategyGroups 집계
        for (StrategyLogEntity l : logs) {
            switch (l.getSignal() != null ? l.getSignal() : "") {
                case "BUY"  -> s.buy++;
                case "SELL" -> s.sell++;
                case "HOLD" -> s.hold++;
            }
            if (l.isWasExecuted()) {
                s.executed++;
            } else if (l.getBlockedReason() != null) {
                s.blocked++;
                String key = l.getBlockedReason().split(":")[0].trim();
                if (!key.isBlank()) s.blockReasons.merge(key, 1, Integer::sum);
            }
            String strat = l.getStrategyName() != null ? l.getStrategyName() : "UNKNOWN";
            byStrategy.computeIfAbsent(strat, k -> new ArrayList<>()).add(l);
        }

        s.strategyStats = byStrategy.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> {
                            List<StrategyLogEntity> g = e.getValue();
                            int buy = 0, sell = 0, hold = 0, executed = 0;
                            for (StrategyLogEntity l : g) {
                                switch (l.getSignal() != null ? l.getSignal() : "") {
                                    case "BUY"  -> buy++;
                                    case "SELL" -> sell++;
                                    case "HOLD" -> hold++;
                                }
                                if (l.isWasExecuted()) executed++;
                            }
                            List<StrategyLogEntity> highConf = g.stream()
                                    .filter(l -> l.getConfidenceScore() != null
                                            && l.getConfidenceScore().compareTo(HIGH_CONF_THRESHOLD) >= 0)
                                    .toList();
                            return AnalysisReport.StrategySignalStat.builder()
                                    .buy(buy).sell(sell).hold(hold).executed(executed)
                                    .accuracy4h(calcAccuracy(g, true))
                                    .accuracy24h(calcAccuracy(g, false))
                                    .avgReturn4h(calcAvgReturn(g, true))
                                    .avgReturn24h(calcAvgReturn(g, false))
                                    .highConfAccuracy4h(calcAccuracy(highConf, true))
                                    .highConfCount(highConf.size())
                                    .expectedValue4h(calcExpectedValue(g, true))
                                    .build();
                        }));

        s.accuracy4h   = calcAccuracy(logs, true);
        s.accuracy24h  = calcAccuracy(logs, false);
        s.avgReturn4h  = calcAvgReturn(logs, true);
        s.avgReturn24h = calcAvgReturn(logs, false);
        return s;
    }

    // ── 포지션 통계 ───────────────────────────────────────────────────────────

    private PositionStats buildPositionStats(List<PositionEntity> closedPos) {
        PositionStats p = new PositionStats();
        p.closedCount = closedPos.size();
        p.totalPnl = closedPos.stream()
                .map(pos -> pos.getRealizedPnl() != null ? pos.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        p.winCount = (int) closedPos.stream()
                .filter(pos -> pos.getRealizedPnl() != null && pos.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
                .count();
        p.lossCount = p.closedCount - p.winCount;
        p.winRate   = p.closedCount > 0 ? pct(p.winCount, p.closedCount) : BigDecimal.ZERO;
        return p;
    }

    // ── 레짐 통계 ─────────────────────────────────────────────────────────────

    private RegimeStats buildRegimeStats(Instant from, Instant to) {
        RegimeStats r = new RegimeStats();
        List<RegimeChangeLogEntity> recentRegimes = regimeChangeLogRepo.findRecent(PageRequest.of(0, 20));
        r.currentRegime = recentRegimes.isEmpty() ? "UNKNOWN" : recentRegimes.get(0).getToRegime();
        r.transitions = new ArrayList<>(recentRegimes.stream()
                .filter(e -> e.getDetectedAt() != null
                        && !e.getDetectedAt().isBefore(from)
                        && !e.getDetectedAt().isAfter(to))
                .map(e -> AnalysisReport.RegimeTransition.builder()
                        .fromRegime(e.getFromRegime())
                        .toRegime(e.getToRegime())
                        .detectedAt(e.getDetectedAt())
                        .build())
                .toList());
        return r;
    }

    // ── 레짐별 신호 품질 ──────────────────────────────────────────────────────

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String[] HOUR_BUCKETS = {"00-04", "04-08", "08-12", "12-16", "16-20", "20-24"};

    /**
     * 레짐별 BUY/SELL 신호 품질을 집계한다.
     * UNKNOWN/TRANSITIONAL 레짐은 제외한다.
     */
    private Map<String, AnalysisReport.RegimeSignalQuality> buildRegimeSignalStats(List<StrategyLogEntity> logs) {
        Map<String, List<StrategyLogEntity>> byRegime = logs.stream()
                .filter(l -> l.getMarketRegime() != null
                        && !"UNKNOWN".equals(l.getMarketRegime())
                        && !"TRANSITIONAL".equals(l.getMarketRegime()))
                .filter(l -> "BUY".equals(l.getSignal()) || "SELL".equals(l.getSignal()))
                .collect(Collectors.groupingBy(StrategyLogEntity::getMarketRegime));

        Map<String, AnalysisReport.RegimeSignalQuality> result = new LinkedHashMap<>();
        byRegime.forEach((regime, regimeLogs) -> {
            List<StrategyLogEntity> buys  = regimeLogs.stream()
                    .filter(l -> "BUY".equals(l.getSignal())).toList();
            List<StrategyLogEntity> sells = regimeLogs.stream()
                    .filter(l -> "SELL".equals(l.getSignal())).toList();
            result.put(regime, AnalysisReport.RegimeSignalQuality.builder()
                    .regime(regime)
                    .signalCount(regimeLogs.size())
                    .buyWinRate4h(calcAccuracy(buys, true))
                    .sellWinRate4h(calcAccuracy(sells, true))
                    .avgReturn4h(calcAvgReturn(regimeLogs, true))
                    .expectedValue4h(calcExpectedValue(regimeLogs, true))
                    .build());
        });
        return result;
    }

    // ── 시간대별 신호 품질 ────────────────────────────────────────────────────

    /**
     * KST 4h 시간대별 BUY/SELL 신호 품질을 집계한다.
     * 버킷: 00-04, 04-08, 08-12, 12-16, 16-20, 20-24
     */
    private List<AnalysisReport.HourlySignalQuality> buildHourlySignalStats(List<StrategyLogEntity> logs) {
        Map<String, List<StrategyLogEntity>> byBucket = new LinkedHashMap<>();
        for (String b : HOUR_BUCKETS) byBucket.put(b, new ArrayList<>());

        logs.stream()
                .filter(l -> "BUY".equals(l.getSignal()) || "SELL".equals(l.getSignal()))
                .filter(l -> l.getCreatedAt() != null)
                .forEach(l -> {
                    ZonedDateTime kst = l.getCreatedAt().atZone(KST);
                    int bucketIdx = kst.getHour() / 4;
                    byBucket.get(HOUR_BUCKETS[bucketIdx]).add(l);
                });

        List<AnalysisReport.HourlySignalQuality> result = new ArrayList<>();
        byBucket.forEach((bucket, bucketLogs) ->
                result.add(AnalysisReport.HourlySignalQuality.builder()
                        .hourBucket(bucket)
                        .signalCount(bucketLogs.size())
                        .accuracy4h(calcAccuracy(bucketLogs, true))
                        .avgReturn4h(calcAvgReturn(bucketLogs, true))
                        .build()));
        return result;
    }

    // ── 전략 간 상관관계 분석 ─────────────────────────────────────────────────

    /**
     * 동일 코인·동일 4h KST 버킷에서 ≥2개 전략이 신호를 낸 경우를 기준으로
     * 컨센서스(전부 같은 방향) vs 분산(방향 혼재) 버킷별 4h 적중률을 비교한다.
     *
     * <p>버킷 키 = coinPair + "|" + (epochSecond / 14400) — 4h = 14,400초
     */
    private AnalysisReport.StrategyCorrelationStats buildCorrelationStats(List<StrategyLogEntity> logs) {
        // 버킷별 BUY/SELL 신호만 수집
        Map<String, List<StrategyLogEntity>> byBucket = new HashMap<>();
        for (StrategyLogEntity l : logs) {
            if (!"BUY".equals(l.getSignal()) && !"SELL".equals(l.getSignal())) continue;
            if (l.getCoinPair() == null || l.getCreatedAt() == null) continue;
            long bucket4h = l.getCreatedAt().getEpochSecond() / 14_400L;
            String key = l.getCoinPair() + "|" + bucket4h;
            byBucket.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
        }

        List<StrategyLogEntity> consensusSignals  = new ArrayList<>();
        List<StrategyLogEntity> divergentSignals  = new ArrayList<>();
        int totalBuckets = 0, consensusBuckets = 0, divergentBuckets = 0;

        for (List<StrategyLogEntity> bucket : byBucket.values()) {
            if (bucket.size() < 2) continue; // 단일 전략 버킷은 제외
            totalBuckets++;
            boolean hasBuy  = bucket.stream().anyMatch(l -> "BUY".equals(l.getSignal()));
            boolean hasSell = bucket.stream().anyMatch(l -> "SELL".equals(l.getSignal()));
            if (hasBuy && !hasSell || !hasBuy && hasSell) {
                // 모두 같은 방향 — 컨센서스
                consensusBuckets++;
                consensusSignals.addAll(bucket);
            } else {
                // BUY와 SELL 혼재 — 분산
                divergentBuckets++;
                divergentSignals.addAll(bucket);
            }
        }

        return AnalysisReport.StrategyCorrelationStats.builder()
                .totalBuckets(totalBuckets)
                .consensusBuckets(consensusBuckets)
                .divergentBuckets(divergentBuckets)
                .consensusAccuracy4h(calcAccuracy(consensusSignals, true))
                .divergentAccuracy4h(calcAccuracy(divergentSignals, true))
                .consensusAvgReturn4h(calcAvgReturn(consensusSignals, true))
                .divergentAvgReturn4h(calcAvgReturn(divergentSignals, true))
                .build();
    }

    // ── 계산 헬퍼 ─────────────────────────────────────────────────────────────

    private static final BigDecimal FEE_THRESHOLD  = TradingConstants.FEE_THRESHOLD;
    /** 고신뢰 신호 기준 — confidence_score ≥ 0.7 */
    private static final BigDecimal HIGH_CONF_THRESHOLD = BigDecimal.valueOf(0.7);

    /** count/total 비율을 소수점 1자리 퍼센트로 반환 */
    private static BigDecimal pct(long count, long total) {
        return BigDecimal.valueOf(count * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * BUY/SELL 신호만 대상으로 방향 적중률 계산.
     * 승리 기준: return > FEE_THRESHOLD (수수료 차감 후 실질 수익)
     */
    private BigDecimal calcAccuracy(List<StrategyLogEntity> logs, boolean is4h) {
        List<StrategyLogEntity> evaluated = logs.stream()
                .filter(l -> "BUY".equals(l.getSignal()) || "SELL".equals(l.getSignal()))
                .filter(l -> is4h ? l.getReturn4hPct() != null : l.getReturn24hPct() != null)
                .toList();
        if (evaluated.isEmpty()) return null;
        long correct = evaluated.stream()
                .filter(l -> {
                    BigDecimal ret = is4h ? l.getReturn4hPct() : l.getReturn24hPct();
                    return ret != null && ret.compareTo(FEE_THRESHOLD) > 0;
                }).count();
        return pct(correct, evaluated.size());
    }

    /**
     * BUY/SELL 신호 기대값(EV) 계산 — EV = win_rate × avg_win + loss_rate × avg_loss.
     * avgLoss는 음수이므로 덧셈으로 처리한다. 수수료 공제 후 실질 기준.
     */
    private BigDecimal calcExpectedValue(List<StrategyLogEntity> logs, boolean is4h) {
        List<StrategyLogEntity> evaluated = logs.stream()
                .filter(l -> "BUY".equals(l.getSignal()) || "SELL".equals(l.getSignal()))
                .filter(l -> is4h ? l.getReturn4hPct() != null : l.getReturn24hPct() != null)
                .toList();
        if (evaluated.isEmpty()) return null;

        List<BigDecimal> wins   = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();
        for (StrategyLogEntity l : evaluated) {
            BigDecimal ret = is4h ? l.getReturn4hPct() : l.getReturn24hPct();
            if (ret.compareTo(FEE_THRESHOLD) > 0) wins.add(ret);
            else losses.add(ret);
        }

        double total    = evaluated.size();
        double winRate  = wins.size()   / total;
        double lossRate = losses.size() / total;
        double avgWin   = wins.isEmpty()   ? 0.0
                : wins.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(wins.size()), 6, RoundingMode.HALF_UP).doubleValue();
        double avgLoss  = losses.isEmpty() ? 0.0
                : losses.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(losses.size()), 6, RoundingMode.HALF_UP).doubleValue();

        double ev = winRate * avgWin + lossRate * avgLoss;
        return BigDecimal.valueOf(ev).setScale(4, RoundingMode.HALF_UP);
    }

    /** BUY/SELL 신호만 대상으로 평균 수익률 계산 */
    private BigDecimal calcAvgReturn(List<StrategyLogEntity> logs, boolean is4h) {
        List<BigDecimal> returns = logs.stream()
                .filter(l -> "BUY".equals(l.getSignal()) || "SELL".equals(l.getSignal()))
                .map(l -> is4h ? l.getReturn4hPct() : l.getReturn24hPct())
                .filter(r -> r != null)
                .toList();
        if (returns.isEmpty()) return null;
        return returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 2, RoundingMode.HALF_UP);
    }

    // ── 실행 중 세션 현황 ─────────────────────────────────────────────────────

    /** 현재 RUNNING 세션 목록 조회 */
    private List<LiveTradingSessionEntity> buildRunningSessions() {
        try {
            return sessionRepo.findByStatus("RUNNING");
        } catch (Exception e) {
            log.debug("RUNNING 세션 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** RUNNING 세션을 ActiveSessionInfo 목록으로 변환 */
    private List<AnalysisReport.ActiveSessionInfo> buildActiveSessions(List<LiveTradingSessionEntity> sessions) {
        return sessions.stream()
                .map(s -> {
                    BigDecimal returnPct = null;
                    if (s.getInitialCapital() != null && s.getTotalAssetKrw() != null
                            && s.getInitialCapital().compareTo(BigDecimal.ZERO) > 0) {
                        returnPct = s.getTotalAssetKrw().subtract(s.getInitialCapital())
                                .divide(s.getInitialCapital(), 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP);
                    }
                    return AnalysisReport.ActiveSessionInfo.builder()
                            .sessionId(s.getId())
                            .strategyType(s.getStrategyType())
                            .coinPair(s.getCoinPair())
                            .timeframe(s.getTimeframe())
                            .returnPct(returnPct)
                            .startedAt(s.getStartedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 실행 중 코인(+ BTC/ETH 기본 포함)의 12h 가격 변화율(%)을 Upbit에서 조회한다.
     * 조회 실패 시 해당 코인은 맵에 포함되지 않는다.
     */
    private Map<String, BigDecimal> buildCoinPriceChanges(List<LiveTradingSessionEntity> sessions, Instant from) {
        Map<String, BigDecimal> result = new HashMap<>();
        // 실행 중 코인 수집 (중복 제거)
        sessions.stream()
                .map(LiveTradingSessionEntity::getCoinPair)
                .distinct()
                .forEach(coin -> {
                    BigDecimal change = fetchPriceChange(coin, from);
                    if (change != null) result.put(coin, change);
                });
        return result;
    }

    /** 기간 내 청산 포지션을 코인별로 집계한다 */
    private Map<String, AnalysisReport.CoinPositionStat> buildCoinPositionStats(List<PositionEntity> closedPos) {
        return closedPos.stream()
                .filter(p -> p.getCoinPair() != null)
                .collect(Collectors.groupingBy(
                        PositionEntity::getCoinPair,
                        Collectors.collectingAndThen(Collectors.toList(), group -> {
                            int total = group.size();
                            int wins  = (int) group.stream()
                                    .filter(p -> p.getRealizedPnl() != null
                                            && p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
                                    .count();
                            int losses = total - wins;
                            BigDecimal totalPnl = group.stream()
                                    .map(p -> p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                            BigDecimal winRate = total > 0 ? pct(wins, total) : BigDecimal.ZERO;
                            return AnalysisReport.CoinPositionStat.builder()
                                    .closedCount(total)
                                    .winCount(wins)
                                    .lossCount(losses)
                                    .winRate(winRate)
                                    .totalPnl(totalPnl)
                                    .build();
                        })));
    }

    // ── 시장 가격 맥락 ────────────────────────────────────────────────────────

    private BigDecimal fetchPriceChange(String market, Instant from) {
        try {
            List<UpbitCandleResponse> recent = upbitRestClient.getCandles(market, "minutes", 60, Instant.now(), 1);
            List<UpbitCandleResponse> past   = upbitRestClient.getCandles(market, "minutes", 60, from, 1);
            if (recent.isEmpty() || past.isEmpty()) return null;
            BigDecimal current = recent.get(0).getTradePrice();
            BigDecimal base    = past.get(0).getTradePrice();
            if (base == null || base.compareTo(BigDecimal.ZERO) == 0) return null;
            return current.subtract(base)
                    .divide(base, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.debug("가격 변화 조회 실패 ({}): {}", market, e.getMessage());
            return null;
        }
    }

    // ── 포지션 현황 ───────────────────────────────────────────────────────────

    /** 현재 오픈 포지션 수 */
    private int buildOpenPositionCount() {
        try {
            return (int) positionRepo.countByStatus("OPEN");
        } catch (Exception e) {
            log.debug("오픈 포지션 수 조회 실패: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 최근 청산 포지션 기준 연속 손실 횟수를 계산한다.
     * 첫 번째 수익 포지션이 나오면 카운트를 멈춘다.
     */
    private int buildConsecutiveLosses() {
        try {
            List<PositionEntity> recent = positionRepo.findRecentClosed(PageRequest.of(0, 20));
            int streak = 0;
            for (PositionEntity p : recent) {
                if (p.getRealizedPnl() != null && p.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0) {
                    streak++;
                } else {
                    break;
                }
            }
            return streak;
        } catch (Exception e) {
            log.debug("연속 손실 계산 실패: {}", e.getMessage());
            return 0;
        }
    }

    // ── 내부 집계 컨테이너 ────────────────────────────────────────────────────

    private static class SignalStats {
        int total, buy, sell, hold, executed, blocked;
        BigDecimal accuracy4h, accuracy24h, avgReturn4h, avgReturn24h;
        Map<String, Integer> blockReasons;
        Map<String, AnalysisReport.StrategySignalStat> strategyStats;
    }

    private static class PositionStats {
        int closedCount, winCount, lossCount;
        BigDecimal totalPnl, winRate;
    }

    private static class RegimeStats {
        String currentRegime;
        List<AnalysisReport.RegimeTransition> transitions;
    }

}
