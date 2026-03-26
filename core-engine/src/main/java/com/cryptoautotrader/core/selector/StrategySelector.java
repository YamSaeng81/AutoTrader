package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.strategy.StrategyRegistry;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MarketRegime에 따라 활성화할 전략 목록과 가중치를 결정한다.
 *
 * <pre>
 * TREND       : SUPERTREND(0.5) + EMA_CROSS(0.3) + ATR_BREAKOUT(0.2)
 * RANGE       : BOLLINGER(0.4)  + VWAP(0.4)       + GRID(0.2)   ← RSI 제거(일관 마이너스), VWAP 대체
 * VOLATILITY  : ATR_BREAKOUT(0.6) + VOLUME_DELTA(0.4)           ← STOCHASTIC_RSI(BLOCKED) 제거, VD 대체
 * TRANSITIONAL: 직전 Regime 전략 그룹 × 0.5 (포지션 축소)
 * </pre>
 */
public final class StrategySelector {

    private StrategySelector() {}

    /**
     * 현재 Regime에 맞는 WeightedStrategy 목록을 반환한다.
     *
     * @param current  현재 MarketRegime
     * @param previous TRANSITIONAL 시 사용할 이전 Regime (non-TRANSITIONAL이면 무시됨)
     */
    public static List<WeightedStrategy> select(MarketRegime current, MarketRegime previous) {
        return switch (current) {
            case TREND       -> trend();
            case RANGE       -> range();
            case VOLATILITY  -> volatility();
            case TRANSITIONAL -> {
                // 직전 Regime 전략 그룹을 0.5 가중치로 축소 — 재귀 시 TRANSITIONAL 제외
                MarketRegime base = (previous == MarketRegime.TRANSITIONAL) ? MarketRegime.RANGE : previous;
                yield select(base, base).stream()
                        .map(ws -> ws.withReducedWeight(0.5))
                        .collect(Collectors.toList());
            }
        };
    }

    /** current == previous 인 일반 호출용 오버로드 */
    public static List<WeightedStrategy> select(MarketRegime current) {
        return select(current, current);
    }

    // ── 전략 그룹 정의 ───────────────────────────────────────────────────

    private static List<WeightedStrategy> trend() {
        return List.of(
                new WeightedStrategy(StrategyRegistry.get("SUPERTREND"),    0.5),
                new WeightedStrategy(StrategyRegistry.get("EMA_CROSS"),     0.3),
                new WeightedStrategy(StrategyRegistry.get("ATR_BREAKOUT"),  0.2)
        );
    }

    private static List<WeightedStrategy> range() {
        return List.of(
                new WeightedStrategy(StrategyRegistry.get("BOLLINGER"),    0.4),
                new WeightedStrategy(StrategyRegistry.get("VWAP"),         0.4),
                new WeightedStrategy(StrategyRegistry.get("GRID"),         0.2)
        );
    }

    private static List<WeightedStrategy> volatility() {
        return List.of(
                new WeightedStrategy(StrategyRegistry.get("ATR_BREAKOUT"),   0.6),
                new WeightedStrategy(StrategyRegistry.get("VOLUME_DELTA"),   0.4)
        );
    }
}
