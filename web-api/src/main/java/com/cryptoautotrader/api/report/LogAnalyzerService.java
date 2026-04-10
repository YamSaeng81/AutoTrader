package com.cryptoautotrader.api.report;

import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.RegimeChangeLogEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.RegimeChangeLogRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
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

    /**
     * 지정 구간의 분석 보고서를 생성한다.
     * DB에서 기간 필터링하여 메모리 부하를 최소화한다.
     */
    public AnalysisReport analyze(Instant from, Instant to) {
        log.info("[LogAnalyzer] 분석 시작 — {} ~ {}", from, to);

        List<StrategyLogEntity> periodLogs = strategyLogRepo.findByPeriod(from, to);
        List<PositionEntity>    closedPos  = positionRepo.findClosedByPeriod(from, to);

        SignalStats    signals  = buildSignalStats(periodLogs);
        PositionStats  positions = buildPositionStats(closedPos);
        RegimeStats    regime   = buildRegimeStats(from, to);

        log.info("[LogAnalyzer] 집계 완료 — 신호 {}건 (실행 {}/차단 {}), 포지션 {}건, 레짐 {}",
                signals.total, signals.executed, signals.blocked, positions.closedCount, regime.currentRegime);

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

    /** count/total 비율을 소수점 1자리 퍼센트로 반환 */
    private static BigDecimal pct(long count, long total) {
        return BigDecimal.valueOf(count * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
    }

    private BigDecimal calcAccuracy(List<StrategyLogEntity> logs, boolean is4h) {
        List<StrategyLogEntity> evaluated = logs.stream()
                .filter(l -> is4h ? l.getReturn4hPct() != null : l.getReturn24hPct() != null)
                .toList();
        if (evaluated.isEmpty()) return null;
        long correct = evaluated.stream()
                .filter(l -> {
                    BigDecimal ret = is4h ? l.getReturn4hPct() : l.getReturn24hPct();
                    return ret != null && ret.compareTo(BigDecimal.ZERO) > 0;
                }).count();
        return pct(correct, evaluated.size());
    }

    private BigDecimal calcAvgReturn(List<StrategyLogEntity> logs, boolean is4h) {
        List<BigDecimal> returns = logs.stream()
                .map(l -> is4h ? l.getReturn4hPct() : l.getReturn24hPct())
                .filter(r -> r != null)
                .toList();
        if (returns.isEmpty()) return null;
        return returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 2, RoundingMode.HALF_UP);
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
