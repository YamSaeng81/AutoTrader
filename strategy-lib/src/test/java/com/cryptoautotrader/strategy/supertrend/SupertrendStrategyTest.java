package com.cryptoautotrader.strategy.supertrend;

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

class SupertrendStrategyTest {

    private final SupertrendStrategy strategy = new SupertrendStrategy();

    @Test
    void 이름은_SUPERTREND() {
        assertThat(strategy.getName()).isEqualTo("SUPERTREND");
    }

    @Test
    void 데이터_부족시_HOLD() {
        // atrPeriod(10) + 2 = 12 필요, 5개만 제공
        List<Candle> candles = TestDataHelper.createUpTrendCandles(5, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("데이터 부족");
    }

    @Test
    void 최소_캔들수_확인() {
        assertThat(strategy.getMinimumCandleCount()).isEqualTo(12);
    }

    @Test
    void 지속적_상승추세에서_BUY() {
        // 충분히 긴 상승 데이터 제공
        List<Candle> candles = TestDataHelper.createUpTrendCandles(50, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "atrPeriod", 10,
                "multiplier", 3.0
        ));
        // 지속적인 상승이면 Supertrend 상승 추세 → BUY
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.BUY);
    }

    @Test
    void 지속적_하락추세에서_SELL() {
        List<Candle> candles = TestDataHelper.createDownTrendCandles(50, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "atrPeriod", 10,
                "multiplier", 3.0
        ));
        // 지속적인 하락이면 Supertrend 하락 추세 → SELL
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.SELL);
    }

    @Test
    void 추세_전환시_강한_신호() {
        // 하락 추세 후 급격히 상승 전환 → 강한 BUY 신호 기대
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        // 하락 25캔들
        for (int i = 0; i < 25; i++) {
            BigDecimal close = price.subtract(price.multiply(new BigDecimal("0.008")));
            BigDecimal open = price;
            BigDecimal high = price.add(price.multiply(new BigDecimal("0.002")));
            BigDecimal low = close.subtract(price.multiply(new BigDecimal("0.003")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }
        // 급격한 상승 전환 (이전 ATR보다 훨씬 큰 폭으로 상승)
        for (int i = 25; i < 40; i++) {
            BigDecimal close = price.add(price.multiply(new BigDecimal("0.015")));
            BigDecimal open = price;
            BigDecimal high = close.add(price.multiply(new BigDecimal("0.005")));
            BigDecimal low = price.subtract(price.multiply(new BigDecimal("0.002")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(300))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "atrPeriod", 10,
                "multiplier", 3.0
        ));
        // 전환 또는 상승 추세 → BUY 예상
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.BUY);
    }

    @Test
    void 신호_강도가_0이상_100이하() {
        List<Candle> candles = TestDataHelper.createUpTrendCandles(30, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of("atrPeriod", 10));
        assertThat(signal.getStrength()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
    }

    @Test
    void 커스텀_multiplier_적용() {
        List<Candle> candles = TestDataHelper.createUpTrendCandles(30, new BigDecimal("50000000"));
        // multiplier가 작을수록 신호가 빠르게 전환됨
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "atrPeriod", 7,
                "multiplier", 1.5
        ));
        assertThat(signal).isNotNull();
        assertThat(signal.getAction()).isNotNull();
    }
}
