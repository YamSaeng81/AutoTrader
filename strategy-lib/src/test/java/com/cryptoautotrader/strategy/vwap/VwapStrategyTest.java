package com.cryptoautotrader.strategy.vwap;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.strategy.TestDataHelper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VwapStrategyTest {

    private final VwapStrategy strategy = new VwapStrategy();

    @Test
    void 이름은_VWAP() {
        assertThat(strategy.getName()).isEqualTo("VWAP");
    }

    @Test
    void 데이터_부족시_HOLD() {
        var candles = TestDataHelper.createRangeCandles(5, new BigDecimal("50000000"));
        var signal = strategy.evaluate(candles, Map.of("period", 20));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    void 횡보_데이터에서_HOLD_반환() {
        var candles = TestDataHelper.createRangeCandles(30, new BigDecimal("50000000"));
        var signal = strategy.evaluate(candles, Map.of("thresholdPct", 2.0));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    void 최소_캔들수_확인() {
        assertThat(strategy.getMinimumCandleCount()).isEqualTo(20);
    }
}
