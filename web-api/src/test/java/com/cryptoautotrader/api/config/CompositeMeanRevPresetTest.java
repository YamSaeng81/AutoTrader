package com.cryptoautotrader.api.config;

import com.cryptoautotrader.core.selector.Ema200RegimeGate;
import com.cryptoautotrader.core.selector.RangeRegimeGate;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMPOSITE_MEANREV_BB 프리셋 계약 테스트 (2026-07-20).
 *
 * <p>동적 세션 6개가 전부 추세추종 계열이라 하락·횡보장에서 11일간 매수 0건이었던
 * 문제의 보완으로 추가된 평균회귀 프리셋의 게이트 계약을 고정한다:
 * EMA200 게이트 면제(하락 레짐 진입이 전제) + RANGE 게이트 비차단(횡보장이 주 무대).</p>
 */
@DisplayName("COMPOSITE_MEANREV_BB 프리셋 — 등록·게이트 계약")
class CompositeMeanRevPresetTest {

    @BeforeAll
    static void registerPresets() {
        new CompositePresetRegistrar().registerPresets();
    }

    @Test
    @DisplayName("레지스트리에 등록되고 stateless로 취급된다")
    void 등록_및_stateless() {
        Strategy strategy = StrategyRegistry.get("COMPOSITE_MEANREV_BB");
        assertThat(strategy.getName()).isEqualTo("COMPOSITE_MEANREV_BB");
        assertThat(StrategyRegistry.isStateful("COMPOSITE_MEANREV_BB")).isFalse();
    }

    @Test
    @DisplayName("EMA200 게이트 면제 + RANGE 게이트 비차단 (평균회귀 계약)")
    void 게이트_계약() {
        assertThat(Ema200RegimeGate.isExempt("COMPOSITE_MEANREV_BB")).isTrue();
        assertThat(RangeRegimeGate.isBlocked("COMPOSITE_MEANREV_BB")).isFalse();
    }

    @Test
    @DisplayName("캔들 부족 시 예외 없이 HOLD (스모크)")
    void 캔들부족_스모크() {
        Strategy strategy = StrategyRegistry.get("COMPOSITE_MEANREV_BB");
        StrategySignal signal = strategy.evaluate(List.of(), Map.of("coinPair", "KRW-BTC"));
        assertThat(signal.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }
}
