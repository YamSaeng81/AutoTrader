package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.backtest.WalkForwardTestRunner.Mode;
import com.cryptoautotrader.core.backtest.WalkForwardTestRunner.WalkForwardResult;
import com.cryptoautotrader.core.backtest.WalkForwardTestRunner.WindowResult;
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
 * 20260415_analy.md Tier 1 §2 회귀 방지.
 * - ROLLING 모드 OOS 윈도우 간 시간 겹침이 없는지
 * - ANCHORED 모드 train 셋이 누적 확장되는지
 * - OOS 합집합 성과 지표가 생성되는지
 */
class WalkForwardTestRunnerTest {

    private final WalkForwardTestRunner runner = new WalkForwardTestRunner();

    @Test
    void ROLLING_모드_OOS_윈도우는_시간_겹침이_없다() {
        List<Candle> candles = createCandles(500);
        BacktestConfig config = baseConfig(candles);

        WalkForwardResult result = runner.run(config, candles, 0.7, 5, Mode.ROLLING);

        assertThat(result.getMode()).isEqualTo(Mode.ROLLING);
        assertThat(result.getWindows()).isNotEmpty();

        // OOS 구간들이 시간순으로 비겹침이어야 한다
        List<WindowResult> windows = result.getWindows();
        for (int i = 1; i < windows.size(); i++) {
            Instant prevEnd = windows.get(i - 1).getOutSampleEnd();
            Instant currStart = windows.get(i).getOutSampleStart();
            assertThat(currStart)
                    .as("window %d OOS start(%s) must be >= prev OOS end(%s)", i, currStart, prevEnd)
                    .isAfterOrEqualTo(prevEnd);
        }
    }

    @Test
    void ANCHORED_모드_train_셋은_누적_확장된다() {
        List<Candle> candles = createCandles(500);
        BacktestConfig config = baseConfig(candles);

        WalkForwardResult result = runner.run(config, candles, 0.5, 4, Mode.ANCHORED);
        List<WindowResult> windows = result.getWindows();

        assertThat(result.getMode()).isEqualTo(Mode.ANCHORED);
        assertThat(windows).hasSizeGreaterThanOrEqualTo(2);

        // 각 윈도우의 IS start는 0번째 캔들 시간으로 동일, IS end는 단조 증가
        Instant firstCandleTime = candles.get(0).getTime();
        for (int i = 0; i < windows.size(); i++) {
            assertThat(windows.get(i).getInSampleStart())
                    .as("ANCHORED 모든 윈도우의 train 시작점은 전체 시작과 같아야 한다")
                    .isEqualTo(firstCandleTime);
        }
        for (int i = 1; i < windows.size(); i++) {
            assertThat(windows.get(i).getInSampleEnd())
                    .as("ANCHORED train 종료점은 단조 증가")
                    .isAfter(windows.get(i - 1).getInSampleEnd());
        }
    }

    @Test
    void 집계_OOS_지표는_null이_아니다() {
        List<Candle> candles = createCandles(400);
        BacktestConfig config = baseConfig(candles);

        WalkForwardResult result = runner.run(config, candles, 0.7, 4, Mode.ROLLING);

        assertThat(result.getAggregatedOutSampleMetrics()).isNotNull();
        assertThat(result.getAggregatedOutSampleMetrics().getSegment()).isEqualTo("OOS_AGGREGATED");
    }

    @Test
    void 레거시_run_시그니처는_ROLLING으로_동작한다() {
        List<Candle> candles = createCandles(400);
        BacktestConfig config = baseConfig(candles);

        WalkForwardResult result = runner.run(config, candles, 0.7, 4);
        assertThat(result.getMode()).isEqualTo(Mode.ROLLING);
    }

    // ── §12 look-ahead bias 방지 테스트 ─────────────────────────

    @Test
    void holdOutCutoff_IS_윈도우는_cutoff_이후_캔들_미포함() {
        // 400개 캔들 생성 (2024-01-01 ~ 2024-01-17 01:00Z)
        List<Candle> candles = createCandles(400);
        // cutoff = 300번째 캔들 시각 (튜닝 75%, 홀드아웃 25%)
        Instant cutoff = candles.get(299).getTime();

        BacktestConfig config = baseConfig(candles);
        WalkForwardResult result = runner.run(config, candles, 0.7, 3, Mode.ROLLING, cutoff);

        // IS 윈도우의 inSampleEnd가 cutoff를 초과하지 않아야 한다
        for (WalkForwardTestRunner.WindowResult w : result.getWindows()) {
            if (w.getInSampleEnd() != null) {
                assertThat(w.getInSampleEnd())
                        .as("IS end는 holdOutCutoff를 넘으면 안 됨")
                        .isBeforeOrEqualTo(cutoff);
            }
        }
    }

    @Test
    void holdOutCutoff_홀드아웃_윈도우가_추가된다() {
        List<Candle> candles = createCandles(400);
        Instant cutoff = candles.get(299).getTime();
        BacktestConfig config = baseConfig(candles);

        WalkForwardResult baseResult = runner.run(config, candles, 0.7, 3, Mode.ROLLING, null);
        WalkForwardResult holdOutResult = runner.run(config, candles, 0.7, 3, Mode.ROLLING, cutoff);

        // holdOut 결과는 기본 결과보다 윈도우가 많아야 한다 (홀드아웃 윈도우 추가)
        assertThat(holdOutResult.getWindows().size())
                .isGreaterThan(baseResult.getWindows().size());
    }

    @Test
    void holdOutCutoff_전체캔들이_cutoff이후면_예외() {
        List<Candle> candles = createCandles(100);
        // cutoff가 모든 캔들보다 이전 시각 → 튜닝 캔들 없음
        Instant cutoff = candles.get(0).getTime().minusSeconds(3600);
        BacktestConfig config = baseConfig(candles);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(config, candles, 0.7, 3, Mode.ROLLING, cutoff)
        );
    }

    private BacktestConfig baseConfig(List<Candle> candles) {
        return BacktestConfig.builder()
                .strategyName("EMA_CROSS")
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.1"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(Map.of("fastPeriod", 5, "slowPeriod", 15))
                .build();
    }

    private List<Candle> createCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < count; i++) {
            double wave = Math.sin(i * 0.2) * 500000;
            BigDecimal close = price.add(BigDecimal.valueOf(wave));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(close.subtract(BigDecimal.valueOf(50000)))
                    .high(close.add(BigDecimal.valueOf(200000)))
                    .low(close.subtract(BigDecimal.valueOf(200000)))
                    .close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return candles;
    }
}
