package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StrategySelector — Regime별 전략 그룹 선택")
class StrategySelectorTest {

    @Test
    @DisplayName("TREND: SUPERTREND(0.5) + EMA_CROSS(0.3) + ATR_BREAKOUT(0.2)")
    void trend_strategies() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.TREND);
        assertThat(list).hasSize(3);
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("SUPERTREND", "EMA_CROSS", "ATR_BREAKOUT");
        assertThat(list.get(0).getWeight()).isEqualTo(0.5);
        assertThat(list.get(1).getWeight()).isEqualTo(0.3);
        assertThat(list.get(2).getWeight()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("RANGE: BOLLINGER(0.4) + RSI(0.4) + GRID(0.2)")
    void range_strategies() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.RANGE);
        assertThat(list).hasSize(3);
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("BOLLINGER", "RSI", "GRID");
    }

    @Test
    @DisplayName("VOLATILITY: ATR_BREAKOUT(0.6) + STOCHASTIC_RSI(0.4)")
    void volatility_strategies() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.VOLATILITY);
        assertThat(list).hasSize(2);
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("ATR_BREAKOUT", "STOCHASTIC_RSI");
        assertThat(list.get(0).getWeight()).isEqualTo(0.6);
        assertThat(list.get(1).getWeight()).isEqualTo(0.4);
    }

    @Test
    @DisplayName("TRANSITIONAL(prev=TREND): TREND 전략 × 0.5 가중치 축소")
    void transitional_prev_trend() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.TRANSITIONAL, MarketRegime.TREND);
        assertThat(list).hasSize(3);
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("SUPERTREND", "EMA_CROSS", "ATR_BREAKOUT");
        assertThat(list.get(0).getWeight()).isEqualTo(0.5 * 0.5);  // 0.25
        assertThat(list.get(1).getWeight()).isEqualTo(0.3 * 0.5);  // 0.15
        assertThat(list.get(2).getWeight()).isEqualTo(0.2 * 0.5);  // 0.10
    }

    @Test
    @DisplayName("TRANSITIONAL(prev=RANGE): RANGE 전략 × 0.5 가중치 축소")
    void transitional_prev_range() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.TRANSITIONAL, MarketRegime.RANGE);
        assertThat(list).hasSize(3);
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("BOLLINGER", "RSI", "GRID");
        assertThat(list.get(0).getWeight()).isEqualTo(0.4 * 0.5);  // 0.2
    }

    @Test
    @DisplayName("TRANSITIONAL(prev=TRANSITIONAL): 무한 재귀 방지 — RANGE 폴백")
    void transitional_prev_transitional_noInfiniteRecursion() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.TRANSITIONAL, MarketRegime.TRANSITIONAL);
        assertThat(list).isNotEmpty();
        // RANGE 폴백 × 0.5
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("BOLLINGER", "RSI", "GRID");
    }

    @Test
    @DisplayName("WeightedStrategy.withReducedWeight() — 체인 호출")
    void weightedStrategy_reducedWeight() {
        List<WeightedStrategy> trend = StrategySelector.select(MarketRegime.TREND);
        WeightedStrategy first = trend.get(0);  // SUPERTREND 0.5
        WeightedStrategy reduced = first.withReducedWeight(0.5);

        assertThat(reduced.getWeight()).isEqualTo(0.25);
        assertThat(reduced.getStrategy().getName()).isEqualTo("SUPERTREND");
    }
}
