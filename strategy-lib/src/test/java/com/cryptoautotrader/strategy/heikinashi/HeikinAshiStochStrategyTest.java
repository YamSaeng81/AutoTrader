package com.cryptoautotrader.strategy.heikinashi;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
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

class HeikinAshiStochStrategyTest {

    private final HeikinAshiStochStrategy strategy = new HeikinAshiStochStrategy();

    @Test
    void 이름은_HEIKIN_ASHI_STOCH() {
        assertThat(strategy.getName()).isEqualTo("HEIKIN_ASHI_STOCH");
    }

    @Test
    void 최소_캔들수_확인() {
        assertThat(strategy.getMinimumCandleCount()).isEqualTo(205);
    }

    @Test
    void 데이터_부족시_HOLD() {
        List<Candle> candles = TestDataHelper.createUpTrendCandles(30, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of());
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("데이터 부족");
    }

    @Test
    void 신호_강도는_0이상_100이하() {
        List<Candle> candles = TestDataHelper.createUpTrendCandles(60, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "emaPeriod", 20, "rsiPeriod", 5, "stochPeriod", 5, "signalPeriod", 2));
        assertThat(signal.getStrength()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
    }

    @Test
    void HeikinAshi_변환_검증() {
        List<Candle> raw = TestDataHelper.createUpTrendCandles(10, new BigDecimal("100"));
        List<Candle> ha = IndicatorUtils.heikinAshi(raw);

        assertThat(ha).hasSameSizeAs(raw);
        // 첫 HA 시가 = (시 + 종) / 2
        Candle first = raw.get(0);
        BigDecimal expectedFirstOpen = first.getOpen().add(first.getClose())
                .divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.HALF_UP);
        assertThat(ha.get(0).getOpen()).isEqualByComparingTo(expectedFirstOpen);
        // HA 고가 >= HA 종가/시가, HA 저가 <= HA 종가/시가 (불변식)
        for (Candle c : ha) {
            assertThat(c.getHigh()).isGreaterThanOrEqualTo(c.getOpen().max(c.getClose()));
            assertThat(c.getLow()).isLessThanOrEqualTo(c.getOpen().min(c.getClose()));
        }
    }

    @Test
    void 롱_진입_조건_충족시_BUY_및_손익비_제안() {
        // 상승추세(EMA 위) → 짧은 눌림(StochRSI K가 D 아래로) → 강한 양봉 반등(골든크로스 + 양봉)
        List<Candle> candles = buildLongSetup();

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "emaPeriod", 20,
                "rsiPeriod", 5,
                "stochPeriod", 5,
                "signalPeriod", 2,
                "maxWickRatio", 1.0,
                "stopLossPct", 1.5,
                "takeProfitPct", 3.0));

        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.BUY);
        BigDecimal entry = candles.get(candles.size() - 1).getClose();
        // 손절 < 진입가 < 익절, 손익비 1:2
        assertThat(signal.getSuggestedStopLoss()).isLessThan(entry);
        assertThat(signal.getSuggestedTakeProfit()).isGreaterThan(entry);
        BigDecimal risk   = entry.subtract(signal.getSuggestedStopLoss());
        BigDecimal reward = signal.getSuggestedTakeProfit().subtract(entry);
        // reward ≈ 2 × risk
        assertThat(reward).isGreaterThan(risk);
    }

    /** 상승추세 + 눌림 + 강한 반등 양봉으로 끝나는 캔들 세트 */
    private static List<Candle> buildLongSetup() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("100");
        int i = 0;

        // 1) 완만한 상승 40캔들 — 가격을 EMA 위로, StochRSI 고점 형성
        for (int k = 0; k < 40; k++, i++) {
            BigDecimal open = price;
            BigDecimal close = price.add(price.multiply(new BigDecimal("0.006")));
            BigDecimal high = close.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low = open.subtract(price.multiply(new BigDecimal("0.001")));
            candles.add(candle(base, i, open, high, low, close));
            price = close;
        }
        // 2) 눌림 3캔들 — StochRSI K를 D 아래로 떨어뜨림
        for (int k = 0; k < 3; k++, i++) {
            BigDecimal open = price;
            BigDecimal close = price.subtract(price.multiply(new BigDecimal("0.005")));
            BigDecimal high = open.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low = close.subtract(price.multiply(new BigDecimal("0.001")));
            candles.add(candle(base, i, open, high, low, close));
            price = close;
        }
        // 3) 직전 캔들은 거의 도지(몸통 작음) — 최종 양봉이 "몸통 길어짐" 조건을 만족하도록
        {
            BigDecimal open = price;
            BigDecimal close = price.subtract(price.multiply(new BigDecimal("0.0003")));
            BigDecimal high = open.add(price.multiply(new BigDecimal("0.0003")));
            BigDecimal low = close.subtract(price.multiply(new BigDecimal("0.0003")));
            candles.add(candle(base, i, open, high, low, close));
            price = close;
            i++;
        }
        // 4) 강한 반등 양봉 — 직전보다 긴 몸통, 골든크로스 유발, 아래꼬리 최소화
        BigDecimal open = price;
        BigDecimal close = price.add(price.multiply(new BigDecimal("0.035")));
        BigDecimal high = close.add(price.multiply(new BigDecimal("0.0005")));
        BigDecimal low = open;
        candles.add(candle(base, i, open, high, low, close));

        return candles;
    }

    private static Candle candle(Instant base, int i, BigDecimal o, BigDecimal h,
                                 BigDecimal l, BigDecimal c) {
        return Candle.builder()
                .time(base.plus(i, ChronoUnit.HOURS))
                .open(o).high(h).low(l).close(c)
                .volume(BigDecimal.valueOf(100))
                .build();
    }
}
