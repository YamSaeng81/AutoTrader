package com.cryptoautotrader.core.regime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarketRegimeFilter — 시장 상태별 전략 적합성 매핑")
class MarketRegimeFilterTest {

    // ── TREND ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TREND: EMA_CROSS, MACD, SUPERTREND, ATR_BREAKOUT 적합")
    void trend_suitableStrategies() {
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.TREND, "EMA_CROSS")).isTrue();
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.TREND, "MACD")).isTrue();
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.TREND, "SUPERTREND")).isTrue();
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.TREND, "ATR_BREAKOUT")).isTrue();
    }

    @Test
    @DisplayName("TREND: GRID, VWAP, BOLLINGER 비활성화 대상")
    void trend_unsuitableStrategies() {
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.TREND, "GRID")).isTrue();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.TREND, "VWAP")).isTrue();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.TREND, "BOLLINGER")).isTrue();
    }

    @Test
    @DisplayName("TREND: 추세 추종 전략은 비활성화 대상 아님")
    void trend_suitableIsNotUnsuitable() {
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.TREND, "EMA_CROSS")).isFalse();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.TREND, "MACD")).isFalse();
    }

    // ── RANGE ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RANGE: VWAP, BOLLINGER, GRID, RSI, ORDERBOOK_IMBALANCE 적합")
    void range_suitableStrategies() {
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.RANGE, "VWAP")).isTrue();
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.RANGE, "BOLLINGER")).isTrue();
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.RANGE, "GRID")).isTrue();
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.RANGE, "RSI")).isTrue();
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.RANGE, "ORDERBOOK_IMBALANCE")).isTrue();
    }

    @Test
    @DisplayName("RANGE: EMA_CROSS, MACD, SUPERTREND, ATR_BREAKOUT 비활성화 대상")
    void range_unsuitableStrategies() {
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.RANGE, "EMA_CROSS")).isTrue();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.RANGE, "MACD")).isTrue();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.RANGE, "SUPERTREND")).isTrue();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.RANGE, "ATR_BREAKOUT")).isTrue();
    }

    // ── VOLATILE ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("VOLATILE: ATR_BREAKOUT, RSI, ORDERBOOK_IMBALANCE 적합")
    void volatile_suitableStrategies() {
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.VOLATILE, "ATR_BREAKOUT")).isTrue();
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.VOLATILE, "RSI")).isTrue();
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.VOLATILE, "ORDERBOOK_IMBALANCE")).isTrue();
    }

    @Test
    @DisplayName("VOLATILE: GRID, SUPERTREND 비활성화 대상")
    void volatile_unsuitableStrategies() {
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.VOLATILE, "GRID")).isTrue();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.VOLATILE, "SUPERTREND")).isTrue();
    }

    @Test
    @DisplayName("VOLATILE: 적합 전략은 비활성화 대상 아님")
    void volatile_suitableIsNotUnsuitable() {
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.VOLATILE, "ATR_BREAKOUT")).isFalse();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.VOLATILE, "RSI")).isFalse();
    }

    // ── getSuitableStrategies / getUnsuitableStrategies ───────────────────

    @Test
    @DisplayName("getSuitableStrategies — TREND 반환 집합 크기 확인")
    void getSuitableStrategies_trend() {
        Set<String> suitable = MarketRegimeFilter.getSuitableStrategies(MarketRegime.TREND);
        assertThat(suitable).containsExactlyInAnyOrder("EMA_CROSS", "MACD", "SUPERTREND", "ATR_BREAKOUT");
    }

    @Test
    @DisplayName("getUnsuitableStrategies — RANGE 반환 집합 크기 확인")
    void getUnsuitableStrategies_range() {
        Set<String> unsuitable = MarketRegimeFilter.getUnsuitableStrategies(MarketRegime.RANGE);
        assertThat(unsuitable).containsExactlyInAnyOrder("EMA_CROSS", "MACD", "SUPERTREND", "ATR_BREAKOUT");
    }

    // ── STOCHASTIC_RSI (6번째 전략) ───────────────────────────────────────

    @Test
    @DisplayName("STOCHASTIC_RSI: RANGE에서 적합")
    void stochasticRsi_suitableInRange() {
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.RANGE, "STOCHASTIC_RSI")).isTrue();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.RANGE, "STOCHASTIC_RSI")).isFalse();
    }

    @Test
    @DisplayName("STOCHASTIC_RSI: VOLATILE에서 적합")
    void stochasticRsi_suitableInVolatile() {
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.VOLATILE, "STOCHASTIC_RSI")).isTrue();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.VOLATILE, "STOCHASTIC_RSI")).isFalse();
    }

    @Test
    @DisplayName("STOCHASTIC_RSI: TREND에서는 적합하지도 비적합하지도 않음 (중립)")
    void stochasticRsi_neutralInTrend() {
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.TREND, "STOCHASTIC_RSI")).isFalse();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.TREND, "STOCHASTIC_RSI")).isFalse();
    }

    @Test
    @DisplayName("알 수 없는 전략명은 적합하지도 부적합하지도 않음")
    void unknownStrategyName_returnsNeutral() {
        assertThat(MarketRegimeFilter.isSuitable(MarketRegime.TREND, "UNKNOWN_STRATEGY")).isFalse();
        assertThat(MarketRegimeFilter.isUnsuitable(MarketRegime.TREND, "UNKNOWN_STRATEGY")).isFalse();
    }
}
