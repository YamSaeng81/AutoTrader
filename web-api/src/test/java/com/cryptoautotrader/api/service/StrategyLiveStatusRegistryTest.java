package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 20260415_analy.md Tier 3 §11 — 전략 운영 가능 여부 매트릭스.
 *
 * <p>StrategyLiveStatusRegistry 의 ENABLED / BLOCKED / EXPERIMENTAL / DEPRECATED
 * 분류가 의도대로 동작하는지 검증한다.</p>
 */
class StrategyLiveStatusRegistryTest {

    private final StrategyLiveStatusRegistry registry = new StrategyLiveStatusRegistry();

    @Test
    @DisplayName("§11 ENABLED 전략 — isBlocked() false, readiness ENABLED")
    void enabledStrategies_areNotBlocked() {
        for (String name : new String[]{
                "COMPOSITE_BREAKOUT", "COMPOSITE_MOMENTUM",
                "COMPOSITE_MOMENTUM_ICHIMOKU", "COMPOSITE_MOMENTUM_ICHIMOKU_V2"}) {
            assertThat(registry.isBlocked(name))
                    .as(name + " should not be blocked")
                    .isFalse();
            assertThat(registry.getReadiness(name))
                    .as(name + " readiness should be ENABLED")
                    .isEqualTo(StrategyLiveStatusRegistry.LiveReadiness.ENABLED);
        }
    }

    @Test
    @DisplayName("§11 BLOCKED 전략 — isBlocked() true, 세션 생성 차단 대상")
    void blockedStrategies_areBlocked() {
        for (String name : new String[]{"STOCHASTIC_RSI", "MACD", "MACD_STOCH_BB"}) {
            assertThat(registry.isBlocked(name))
                    .as(name + " should be blocked")
                    .isTrue();
            assertThat(registry.getReadiness(name))
                    .as(name + " readiness should be BLOCKED")
                    .isEqualTo(StrategyLiveStatusRegistry.LiveReadiness.BLOCKED);
        }
    }

    @Test
    @DisplayName("§11 EXPERIMENTAL 전략 — isBlocked() false, readiness EXPERIMENTAL")
    void experimentalStrategies_areNotBlocked() {
        for (String name : new String[]{
                "VWAP", "EMA_CROSS", "BOLLINGER", "GRID",
                "RSI", "SUPERTREND", "ATR_BREAKOUT", "VOLUME_DELTA",
                "FAIR_VALUE_GAP", "COMPOSITE", "COMPOSITE_ETH"}) {
            assertThat(registry.isBlocked(name))
                    .as(name + " should not be blocked (EXPERIMENTAL)")
                    .isFalse();
            assertThat(registry.getReadiness(name))
                    .as(name + " readiness should be EXPERIMENTAL")
                    .isEqualTo(StrategyLiveStatusRegistry.LiveReadiness.EXPERIMENTAL);
        }
    }

    @Test
    @DisplayName("§11 DEPRECATED 전략 — isBlocked() true, readiness DEPRECATED")
    void deprecatedStrategies_areBlocked() {
        assertThat(registry.isBlocked("TEST_TIMED")).isTrue();
        assertThat(registry.getReadiness("TEST_TIMED"))
                .isEqualTo(StrategyLiveStatusRegistry.LiveReadiness.DEPRECATED);
    }

    @Test
    @DisplayName("§11 미등록 전략 — EXPERIMENTAL 기본값 반환")
    void unknownStrategy_defaultsToExperimental() {
        assertThat(registry.getReadiness("UNKNOWN_STRATEGY_XYZ"))
                .isEqualTo(StrategyLiveStatusRegistry.LiveReadiness.EXPERIMENTAL);
        assertThat(registry.isBlocked("UNKNOWN_STRATEGY_XYZ")).isFalse();
    }

    @Test
    @DisplayName("§11 getAll() — BLOCKED 전략 3건 이상 포함")
    void getAll_containsAllBlockedStrategies() {
        long blockedCount = registry.getAll().values().stream()
                .filter(e -> e.readiness() == StrategyLiveStatusRegistry.LiveReadiness.BLOCKED)
                .count();
        assertThat(blockedCount).isGreaterThanOrEqualTo(3);
    }
}
