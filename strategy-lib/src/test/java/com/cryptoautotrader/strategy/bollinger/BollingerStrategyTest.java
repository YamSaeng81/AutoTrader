package com.cryptoautotrader.strategy.bollinger;

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

class BollingerStrategyTest {

    private final BollingerStrategy strategy = new BollingerStrategy();

    @Test
    void 이름은_BOLLINGER() {
        assertThat(strategy.getName()).isEqualTo("BOLLINGER");
    }

    @Test
    void 데이터_부족시_HOLD() {
        var candles = TestDataHelper.createRangeCandles(10, new BigDecimal("50000000"));
        var signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    void 횡보_데이터에서_밴드내_HOLD() {
        var candles = TestDataHelper.createRangeCandles(30, new BigDecimal("50000000"));
        var signal = strategy.evaluate(candles, Map.of());
        // 횡보 시 가격이 밴드 안에 있으므로 HOLD
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    void 급락시_하단밴드_이탈_BUY() {
        // 안정적 횡보 후 급락
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 20; i++) {
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price).high(price.add(BigDecimal.valueOf(50000)))
                    .low(price.subtract(BigDecimal.valueOf(50000)))
                    .close(price).volume(BigDecimal.valueOf(100))
                    .build());
        }
        // 급락
        BigDecimal crashPrice = price.subtract(new BigDecimal("2000000"));
        candles.add(Candle.builder()
                .time(base.plus(20, ChronoUnit.HOURS))
                .open(price).high(price).low(crashPrice).close(crashPrice)
                .volume(BigDecimal.valueOf(500))
                .build());

        var signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.BUY);
    }

    @Test
    void 포화깊이_얕을수록_강도_높음_재스케일_검증() {
        // 동일 밴드 이탈에 대해 strengthSaturationDepth가 얕을수록 강도가 커야 한다.
        // 기존 공식(깊이=1.0 상당)에서는 밴드 근접 신호의 강도가 구조적으로 낮았다 —
        // 재스케일이 살아있고 파라미터로 제어됨을 고정한다.
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");
        for (int i = 0; i < 20; i++) {
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price).high(price.add(BigDecimal.valueOf(50000)))
                    .low(price.subtract(BigDecimal.valueOf(50000)))
                    .close(price).volume(BigDecimal.valueOf(100))
                    .build());
        }
        // 소폭 하락 — 하단 밴드 근접(밴드를 극단적으로 뚫지는 않는 중간 이탈)
        BigDecimal dipPrice = price.subtract(new BigDecimal("120000"));
        candles.add(Candle.builder()
                .time(base.plus(20, ChronoUnit.HOURS))
                .open(price).high(price).low(dipPrice).close(dipPrice)
                .volume(BigDecimal.valueOf(200))
                .build());

        var shallow = strategy.evaluate(candles, Map.of("strengthSaturationDepth", 0.35));
        var deep    = strategy.evaluate(candles, Map.of("strengthSaturationDepth", 5.0));

        assertThat(shallow.getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(shallow.getStrength()).isGreaterThan(deep.getStrength());
    }
}
