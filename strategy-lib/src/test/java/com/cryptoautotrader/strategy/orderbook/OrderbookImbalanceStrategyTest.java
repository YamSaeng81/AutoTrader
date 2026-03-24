package com.cryptoautotrader.strategy.orderbook;

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

class OrderbookImbalanceStrategyTest {

    private final OrderbookImbalanceStrategy strategy = new OrderbookImbalanceStrategy();

    @Test
    void 이름은_ORDERBOOK_IMBALANCE() {
        assertThat(strategy.getName()).isEqualTo("ORDERBOOK_IMBALANCE");
    }

    @Test
    void 데이터_부족시_HOLD() {
        List<Candle> candles = TestDataHelper.createRangeCandles(3, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of("lookback", 5));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("데이터 부족");
    }

    @Test
    void 최소_캔들수_확인() {
        assertThat(strategy.getMinimumCandleCount()).isEqualTo(15);
    }

    // ========== 실시간 호가 모드 테스트 ==========

    @Test
    void 실시간_매수_우세_BUY() {
        List<Candle> candles = TestDataHelper.createRangeCandles(15, new BigDecimal("50000000"));
        // 매수량 70%, 매도량 30% → 불균형비율 0.7 > 임계값 0.65 → BUY
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "imbalanceThreshold", 0.65,
                "bidVolume", 700.0,
                "askVolume", 300.0
        ));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(signal.getReason()).contains("매수 우세");
    }

    @Test
    void 실시간_매도_우세_SELL() {
        List<Candle> candles = TestDataHelper.createRangeCandles(15, new BigDecimal("50000000"));
        // 매수량 25%, 매도량 75% → 불균형비율 0.25 < (1-0.65=0.35) → SELL
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "imbalanceThreshold", 0.65,
                "bidVolume", 250.0,
                "askVolume", 750.0
        ));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.SELL);
        assertThat(signal.getReason()).contains("매도 우세");
    }

    @Test
    void 실시간_균형_HOLD() {
        List<Candle> candles = TestDataHelper.createRangeCandles(15, new BigDecimal("50000000"));
        // 매수 50%, 매도 50% → 균형 → HOLD
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "imbalanceThreshold", 0.65,
                "bidVolume", 500.0,
                "askVolume", 500.0
        ));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    void 실시간_임계값_경계에서_BUY() {
        List<Candle> candles = TestDataHelper.createRangeCandles(15, new BigDecimal("50000000"));
        // 불균형비율 = 0.65 (임계값과 동일) → BUY (compareTo >= 0)
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "imbalanceThreshold", 0.65,
                "bidVolume", 650.0,
                "askVolume", 350.0
        ));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.BUY);
    }

    // ========== 캔들 기반 근사치 모드 테스트 ==========

    @Test
    void 캔들_상승_Delta_가속_BUY() {
        // lookback=6: 전반부(0-2) 1% 상승, 후반부(3-5) 5% 상승 → Delta 가속 + 매수 우세 → BUY
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        // 앞 9개 캔들 중 lookback=6 기준 전반부(3개)는 1% 상승
        for (int i = 0; i < 12; i++) {
            String riseStr;
            int lookbackIdx = i - (12 - 6); // lookback 내 인덱스
            if (lookbackIdx >= 0 && lookbackIdx < 3) {
                riseStr = "0.01"; // 전반부: 1% 상승
            } else if (lookbackIdx >= 3) {
                riseStr = "0.05"; // 후반부: 5% 상승
            } else {
                riseStr = "0.02"; // lookback 이전: 중간 상승
            }
            BigDecimal open = price;
            BigDecimal close = price.add(price.multiply(new BigDecimal(riseStr)));
            BigDecimal high = close.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low = open.subtract(price.multiply(new BigDecimal("0.001")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "imbalanceThreshold", 0.55,
                "lookback", 6
        ));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(signal.getReason()).contains("Delta가속");
    }

    @Test
    void 캔들_하락_Delta_가속_SELL() {
        // lookback=6: 전반부(0-2) 1% 하락, 후반부(3-5) 5% 하락 → Delta 가속(매도) + 매도 우세 → SELL
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 12; i++) {
            String fallStr;
            int lookbackIdx = i - (12 - 6);
            if (lookbackIdx >= 0 && lookbackIdx < 3) {
                fallStr = "0.01"; // 전반부: 1% 하락
            } else if (lookbackIdx >= 3) {
                fallStr = "0.05"; // 후반부: 5% 하락
            } else {
                fallStr = "0.02";
            }
            BigDecimal open = price;
            BigDecimal close = price.subtract(price.multiply(new BigDecimal(fallStr)));
            BigDecimal high = open.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low = close.subtract(price.multiply(new BigDecimal("0.001")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "imbalanceThreshold", 0.55,
                "lookback", 6
        ));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.SELL);
        assertThat(signal.getReason()).contains("Delta가속");
    }

    @Test
    void 캔들_매수_우세이나_Delta_감속시_HOLD() {
        // 모두 동일한 3% 상승 캔들 → 불균형은 존재하지만 Delta가 평탄(가속 없음) → HOLD
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < 10; i++) {
            BigDecimal open = price;
            BigDecimal close = price.add(price.multiply(new BigDecimal("0.03")));
            BigDecimal high = close.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low = open.subtract(price.multiply(new BigDecimal("0.001")));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }

        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "imbalanceThreshold", 0.55,
                "lookback", 5
        ));
        // 동일 패턴 반복 → Delta 가속 없음 → HOLD 격하
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(signal.getReason()).contains("Delta 감속");
    }

    @Test
    void 신호_강도가_0이상_100이하_실시간() {
        List<Candle> candles = TestDataHelper.createRangeCandles(15, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "bidVolume", 800.0,
                "askVolume", 200.0
        ));
        assertThat(signal.getStrength()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
    }

    @Test
    void 신호_강도가_0이상_100이하_캔들() {
        List<Candle> candles = TestDataHelper.createUpTrendCandles(10, new BigDecimal("50000000"));
        StrategySignal signal = strategy.evaluate(candles, Map.of(
                "imbalanceThreshold", 0.55,
                "lookback", 5
        ));
        assertThat(signal.getStrength()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
    }
}
