package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.backtest.WalkForwardTestRunner.Mode;
import com.cryptoautotrader.core.backtest.WalkForwardTestRunner.WalkForwardResult;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 홀드아웃이 <b>표시용 창이 아니라 최종 게이트</b>인지 고정한다.
 *
 * <h3>이전 동작</h3>
 * <p>{@code runWithHoldOut()} 은 홀드아웃 백테스트를 돌려 마지막 윈도우로 붙이기만 했다.
 * 반환값의 {@code overfittingScore}·{@code verdict}·{@code aggregatedOutSampleMetrics} 는
 * 전부 튜닝 구간 값이었고, 홀드아웃 거래를 모으는 {@code allOosTrades} 는 만들어만 놓고
 * 쓰이지 않았다. <b>홀드아웃이 큰 손실이어도 최종 판정이 바뀌지 않았다.</b>
 * 기존 테스트는 "홀드아웃 창이 추가되는가"만 봤기 때문에 이 사실을 잡지 못했다.
 *
 * <h3>데이터 설계</h3>
 * <p>튜닝 구간은 100~110 고정 범위 진동이라 GRID 가 저가 매수·고가 매도로 수익을 내고,
 * 홀드아웃 구간은 같은 진동을 유지한 채 추세만 하락시켜 GRID 가 계속 물리게 만든다.
 * 진폭·감쇠율만 바꾸면 홀드아웃이 통과하는 경우로도 넘어가므로, 두 방향을 모두 고정한다.
 */
class WalkForwardHoldOutGateTest {

    private final WalkForwardTestRunner runner = new WalkForwardTestRunner();

    private static final int TOTAL  = 1600;
    private static final int SPLIT  = 1200;   // 튜닝 1200 / 홀드아웃 400
    private static final int WINDOWS = 3;

    /**
     * 튜닝 구간 진동 주기(봉)와 GRID 트리거 폭.
     *
     * <p><b>2026-09-21 재조율 (Wave 3-I).</b> 원래는 주기 40 · triggerPct 기본값(5) 이었다.
     * GRID 레벨 해제 결함을 고치자 GRID 가 사이클마다 회전하게 되어 거래 모집단이 통째로
     * 바뀌었고, "튜닝만 보면 통과" 라는 <b>전제 자체가 깨졌다</b>(ACCEPTABLE → OVERFITTING).
     *
     * <p>이 테스트가 재는 것은 GRID 가 아니라 <b>홀드아웃 게이트</b>다. 그래서 전제가 다시
     * 성립하도록 데이터를 맞췄다. 동시에 표본을 두껍게 잡았다 — 이전 조합은 튜닝 OOS 6건 ·
     * 홀드아웃 15건으로 판정 최소치({@link WalkForwardTestRunner#MIN_OOS_TRADES_FOR_VERDICT}=5)
     * 바로 위에 걸쳐 있어, 거래 한두 건만 움직여도 INSUFFICIENT_DATA 로 미끄러졌다.
     * 지금 조합은 튜닝 9건 · 홀드아웃 34건이다.
     */
    private static final double TUNING_PERIOD = 80;
    private static final double TRIGGER_PCT   = 15.0;

    @Test
    void 홀드아웃_손실이_최종_verdict를_뒤집는다() {
        // 홀드아웃에서 15건 거래에 기대값 −1.67% — 튜닝 구간만 보면 통과할 조합이다.
        List<Candle> candles = mixed(0.05, 0.98, 40);
        Instant cutoff = candles.get(SPLIT - 1).getTime();
        BacktestConfig config = config(candles);

        WalkForwardResult tuningOnly = runner.run(config, tuning(candles), 0.7, WINDOWS, Mode.ROLLING, null);
        WalkForwardResult withHoldOut = runner.run(config, candles, 0.7, WINDOWS, Mode.ROLLING, cutoff);

        // 전제: 홀드아웃이 없었다면 통과했을 조합이어야 이 테스트가 의미를 갖는다.
        assertThat(tuningOnly.getVerdict()).isEqualTo("ACCEPTABLE");
        assertThat(tuningOnly.getAggregatedOutSampleMetrics().getExpectancyPct().signum())
                .as("튜닝 OOS 기대값이 양수여야 '홀드아웃이 뒤집었다'고 말할 수 있다")
                .isPositive();

        // 홀드아웃은 표본도 충분하고(≥5) 기대값이 음수 — 즉 '잴 것이 없어서'가 아니라 '졌기 때문에' 실패
        assertThat(withHoldOut.getHoldOutMetrics().getTotalTrades())
                .isGreaterThanOrEqualTo(WalkForwardTestRunner.MIN_OOS_TRADES_FOR_VERDICT);
        assertThat(withHoldOut.getHoldOutMetrics().getExpectancyPct().signum()).isNegative();

        assertThat(withHoldOut.getHoldOutPassed()).isFalse();
        assertThat(withHoldOut.getVerdict())
                .as("홀드아웃 손실이 최종 판정에 반영되어야 한다")
                .isEqualTo(WalkForwardTestRunner.VERDICT_HOLD_OUT_FAILED);
    }

