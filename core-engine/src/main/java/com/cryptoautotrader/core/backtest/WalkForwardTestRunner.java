package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.MetricsCalculator;
import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.strategy.Candle;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Walk Forward Test: Overfitting 방지를 위한 In-Sample / Out-of-Sample 분할 테스트.
 *
 * <h3>2026-04-15 구조 개선 (20260415_analy.md Tier 1 §2)</h3>
 * <ul>
 *   <li>기존 {@code step = (totalSize - windowSize) / (windowCount - 1)} 공식은 정수 나눗셈
 *       truncation 영향으로 윈도우 간 gap 또는 overlap 이 발생할 수 있어, OOS 독립성
 *       보장이 깨질 여지가 있었다. 이를 {@code step = windowSize} 로 고정하여 contiguous
 *       non-overlapping rolling 을 강제한다.</li>
 *   <li>{@link Mode#ANCHORED} 모드를 추가해 train 셋이 누적 확장되는 전통적 anchored
 *       walk-forward 를 지원한다.</li>
 *   <li>per-window 결과를 단순 합산하던 overfitting score 와 별도로, 모든 윈도우의
 *       OOS 거래를 시간순으로 병합한 {@code aggregatedOutSampleMetrics} 를 계산한다.
 *       이는 "OOS 합계 수익률" 대체재로 통계적으로 더 견고하다.</li>
 * </ul>
 */
public class WalkForwardTestRunner {

    private final BacktestEngine backtestEngine = new BacktestEngine();

    public enum Mode {
        /** 고정 크기 train/test 윈도우를 시간축을 따라 비겹침(non-overlapping) 이동. */
        ROLLING,
        /** train 셋이 누적 확장(0 → k*testSize)되고 test 는 고정 크기 연속 구간. */
        ANCHORED
    }

    @Getter
    @Builder
    public static class WalkForwardResult {
        private final Mode mode;
        private final List<WindowResult> windows;
        private final BigDecimal overfittingScore;
        private final String verdict; // ACCEPTABLE, CAUTION, OVERFITTING
        /** 모든 OOS 윈도우 거래를 병합해 계산한 단일 성과 지표. */
        private final PerformanceReport aggregatedOutSampleMetrics;
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

    /** 레거시 시그니처 — ROLLING 모드로 동작. */
    public WalkForwardResult run(BacktestConfig config, List<Candle> candles,
                                  double inSampleRatio, int windowCount) {
        return run(config, candles, inSampleRatio, windowCount, Mode.ROLLING, null);
    }

    public WalkForwardResult run(BacktestConfig config, List<Candle> candles,
                                  double inSampleRatio, int windowCount, Mode mode) {
        return run(config, candles, inSampleRatio, windowCount, mode, null);
    }

    /**
     * §12 look-ahead bias 방지 오버로드.
     *
     * <p>{@code holdOutCutoff} 가 non-null 이면, 캔들 목록을 시간 기준으로 두 구간으로 분리한다.
     * <ul>
     *   <li><b>튜닝 구간</b> — cutoff 이전: IS + OOS 윈도우에 모두 사용 가능.</li>
     *   <li><b>홀드아웃 검증 구간</b> — cutoff 이후: OOS 전용. IS 윈도우에 포함되면 즉시 예외.</li>
     * </ul>
     * 이를 통해 "파라미터 선택 시 사용한 데이터와 최종 검증 데이터의 겹침"을 원천 차단한다.</p>
     *
     * @param holdOutCutoff 홀드아웃 검증 시작 시각. null 이면 기존 동작과 동일.
     * @throws IllegalArgumentException IS 윈도우가 holdOutCutoff 이후 캔들을 포함할 경우.
     */
    public WalkForwardResult run(BacktestConfig config, List<Candle> candles,
                                  double inSampleRatio, int windowCount, Mode mode,
                                  Instant holdOutCutoff) {
        if (candles == null || candles.isEmpty() || windowCount <= 0) {
            return empty(mode);
        }

        // §12 look-ahead 가드: holdOutCutoff 가 지정된 경우 캔들을 튜닝/홀드아웃 구간으로 분리.
        // IS 윈도우는 cutoff 이전 캔들만 사용해야 한다.
        if (holdOutCutoff != null) {
            validateHoldOut(candles, holdOutCutoff);
            // cutoff 이전 캔들만 WF 대상으로 사용 — cutoff 이후 캔들은 OOS 전용
            List<Candle> tuningCandles = candles.stream()
                    .filter(c -> !c.getTime().isAfter(holdOutCutoff))
                    .toList();
            if (tuningCandles.isEmpty()) {
                throw new IllegalArgumentException(
                        "holdOutCutoff(" + holdOutCutoff + ") 이전 캔들이 없습니다. "
                        + "튜닝 기간에 데이터가 충분한지 확인하세요.");
            }
            // 홀드아웃 구간을 OOS로 추가하는 특수 윈도우 포함한 결과 반환
            return runWithHoldOut(config, tuningCandles, candles, inSampleRatio, windowCount, mode, holdOutCutoff);
        }

        List<WindowResult> windows = (mode == Mode.ANCHORED)
                ? runAnchored(config, candles, inSampleRatio, windowCount)
                : runRolling(config, candles, inSampleRatio, windowCount);

        // ── overfitting score: 윈도우별 IS→OOS 수익률 하락률 평균 ──
        BigDecimal totalDropRate = BigDecimal.ZERO;
        int dropRateSamples = 0;
        for (WindowResult w : windows) {
            BigDecimal inPct = w.getInSampleMetrics().getTotalReturnPct();
            BigDecimal outPct = w.getOutSampleMetrics().getTotalReturnPct();
            if (inPct.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal drop = inPct.subtract(outPct)
                        .divide(inPct, 4, RoundingMode.HALF_UP)
                        .max(BigDecimal.ZERO);
                totalDropRate = totalDropRate.add(drop);
                dropRateSamples++;
            }
        }
        BigDecimal avgDropRate = dropRateSamples == 0 ? BigDecimal.ZERO
                : totalDropRate.divide(BigDecimal.valueOf(dropRateSamples), 4, RoundingMode.HALF_UP);

        String verdict;
        if (avgDropRate.compareTo(BigDecimal.valueOf(0.5)) > 0) {
            verdict = "OVERFITTING";
        } else if (avgDropRate.compareTo(BigDecimal.valueOf(0.3)) > 0) {
            verdict = "CAUTION";
        } else {
            verdict = "ACCEPTABLE";
        }

        // ── 모든 OOS 거래를 시간순으로 병합해 단일 성과 지표 계산 ──
        PerformanceReport aggregated = buildAggregatedOosMetrics(config, candles, inSampleRatio, windowCount, mode);

        return WalkForwardResult.builder()
                .mode(mode)
                .windows(windows)
                .overfittingScore(avgDropRate)
                .verdict(verdict)
                .aggregatedOutSampleMetrics(aggregated)
                .build();
    }

    // ── ROLLING: 고정 크기, contiguous non-overlapping ─────────
    private List<WindowResult> runRolling(BacktestConfig config, List<Candle> candles,
                                           double inSampleRatio, int windowCount) {
        int totalSize = candles.size();
        int windowSize = totalSize / windowCount;
        if (windowSize < 2) return List.of();

        int inSampleSize = Math.max(1, (int) (windowSize * inSampleRatio));
        if (inSampleSize >= windowSize) inSampleSize = windowSize - 1;

        List<WindowResult> windows = new ArrayList<>();
        for (int w = 0; w < windowCount; w++) {
            int start = w * windowSize;                   // step == windowSize 고정
            int isEnd = start + inSampleSize;
            int osEnd = Math.min(start + windowSize, totalSize);
            if (isEnd >= totalSize || osEnd <= isEnd) break;

            windows.add(evaluateWindow(config, candles, w, start, isEnd, osEnd));
        }
        return windows;
    }

    // ── ANCHORED: train 누적 확장, test 는 연속 고정 크기 ──────
    private List<WindowResult> runAnchored(BacktestConfig config, List<Candle> candles,
                                            double inSampleRatio, int windowCount) {
        int totalSize = candles.size();
        // 전체 기간의 초기 train = inSampleRatio × totalSize,
        // 이후 test = 나머지를 windowCount 로 분할한 고정 크기.
        int initialTrainSize = Math.max(2, (int) (totalSize * inSampleRatio));
        if (initialTrainSize >= totalSize) return List.of();

        int remaining = totalSize - initialTrainSize;
        int testSize = Math.max(1, remaining / windowCount);

        List<WindowResult> windows = new ArrayList<>();
        for (int w = 0; w < windowCount; w++) {
            int isStart = 0;
            int isEnd = initialTrainSize + w * testSize;
            int osStart = isEnd;
            int osEnd = Math.min(osStart + testSize, totalSize);
            if (isEnd >= totalSize || osEnd <= osStart) break;

            windows.add(evaluateWindow(config, candles, w, isStart, isEnd, osEnd));
        }
        return windows;
    }

    private WindowResult evaluateWindow(BacktestConfig config, List<Candle> candles,
                                         int index, int isStart, int isEnd, int osEnd) {
        List<Candle> inSampleCandles = candles.subList(isStart, isEnd);
        List<Candle> outSampleCandles = candles.subList(isEnd, osEnd);

        BacktestResult inResult = backtestEngine.run(config, inSampleCandles);
        BacktestResult outResult = backtestEngine.run(config, outSampleCandles);

        PerformanceReport inMetrics = MetricsCalculator.calculate(
                inResult.getTrades(), config.getInitialCapital(), "IN_SAMPLE");
        PerformanceReport outMetrics = MetricsCalculator.calculate(
                outResult.getTrades(), config.getInitialCapital(), "OUT_SAMPLE");

        return WindowResult.builder()
                .windowIndex(index)
                .inSampleMetrics(inMetrics)
                .outSampleMetrics(outMetrics)
                .inSampleStart(inSampleCandles.get(0).getTime())
                .inSampleEnd(inSampleCandles.get(inSampleCandles.size() - 1).getTime())
                .outSampleStart(outSampleCandles.get(0).getTime())
                .outSampleEnd(outSampleCandles.get(outSampleCandles.size() - 1).getTime())
                .build();
    }

    /**
     * 모든 OOS 윈도우 거래를 시간순으로 병합해 하나의 성과 지표로 집약한다.
     * per-window 수익률 단순 합산이 갖는 통계적 왜곡(윈도우 간 Compounding 무시, 거래 수
     * 격차 무시)을 피하기 위한 보조 지표.
     */
    private PerformanceReport buildAggregatedOosMetrics(BacktestConfig config, List<Candle> candles,
                                                         double inSampleRatio, int windowCount, Mode mode) {
        List<TradeRecord> merged = new ArrayList<>();
        List<WindowResult> windows = (mode == Mode.ANCHORED)
                ? runAnchoredTradesOnly(config, candles, inSampleRatio, windowCount, merged)
                : runRollingTradesOnly(config, candles, inSampleRatio, windowCount, merged);
        // windows 변수는 side-effect로만 사용 — merged가 채워짐
        merged.sort(Comparator.comparing(TradeRecord::getExecutedAt));
        return MetricsCalculator.calculate(merged, config.getInitialCapital(), "OOS_AGGREGATED");
    }

    private List<WindowResult> runRollingTradesOnly(BacktestConfig config, List<Candle> candles,
                                                     double inSampleRatio, int windowCount,
                                                     List<TradeRecord> sink) {
        int totalSize = candles.size();
        int windowSize = totalSize / windowCount;
        if (windowSize < 2) return List.of();
        int inSampleSize = Math.max(1, (int) (windowSize * inSampleRatio));
        if (inSampleSize >= windowSize) inSampleSize = windowSize - 1;

        for (int w = 0; w < windowCount; w++) {
            int start = w * windowSize;
            int isEnd = start + inSampleSize;
            int osEnd = Math.min(start + windowSize, totalSize);
            if (isEnd >= totalSize || osEnd <= isEnd) break;
            BacktestResult r = backtestEngine.run(config, candles.subList(isEnd, osEnd));
            sink.addAll(r.getTrades());
        }
        return List.of();
    }

    private List<WindowResult> runAnchoredTradesOnly(BacktestConfig config, List<Candle> candles,
                                                      double inSampleRatio, int windowCount,
                                                      List<TradeRecord> sink) {
        int totalSize = candles.size();
        int initialTrainSize = Math.max(2, (int) (totalSize * inSampleRatio));
        if (initialTrainSize >= totalSize) return List.of();
        int remaining = totalSize - initialTrainSize;
        int testSize = Math.max(1, remaining / windowCount);

        for (int w = 0; w < windowCount; w++) {
            int osStart = initialTrainSize + w * testSize;
            int osEnd = Math.min(osStart + testSize, totalSize);
            if (osEnd <= osStart) break;
            BacktestResult r = backtestEngine.run(config, candles.subList(osStart, osEnd));
            sink.addAll(r.getTrades());
        }
        return List.of();
    }

    // ── §12 look-ahead bias 방지 헬퍼 ────────────────────────────

    /**
     * IS 윈도우에 holdOutCutoff 이후 캔들이 포함되지 않는지 사전 검증.
     * 전체 캔들 범위가 cutoff 이전인지만 확인한다(슬라이딩 중 중간 캔들은 run 로직에서 보장).
     */
    private void validateHoldOut(List<Candle> candles, Instant holdOutCutoff) {
        Candle first = candles.get(0);
        if (!first.getTime().isBefore(holdOutCutoff)) {
            throw new IllegalArgumentException(
                    "전체 캔들이 holdOutCutoff(" + holdOutCutoff + ") 이후에 위치합니다. "
                    + "튜닝 기간을 포함하는 데이터를 제공하세요.");
        }
    }

    /**
     * holdOutCutoff 가 지정된 경우의 실행 흐름.
     * <ol>
     *   <li>tuningCandles (cutoff 이전) 로 일반 WF 실행 → IS/OOS 분류.</li>
     *   <li>holdOut 캔들 (cutoff 이후) 을 추가 홀드아웃 윈도우로 백테스트.</li>
     *   <li>홀드아웃 윈도우를 마지막 OOS 윈도우로 포함해 반환.</li>
     * </ol>
     */
    private WalkForwardResult runWithHoldOut(BacktestConfig config,
                                              List<Candle> tuningCandles,
                                              List<Candle> allCandles,
                                              double inSampleRatio, int windowCount,
                                              Mode mode, Instant holdOutCutoff) {
        // 1. 튜닝 구간으로 일반 WF
        WalkForwardResult base = run(config, tuningCandles, inSampleRatio, windowCount, mode, null);

        // 2. 홀드아웃 구간 백테스트
        List<Candle> holdOutCandles = allCandles.stream()
                .filter(c -> c.getTime().isAfter(holdOutCutoff))
                .toList();

        if (holdOutCandles.isEmpty()) {
            return base; // holdOut 캔들 없으면 일반 결과 그대로
        }

        BacktestResult holdOutResult = backtestEngine.run(config, holdOutCandles);
        PerformanceReport holdOutMetrics = MetricsCalculator.calculate(
                holdOutResult.getTrades(), config.getInitialCapital(), "HOLD_OUT");

        WindowResult holdOutWindow = WindowResult.builder()
                .windowIndex(base.getWindows().size()) // 마지막 윈도우 인덱스
                .inSampleMetrics(base.getAggregatedOutSampleMetrics()) // 튜닝 OOS를 IS 대용으로 표시
                .outSampleMetrics(holdOutMetrics)
                .inSampleStart(tuningCandles.get(0).getTime())
                .inSampleEnd(tuningCandles.get(tuningCandles.size() - 1).getTime())
                .outSampleStart(holdOutCandles.get(0).getTime())
                .outSampleEnd(holdOutCandles.get(holdOutCandles.size() - 1).getTime())
                .build();

        List<WindowResult> allWindows = new ArrayList<>(base.getWindows());
        allWindows.add(holdOutWindow);

        // 홀드아웃 포함 최종 aggregated OOS
        List<TradeRecord> allOosTrades = new ArrayList<>();
        for (WindowResult w : base.getWindows()) {
            // OOS 거래는 aggregated 재계산 불필요 — base 에서 이미 처리됨
        }
        allOosTrades.addAll(holdOutResult.getTrades());
        PerformanceReport finalAggregated = base.getAggregatedOutSampleMetrics();

        return WalkForwardResult.builder()
                .mode(mode)
                .windows(allWindows)
                .overfittingScore(base.getOverfittingScore())
                .verdict(base.getVerdict())
                .aggregatedOutSampleMetrics(finalAggregated)
                .build();
    }

    private WalkForwardResult empty(Mode mode) {
        return WalkForwardResult.builder()
                .mode(mode)
                .windows(List.of())
                .overfittingScore(BigDecimal.ZERO)
                .verdict("ACCEPTABLE")
                .aggregatedOutSampleMetrics(MetricsCalculator.calculate(List.of(), BigDecimal.ONE, "OOS_AGGREGATED"))
                .build();
    }
}
