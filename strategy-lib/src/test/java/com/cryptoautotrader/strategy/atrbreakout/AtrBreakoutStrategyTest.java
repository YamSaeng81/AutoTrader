package com.cryptoautotrader.strategy.atrbreakout;

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

class AtrBreakoutStrategyTest {

    private final AtrBreakoutStrategy strategy = new AtrBreakoutStrategy();

    @Test
    void 이름은_ATR_BREAKOUT() {
        assertThat(strategy.getName()).isEqualTo("ATR_BREAKOUT");
    }

    @Test
    void 데이터_부족시_HOLD() {
        List<Candle> candles = TestDataHelper.createRangeCandles(10, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("데이터 부족");
    }

    @Test
    void 최소_캔들수_확인() {
        assertThat(strategy.getMinimumCandleCount()).isEqualTo(15);
    }

    @Test
    void 상방_돌파시_BUY() {
        // 마지막 캔들의 종가가 시가 + ATR*multiplier를 넘도록 설계
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        // 안정적인 횡보 데이터 20캔들 (ATR이 작게 유지)
        for (int i = 0; i < 20; i++) {
            BigDecimal rangeSize = new BigDecimal("100000"); // 10만원 범위
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price)
                    .high(price.add(rangeSize))
                    .low(price.subtract(rangeSize))
                    .close(price) // 종가 = 시가 (변화 없음)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }

        // 마지막 캔들: 시가에서 ATR(~20만원)*1.5(~30만원)를 크게 초과하는 급등
        // ATR ≈ 20만원 * 2 = 40만원 (high-low), multiplier=1.5 → 기준선 = 시가 + 60만원
        // 종가 = 시가 + 200만원 으로 설정 → 명확한 상방 돌파
        candles.add(Candle.builder()
                .time(base.plus(20, ChronoUnit.HOURS))
                .open(price)
                .high(price.add(new BigDecimal("2100000")))
                .low(price.subtract(new BigDecimal("50000")))
                .close(price.add(new BigDecimal("2000000"))) // 시가 + 200만원
                .volume(BigDecimal.valueOf(500))
                .build());

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "atrPeriod", 14,
                "multiplier", 1.5,
                "useStopLoss", true
        ));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(signal.getReason()).contains("상방 돌파");
    }

    @Test
    void 하방_돌파_손절활성_SELL() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        // 안정적인 횡보 데이터 20캔들
        for (int i = 0; i < 20; i++) {
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price)
                    .high(price.add(new BigDecimal("100000")))
                    .low(price.subtract(new BigDecimal("100000")))
                    .close(price)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }

        // 마지막 캔들: 급락 (시가 - 200만원)
        candles.add(Candle.builder()
                .time(base.plus(20, ChronoUnit.HOURS))
                .open(price)
                .high(price.add(new BigDecimal("50000")))
                .low(price.subtract(new BigDecimal("2100000")))
                .close(price.subtract(new BigDecimal("2000000")))
                .volume(BigDecimal.valueOf(500))
                .build());

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "atrPeriod", 14,
                "multiplier", 1.5,
                "useStopLoss", true
        ));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.SELL);
        assertThat(signal.getReason()).contains("하방 돌파");
    }

    @Test
    void 하방_돌파_손절비활성_HOLD() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 20; i++) {
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price)
                    .high(price.add(new BigDecimal("100000")))
                    .low(price.subtract(new BigDecimal("100000")))
                    .close(price)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        candles.add(Candle.builder()
                .time(base.plus(20, ChronoUnit.HOURS))
                .open(price)
                .high(price.add(new BigDecimal("50000")))
                .low(price.subtract(new BigDecimal("2100000")))
                .close(price.subtract(new BigDecimal("2000000")))
                .volume(BigDecimal.valueOf(500))
                .build());

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "atrPeriod", 14,
                "multiplier", 1.5,
                "useStopLoss", false // 손절 비활성
        ));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    void 비돌파_구간에서_HOLD() {
        List<Candle> candles = TestDataHelper.createRangeCandles(30, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of());
        // 횡보에서 돌파가 없으면 HOLD
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    void 신호_강도가_0이상_100이하() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 20; i++) {
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price).high(price.add(new BigDecimal("100000")))
                    .low(price.subtract(new BigDecimal("100000"))).close(price)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        candles.add(Candle.builder()
                .time(base.plus(20, ChronoUnit.HOURS))
                .open(price).high(price.add(new BigDecimal("2100000")))
                .low(price.subtract(new BigDecimal("50000")))
                .close(price.add(new BigDecimal("2000000")))
                .volume(BigDecimal.valueOf(500))
                .build());

        StrategySignal signal = strategy.evaluate(candles, Map.of("atrPeriod", 14, "multiplier", 1.5));
        assertThat(signal.getStrength()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
    }
}
