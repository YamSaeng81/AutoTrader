package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Walk Forward 과적합 점수의 <b>기간 길이 편향</b> — 2026-10-28 리뷰 11차에서 지적.
 *
 * <h3>무엇이 문제인가</h3>
 * <p>{@code WalkForwardTestRunner.run} 은 윈도우마다 IS·OOS <b>누적 수익률</b>을 그대로 비교한다
 * ({@code (IS − OOS) / IS}). ROLLING · 비율 0.7 이면 IS 는 OOS 보다 7/3 배 길다. 시간당 성과가
 * 같아도 누적 수익률은 길이에 비례하므로 하락률이 1 − 3/7 ≈ 0.571 이 되고, 임계값 0.5 를 넘어
 * <b>성과 저하가 전혀 없어도 OVERFITTING</b> 이 나온다.</p>
 *
 * <h3>이 시험이 하는 일</h3>
 * <ul>
 *   <li>현재 판정식이 정상(시간당 성과 일정) 가짜 자료에서 OVERFITTING 을 내는 것을 <b>실제 run() 경로로</b> 고정한다.
 *       — 현재 동작의 특성 시험이다. 수정이 반영되면 이 단언은 새 동작으로 바꾸고 이 기록은 남긴다.</li>
 *   <li>수정 후보(기간 보정 하락률)를 <b>시험 코드 안에서만</b> 계산해, 같은 자료에서 ACCEPTABLE,
 *       OOS 를 실제로 약화시킨 자료에서는 여전히 OVERFITTING 이 나오는지 확인한다.</li>
 * </ul>
 * <p>🔴 운영 코드는 바꾸지 않는다. 후보 판정식은 리뷰 확정 전까지 시험 코드에만 있다
 * (설계: docs/WF_PERIOD_BIAS_FIX.md).</p>
 */
class WalkForwardPeriodBiasTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final int N = 5000;           // windowSize 1000 → IS 700 · OOS 300
    private static final int WINDOWS = 5;
    private static final double IS_RATIO = 0.7;
    private static final int MIN_CANDLES = 20;
    private static final String PROBE = "WF_PERIOD_PROBE";

    /**
     * 시각만 보고 매매하는 탐침 전략 — 20시간마다 매수, 12시간 뒤 매도.
     * 가격 추세가 일정하면 언제 거래하든 거래당 성과가 같다 → 시간당 성과가 구간과 무관하다.
     */
    private static class PeriodProbe implements Strategy {
        @Override public String getName() { return PROBE; }
        @Override public int getMinimumCandleCount() { return MIN_CANDLES; }
        @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) {
            long h = c.get(c.size() - 1).getTime().getEpochSecond() / 3600;
            if (h % 20 == 0) return StrategySignal.buy(new BigDecimal("80"), "탐침 매수");
            if (h % 20 == 12) return StrategySignal.sell(new BigDecimal("80"), "탐침 매도");
            return StrategySignal.hold("탐침 대기");
        }
    }

    @BeforeEach
    void registerProbe() {
        StrategyRegistry.register(new PeriodProbe());
    }

    /** 봉 i 의 시간당 로그 추세를 받아 매끈한 캔들 열을 만든다. */
    private static List<Candle> candles(IntToDoubleFunction driftPerBar) {
        List<Candle> out = new ArrayList<>();
        double price = 1000.0;
        for (int i = 0; i < N; i++) {
            double open = price;
            double close = open * Math.exp(driftPerBar.applyAsDouble(i));
            out.add(Candle.builder()
                    .time(T0.plus(Duration.ofHours(i)))
                    .open(bd(open)).close(bd(close))
                    .high(bd(Math.max(open, close) * 1.0003))
                    .low(bd(Math.min(open, close) * 0.9997))
                    .volume(new BigDecimal("1000"))
                    .build());
            price = close;
        }
        return out;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(8, java.math.RoundingMode.HALF_UP);
    }

    private static BacktestConfig config() {
        return BacktestConfig.builder()
                .strategyName(PROBE)
                .coinPair("KRW-TEST")
                .timeframe("H1")
                .startDate(T0)
                .endDate(T0.plus(Duration.ofHours(N)))
                .initialCapital(new BigDecimal("10000000"))
                .strategyParams(Map.of())
                .build();
    }

    // ── 수정 후보 (시험 전용) ─────────────────────────────────────

    /** 봉 수 보정 방식. */
    enum Norm { LINEAR, LOG }

    /**
     * 기간 보정 하락률 — 후보. 윈도우마다 수익률을 <b>실제 평가된 봉 수</b>로 나눈 뒤 비교한다.
     * 평가 봉 수 = 구간 봉 수 − 최소 캔들 수 − 1 (BacktestEngine 루프 {@code i = minCandles .. size-2}).
     * 하락률 계산 대상(IS > 0)·0 하한·평균은 현재 식과 같다.
     */
    static double normalizedDropRate(List<WalkForwardTestRunner.WindowResult> windows, int minCandles, Norm norm) {
        double total = 0;
        int samples = 0;
        for (WalkForwardTestRunner.WindowResult w : windows) {
            double inPct = w.getInSampleMetrics().getTotalReturnPct().doubleValue();
            double outPct = w.getOutSampleMetrics().getTotalReturnPct().doubleValue();
            if (inPct <= 0) continue;
            long inBars = hours(w.getInSampleStart(), w.getInSampleEnd()) + 1 - minCandles - 1;
            long outBars = hours(w.getOutSampleStart(), w.getOutSampleEnd()) + 1 - minCandles - 1;
            double inRate = rate(inPct, inBars, norm);
            double outRate = rate(outPct, outBars, norm);
            total += Math.max(0, (inRate - outRate) / inRate);
            samples++;
        }
        return samples == 0 ? 0 : total / samples;
    }

    private static double rate(double pct, long bars, Norm norm) {
        if (norm == Norm.LINEAR) return pct / bars;
        // 원금 전액 손실(−100% 이하)은 로그가 정의되지 않는다 — 시간당 성과를 −∞ 로 본다.
        if (pct <= -100) return Double.NEGATIVE_INFINITY;
        return Math.log1p(pct / 100.0) / bars;
    }

    private static long hours(Instant a, Instant b) {
        return Duration.between(a, b).toHours();
    }

    /** 현재 판정식과 같은 임계값(0.5 / 0.3). 임계값은 바꾸지 않는다. */
    static String verdictOf(double dropRate) {
        if (dropRate > 0.5) return "OVERFITTING";
        if (dropRate > 0.3) return "CAUTION";
        return "ACCEPTABLE";
    }

    private static double logRatePerBar(WalkForwardTestRunner.WindowResult w, boolean inSample) {
        PerformanceReport m = inSample ? w.getInSampleMetrics() : w.getOutSampleMetrics();
        Instant s = inSample ? w.getInSampleStart() : w.getOutSampleStart();
        Instant e = inSample ? w.getInSampleEnd() : w.getOutSampleEnd();
        return rate(m.getTotalReturnPct().doubleValue(), hours(s, e) - MIN_CANDLES, Norm.LOG);
    }

    // ── 시험 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("시간당 성과가 같은 가짜 자료 — 현재 식은 OVERFITTING, 기간 보정 후보는 ACCEPTABLE")
    void 정상자료에서_기간길이만으로_과적합판정() {
        // 전 구간 같은 추세(시간당 +0.05%) — IS·OOS 의 시간당 성과가 같도록 만든 자료
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                .run(config(), candles(i -> 0.0005), IS_RATIO, WINDOWS);

        assertThat(r.getWindows()).hasSize(WINDOWS);
        assertThat(r.getAggregatedOutSampleMetrics().getTotalTrades()).isGreaterThanOrEqualTo(20);

        // 전제 확인: 윈도우마다 IS·OOS 의 시간당 로그 성과가 5% 이내로 같다 (성과 저하 없음)
        for (WalkForwardTestRunner.WindowResult w : r.getWindows()) {
            double is = logRatePerBar(w, true);
            double os = logRatePerBar(w, false);
            assertThat(os / is).as("윈도우 %d 시간당 성과 OOS/IS", w.getWindowIndex()).isBetween(0.95, 1.05);
        }

        double linear = normalizedDropRate(r.getWindows(), MIN_CANDLES, Norm.LINEAR);
        double log = normalizedDropRate(r.getWindows(), MIN_CANDLES, Norm.LOG);
        System.out.printf("[정상 자료] 현재 점수=%s 판정=%s | 후보 LINEAR=%.4f(%s) LOG=%.4f(%s)%n",
                r.getOverfittingScore(), r.getVerdict(), linear, verdictOf(linear), log, verdictOf(log));
        for (WalkForwardTestRunner.WindowResult w : r.getWindows()) {
            System.out.printf("  w%d IS %s%% / OOS %s%%%n", w.getWindowIndex(),
                    w.getInSampleMetrics().getTotalReturnPct(), w.getOutSampleMetrics().getTotalReturnPct());
        }

        // 현재 동작(특성 시험): 성과 저하가 없는데 OVERFITTING
        assertThat(r.getOverfittingScore().doubleValue()).isGreaterThan(0.5);
        assertThat(r.getVerdict()).isEqualTo("OVERFITTING");
        // 후보: 기간을 보정하면 ACCEPTABLE
        assertThat(verdictOf(log)).isEqualTo("ACCEPTABLE");
        assertThat(log).isLessThan(0.05);
    }

    @Test
    @DisplayName("OOS 시간당 성과를 실제로 30% 로 낮춘 가짜 자료 — 기간 보정 후보도 OVERFITTING")
    void 실제_성과저하는_보정후에도_과적합() {
        int windowSize = N / WINDOWS;
        int isSize = (int) (windowSize * IS_RATIO);
        // OOS 구간만 추세를 IS 의 30% 로 — 시간당 성과가 실제로 70% 하락
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                .run(config(), candles(i -> (i % windowSize) < isSize ? 0.0005 : 0.00015), IS_RATIO, WINDOWS);

        double log = normalizedDropRate(r.getWindows(), MIN_CANDLES, Norm.LOG);
        double linear = normalizedDropRate(r.getWindows(), MIN_CANDLES, Norm.LINEAR);
        System.out.printf("[OOS 약화 자료] 현재 점수=%s 판정=%s | 후보 LINEAR=%.4f(%s) LOG=%.4f(%s)%n",
                r.getOverfittingScore(), r.getVerdict(), linear, verdictOf(linear), log, verdictOf(log));

        assertThat(r.getVerdict()).isEqualTo("OVERFITTING");
        assertThat(verdictOf(log)).as("보정이 실제 성과 저하까지 가리면 안 된다").isEqualTo("OVERFITTING");
    }

    @Test
    @DisplayName("OOS 추세를 10% 낮춘 가짜 자료 — 보정 후보는 ACCEPTABLE (작은 저하는 통과)")
    void 작은_성과저하는_보정후_통과() {
        int windowSize = N / WINDOWS;
        int isSize = (int) (windowSize * IS_RATIO);
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                .run(config(), candles(i -> (i % windowSize) < isSize ? 0.0005 : 0.00045), IS_RATIO, WINDOWS);

        double log = normalizedDropRate(r.getWindows(), MIN_CANDLES, Norm.LOG);
        System.out.printf("[OOS 추세 10%% 저하 자료] 현재 점수=%s 판정=%s | 후보 LOG=%.4f(%s)%n",
                r.getOverfittingScore(), r.getVerdict(), log, verdictOf(log));

        assertThat(r.getVerdict()).as("현재 식은 작은 저하도 OVERFITTING 으로 본다").isEqualTo("OVERFITTING");
        assertThat(verdictOf(log)).isEqualTo("ACCEPTABLE");
    }

    @Test
    @DisplayName("리뷰 수치 재현 — 70/30 구간 +7%/+3% 는 OVERFITTING, 50/50 +5%/+5% 는 ACCEPTABLE")
    void 리뷰_수치_재현() throws Exception {
        // 현재 판정 함수(private)에 현재 식의 하락률을 넣는다 — 리뷰가 확인한 것과 같은 경로
        Method resolve = WalkForwardTestRunner.class.getDeclaredMethod(
                "resolveVerdict", BigDecimal.class, int.class, PerformanceReport.class);
        resolve.setAccessible(true);
        PerformanceReport enough = PerformanceReport.builder().totalTrades(30).build();

        BigDecimal drop7030 = new BigDecimal("7").subtract(new BigDecimal("3"))
                .divide(new BigDecimal("7"), 4, java.math.RoundingMode.HALF_UP);
        assertThat(drop7030).isEqualByComparingTo("0.5714");
        assertThat(resolve.invoke(null, drop7030, 1, enough)).isEqualTo("OVERFITTING");
        assertThat(resolve.invoke(null, BigDecimal.ZERO, 1, enough)).isEqualTo("ACCEPTABLE"); // 50/50

        // 후보(LINEAR): +7%/70 봉 vs +3%/30 봉 → 시간당 같음 → 하락률 0
        double candidate = Math.max(0, (7.0 / 70 - 3.0 / 30) / (7.0 / 70));
        assertThat(verdictOf(candidate)).isEqualTo("ACCEPTABLE");
    }
}