    @Test
    void 홀드아웃_통과시_기존_verdict를_유지한다() {
        // 게이트가 '항상 실패'가 아님을 고정한다 — 통과 경로가 없으면 위 테스트는 무의미하다.
        List<Candle> candles = mixed(0.10, 0.98, 12);
        Instant cutoff = candles.get(SPLIT - 1).getTime();
        BacktestConfig config = config(candles);

        WalkForwardResult withHoldOut = runner.run(config, candles, 0.7, WINDOWS, Mode.ROLLING, cutoff);

        assertThat(withHoldOut.getHoldOutMetrics().getExpectancyPct().signum()).isPositive();
        assertThat(withHoldOut.getHoldOutPassed()).isTrue();
        assertThat(withHoldOut.getVerdict())
                .isNotEqualTo(WalkForwardTestRunner.VERDICT_HOLD_OUT_FAILED);
    }

    @Test
    void 홀드아웃_거래는_튜닝_OOS_지표에_섞이지_않는다() {
        // 홀드아웃은 파라미터 선택 과정을 보지 못한 독립 표본이다. 튜닝 OOS 에 합치면
        // 표본 하나가 다수에 묻혀 최종 판정에서 사라진다.
        List<Candle> candles = mixed(0.05, 0.98, 40);
        Instant cutoff = candles.get(SPLIT - 1).getTime();
        BacktestConfig config = config(candles);

        WalkForwardResult tuningOnly = runner.run(config, tuning(candles), 0.7, WINDOWS, Mode.ROLLING, null);
        WalkForwardResult withHoldOut = runner.run(config, candles, 0.7, WINDOWS, Mode.ROLLING, cutoff);

        assertThat(withHoldOut.getAggregatedOutSampleMetrics().getTotalTrades())
                .as("aggregated OOS 는 튜닝 구간만 담아야 한다")
                .isEqualTo(tuningOnly.getAggregatedOutSampleMetrics().getTotalTrades());
        assertThat(withHoldOut.getHoldOutMetrics().getTotalTrades())
                .as("홀드아웃 지표는 별도로 남아야 한다")
                .isGreaterThan(0);
    }

    @Test
    void 홀드아웃_없이_실행하면_홀드아웃_필드가_비어있다() {
        List<Candle> candles = mixed(0.05, 0.98, 40);
        WalkForwardResult result = runner.run(config(candles), candles, 0.7, WINDOWS, Mode.ROLLING, null);

        assertThat(result.getHoldOutMetrics()).isNull();
        assertThat(result.getHoldOutPassed()).isNull();
        assertThat(result.getVerdict()).isNotEqualTo(WalkForwardTestRunner.VERDICT_HOLD_OUT_FAILED);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────

    private List<Candle> tuning(List<Candle> all) {
        return all.subList(0, SPLIT);
    }

    private BacktestConfig config(List<Candle> candles) {
        return BacktestConfig.builder()
                .strategyName("GRID")
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.1"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(Map.of("triggerPct", TRIGGER_PCT))
                .build();
    }

    /**
     * 튜닝 구간: 100~110 고정 범위 진동. 홀드아웃 구간: 같은 진동을 유지한 채 추세만 하락.
     *
     * @param amp      홀드아웃 진폭 비율
     * @param decay    홀드아웃 봉당 감쇠율
     * @param holdPeriod 홀드아웃 진동 주기(봉)
     */
    private List<Candle> mixed(double amp, double decay, double holdPeriod) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");

        for (int i = 0; i < TOTAL; i++) {
            double price, high, low;
            if (i < SPLIT) {
                price = 105 + 5 * Math.sin(i * 2 * Math.PI / TUNING_PERIOD);
                high = 110;
                low = 100;
            } else {
                double t = i - SPLIT;
                double mid = 105 * Math.pow(decay, t);
                price = mid + (mid * amp) * Math.sin(t * 2 * Math.PI / holdPeriod);
                high = mid * (1 + amp);
                low  = mid * (1 - amp);
            }
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(BigDecimal.valueOf(price))
                    .high(BigDecimal.valueOf(high))
                    .low(BigDecimal.valueOf(low))
                    .close(BigDecimal.valueOf(price))
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return candles;
    }
}
