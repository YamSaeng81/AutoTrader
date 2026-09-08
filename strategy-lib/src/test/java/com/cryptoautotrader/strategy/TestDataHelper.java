package com.cryptoautotrader.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class TestDataHelper {

    private TestDataHelper() {}

    /**
     * 거래량 난수 — <b>고정 시드</b>. 캔들 생성 헬퍼마다 새로 만들어 호출 순서에 무관하게
     * 같은 수열을 낸다.
     *
     * <p>이전에는 {@code Math.random()} 이라 실행마다 거래량이 달라졌다. 거래량으로 판정하는
     * 전략에서 이건 그대로 flaky 로 이어진다 — {@code VolumeDeltaStrategyTest}
     * §포화점_낮을수록_강도_높음_재스케일_검증 이 우연히 거래량이 하락 배열로 나온 실행에서
     * BUY 단정에 실패했다(2026-09-08, 3회 중 1회 재현).</p>
     *
     * <p>거래량에 변동성이 필요한 것은 맞으므로 상수로 만들지 않고 시드만 고정한다.</p>
     */
    private static final long VOLUME_SEED = 20260908L;

    /** 추세 캔들의 캔들당 거래량 증가분 — 지터(±5)보다 충분히 커야 순서가 뒤집히지 않는다. */
    private static final double VOLUME_RAMP_PER_CANDLE = 3.0;

    /** 횡보 캔들 거래량 — 추세가 없으므로 기준선 주변 지터만. */
    private static BigDecimal flatVolume(Random rnd) {
        return BigDecimal.valueOf(100 + rnd.nextDouble() * 50);
    }

    /**
     * 추세 캔들 거래량 — <b>캔들이 진행할수록 증가한다.</b>
     *
     * <p>이전에는 추세 캔들에도 평평한 난수(100~150)를 줬다. {@code VolumeDeltaStrategy} 는
     * {@code buyRatio} 가 캔들 모양으로 고정(상승 캔들 0.75)이라 {@code delta ∝ volume} 이 되고,
     * BUY 조건인 "후반부 평균 Delta > 전반부 평균 Delta"가 <b>후반부 거래량이 더 큰가</b>로
     * 환원된다 — 평평한 난수에서는 동전 던지기다. 실제로
     * {@code VolumeDeltaStrategyTest} §포화점_낮을수록_강도_높음_재스케일_검증 이
     * 3회 중 1회 실패했다(2026-09-08).
     *
     * <p>시드만 고정하면 flaky 는 사라지지만 통과 여부가 시드 뽑기가 된다. 근본은 데이터가
     * 추세를 나타내지 않는다는 것이다 — <b>상승 추세는 매수 압력이 강화되는 국면</b>이고
     * 그게 이 전략이 잡으려는 신호다. 하락 추세도 같은 램프를 쓴다: {@code buyRatio} 가 낮아
     * delta 가 음수이므로, 거래량이 늘면 후반부가 더 음수가 되어 {@code deltaWeakening} 이 성립한다.</p>
     */
    private static BigDecimal trendVolume(Random rnd, int index) {
        return BigDecimal.valueOf(100 + index * VOLUME_RAMP_PER_CANDLE + rnd.nextDouble() * 10);
    }

    /**
     * 상승 추세 캔들 데이터 생성
     */
    public static List<Candle> createUpTrendCandles(int count, BigDecimal startPrice) {
        List<Candle> candles = new ArrayList<>();
        Instant baseTime = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = startPrice;
        Random rnd = new Random(VOLUME_SEED);

        for (int i = 0; i < count; i++) {
            BigDecimal open = price;
            BigDecimal close = price.add(price.multiply(new BigDecimal("0.005"))); // 0.5% 상승
            BigDecimal high = close.add(price.multiply(new BigDecimal("0.002")));
            BigDecimal low = open.subtract(price.multiply(new BigDecimal("0.001")));
            BigDecimal volume = trendVolume(rnd, i);

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
        Random rnd = new Random(VOLUME_SEED);

        for (int i = 0; i < count; i++) {
            BigDecimal open = price;
            BigDecimal close = price.subtract(price.multiply(new BigDecimal("0.005")));
            BigDecimal high = open.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low = close.subtract(price.multiply(new BigDecimal("0.002")));
            BigDecimal volume = trendVolume(rnd, i);

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
        Random rnd = new Random(VOLUME_SEED);

        for (int i = 0; i < count; i++) {
            double oscillation = Math.sin(i * 0.3) * 0.005;
            BigDecimal close = centerPrice.add(centerPrice.multiply(BigDecimal.valueOf(oscillation)));
            BigDecimal open = centerPrice.add(centerPrice.multiply(BigDecimal.valueOf(oscillation * 0.5)));
            BigDecimal high = close.max(open).add(centerPrice.multiply(new BigDecimal("0.002")));
            BigDecimal low = close.min(open).subtract(centerPrice.multiply(new BigDecimal("0.002")));
            BigDecimal volume = flatVolume(rnd);

            candles.add(Candle.builder()
                    .time(baseTime.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close).volume(volume)
                    .build());
        }
        return candles;
    }
}
