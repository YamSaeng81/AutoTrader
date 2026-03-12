package com.cryptoautotrader.strategy.rsi;

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

class RsiStrategyTest {

    private final RsiStrategy strategy = new RsiStrategy();

    @Test
    void 이름은_RSI() {
        assertThat(strategy.getName()).isEqualTo("RSI");
    }

    @Test
    void 데이터_부족시_HOLD() {
        // 기본 period(14) + divergenceLookback(5) = 19 필요, 10개만 제공
        List<Candle> candles = TestDataHelper.createUpTrendCandles(10, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("데이터 부족");
    }

    @Test
    void 최소_캔들수_확인() {
        assertThat(strategy.getMinimumCandleCount()).isEqualTo(20);
    }

    @Test
    void 급락_구간에서_과매도_BUY_신호() {
        // 30캔들 하락 후 RSI가 30 이하로 내려가도록 급격한 하락 데이터 생성
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        // 급격한 하락: 매 캔들마다 2% 하락
        for (int i = 0; i < 30; i++) {
            BigDecimal close = price.subtract(price.multiply(new BigDecimal("0.02")));
            BigDecimal open = price;
            BigDecimal high = price.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low = close.subtract(price.multiply(new BigDecimal("0.003")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "period", 14,
                "oversoldLevel", 30.0,
                "overboughtLevel", 70.0,
                "useDivergence", false
        ));
        // 급락 후 RSI는 과매도 구간 → BUY 예상
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(signal.getReason()).contains("과매도");
    }

    @Test
    void 급등_구간에서_과매수_SELL_신호() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        // 급격한 상승: 매 캔들마다 2% 상승
        for (int i = 0; i < 30; i++) {
            BigDecimal close = price.add(price.multiply(new BigDecimal("0.02")));
            BigDecimal open = price;
            BigDecimal high = close.add(price.multiply(new BigDecimal("0.003")));
            BigDecimal low = price.subtract(price.multiply(new BigDecimal("0.001")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "period", 14,
                "oversoldLevel", 30.0,
                "overboughtLevel", 70.0,
                "useDivergence", false
        ));
        // 급등 후 RSI는 과매수 구간 → SELL 예상
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.SELL);
        assertThat(signal.getReason()).contains("과매수");
    }

    @Test
    void 횡보_구간에서_HOLD() {
        List<Candle> candles = TestDataHelper.createRangeCandles(30, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "period", 14,
                "oversoldLevel", 30.0,
                "overboughtLevel", 70.0,
                "useDivergence", false
        ));
        // 횡보에서는 RSI가 중립 구간 → HOLD 예상
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    void 신호_강도가_0이상_100이하() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");
        for (int i = 0; i < 30; i++) {
            BigDecimal close = price.subtract(price.multiply(new BigDecimal("0.02")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price).high(price).low(close).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }
        StrategySignal signal = strategy.evaluate(candles, Map.of("useDivergence", false));
        assertThat(signal.getStrength()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
    }
}
