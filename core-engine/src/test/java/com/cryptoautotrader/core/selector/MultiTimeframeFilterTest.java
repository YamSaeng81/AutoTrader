package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.strategy.StrategySignal.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MultiTimeframeFilter — HTF/LTF 신호 조합")
class MultiTimeframeFilterTest {

    private static Strategy stub(String name, Action action, int minCandles) {
        return new Strategy() {
            @Override public String getName()               { return name; }
            @Override public int    getMinimumCandleCount() { return minCandles; }
            @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) {
                return switch (action) {
                    case BUY  -> StrategySignal.buy(BigDecimal.valueOf(80), name + "-BUY");
                    case SELL -> StrategySignal.sell(BigDecimal.valueOf(80), name + "-SELL");
                    case HOLD -> StrategySignal.hold(name + "-HOLD");
                };
            }
        };
    }

    private static List<Candle> candles(int count) {
        var list = new java.util.ArrayList<Candle>(count);
        for (int i = 0; i < count; i++) {
            list.add(Candle.builder()
                    .time(Instant.ofEpochSecond(i * 60L))
                    .open(BigDecimal.valueOf(100)).high(BigDecimal.valueOf(105))
                    .low(BigDecimal.valueOf(95)).close(BigDecimal.valueOf(102))
                    .volume(BigDecimal.valueOf(10))
                    .build());
        }
        return list;
    }

    @Test
    @DisplayName("HTF BUY + LTF BUY → BUY 통과")
    void htfBuy_ltfBuy_passes() {
        MultiTimeframeFilter filter = new MultiTimeframeFilter(
                stub("HTF", Action.BUY, 1),
                stub("LTF", Action.BUY, 1));
        StrategySignal result = filter.evaluate(candles(5), candles(5), Map.of(), Map.of());
        assertThat(result.getAction()).isEqualTo(Action.BUY);
    }

    @Test
    @DisplayName("HTF SELL + LTF SELL → SELL 통과")
    void htfSell_ltfSell_passes() {
        MultiTimeframeFilter filter = new MultiTimeframeFilter(
                stub("HTF", Action.SELL, 1),
                stub("LTF", Action.SELL, 1));
        StrategySignal result = filter.evaluate(candles(5), candles(5), Map.of(), Map.of());
        assertThat(result.getAction()).isEqualTo(Action.SELL);
    }

    @Test
    @DisplayName("HTF BUY + LTF SELL → HOLD (역추세 억제)")
    void htfBuy_ltfSell_suppressed() {
        MultiTimeframeFilter filter = new MultiTimeframeFilter(
                stub("HTF", Action.BUY, 1),
                stub("LTF", Action.SELL, 1));
        StrategySignal result = filter.evaluate(candles(5), candles(5), Map.of(), Map.of());
        assertThat(result.getAction()).isEqualTo(Action.HOLD);
        assertThat(result.getReason()).contains("MTF 역추세 억제");
    }

    @Test
    @DisplayName("HTF SELL + LTF BUY → HOLD (역추세 억제)")
    void htfSell_ltfBuy_suppressed() {
        MultiTimeframeFilter filter = new MultiTimeframeFilter(
                stub("HTF", Action.SELL, 1),
                stub("LTF", Action.BUY, 1));
        StrategySignal result = filter.evaluate(candles(5), candles(5), Map.of(), Map.of());
        assertThat(result.getAction()).isEqualTo(Action.HOLD);
        assertThat(result.getReason()).contains("MTF 역추세 억제");
    }

    @Test
    @DisplayName("HTF HOLD → LTF 신호 그대로 통과")
    void htfHold_ltfSignalPassThrough() {
        MultiTimeframeFilter filter = new MultiTimeframeFilter(
                stub("HTF", Action.HOLD, 1),
                stub("LTF", Action.BUY, 1));
        StrategySignal result = filter.evaluate(candles(5), candles(5), Map.of(), Map.of());
        assertThat(result.getAction()).isEqualTo(Action.BUY);
    }

    @Test
    @DisplayName("HTF 데이터 부족 → LTF 신호 그대로 통과")
    void htfInsufficientData_ltfPassThrough() {
        // HTF minCandleCount=10, but only 5 candles provided → bypass HTF filter
        MultiTimeframeFilter filter = new MultiTimeframeFilter(
                stub("HTF", Action.SELL, 10),
                stub("LTF", Action.BUY, 1));
        StrategySignal result = filter.evaluate(candles(5), candles(5), Map.of(), Map.of());
        assertThat(result.getAction()).isEqualTo(Action.BUY);
    }

    @Test
    @DisplayName("TimeframePreset — SUPERTREND 1h 파라미터 확인")
    void timeframePreset_supertrend_h1() {
        Map<String, Object> params = TimeframePreset.forStrategy("SUPERTREND", TimeframePreset.H1);
        assertThat(params.get("atrPeriod")).isEqualTo(14);
        assertThat(((Number) params.get("multiplier")).doubleValue()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("TimeframePreset — EMA_CROSS 5m 파라미터 확인")
    void timeframePreset_emaCross_m5() {
        Map<String, Object> params = TimeframePreset.forStrategy("EMA_CROSS", TimeframePreset.M5);
        assertThat(params.get("fastPeriod")).isEqualTo(5);
        assertThat(params.get("slowPeriod")).isEqualTo(13);
    }

    @Test
    @DisplayName("TimeframePreset — 알 수 없는 전략명 → 빈 맵")
    void timeframePreset_unknownStrategy_emptyMap() {
        Map<String, Object> params = TimeframePreset.forStrategy("UNKNOWN", TimeframePreset.H1);
        assertThat(params).isEmpty();
    }

    @Test
    @DisplayName("CandleDownsampler — factor=3이면 3개씩 묶어 HTF 캔들 생성")
    void downsampler_factor3_groupsCorrectly() {
        // 9개 LTF 캔들 → 3개 HTF 캔들
        List<Candle> ltf = candles(9);
        List<Candle> htf = CandleDownsampler.downsample(ltf, 3);
        assertThat(htf).hasSize(3);
    }

    @Test
    @DisplayName("CandleDownsampler — 불완전한 마지막 그룹도 포함")
    void downsampler_incompleteLastGroup_included() {
        // 10개 LTF, factor=3 → 그룹 [0-2],[3-5],[6-8],[9] → 4개
        List<Candle> ltf = candles(10);
        List<Candle> htf = CandleDownsampler.downsample(ltf, 3);
        assertThat(htf).hasSize(4);
    }

    @Test
    @DisplayName("CandleDownsampler — factor=1이면 원본 그대로 반환")
    void downsampler_factor1_returnsSame() {
        List<Candle> ltf = candles(5);
        List<Candle> htf = CandleDownsampler.downsample(ltf, 1);
        assertThat(htf).hasSize(5);
    }

    @Test
    @DisplayName("CandleDownsampler — HTF 캔들 OHLCV 집계 검증")
    void downsampler_ohlcvAggregation() {
        // 캔들 3개: close=102,102,102 → HTF close=마지막 캔들=102, volume=합산=30
        List<Candle> ltf = candles(3);
        List<Candle> htf = CandleDownsampler.downsample(ltf, 3);
        assertThat(htf).hasSize(1);
        assertThat(htf.get(0).getVolume()).isEqualByComparingTo(BigDecimal.valueOf(30)); // 10×3
        assertThat(htf.get(0).getClose()).isEqualByComparingTo(BigDecimal.valueOf(102));
    }
}
