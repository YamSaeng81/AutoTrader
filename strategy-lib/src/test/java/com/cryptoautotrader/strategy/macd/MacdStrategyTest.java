package com.cryptoautotrader.strategy.macd;

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

class MacdStrategyTest {

    private final MacdStrategy strategy = new MacdStrategy();

    @Test
    void 이름은_MACD() {
        assertThat(strategy.getName()).isEqualTo("MACD");
    }

    @Test
    void 데이터_부족시_HOLD() {
        // 기본 slowPeriod(26) + signalPeriod(9) + 1 = 36 필요, 20개만 제공
        List<Candle> candles = TestDataHelper.createUpTrendCandles(20, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("데이터 부족");
    }

    @Test
    void 최소_캔들수_확인() {
        assertThat(strategy.getMinimumCandleCount()).isEqualTo(36);
    }

    @Test
    void 횡보_후_상승_전환시_BUY_또는_HOLD() {
        // 횡보 40캔들 후 상승 10캔들
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        // 횡보 40캔들
        for (int i = 0; i < 40; i++) {
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price).high(price.add(BigDecimal.valueOf(50000)))
                    .low(price.subtract(BigDecimal.valueOf(50000)))
                    .close(price).volume(BigDecimal.valueOf(100))
                    .build());
        }
        // 상승 10캔들
        for (int i = 40; i < 50; i++) {
            price = price.add(new BigDecimal("300000"));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price.subtract(new BigDecimal("300000")))
                    .high(price.add(new BigDecimal("100000")))
                    .low(price.subtract(new BigDecimal("350000")))
                    .close(price).volume(BigDecimal.valueOf(200))
                    .build());
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getAction()).isIn(StrategySignal.Action.BUY, StrategySignal.Action.HOLD);
    }

    @Test
    void 지속적_하락에서_SELL_또는_HOLD() {
        List<Candle> candles = TestDataHelper.createDownTrendCandles(60, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getAction()).isIn(StrategySignal.Action.SELL, StrategySignal.Action.HOLD);
    }

    @Test
    void 지속적_상승에서_BUY_또는_HOLD() {
        List<Candle> candles = TestDataHelper.createUpTrendCandles(60, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getAction()).isIn(StrategySignal.Action.BUY, StrategySignal.Action.HOLD);
    }

    @Test
    void 신호_강도가_0이상_100이하() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        // 횡보 후 급등 패턴으로 골든크로스 유도
        for (int i = 0; i < 40; i++) {
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price).high(price.add(BigDecimal.valueOf(10000)))
                    .low(price.subtract(BigDecimal.valueOf(10000)))
                    .close(price).volume(BigDecimal.valueOf(100))
                    .build());
        }
        for (int i = 40; i < 60; i++) {
            price = price.add(new BigDecimal("500000"));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price.subtract(new BigDecimal("500000")))
                    .high(price.add(new BigDecimal("100000")))
                    .low(price.subtract(new BigDecimal("600000")))
                    .close(price).volume(BigDecimal.valueOf(300))
                    .build());
        }
        StrategySignal signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getStrength()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
    }

    @Test
    void 커스텀_파라미터_적용() {
        List<Candle> candles = TestDataHelper.createUpTrendCandles(60, new BigDecimal("50000000"));
        // fastPeriod=5, slowPeriod=13, signalPeriod=5 로 더 민감한 설정
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "fastPeriod", 5,
                "slowPeriod", 13,
                "signalPeriod", 5
        ));
        assertThat(signal).isNotNull();
        assertThat(signal.getAction()).isNotNull();
    }
}
