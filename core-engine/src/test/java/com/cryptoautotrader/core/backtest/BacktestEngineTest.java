package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestEngineTest {

    private final BacktestEngine engine = new BacktestEngine();

    @Test
    void 백테스트_실행_결과_반환() {
        List<Candle> candles = createSimpleCandles(50);

        BacktestConfig config = BacktestConfig.builder()
                .strategyName("EMA_CROSS")
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.1"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(Map.of("fastPeriod", 5, "slowPeriod", 15))
                .build();

        BacktestResult result = engine.run(config, candles);

        assertThat(result).isNotNull();
        assertThat(result.getConfig()).isEqualTo(config);
        assertThat(result.getTrades()).isNotNull();
        assertThat(result.getMetrics()).isNotNull();
    }

    @Test
    void FillSimulation_활성화시_정상실행() {
        List<Candle> candles = createSimpleCandles(50);

        BacktestConfig config = BacktestConfig.builder()
                .strategyName("VWAP")
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .strategyParams(Map.of("thresholdPct", 0.5))
                .fillSimulationEnabled(true)
                .impactFactor(new BigDecimal("0.1"))
                .fillRatio(new BigDecimal("0.3"))
                .build();

        BacktestResult result = engine.run(config, candles);
        assertThat(result).isNotNull();
        assertThat(result.getMetrics()).isNotNull();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("§15 백테스트 결정론 — 같은 입력 → 같은 트레이드 시퀀스")
    void determinism_sameInputProducesSameResult() {
        List<Candle> candles = createSimpleCandles(100);

        BacktestConfig config = BacktestConfig.builder()
                .strategyName("EMA_CROSS")
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.1"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(Map.of("fastPeriod", 5, "slowPeriod", 15))
                .build();

        BacktestResult r1 = engine.run(config, candles);
        BacktestResult r2 = engine.run(config, candles);

        assertThat(r1.getTrades().size()).isEqualTo(r2.getTrades().size());
        // 거래가 1건 이상인 경우 동일한 순서의 체결가·pnl 확인
        if (!r1.getTrades().isEmpty()) {
            for (int i = 0; i < r1.getTrades().size(); i++) {
                assertThat(r1.getTrades().get(i).getPrice())
                        .isEqualByComparingTo(r2.getTrades().get(i).getPrice());
                assertThat(r1.getTrades().get(i).getPnl())
                        .isEqualByComparingTo(r2.getTrades().get(i).getPnl());
            }
        }
        assertThat(r1.getMetrics().getTotalReturnPct())
                .isEqualByComparingTo(r2.getMetrics().getTotalReturnPct());
    }

    private List<Candle> createSimpleCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < count; i++) {
            double wave = Math.sin(i * 0.2) * 500000;
            BigDecimal close = price.add(BigDecimal.valueOf(wave));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(close.subtract(BigDecimal.valueOf(50000)))
                    .high(close.add(BigDecimal.valueOf(200000)))
                    .low(close.subtract(BigDecimal.valueOf(200000)))
                    .close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return candles;
    }
}
