package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Walk-Forward 구간 예열의 경계 — 2026-10-28 리뷰 12차 (G3, 지적 1·2).
 *
 * <p>예열은 구간 직전 봉을 <b>지표 이력으로만</b> 준다. 이 시험이 고정하는 것:</p>
 * <ul>
 *   <li>예열 봉에서는 전략을 부르지 않고 주문·수수료·포지션도 만들지 않는다.</li>
 *   <li>체결은 구간 안에서만 생긴다(구간 첫 봉 종가 신호 → 다음 봉 시가 체결).</li>
 *   <li>구간마다 새 전략·초기자본·무보유로 시작한다 — IS 의 거래 상태가 OOS 로 넘어가지 않는다.</li>
 *   <li>개별 윈도우 OOS 와 병합 OOS 가 같은 실행에서 나온다.</li>
 *   <li>평가 봉 수는 엔진이 센 값이고, 이력이 모자라면(데이터 첫머리) 그만큼 줄어든다.</li>
 *   <li>점수를 정의할 수 없으면 비유한 값을 담지 않고 판정 불가로 둔다.</li>
 * </ul>
 */
class WalkForwardSegmentWarmupTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final int MIN_CANDLES = 20;
    private static final String PROBE = "WARMUP_BOUNDARY_PROBE";

    /** 전략 호출 기록: 마지막 봉 시각과 받은 창 길이. */
    private record Call(Instant last, int windowSize) { }

    private static final List<Call> CALLS = new ArrayList<>();

    /**
     * 상태를 가진 탐침 — 자기 호출 횟수로 매매한다(1번째 호출 매수, 이후 15번째마다 매도·매수 교대).
     * 인스턴스가 구간을 넘어 이어지면 호출 횟수가 달라져 거래가 바뀐다.
     */
    private static class CountingProbe implements Strategy {
        private int calls = 0;
        @Override public String getName() { return PROBE; }
        @Override public int getMinimumCandleCount() { return MIN_CANDLES; }
        @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) {
            CALLS.add(new Call(c.get(c.size() - 1).getTime(), c.size()));
            int n = calls++;
            if (n % 30 == 0) return StrategySignal.buy(new BigDecimal("80"), "탐침 매수 #" + n);
            if (n % 30 == 15) return StrategySignal.sell(new BigDecimal("80"), "탐침 매도 #" + n);
            return StrategySignal.hold("탐침 대기");
        }
    }

    @BeforeEach
    void setUp() {
        StrategyRegistry.registerStateful(PROBE, CountingProbe::new);
        CALLS.clear();
    }

    private static List<Candle> candles(int n) {
        List<Candle> out = new ArrayList<>();
        double price = 1000.0;
        for (int i = 0; i < n; i++) {
            double open = price;
            double close = open * (1 + 0.004 * Math.sin(i * 0.37));
            out.add(Candle.builder()
                    .time(T0.plus(Duration.ofHours(i)))
                    .open(bd(open)).close(bd(close))
                    .high(bd(Math.max(open, close) * 1.002))
                    .low(bd(Math.min(open, close) * 0.998))
                    .volume(new BigDecimal("1000"))
                    .build());
            price = close;
        }
        return out;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP);
    }

    private static BacktestConfig config(List<Candle> c) {
        return BacktestConfig.builder()
                .strategyName(PROBE)
                .coinPair("KRW-TEST")
                .timeframe("H1")
                .startDate(c.get(0).getTime())
                .endDate(c.get(c.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .strategyParams(Map.of())
                .build();
    }

    private static String trace(List<TradeRecord> trades) {
        StringBuilder sb = new StringBuilder();
        for (TradeRecord t : trades) {
            sb.append(t.getSide()).append('@').append(t.getExecutedAt()).append(':')
                    .append(t.getPrice().toPlainString()).append('x').append(t.getQuantity().toPlainString()).append(';');
        }
        return sb.toString();
    }

    // ── 엔진: 예열과 거래의 경계 ───────────────────────────────────

    @Test
    @DisplayName("예열 봉에서는 전략 호출·주문이 없고, 체결은 구간 안에서만 생긴다")
    void 예열은_이력으로만_쓰인다() {
        List<Candle> c = candles(1000);
        int tradeStart = 600;
        BacktestResult r = new BacktestEngine().runSegment(config(c), c, tradeStart);

        assertThat(r.getWarmupBars()).isEqualTo(tradeStart);
        assertThat(r.getEvaluatedBars()).isEqualTo(1000 - 1 - tradeStart);
        assertThat(CALLS).isNotEmpty();
        // 첫 호출은 구간 첫 봉에서, 창은 직전 이력을 포함한 최대 길이
        assertThat(CALLS.get(0).last()).isEqualTo(c.get(tradeStart).getTime());
        assertThat(CALLS.get(0).windowSize()).isEqualTo(BacktestEngine.MAX_LOOKBACK);
        assertThat(CALLS).allSatisfy(call -> assertThat(call.last()).isAfterOrEqualTo(c.get(tradeStart).getTime()));
        assertThat(CALLS.size()).isLessThanOrEqualTo(r.getEvaluatedBars());

        // 거래는 구간 안에서만, 첫 거래는 매수(넘어온 포지션 없음)
        assertThat(r.getTrades()).isNotEmpty();
        assertThat(r.getTrades().get(0).getSide()).isEqualTo(OrderSide.BUY);
        assertThat(r.getTrades().get(0).getExecutedAt()).isEqualTo(c.get(tradeStart + 1).getTime());
        assertThat(r.getTrades()).allSatisfy(t -> assertThat(t.getExecutedAt())
                .isAfter(c.get(tradeStart).getTime())
                .isBeforeOrEqualTo(c.get(c.size() - 1).getTime()));
    }

    @Test
    @DisplayName("예열 이력이 최소 캔들 수보다 모자라면 기존처럼 최소 캔들 수까지 기다린다")
    void 부족한_예열이력() {
        List<Candle> c = candles(400);
        BacktestResult r = new BacktestEngine().runSegment(config(c), c, 5);

        assertThat(r.getEvaluatedBars()).isEqualTo(400 - 1 - MIN_CANDLES);
        assertThat(CALLS.get(0).last()).isEqualTo(c.get(MIN_CANDLES).getTime());
        assertThat(CALLS.get(0).windowSize()).isEqualTo(MIN_CANDLES + 1);
    }

    @Test
    @DisplayName("예열 0 은 기존 run() 과 같다")
    void 예열없음은_기존경로와_같다() {
        List<Candle> c = candles(600);
        BacktestEngine engine = new BacktestEngine();
        String a = trace(engine.run(config(c), c).getTrades());
        String b = trace(engine.runSegment(config(c), c, 0).getTrades());
        assertThat(a).isNotEmpty().isEqualTo(b);
        assertThat(engine.run(config(c), c).getEvaluatedBars()).isEqualTo(600 - 1 - MIN_CANDLES);
    }

    @Test
    @DisplayName("거래 시작 위치가 범위 밖이면 거부한다")
    void 범위밖_거래시작() {
        List<Candle> c = candles(100);
        assertThatThrownBy(() -> new BacktestEngine().runSegment(config(c), c, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BacktestEngine().runSegment(config(c), c, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Walk-Forward: 윈도우마다 같은 경로 ─────────────────────────

    @Test
    @DisplayName("WF 윈도우 — 첫 IS 는 이력 없음, 이후 구간은 직전 499봉 예열, 평가 봉 수는 엔진 값")
    void WF_윈도우별_예열과_평가봉수() {
        List<Candle> c = candles(3000);   // windowSize 1000 → IS 700 · OOS 300
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner().run(config(c), c, 0.7, 3);

        List<WalkForwardTestRunner.WindowResult> w = r.getWindows();
        assertThat(w).hasSize(3);
        assertThat(w.get(0).getInSampleWarmupBars()).isZero();
        assertThat(w.get(0).getInSampleEvaluatedBars()).isEqualTo(700 - MIN_CANDLES - 1);
        for (WalkForwardTestRunner.WindowResult x : w) {
            assertThat(x.getOutSampleWarmupBars()).isEqualTo(BacktestEngine.MAX_LOOKBACK - 1);
            assertThat(x.getOutSampleEvaluatedBars()).isEqualTo(300 - 1);
        }
        assertThat(w.get(1).getInSampleWarmupBars()).isEqualTo(BacktestEngine.MAX_LOOKBACK - 1);
        assertThat(w.get(1).getInSampleEvaluatedBars()).isEqualTo(700 - 1);
        assertThat(r.getScoreMethod()).isEqualTo(WalkForwardTestRunner.SCORE_METHOD);
    }

    @Test
    @DisplayName("WF OOS 는 IS 상태를 이어받지 않고, 단독 실행과 같으며, 병합 OOS 와 같은 실행에서 나온다")
    void WF_OOS_독립과_병합경로() {
        List<Candle> c = candles(3000);
        BacktestConfig cfg = config(c);
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner().run(cfg, c, 0.7, 3);

        int sumOos = 0;
        for (WalkForwardTestRunner.WindowResult x : r.getWindows()) {
            int isEnd = 1000 * x.getWindowIndex() + 700;
            int from = isEnd - (BacktestEngine.MAX_LOOKBACK - 1);
            // 새 엔진·새 전략으로 OOS 만 단독 실행 — 앞 구간을 돌리지 않은 상태
            BacktestResult alone = new BacktestEngine().runSegment(cfg, c.subList(from, isEnd + 300), isEnd - from);
            PerformanceReport aloneM = com.cryptoautotrader.core.metrics.MetricsCalculator.calculate(
                    alone.getTrades(), cfg.getInitialCapital(), "OUT_SAMPLE");

            assertThat(x.getOutSampleMetrics().getTotalTrades()).isEqualTo(aloneM.getTotalTrades());
            assertThat(x.getOutSampleMetrics().getTotalReturnPct()).isEqualByComparingTo(aloneM.getTotalReturnPct());
            assertThat(alone.getTrades().get(0).getSide()).as("OOS 첫 거래는 매수 — 넘어온 포지션 없음")
                    .isEqualTo(OrderSide.BUY);
            assertThat(alone.getTrades()).allSatisfy(t -> assertThat(t.getExecutedAt()).isAfter(c.get(isEnd).getTime()));
            sumOos += x.getOutSampleMetrics().getTotalTrades();
        }
        assertThat(r.getAggregatedOutSampleMetrics().getTotalTrades())
                .as("병합 OOS 는 윈도우 OOS 거래를 그대로 모은 것").isEqualTo(sumOos);
    }

    @Test
    @DisplayName("홀드아웃도 같은 예열 경로 — 튜닝 구간 끝 봉은 이력으로만 들어간다")
    void 홀드아웃_예열() {
        List<Candle> c = candles(2400);
        Instant cutoff = c.get(1999).getTime();
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                .run(config(c), c, 0.7, 2, WalkForwardTestRunner.Mode.ROLLING, cutoff);

        WalkForwardTestRunner.WindowResult hold = r.getWindows().get(r.getWindows().size() - 1);
        assertThat(hold.getOutSampleStart()).isEqualTo(c.get(2000).getTime());
        assertThat(hold.getOutSampleWarmupBars()).isEqualTo(BacktestEngine.MAX_LOOKBACK - 1);
        assertThat(hold.getOutSampleEvaluatedBars()).isEqualTo(400 - 1);
        assertThat(r.getScoreMethod()).isEqualTo(WalkForwardTestRunner.SCORE_METHOD);
    }

    // ── 정의되지 않는 점수 ─────────────────────────────────────────

    @Test
    @DisplayName("평가 봉 0 · 수익률 −100% 이하는 성장률 null, 그런 윈도우가 있으면 점수 null")
    void 정의되지_않는_점수는_판정불가() {
        assertThat(WalkForwardTestRunner.perBarLogGrowth(new BigDecimal("5"), 0)).isNull();
        assertThat(WalkForwardTestRunner.perBarLogGrowth(new BigDecimal("-100"), 50)).isNull();
        assertThat(WalkForwardTestRunner.perBarLogGrowth(new BigDecimal("-150"), 50)).isNull();
        assertThat(WalkForwardTestRunner.perBarLogGrowth(new BigDecimal("5"), 50)).isNotNull();

        WalkForwardTestRunner.WindowResult ok = window(new BigDecimal("4"), new BigDecimal("2"), 100, 50);
        WalkForwardTestRunner.WindowResult wiped = window(new BigDecimal("4"), new BigDecimal("-100"), 100, 50);
        WalkForwardTestRunner.WindowResult noBars = window(new BigDecimal("4"), new BigDecimal("1"), 100, 0);

        assertThat(WalkForwardTestRunner.score(List.of(ok)).value()).isNotNull();
        WalkForwardTestRunner.Score s1 = WalkForwardTestRunner.score(List.of(ok, wiped));
        assertThat(s1.value()).as("원금 전액 손실 윈도우를 빼고 점수를 내면 안 된다").isNull();
        assertThat(s1.raw()).isNotNull();
        assertThat(WalkForwardTestRunner.score(List.of(ok, noBars)).value()).isNull();
    }

    private static WalkForwardTestRunner.WindowResult window(BigDecimal inPct, BigDecimal outPct, int inBars, int outBars) {
        return WalkForwardTestRunner.WindowResult.builder()
                .inSampleMetrics(PerformanceReport.builder().totalReturnPct(inPct).build())
                .outSampleMetrics(PerformanceReport.builder().totalReturnPct(outPct).build())
                .inSampleEvaluatedBars(inBars)
                .outSampleEvaluatedBars(outBars)
                .inSamplePerBarLogGrowth(WalkForwardTestRunner.perBarLogGrowth(inPct, inBars))
                .outSamplePerBarLogGrowth(WalkForwardTestRunner.perBarLogGrowth(outPct, outBars))
                .build();
    }
}
