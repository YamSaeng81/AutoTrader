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
    // 각 메서드는 WeightOverrideStore에 해당 regime 오버라이드가 있으면 동적 가중치를 사용한다.
    // 오버라이드가 없으면 하드코딩 기본값을 그대로 사용해 기존 동작을 보장한다.

    private static List<WeightedStrategy> trend() {
        final String r = "TREND";
        return List.of(
                ws(r, "SUPERTREND",   0.5),
                ws(r, "EMA_CROSS",    0.3),
                ws(r, "ATR_BREAKOUT", 0.2)
        );
    }

    private static List<WeightedStrategy> range() {
        final String r = "RANGE";
        return List.of(
                ws(r, "BOLLINGER", 0.4),
                ws(r, "VWAP",      0.4),
                ws(r, "GRID",      0.2)
        );
    }

    private static List<WeightedStrategy> volatility() {
        final String r = "VOLATILITY";
        return List.of(
                ws(r, "ATR_BREAKOUT",  0.6),
                ws(r, "VOLUME_DELTA",  0.4)
        );
    }

    /** WeightOverrideStore에서 가중치를 조회해 WeightedStrategy를 생성한다. */
    private static WeightedStrategy ws(String regime, String name, double defaultWeight) {
        double weight = WeightOverrideStore.get(regime, name, defaultWeight);
        return new WeightedStrategy(StrategyRegistry.get(name), weight);
    }
}
