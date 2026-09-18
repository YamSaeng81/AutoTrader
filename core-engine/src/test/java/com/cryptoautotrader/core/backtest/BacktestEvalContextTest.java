package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백테스트 평가 컨텍스트가 실거래 엔진과 같은 키를 전달하는지 검증한다.
 *
 * <p>LIVE/DYNAMIC/PAPER는 전략 평가 params에 {@code coinPair}와 {@code timeframe}을 함께 넣는다
 * (LiveTradingService 참조). BacktestEngine은 {@code coinPair}만 넣고 있어, COMPOSITE 계열이
 * WeightOverrideStore를 coin·regime 수준으로만 조회했다 — H1/M15별로 저장한 가중치 override가
 * 백테스트에서만 무시되어, 같은 전략·기간이라도 운영과 다른 가중치로 평가됐다.
 *
 * <p>세 엔진의 체결 오차 외 로직 동일성이 이 프로젝트의 전제이므로, 평가 컨텍스트 키는
 * 엔진 간 차이가 없어야 한다.
 */
class BacktestEvalContextTest {

    private final BacktestEngine engine = new BacktestEngine();

    /** 전략이 실제로 받은 params를 그대로 보관하는 프로브. 신호는 항상 HOLD. */
    private static class ParamCapturingStrategy implements Strategy {
        private Map<String, Object> lastParams;

        @Override
        public String getName() {
            return "PARAM_PROBE";
        }

        @Override
        public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            lastParams = params;
            return StrategySignal.hold("probe");
        }

        @Override
        public int getMinimumCandleCount() {
            return 5;
        }
    }

    @Test
    void 평가_params에_coinPair와_timeframe이_모두_주입된다() {
        List<Candle> candles = createCandles(30);
        ParamCapturingStrategy probe = new ParamCapturingStrategy();

        BacktestConfig config = BacktestConfig.builder()
                .strategyName("PARAM_PROBE")
                .coinPair("KRW-ETH")
                .timeframe("M15")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .strategyParams(Map.of())
                .build();

        engine.run(config, candles, probe);

        assertThat(probe.lastParams).isNotNull();
        assertThat(probe.lastParams).containsEntry("coinPair", "KRW-ETH");
        assertThat(probe.lastParams)
                .as("timeframe이 없으면 코인·레짐 수준 가중치로 폴백해 운영과 다른 평가가 된다")
                .containsEntry("timeframe", "M15");
    }

    @Test
    void timeframe이_null이면_키를_넣지_않는다() {
        List<Candle> candles = createCandles(30);
        ParamCapturingStrategy probe = new ParamCapturingStrategy();

        BacktestConfig config = BacktestConfig.builder()
                .strategyName("PARAM_PROBE")
                .coinPair("KRW-ETH")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .strategyParams(Map.of())
                .build();

        engine.run(config, candles, probe);

        assertThat(probe.lastParams).isNotNull();
        // null을 넣으면 하위 전략의 파싱·조회 키가 "null" 문자열로 오염될 수 있다
        assertThat(probe.lastParams).doesNotContainKey("timeframe");
    }

    private List<Candle> createCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");

        for (int i = 0; i < count; i++) {
            BigDecimal p = price.add(BigDecimal.valueOf(i * 10000L));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(p)
                    .high(p.add(new BigDecimal("50000")))
                    .low(p.subtract(new BigDecimal("50000")))
                    .close(p)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return candles;
    }
}
