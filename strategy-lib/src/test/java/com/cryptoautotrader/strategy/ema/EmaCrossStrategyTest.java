package com.cryptoautotrader.strategy.ema;

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

class EmaCrossStrategyTest {

    private final EmaCrossStrategy strategy = new EmaCrossStrategy();

    @Test
    void 이름은_EMA_CROSS() {
        assertThat(strategy.getName()).isEqualTo("EMA_CROSS");
    }

    @Test
    void 데이터_부족시_HOLD() {
        var candles = TestDataHelper.createRangeCandles(10, new BigDecimal("50000000"));
        var signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    void 상승_추세에서_골든크로스_발생_가능() {
        // 횡보 후 상승으로 전환하는 데이터 생성
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        // 횡보 20캔들
        for (int i = 0; i < 20; i++) {
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price).high(price.add(BigDecimal.valueOf(100000)))
                    .low(price.subtract(BigDecimal.valueOf(100000)))
                    .close(price).volume(BigDecimal.valueOf(100))
                    .build());
        }
        // 급상승 5캔들
        for (int i = 20; i < 25; i++) {
            price = price.add(new BigDecimal("500000"));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price.subtract(new BigDecimal("500000")))
                    .high(price.add(new BigDecimal("100000")))
                    .low(price.subtract(new BigDecimal("600000")))
                    .close(price).volume(BigDecimal.valueOf(200))
                    .build());
        }

        var signal = strategy.evaluate(candles, Map.of("fastPeriod", 5, "slowPeriod", 15));
        // 급상승이므로 BUY 또는 HOLD 중 하나
        assertThat(signal.getAction()).isIn(StrategySignal.Action.BUY, StrategySignal.Action.HOLD);
    }

    @Test
    void 최소_캔들수_확인() {
        assertThat(strategy.getMinimumCandleCount()).isEqualTo(51);
    }
}
