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
 *   <li>이전 식(누적 수익률 비교, {@code rawOverfittingScore})이 정상(봉당 성과 일정) 가짜 자료에서
 *       OVERFITTING 을 내는 것을 <b>실제 run() 경로로</b> 기록한다.</li>
 *   <li>수정 식({@code PER_BAR_LOG_V1}, 판정에 쓰는 값)이 같은 자료에서 ACCEPTABLE, OOS 를 실제로
 *       약화시킨 자료에서는 여전히 OVERFITTING 을 내는지 확인한다.</li>
 *   <li>분모가 경과 시간이 아니라 엔진이 센 평가 봉 수인지 결측 봉 자료로 확인한다.</li>
 * </ul>
 * <p>개정: 2026-10-28 초안은 운영 코드를 바꾸지 않고 후보 식을 시험 코드에서만 계산했다. 리뷰 12차
 * 승인(F1·F3) 뒤 엔진에 구현하고 단언을 새 동작으로 바꿨다(설계: docs/WF_PERIOD_BIAS_FIX.md).
 * 운영 배포는 별도 승인이다.</p>
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

    // ── 비교용 계산 (시험 전용) ─────────────────────────────────

    /**
     * 단리 환산 하락률 — 설계 안 (나), 기록용. 분모는 <b>엔진이 센 평가 봉 수</b>다.
     * 2026-10-28 초안은 경과 시간을 봉 수로 썼다(리뷰 12차 지적 1: 결측 봉이 있으면 달라진다).
     */
    static double linearDropRate(List<WalkForwardTestRunner.WindowResult> windows) {
        double total = 0;
        int samples = 0;
        for (WalkForwardTestRunner.WindowResult w : windows) {
            double inPct = w.getInSampleMetrics().getTotalReturnPct().doubleValue();
            double outPct = w.getOutSampleMetrics().getTotalReturnPct().doubleValue();
            if (inPct <= 0) continue;
            double inRate = inPct / w.getInSampleEvaluatedBars();
            double outRate = outPct / w.getOutSampleEvaluatedBars();
            total += Math.max(0, (inRate - outRate) / inRate);
            samples++;
        }
        return samples == 0 ? 0 : total / samples;
    }

    /** 현재 판정식과 같은 임계값(0.5 / 0.3). 임계값은 바꾸지 않는다. */
    static String verdictOf(double dropRate) {
        if (dropRate > 0.5) return "OVERFITTING";
        if (dropRate > 0.3) return "CAUTION";
        return "ACCEPTABLE";
    }

    // ── 시험 ─────────────────────────────────────────────────────
    //
    // 개정(2026-10-28 리뷰 12차): 수정 (가)·구간 예열이 엔진에 들어갔다. 초안의 "현재 식 = OVERFITTING"
    // 단언은 rawOverfittingScore(이전 식, 같은 윈도우) 쪽으로 옮기고, 판정 단언은 새 동작으로 바꿨다.
    // 시험 자체는 지우지 않았다. 초안 수치(예열 전): 정상 0.5986 → OVERFITTING · LOG 0.0000,
    // 10% 저하 0.6796 · LOG 0.1983, 30% 저하 0.8815 · LOG 0.7009.

    @Test
    @DisplayName("시간당 성과가 같은 가짜 자료 — 이전 식은 OVERFITTING, 수정 식(PER_BAR_LOG_V1)은 ACCEPTABLE")
    void 정상자료에서_기간길이만으로_과적합판정() {
        // 전 구간 같은 추세(봉당 +0.05%) — IS·OOS 의 봉당 성과가 같도록 만든 자료
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                .run(config(), candles(i -> 0.0005), IS_RATIO, WINDOWS);

        assertThat(r.getWindows()).hasSize(WINDOWS);
        assertThat(r.getAggregatedOutSampleMetrics().getTotalTrades()).isGreaterThanOrEqualTo(20);
        assertThat(r.getScoreMethod()).isEqualTo(WalkForwardTestRunner.SCORE_METHOD);

        // 전제 확인: 윈도우마다 IS·OOS 의 봉당 로그 성과가 5% 이내로 같다 (성과 저하 없음)
        for (WalkForwardTestRunner.WindowResult w : r.getWindows()) {
            double is = w.getInSamplePerBarLogGrowth().doubleValue();
            double os = w.getOutSamplePerBarLogGrowth().doubleValue();
            assertThat(os / is).as("윈도우 %d 봉당 성과 OOS/IS", w.getWindowIndex()).isBetween(0.95, 1.05);
        }

        double linear = linearDropRate(r.getWindows());
        System.out.printf("[정상 자료] 수정 점수=%s 판정=%s | 이전 식=%s(%s) | LINEAR=%.4f%n",
                r.getOverfittingScore(), r.getVerdict(), r.getRawOverfittingScore(),
                verdictOf(r.getRawOverfittingScore().doubleValue()), linear);
        for (WalkForwardTestRunner.WindowResult w : r.getWindows()) {
            System.out.printf("  w%d IS %s%% (%d봉, 예열 %d) / OOS %s%% (%d봉, 예열 %d)%n", w.getWindowIndex(),
                    w.getInSampleMetrics().getTotalReturnPct(), w.getInSampleEvaluatedBars(), w.getInSampleWarmupBars(),
                    w.getOutSampleMetrics().getTotalReturnPct(), w.getOutSampleEvaluatedBars(), w.getOutSampleWarmupBars());
        }

        // 이전 식(특성 기록): 성과 저하가 없는데 OVERFITTING
        assertThat(r.getRawOverfittingScore().doubleValue()).isGreaterThan(0.5);
        // 수정 식: ACCEPTABLE
        assertThat(r.getVerdict()).isEqualTo("ACCEPTABLE");
        assertThat(r.getOverfittingScore().doubleValue()).isLessThan(0.05);
    }

    @Test
    @DisplayName("OOS 성과를 실제로 30% 로 낮춘 가짜 자료 — 수정 식도 OVERFITTING")
    void 실제_성과저하는_보정후에도_과적합() {
        int windowSize = N / WINDOWS;
        int isSize = (int) (windowSize * IS_RATIO);
        // OOS 구간만 추세를 IS 의 30% 로 — 봉당 성과가 실제로 70% 하락
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                .run(config(), candles(i -> (i % windowSize) < isSize ? 0.0005 : 0.00015), IS_RATIO, WINDOWS);

        System.out.printf("[OOS 약화 자료] 수정 점수=%s 판정=%s | 이전 식=%s | LINEAR=%.4f%n",
                r.getOverfittingScore(), r.getVerdict(), r.getRawOverfittingScore(), linearDropRate(r.getWindows()));

        assertThat(r.getVerdict()).as("보정이 실제 성과 저하까지 가리면 안 된다").isEqualTo("OVERFITTING");
        assertThat(verdictOf(r.getRawOverfittingScore().doubleValue())).isEqualTo("OVERFITTING");
    }

    @Test
    @DisplayName("OOS 추세를 10% 낮춘 가짜 자료 — 수정 식은 ACCEPTABLE (작은 저하는 통과)")
    void 작은_성과저하는_보정후_통과() {
        int windowSize = N / WINDOWS;
        int isSize = (int) (windowSize * IS_RATIO);
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                .run(config(), candles(i -> (i % windowSize) < isSize ? 0.0005 : 0.00045), IS_RATIO, WINDOWS);

        System.out.printf("[OOS 추세 10%% 저하 자료] 수정 점수=%s 판정=%s | 이전 식=%s%n",
                r.getOverfittingScore(), r.getVerdict(), r.getRawOverfittingScore());

        assertThat(verdictOf(r.getRawOverfittingScore().doubleValue()))
                .as("이전 식은 작은 저하도 OVERFITTING 으로 본다").isEqualTo("OVERFITTING");
        assertThat(r.getVerdict()).isEqualTo("ACCEPTABLE");
    }

    @Test
    @DisplayName("결측 봉이 있는 정상 자료 — 분모는 경과 시간이 아니라 엔진이 센 평가 봉 수다")
    void 결측봉이_있어도_평가봉수로_보정() {
        // 7봉마다 1봉을 뺀다 — 경과 시간과 봉 수가 달라진다. 추세는 봉당 일정.
        List<Candle> all = candles(i -> 0.0005);
        List<Candle> gapped = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (i % 7 != 3) gapped.add(all.get(i));
        }
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                .run(config(), gapped, IS_RATIO, WINDOWS);

        int windowSize = gapped.size() / WINDOWS;
        int isSize = (int) (windowSize * IS_RATIO);
        int osSize = windowSize - isSize;
        for (WalkForwardTestRunner.WindowResult w : r.getWindows()) {
            long osHours = Duration.between(w.getOutSampleStart(), w.getOutSampleEnd()).toHours() + 1;
            // OOS 는 직전 이력이 499봉 이상이라 구간 첫 봉부터 평가한다 → 구간 봉 수 − 1
            assertThat(w.getOutSampleEvaluatedBars()).isEqualTo(osSize - 1);
            assertThat(osHours).as("결측 때문에 경과 시간이 봉 수보다 길다").isGreaterThan(osSize);
        }
        System.out.printf("[결측 봉 자료] 수정 점수=%s 판정=%s | 이전 식=%s%n",
                r.getOverfittingScore(), r.getVerdict(), r.getRawOverfittingScore());
        assertThat(r.getVerdict()).isEqualTo("ACCEPTABLE");
    }

    @Test
    @DisplayName("리뷰 수치 재현 — 70/30 구간 +7%/+3% 는 이전 식 OVERFITTING, 수정 식 하락률 0")
    void 리뷰_수치_재현() throws Exception {
        // 판정 함수(private)에 이전 식의 하락률을 넣는다 — 리뷰가 확인한 것과 같은 경로
        Method resolve = WalkForwardTestRunner.class.getDeclaredMethod(
                "resolveVerdict", BigDecimal.class, int.class, PerformanceReport.class);
        resolve.setAccessible(true);
        PerformanceReport enough = PerformanceReport.builder().totalTrades(30).build();

        BigDecimal drop7030 = new BigDecimal("7").subtract(new BigDecimal("3"))
                .divide(new BigDecimal("7"), 4, java.math.RoundingMode.HALF_UP);
        assertThat(drop7030).isEqualByComparingTo("0.5714");
        assertThat(resolve.invoke(null, drop7030, 1, enough)).isEqualTo("OVERFITTING");
        assertThat(resolve.invoke(null, BigDecimal.ZERO, 1, enough)).isEqualTo("ACCEPTABLE"); // 50/50

        // 수정 식: +7%/70 봉 vs +3%/30 봉 → 봉당 로그 성장률은 OOS 쪽이 조금 높다 → 하락률 0
        double gIs = WalkForwardTestRunner.perBarLogGrowth(new BigDecimal("7"), 70).doubleValue();
        double gOos = WalkForwardTestRunner.perBarLogGrowth(new BigDecimal("3"), 30).doubleValue();
        assertThat(verdictOf(Math.max(0, (gIs - gOos) / gIs))).isEqualTo("ACCEPTABLE");
    }
}
