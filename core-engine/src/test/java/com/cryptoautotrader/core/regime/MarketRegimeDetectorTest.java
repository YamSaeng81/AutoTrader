package com.cryptoautotrader.core.regime;

import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketRegimeDetectorTest {

    private final MarketRegimeDetector detector = new MarketRegimeDetector();

    @Test
    void 데이터_부족시_RANGE_기본값() {
        List<Candle> candles = createCandles(10, new BigDecimal("50000000"), 0);
        MarketRegime regime = detector.detect(candles);
        assertThat(regime).isEqualTo(MarketRegime.RANGE);
    }

    @Test
    void 횡보_데이터에서_RANGE_또는_VOLATILE() {
        List<Candle> candles = createCandles(50, new BigDecimal("50000000"), 0);
        MarketRegime regime = detector.detect(candles);
        assertThat(regime).isIn(MarketRegime.RANGE, MarketRegime.VOLATILE);
    }

    @Test
    void 강한_추세에서_TREND() {
        // 강한 상승 추세 생성
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 50; i++) {
            BigDecimal open = price;
            price = price.add(new BigDecimal("500000")); // 매 캔들 50만원 상승
            BigDecimal high = price.add(new BigDecimal("100000"));
            BigDecimal low = open.subtract(new BigDecimal("50000"));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(price)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }

        MarketRegime regime = detector.detect(candles);
        assertThat(regime).isEqualTo(MarketRegime.TREND);
    }

    private List<Candle> createCandles(int count, BigDecimal centerPrice, double trend) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");

        for (int i = 0; i < count; i++) {
            double noise = Math.sin(i * 0.5) * 50000;
            BigDecimal close = centerPrice.add(BigDecimal.valueOf(noise + trend * i));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(close.subtract(BigDecimal.valueOf(10000)))
                    .high(close.add(BigDecimal.valueOf(80000)))
                    .low(close.subtract(BigDecimal.valueOf(80000)))
                    .close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return candles;
    }
}
