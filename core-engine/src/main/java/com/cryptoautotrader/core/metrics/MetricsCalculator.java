package com.cryptoautotrader.core.metrics;

import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 8가지 성과 지표 계산
 */
public final class MetricsCalculator {

    private static final int SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ANNUALIZATION_FACTOR = BigDecimal.valueOf(Math.sqrt(365));

    private MetricsCalculator() {}

    public static PerformanceReport calculate(List<TradeRecord> trades, BigDecimal initialCapital) {
        return calculate(trades, initialCapital, "FULL");
    }

    public static PerformanceReport calculate(List<TradeRecord> trades, BigDecimal initialCapital, String segment) {
        // 매도 거래만 필터 (매수→매도 쌍으로 PnL 계산)
        List<TradeRecord> sellTrades = trades.stream()
                .filter(t -> t.getSide() == OrderSide.SELL)
                .toList();

        int totalTrades = sellTrades.size();
        if (totalTrades == 0) {
            return emptyReport(segment);
        }

        // 승/패 분류
        List<BigDecimal> profits = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();
        for (TradeRecord t : sellTrades) {
            if (t.getPnl().compareTo(BigDecimal.ZERO) > 0) {
                profits.add(t.getPnl());
            } else {
                losses.add(t.getPnl());
            }
        }

        int winningTrades = profits.size();
        int losingTrades = losses.size();

        // 총 수익률
        BigDecimal totalPnl = sellTrades.stream()
                .map(TradeRecord::getPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalReturnPct = totalPnl.divide(initialCapital, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED);

        // 승률
        BigDecimal winRatePct = BigDecimal.valueOf(winningTrades)
                .divide(BigDecimal.valueOf(totalTrades), SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED);

        // MDD
        BigDecimal mddPct = calculateMDD(trades, initialCapital);

        // 평균 수익/손실
        BigDecimal avgProfitPct = profits.isEmpty() ? BigDecimal.ZERO
                : profits.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(profits.size()), SCALE, RoundingMode.HALF_UP)
                .divide(initialCapital, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED);
        BigDecimal avgLossPct = losses.isEmpty() ? BigDecimal.ZERO
                : losses.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(losses.size()), SCALE, RoundingMode.HALF_UP)
                .divide(initialCapital, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED);

        // 승패비
        BigDecimal winLossRatio = avgLossPct.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : avgProfitPct.divide(avgLossPct.abs(), SCALE, RoundingMode.HALF_UP);

        // 일별 수익률 계산 (Sharpe, Sortino용)
        List<BigDecimal> dailyReturns = calculateDailyReturns(sellTrades, initialCapital);

        BigDecimal sharpeRatio = calculateSharpe(dailyReturns);
        BigDecimal sortinoRatio = calculateSortino(dailyReturns);
        BigDecimal calmarRatio = mddPct.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : totalReturnPct.divide(mddPct.abs(), SCALE, RoundingMode.HALF_UP);
        BigDecimal recoveryFactor = mddPct.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : totalReturnPct.divide(mddPct.abs(), SCALE, RoundingMode.HALF_UP);

        int maxConsecutiveLoss = calculateMaxConsecutiveLoss(sellTrades);
        Map<String, BigDecimal> monthlyReturns = calculateMonthlyReturns(sellTrades, initialCapital);

