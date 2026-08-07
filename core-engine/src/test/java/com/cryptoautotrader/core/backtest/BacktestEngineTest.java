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

    /**
     * 신호 반전(invertSignals) — 2026-08-07 신설.
     *
     * <p>실전 신호 기대값이 체계적으로 음수라(BUY 후 24h −4.81%) "신호가 방향만 반대인가"를
     * 판정하기 위한 연구용 플래그. 기본값이 false여야 하고(실수로 켜지면 실거래 백테스트가
     * 통째로 오염된다), 켰을 때 실제로 다른 매매가 나와야 의미가 있다.
     */
    @Test
    void invertSignals_기본값은_false다() {
        BacktestConfig config = BacktestConfig.builder()
                .strategyName("EMA_CROSS")
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .strategyParams(Map.of())
                .build();

        assertThat(config.isInvertSignals()).isFalse();
    }

    @Test
    void invertSignals_켜면_정방향과_다른_매매가_나온다() {
        List<Candle> candles = createSimpleCandles(120);
        Map<String, Object> params = Map.of("fastPeriod", 5, "slowPeriod", 15);

        BacktestResult normal = engine.run(invertConfig(candles, params, false), candles);
        BacktestResult inverted = engine.run(invertConfig(candles, params, true), candles);

        // 정방향이 거래를 냈는데 반전이 완전히 동일하다면 플래그가 배선되지 않은 것이다.
        assertThat(normal.getTrades()).isNotEmpty();
        assertThat(inverted.getTrades())
                .as("반전 시 매매 결과가 정방향과 달라야 한다")
                .isNotEqualTo(normal.getTrades());

        // 반전된 진입은 사유에 [반전] 접두어가 붙어 로그에서 식별 가능해야 한다.
        boolean hasInvertedReason = inverted.getTrades().stream()
                .anyMatch(t -> t.getSignalReason() != null && t.getSignalReason().contains("[반전]"));
        assertThat(hasInvertedReason)
                .as("반전 실험임을 거래 로그에서 식별할 수 있어야 한다")
                .isTrue();
    }

    private BacktestConfig invertConfig(List<Candle> candles, Map<String, Object> params, boolean invert) {
        return BacktestConfig.builder()
                .strategyName("EMA_CROSS")
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.1"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(params)
                .invertSignals(invert)
                .build();
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
