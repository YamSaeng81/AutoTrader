package com.cryptoautotrader.strategy.volumedelta;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.strategy.TestDataHelper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VolumeDeltaStrategyTest {

    private final VolumeDeltaStrategy strategy = new VolumeDeltaStrategy();

    @Test
    void 이름은_VOLUME_DELTA() {
        assertThat(strategy.getName()).isEqualTo("VOLUME_DELTA");
    }

    @Test
    void 최소_캔들수_확인() {
        assertThat(strategy.getMinimumCandleCount()).isEqualTo(20);
    }

    @Test
    void 데이터_부족시_HOLD() {
        List<Candle> candles = TestDataHelper.createRangeCandles(10, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of("lookback", 20));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("데이터 부족");
    }

    // ========== BUY 신호 테스트 ==========

    @Test
    void 매수_압력_강화_BUY() {
        // lookback=10: 전반부(0~4) 1% 상승, 후반부(5~9) 5% 상승 → Delta 강화 + 누적Delta 양수 → BUY
        List<Candle> candles = new ArrayList<>();
        Instant base  = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 15; i++) {
            int lookbackIdx = i - (15 - 10);
            String riseStr;
            if (lookbackIdx >= 0 && lookbackIdx < 5) riseStr = "0.01";
            else if (lookbackIdx >= 5)               riseStr = "0.05";
            else                                     riseStr = "0.02";

            BigDecimal open  = price;
            BigDecimal close = price.add(price.multiply(new BigDecimal(riseStr)));
            BigDecimal high  = close.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low   = open.subtract(price.multiply(new BigDecimal("0.001")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "lookback", 10, "signalThreshold", 0.05, "divergenceMode", false));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(signal.getReason()).contains("누적Delta 매수 우세");
    }

    // ========== SELL 신호 테스트 ==========

    @Test
    void 매도_압력_강화_SELL() {
        // lookback=10: 전반부(0~4) 1% 하락, 후반부(5~9) 5% 하락 → Delta 약화 + 누적Delta 음수 → SELL
        List<Candle> candles = new ArrayList<>();
        Instant base  = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 15; i++) {
            int lookbackIdx = i - (15 - 10);
            String fallStr;
            if (lookbackIdx >= 0 && lookbackIdx < 5) fallStr = "0.01";
            else if (lookbackIdx >= 5)               fallStr = "0.05";
            else                                     fallStr = "0.02";

            BigDecimal open  = price;
            BigDecimal close = price.subtract(price.multiply(new BigDecimal(fallStr)));
            BigDecimal high  = open.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low   = close.subtract(price.multiply(new BigDecimal("0.001")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "lookback", 10, "signalThreshold", 0.05, "divergenceMode", false));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.SELL);
        assertThat(signal.getReason()).contains("누적Delta 매도 우세");
    }

    // ========== HOLD 신호 테스트 ==========

    @Test
    void 누적Delta_임계값_미만_HOLD() {
        // 아주 작은 상승 → 누적Delta 양수이지만 임계값(0.40) 미만 → HOLD
        List<Candle> candles = TestDataHelper.createUpTrendCandles(25, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "lookback", 20, "signalThreshold", 0.40, "divergenceMode", false));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    void Delta_강화_없으면_BUY_신호_HOLD_격하() {
        // 모두 동일한 3% 상승 캔들 → 누적Delta는 양수지만 전/후반부 평균 Delta가 동일 → HOLD
        List<Candle> candles = new ArrayList<>();
        Instant base  = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 25; i++) {
            BigDecimal open  = price;
            BigDecimal close = price.add(price.multiply(new BigDecimal("0.03")));
            BigDecimal high  = close.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low   = open.subtract(price.multiply(new BigDecimal("0.001")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "lookback", 20, "signalThreshold", 0.05, "divergenceMode", false));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("약화");
    }

    // ========== 다이버전스 필터 테스트 ==========

    @Test
    void 약세_다이버전스_가격상승_Delta음수_HOLD() {
        // 가격은 오르지만 Delta가 음수 → 약세 다이버전스 → SELL 보류 → HOLD
        List<Candle> candles = new ArrayList<>();
        Instant base  = Instant.parse("2024-01-01T00:00:00Z");

        // 가격은 서서히 상승하지만, 캔들이 위꼬리 패턴(high가 훨씬 높고, close는 open보다 약간만 높음)
        // → 매도 볼륨이 더 많아 Delta 음수
        BigDecimal price = new BigDecimal("50000000");
        for (int i = 0; i < 25; i++) {
            BigDecimal open  = price;
            BigDecimal close = price.add(price.multiply(new BigDecimal("0.001")));  // 0.1% 상승 (가격↑)
            BigDecimal high  = open.add(price.multiply(new BigDecimal("0.05")));    // high 훨씬 위
            BigDecimal low   = open.subtract(price.multiply(new BigDecimal("0.001")));
            // (close - low) / (high - low) ≈ 0.002/0.051 ≈ 0.039 → 매도 볼륨 압도적
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "lookback", 20, "signalThreshold", 0.05, "divergenceMode", true));
        // 가격↑ + 누적Delta < 0 → 약세 다이버전스 → SELL 보류 → HOLD
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("다이버전스");
    }

    // ========== 신호 강도 테스트 ==========

    @Test
    void 신호_강도가_0이상_100이하() {
        List<Candle> candles = TestDataHelper.createUpTrendCandles(25, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "lookback", 20, "signalThreshold", 0.05, "divergenceMode", false));
        assertThat(signal.getStrength()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
    }
}