        return PerformanceReport.builder()
                .totalReturnPct(totalReturnPct)
                .winRatePct(winRatePct)
                .mddPct(mddPct)
                .sharpeRatio(sharpeRatio)
                .sortinoRatio(sortinoRatio)
                .calmarRatio(calmarRatio)
                .winLossRatio(winLossRatio)
                .recoveryFactor(recoveryFactor)
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .avgProfitPct(avgProfitPct)
                .avgLossPct(avgLossPct)
                .maxConsecutiveLoss(maxConsecutiveLoss)
                .monthlyReturns(monthlyReturns)
                .segment(segment)
                .build();
    }

    private static BigDecimal calculateMDD(List<TradeRecord> trades, BigDecimal initialCapital) {
        BigDecimal peak = initialCapital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal equity = initialCapital;

        for (TradeRecord trade : trades) {
            if (trade.getSide() == OrderSide.SELL) {
                equity = equity.add(trade.getPnl());
                peak = peak.max(equity);
                BigDecimal drawdown = equity.subtract(peak)
                        .divide(peak, SCALE, RoundingMode.HALF_UP)
                        .multiply(HUNDRED);
                maxDrawdown = maxDrawdown.min(drawdown);
            }
        }
        return maxDrawdown;
    }

    private static List<BigDecimal> calculateDailyReturns(List<TradeRecord> sellTrades, BigDecimal initialCapital) {
        List<BigDecimal> returns = new ArrayList<>();
        for (TradeRecord t : sellTrades) {
            returns.add(t.getPnl().divide(initialCapital, SCALE, RoundingMode.HALF_UP));
        }
        return returns;
    }

    private static BigDecimal calculateSharpe(List<BigDecimal> returns) {
        if (returns.size() < 2) return BigDecimal.ZERO;
        BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), SCALE, RoundingMode.HALF_UP);
        BigDecimal variance = returns.stream()
                .map(r -> r.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), SCALE, RoundingMode.HALF_UP);
        double stdDev = Math.sqrt(variance.doubleValue());
        if (stdDev == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(mean.doubleValue() / stdDev)
                .multiply(ANNUALIZATION_FACTOR)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateSortino(List<BigDecimal> returns) {
        if (returns.size() < 2) return BigDecimal.ZERO;
        BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), SCALE, RoundingMode.HALF_UP);
        BigDecimal downsideVariance = returns.stream()
                .filter(r -> r.compareTo(BigDecimal.ZERO) < 0)
                .map(r -> r.pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), SCALE, RoundingMode.HALF_UP);
        double downsideDev = Math.sqrt(downsideVariance.doubleValue());
        if (downsideDev == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(mean.doubleValue() / downsideDev)
                .multiply(ANNUALIZATION_FACTOR)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static int calculateMaxConsecutiveLoss(List<TradeRecord> sellTrades) {
        int max = 0, current = 0;
        for (TradeRecord t : sellTrades) {
            if (t.getPnl().compareTo(BigDecimal.ZERO) < 0) {
                current++;
                max = Math.max(max, current);
            } else {
                current = 0;
            }
        }
        return max;
    }

    private static Map<String, BigDecimal> calculateMonthlyReturns(List<TradeRecord> sellTrades, BigDecimal initialCapital) {
        Map<String, BigDecimal> monthly = new TreeMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneId.of("Asia/Seoul"));

        for (TradeRecord t : sellTrades) {
            String month = fmt.format(t.getExecutedAt());
            monthly.merge(month, t.getPnl(), BigDecimal::add);
        }

        // PnL → 수익률(%)로 변환
        monthly.replaceAll((k, v) -> v.divide(initialCapital, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED));
        return monthly;
    }

    private static PerformanceReport emptyReport(String segment) {
        return PerformanceReport.builder()
                .totalReturnPct(BigDecimal.ZERO).winRatePct(BigDecimal.ZERO)
                .mddPct(BigDecimal.ZERO).sharpeRatio(BigDecimal.ZERO)
                .sortinoRatio(BigDecimal.ZERO).calmarRatio(BigDecimal.ZERO)
                .winLossRatio(BigDecimal.ZERO).recoveryFactor(BigDecimal.ZERO)
                .totalTrades(0).winningTrades(0).losingTrades(0)
                .avgProfitPct(BigDecimal.ZERO).avgLossPct(BigDecimal.ZERO)
                .maxConsecutiveLoss(0).monthlyReturns(Map.of()).segment(segment)
                .build();
    }
}
