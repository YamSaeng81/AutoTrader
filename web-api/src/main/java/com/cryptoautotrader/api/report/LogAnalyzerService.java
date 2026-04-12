package com.cryptoautotrader.api.report;

import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.RegimeChangeLogEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
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
import java.util.ArrayList;
import java.util.HashMap;
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
    private final UpbitRestClient upbitRestClient;

    /**
     * 지정 구간의 분석 보고서를 생성한다.
     * DB에서 기간 필터링하여 메모리 부하를 최소화한다.
     */
    public AnalysisReport analyze(Instant from, Instant to) {
        log.info("[LogAnalyzer] 분석 시작 — {} ~ {}", from, to);

        List<StrategyLogEntity> periodLogs = strategyLogRepo.findByPeriod(from, to);
        List<PositionEntity>    closedPos  = positionRepo.findClosedByPeriod(from, to);

        SignalStats    signals   = buildSignalStats(periodLogs);
        PositionStats  positions = buildPositionStats(closedPos);
        RegimeStats    regime    = buildRegimeStats(from, to);
        PriceContext   price     = buildPriceContext(from);
        int            openPos   = buildOpenPositionCount();
        int            streak    = buildConsecutiveLosses();

        log.info("[LogAnalyzer] 집계 완료 — 신호 {}건 (실행 {}/차단 {}), 포지션 {}건, 레짐 {}, BTC {}%",
                signals.total, signals.executed, signals.blocked, positions.closedCount,
                regime.currentRegime, price.btcChange);

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
                .btcPriceChange12h(price.btcChange)
                .ethPriceChange12h(price.ethChange)
                // 포지션 현황
                .openPositionCount(openPos)
                .consecutiveLosses(streak)
                .build();
    }

    // ── 신호 통계 ─────────────────────────────────────────────────────────────

    private SignalStats buildSignalStats(List<StrategyLogEntity> logs) {
        SignalStats s = new SignalStats();
        s.total    = logs.size();
        s.buy      = (int) logs.stream().filter(l -> "BUY".equals(l.getSignal())).count();
        s.sell     = (int) logs.stream().filter(l -> "SELL".equals(l.getSignal())).count();
        s.hold     = (int) logs.stream().filter(l -> "HOLD".equals(l.getSignal())).count();
        s.executed = (int) logs.stream().filter(StrategyLogEntity::isWasExecuted).count();
        s.blocked  = (int) logs.stream()
                .filter(l -> !l.isWasExecuted() && l.getBlockedReason() != null).count();

        s.blockReasons = new HashMap<>();
        logs.stream()
                .filter(l -> l.getBlockedReason() != null && !l.getBlockedReason().isBlank())
                .forEach(l -> s.blockReasons.merge(l.getBlockedReason().split(":")[0].trim(), 1, Integer::sum));

        s.strategyStats = logs.stream()
                .collect(Collectors.groupingBy(
                        l -> l.getStrategyName() != null ? l.getStrategyName() : "UNKNOWN",
                        Collectors.collectingAndThen(Collectors.toList(), g ->
                                AnalysisReport.StrategySignalStat.builder()
                                        .buy((int) g.stream().filter(l -> "BUY".equals(l.getSignal())).count())
                                        .sell((int) g.stream().filter(l -> "SELL".equals(l.getSignal())).count())
                                        .hold((int) g.stream().filter(l -> "HOLD".equals(l.getSignal())).count())
                                        .executed((int) g.stream().filter(StrategyLogEntity::isWasExecuted).count())
                                        .accuracy4h(calcAccuracy(g, true))
                                        .accuracy24h(calcAccuracy(g, false))
                                        .avgReturn4h(calcAvgReturn(g, true))
                                        .avgReturn24h(calcAvgReturn(g, false))
                                        .build())));

        s.accuracy4h  = calcAccuracy(logs, true);
        s.accuracy24h = calcAccuracy(logs, false);
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

    // ── 계산 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * 수수료 임계값: 업비트 왕복 수수료(매수 0.05% + 매도 0.05%) 기준.
     * 이 값을 초과해야 실질 승리로 판정한다.
     */
    private static final BigDecimal FEE_THRESHOLD = new BigDecimal("0.10");

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

    // ── 시장 가격 맥락 ────────────────────────────────────────────────────────

    /**
     * BTC/ETH의 12h 가격 변화율(%)을 Upbit에서 조회한다.
     * 조회 실패 시 null을 담아 반환하며 보고서 생성에 영향을 주지 않는다.
     */
    private PriceContext buildPriceContext(Instant from) {
        PriceContext ctx = new PriceContext();
        ctx.btcChange = fetchPriceChange("KRW-BTC", from);
        ctx.ethChange = fetchPriceChange("KRW-ETH", from);
        return ctx;
    }

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

    private static class PriceContext {
        BigDecimal btcChange;
        BigDecimal ethChange;
    }
}
