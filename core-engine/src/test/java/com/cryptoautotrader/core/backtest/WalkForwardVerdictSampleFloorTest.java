package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Walk Forward 판정의 <b>표본 하한</b> — 2026-09-09 신설.
 *
 * <h3>무엇이 문제였나</h3>
 * <p>과적합 점수({@code avgDropRate})는 <b>인샘플 수익률이 양수인 윈도우</b>에서만 계산된다.
 * 거래가 거의 없으면 그런 윈도우가 하나도 없고, 그러면 점수가 0 으로 남아
 * <b>가장 좋은 판정인 {@code ACCEPTABLE}</b> 이 나왔다 — "하락이 없다" 가 아니라
 * "잴 것이 없다" 인데도.</p>
 *
 * <p>2026-09-09 WF 재검증 80조합에서 실제로 관측됐다:</p>
 * <pre>
 *   MTF_BTC   / KRW-EUL  / M15  →  ACCEPTABLE,  기대값 −4.160%,  n=2
 *   ICHIMOKU  / KRW-PROM / H1   →  ACCEPTABLE,  기대값  0.000%,  n=0
 * </pre>
 *
 * <p>게이트는 자체 표본 하한({@code MIN_TRADES=5})으로 걸러 실피해는 없었다. 하지만
 * <b>화면·텔레그램·리포트는 verdict 만 본다</b> — 거기서는 "양호"로 읽혔다.
 * 판정을 내는 쪽에서 막는 것이 옳다.</p>
 */
class WalkForwardVerdictSampleFloorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** 신호를 전혀 내지 않는 전략 — 거래 0건 상황을 만든다. */
    private static class NeverTradesStrategy implements Strategy {
        @Override public String getName() { return "NEVER"; }
        @Override public int getMinimumCandleCount() { return 20; }
        @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) {
            return StrategySignal.hold("거래 없음");
        }
    }

    private static List<Candle> flat(int count) {
        List<Candle> out = new ArrayList<>();
        BigDecimal base = new BigDecimal("1000");
        for (int i = 0; i < count; i++) {
            out.add(Candle.builder()
                    .time(T0.plus(Duration.ofHours(i)))
                    .open(base).high(base.add(BigDecimal.ONE))
                    .low(base.subtract(BigDecimal.ONE)).close(base)
                    .volume(new BigDecimal("1000"))
                    .build());
        }
        return out;
    }

    @BeforeEach
    void registerStub() {
        // 러너는 config.strategyName 으로 StrategyRegistry 를 조회한다 — 스텁을 등록해 둔다.
        StrategyRegistry.register(new NeverTradesStrategy());
    }

    private static BacktestConfig config() {
        return BacktestConfig.builder()
                .strategyName("NEVER")
                .coinPair("KRW-TEST")
                .timeframe("H1")
                .startDate(T0)
                .endDate(T0.plus(Duration.ofDays(60)))
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.1"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(Map.of())
                .build();
    }

    @Test
    @DisplayName("거래가 없으면 ACCEPTABLE 이 아니라 판정 불가다 (핵심 회귀)")
    void 거래가_없으면_판정불가() {
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                .run(config(), flat(600), 0.7, 5);

        assertThat(r.getAggregatedOutSampleMetrics().getTotalTrades())
                .as("이 시나리오는 거래가 0건이어야 한다")
                .isZero();
        assertThat(r.getVerdict())
                .as("거래 0건인데 ACCEPTABLE 이면 화면·텔레그램·리포트가 '양호'로 읽는다. "
                        + "'과적합이 아니다' 와 '잴 것이 없다' 는 다른 상태다.")
                .isEqualTo(WalkForwardTestRunner.VERDICT_INSUFFICIENT_DATA);
    }

    @Test
    @DisplayName("캔들이 부족해 윈도우를 못 만들면 판정 불가다")
    void 캔들이_부족하면_판정불가() {
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                .run(config(), flat(10), 0.7, 5);

        assertThat(r.getVerdict())
                .as("데이터가 아예 없는데 양호 판정이 나오면 안 된다")
                .isEqualTo(WalkForwardTestRunner.VERDICT_INSUFFICIENT_DATA);
    }

    @Test
    @DisplayName("판정 불가 하한이 게이트의 표본 하한보다 느슨하지 않다")
    void 하한이_게이트와_어긋나지_않는다() {
        // 게이트(WalkForwardValidationGate.MIN_TRADES = 5)가 통과시키는 표본을
        // 판정 쪽이 "충분하다" 고 말하면, verdict 만 보는 소비자가 게이트보다 관대해진다.
        assertThat(WalkForwardTestRunner.MIN_OOS_TRADES_FOR_VERDICT)
                .as("게이트 MIN_TRADES(5) 와 어긋나면 두 기준이 갈린다 — 한쪽만 고쳐지는 원래 결함으로 돌아간다")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("판정 불가는 과적합과 다른 값이다 — 소비자가 구분할 수 있어야 한다")
    void 판정불가는_과적합과_구분된다() {
        assertThat(WalkForwardTestRunner.VERDICT_INSUFFICIENT_DATA)
                .isNotEqualTo("OVERFITTING")
                .isNotEqualTo("ACCEPTABLE")
                .isNotEqualTo("CAUTION");
    }
}
