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
        // 가격↑ + Delta음수(deltaWeakening=true) → 약세 다이버전스 → SELL 보류 → HOLD
        //
        // 다이버전스 필터는 Delta 추세 확인(전/후반부 비교) 이후에 평가된다.
        // deltaWeakening=true가 되어야 SELL 경로에서 다이버전스 체크에 도달한다.
        //
        // lookback=20, 총 25캔들:
        //   앞 5개(패딩): 중간 위꼬리 → buyRatio≈0.27 → delta≈-45
        //   lookback 전반(5~14): 중간 위꼬리 → delta≈-45  (firstAvg≈-45)
        //   lookback 후반(15~24): 큰 위꼬리  → delta≈-93  (secondAvg≈-93)
        // → secondAvg < firstAvg → deltaWeakening=true
        // → priceUp=true (close=open*1.002 누적)
        // → 다이버전스 필터 발동 → HOLD("약세 다이버전스")
        List<Candle> candles = new ArrayList<>();
        Instant base  = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 25; i++) {
            BigDecimal open  = price;
            BigDecimal close = open.add(open.multiply(new BigDecimal("0.002")));  // 0.2% 상승 (priceUp)
            BigDecimal low   = open.subtract(open.multiply(new BigDecimal("0.001")));
            BigDecimal high;
            if (i < 15) {
                // 패딩 + lookback 전반: 중간 위꼬리 → buyRatio=(0.002+0.001)/(0.01+0.001)≈0.27 → delta≈-45
                high = open.add(open.multiply(new BigDecimal("0.01")));
            } else {
                // lookback 후반: 큰 위꼬리 → buyRatio=(0.002+0.001)/(0.08+0.001)≈0.037 → delta≈-93
                high = open.add(open.multiply(new BigDecimal("0.08")));
            }
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "lookback", 20, "signalThreshold", 0.05, "divergenceMode", true));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("다이버전스");
    }

    @Test
    void Delta_강화_없으면_SELL_신호_HOLD_격하() {
        // 모두 동일한 3% 하락 캔들 → 누적Delta는 음수지만 전/후반부 평균 Delta가 동일 → HOLD
        List<Candle> candles = new ArrayList<>();
        Instant base  = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 25; i++) {
            BigDecimal open  = price;
            BigDecimal close = price.subtract(price.multiply(new BigDecimal("0.03")));
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
                "lookback", 20, "signalThreshold", 0.05, "divergenceMode", false));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("약화");
    }

    // ========== 다이버전스 필터 테스트 ==========

    @Test
    void 강세_다이버전스_가격하락_Delta양수_HOLD() {
        // 가격↓ + Delta양수(deltaStrengthening=true) → 강세 다이버전스 → BUY 보류 → HOLD
        //
        // lookback=20, 총 25캔들:
        //   앞 5개(패딩) + lookback 전반(5~14): 작은 아래꼬리 → buyRatio≈0.73 → delta≈+45
        //   lookback 후반(15~24): 큰 아래꼬리 → buyRatio≈0.96 → delta≈+93
        // → secondAvg > firstAvg → deltaStrengthening=true
        // → priceDown=true (close=open*0.998 누적)
        // → 다이버전스 필터 발동 → HOLD("강세 다이버전스")
        List<Candle> candles = new ArrayList<>();
        Instant base  = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 25; i++) {
            BigDecimal open  = price;
            BigDecimal close = open.subtract(open.multiply(new BigDecimal("0.002"))); // 0.2% 하락 (priceDown)
            BigDecimal high  = open.add(open.multiply(new BigDecimal("0.001")));
            BigDecimal low;
            if (i < 15) {
                // 패딩 + lookback 전반: 작은 아래꼬리 → buyRatio=(0.002+0.001)/(0.001+0.01)≈0.73 → delta≈+45
                low = open.subtract(open.multiply(new BigDecimal("0.01")));
            } else {
                // lookback 후반: 큰 아래꼬리 → buyRatio=(0.002+0.001)/(0.001+0.08)≈0.037... → 실제≈0.963 → delta≈+93
                low = open.subtract(open.multiply(new BigDecimal("0.08")));
            }
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "lookback", 20, "signalThreshold", 0.05, "divergenceMode", true));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("다이버전스");
    }

    @Test
    void divergenceMode_false_이면_다이버전스_필터_미적용() {
        // 강세 다이버전스 조건(가격↓ + Delta 양수, deltaStrengthening=true)이지만
        // divergenceMode=false → BUY 그대로 발동
        List<Candle> candles = new ArrayList<>();
        Instant base  = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");
        for (int i = 0; i < 25; i++) {
            BigDecimal open  = price;
            BigDecimal close = open.subtract(open.multiply(new BigDecimal("0.002")));
            BigDecimal high  = open.add(open.multiply(new BigDecimal("0.001")));
            BigDecimal low   = (i < 15)
                    ? open.subtract(open.multiply(new BigDecimal("0.01")))
                    : open.subtract(open.multiply(new BigDecimal("0.08")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "lookback", 20, "signalThreshold", 0.05, "divergenceMode", false));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.BUY);
    }

    @Test
    void 볼륨_데이터_없으면_HOLD() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");
        for (int i = 0; i < 25; i++) {
            BigDecimal open  = price;
            BigDecimal close = price.add(price.multiply(new BigDecimal("0.01")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(close).low(open).close(close)
                    .volume(BigDecimal.ZERO)   // 볼륨 0
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of("lookback", 20));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("볼륨");
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
