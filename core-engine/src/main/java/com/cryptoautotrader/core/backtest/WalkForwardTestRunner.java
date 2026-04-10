package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.MetricsCalculator;
import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.strategy.Candle;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Walk Forward Test: Overfitting 방지를 위한 In-Sample / Out-of-Sample 분할 테스트
 */
public class WalkForwardTestRunner {

    private final BacktestEngine backtestEngine = new BacktestEngine();

    @Getter
    @Builder
    public static class WalkForwardResult {
        private final List<WindowResult> windows;
        private final BigDecimal overfittingScore;
        private final String verdict; // ACCEPTABLE, CAUTION, OVERFITTING
    }

    @Getter
    @Builder
    public static class WindowResult {
        private final int windowIndex;
        private final PerformanceReport inSampleMetrics;
        private final PerformanceReport outSampleMetrics;
        private final Instant inSampleStart;
        private final Instant inSampleEnd;
        private final Instant outSampleStart;
        private final Instant outSampleEnd;
    }

    public WalkForwardResult run(BacktestConfig config, List<Candle> candles,
                                  double inSampleRatio, int windowCount) {
        int totalSize = candles.size();
        int windowSize = totalSize / windowCount;
        int inSampleSize = (int) (windowSize * inSampleRatio);
        int outSampleSize = windowSize - inSampleSize;
        int step = (totalSize - windowSize) / Math.max(windowCount - 1, 1);

        List<WindowResult> windows = new ArrayList<>();
        BigDecimal totalDropRate = BigDecimal.ZERO;

        for (int w = 0; w < windowCount; w++) {
            int start = w * step;
            int inSampleEnd = Math.min(start + inSampleSize, totalSize);
            int outSampleEnd = Math.min(start + windowSize, totalSize);

            if (inSampleEnd >= totalSize || outSampleEnd > totalSize) break;

            List<Candle> inSampleCandles = candles.subList(start, inSampleEnd);
            List<Candle> outSampleCandles = candles.subList(inSampleEnd, outSampleEnd);

            BacktestResult inResult = backtestEngine.run(config, inSampleCandles);
            BacktestResult outResult = backtestEngine.run(config, outSampleCandles);

            PerformanceReport inMetrics = MetricsCalculator.calculate(
                    inResult.getTrades(), config.getInitialCapital(), "IN_SAMPLE");
            PerformanceReport outMetrics = MetricsCalculator.calculate(
                    outResult.getTrades(), config.getInitialCapital(), "OUT_SAMPLE");

            windows.add(WindowResult.builder()
                    .windowIndex(w)
                    .inSampleMetrics(inMetrics)
                    .outSampleMetrics(outMetrics)
                    .inSampleStart(inSampleCandles.get(0).getTime())
                    .inSampleEnd(inSampleCandles.get(inSampleCandles.size() - 1).getTime())
                    .outSampleStart(outSampleCandles.get(0).getTime())
                    .outSampleEnd(outSampleCandles.get(outSampleCandles.size() - 1).getTime())
                    .build());

            // In-Sample 대비 Out-of-Sample 수익률 하락률
            if (inMetrics.getTotalReturnPct().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal dropRate = inMetrics.getTotalReturnPct().subtract(outMetrics.getTotalReturnPct())
                        .divide(inMetrics.getTotalReturnPct(), 4, RoundingMode.HALF_UP);
                totalDropRate = totalDropRate.add(dropRate.max(BigDecimal.ZERO));
            }
        }

        BigDecimal avgDropRate = windows.isEmpty() ? BigDecimal.ZERO
                : totalDropRate.divide(BigDecimal.valueOf(windows.size()), 4, RoundingMode.HALF_UP);

        String verdict;
        if (avgDropRate.compareTo(BigDecimal.valueOf(0.5)) > 0) {
            verdict = "OVERFITTING";
        } else if (avgDropRate.compareTo(BigDecimal.valueOf(0.3)) > 0) {
            verdict = "CAUTION";
        } else {
            verdict = "ACCEPTABLE";
        }

        return WalkForwardResult.builder()
                .windows(windows)
                .overfittingScore(avgDropRate)
                .verdict(verdict)
                .build();
    }
}
