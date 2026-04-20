package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StrategySelector — Regime별 전략 그룹 선택")
class StrategySelectorTest {

    @Test
    @DisplayName("TREND: COMPOSITE_BREAKOUT(0.65) + COMPOSITE_MOMENTUM(0.35)")
    void trend_strategies() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.TREND);
        assertThat(list).hasSize(2);
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("COMPOSITE_BREAKOUT", "COMPOSITE_MOMENTUM");
        assertThat(list.get(0).getWeight()).isEqualTo(0.65);
        assertThat(list.get(1).getWeight()).isEqualTo(0.35);
    }

    @Test
    @DisplayName("RANGE: COMPOSITE_MOMENTUM(0.60) + COMPOSITE_BREAKOUT(0.40)")
    void range_strategies() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.RANGE);
        assertThat(list).hasSize(2);
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("COMPOSITE_MOMENTUM", "COMPOSITE_BREAKOUT");
        assertThat(list.get(0).getWeight()).isEqualTo(0.60);
        assertThat(list.get(1).getWeight()).isEqualTo(0.40);
    }

    @Test
    @DisplayName("VOLATILITY: COMPOSITE_BREAKOUT(0.70) + COMPOSITE_MOMENTUM(0.30)")
    void volatility_strategies() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.VOLATILITY);
        assertThat(list).hasSize(2);
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("COMPOSITE_BREAKOUT", "COMPOSITE_MOMENTUM");
        assertThat(list.get(0).getWeight()).isEqualTo(0.70);
        assertThat(list.get(1).getWeight()).isEqualTo(0.30);
    }

    @Test
    @DisplayName("TRANSITIONAL(prev=TREND): TREND 전략 × 0.5 가중치 축소")
    void transitional_prev_trend() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.TRANSITIONAL, MarketRegime.TREND);
        assertThat(list).hasSize(2);
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("COMPOSITE_BREAKOUT", "COMPOSITE_MOMENTUM");
        assertThat(list.get(0).getWeight()).isEqualTo(0.65 * 0.5);  // 0.325
        assertThat(list.get(1).getWeight()).isEqualTo(0.35 * 0.5);  // 0.175
    }

    @Test
    @DisplayName("TRANSITIONAL(prev=RANGE): RANGE 전략 × 0.5 가중치 축소")
    void transitional_prev_range() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.TRANSITIONAL, MarketRegime.RANGE);
        assertThat(list).hasSize(2);
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("COMPOSITE_MOMENTUM", "COMPOSITE_BREAKOUT");
        assertThat(list.get(0).getWeight()).isEqualTo(0.60 * 0.5);  // 0.30
    }

    @Test
    @DisplayName("TRANSITIONAL(prev=TRANSITIONAL): 무한 재귀 방지 — RANGE 폴백")
    void transitional_prev_transitional_noInfiniteRecursion() {
        List<WeightedStrategy> list = StrategySelector.select(MarketRegime.TRANSITIONAL, MarketRegime.TRANSITIONAL);
        assertThat(list).isNotEmpty();
        // RANGE 폴백 × 0.5
        assertThat(list).extracting(ws -> ws.getStrategy().getName())
                .containsExactly("COMPOSITE_MOMENTUM", "COMPOSITE_BREAKOUT");
    }

    @Test
    @DisplayName("WeightedStrategy.withReducedWeight() — 체인 호출")
    void weightedStrategy_reducedWeight() {
        List<WeightedStrategy> trend = StrategySelector.select(MarketRegime.TREND);
        WeightedStrategy first = trend.get(0);  // COMPOSITE_BREAKOUT 0.65
        WeightedStrategy reduced = first.withReducedWeight(0.5);

        assertThat(reduced.getWeight()).isEqualTo(0.65 * 0.5);
        assertThat(reduced.getStrategy().getName()).isEqualTo("COMPOSITE_BREAKOUT");
    }
}
