package com.cryptoautotrader.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public final class TestDataHelper {

    private TestDataHelper() {}

    /**
     * 상승 추세 캔들 데이터 생성
     */
    public static List<Candle> createUpTrendCandles(int count, BigDecimal startPrice) {
        List<Candle> candles = new ArrayList<>();
        Instant baseTime = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = startPrice;

        for (int i = 0; i < count; i++) {
            BigDecimal open = price;
            BigDecimal close = price.add(price.multiply(new BigDecimal("0.005"))); // 0.5% 상승
            BigDecimal high = close.add(price.multiply(new BigDecimal("0.002")));
            BigDecimal low = open.subtract(price.multiply(new BigDecimal("0.001")));
            BigDecimal volume = BigDecimal.valueOf(100 + Math.random() * 50);

            candles.add(Candle.builder()
                    .time(baseTime.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close).volume(volume)
                    .build());
            price = close;
        }
        return candles;
    }

    /**
     * 하락 추세 캔들 데이터 생성
     */
    public static List<Candle> createDownTrendCandles(int count, BigDecimal startPrice) {
        List<Candle> candles = new ArrayList<>();
        Instant baseTime = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = startPrice;

        for (int i = 0; i < count; i++) {
            BigDecimal open = price;
            BigDecimal close = price.subtract(price.multiply(new BigDecimal("0.005")));
            BigDecimal high = open.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low = close.subtract(price.multiply(new BigDecimal("0.002")));
            BigDecimal volume = BigDecimal.valueOf(100 + Math.random() * 50);

            candles.add(Candle.builder()
                    .time(baseTime.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close).volume(volume)
                    .build());
            price = close;
        }
        return candles;
    }

    /**
     * 횡보 캔들 데이터 (oscillating around center)
     */
    public static List<Candle> createRangeCandles(int count, BigDecimal centerPrice) {
        List<Candle> candles = new ArrayList<>();
        Instant baseTime = Instant.parse("2024-01-01T00:00:00Z");

        for (int i = 0; i < count; i++) {
            double oscillation = Math.sin(i * 0.3) * 0.005;
            BigDecimal close = centerPrice.add(centerPrice.multiply(BigDecimal.valueOf(oscillation)));
            BigDecimal open = centerPrice.add(centerPrice.multiply(BigDecimal.valueOf(oscillation * 0.5)));
            BigDecimal high = close.max(open).add(centerPrice.multiply(new BigDecimal("0.002")));
            BigDecimal low = close.min(open).subtract(centerPrice.multiply(new BigDecimal("0.002")));
            BigDecimal volume = BigDecimal.valueOf(100 + Math.random() * 50);

            candles.add(Candle.builder()
                    .time(baseTime.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close).volume(volume)
                    .build());
        }
        return candles;
    }
}
