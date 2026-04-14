package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.strategy.StrategyRegistry;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MarketRegime에 따라 활성화할 Composite 전략과 가중치를 결정한다.
 *
 * <pre>
 * TREND       : COMPOSITE_BREAKOUT(0.65) + COMPOSITE_MOMENTUM(0.35)
 *               — ATR/VD/RSI/EMA 기반 돌파 vs MACD/VWAP/GRID 기반 모멘텀
 *               — 백테스트 근거: BREAKOUT BTC +104%, SOL +65%, ETH +39%
 * RANGE       : COMPOSITE_MOMENTUM(0.60) + COMPOSITE_BREAKOUT(0.40)
 *               — VWAP·GRID(레인지 친화) vs ATR 돌파(레인지서 약화)
 * VOLATILITY  : COMPOSITE_BREAKOUT(0.70) + COMPOSITE_MOMENTUM(0.30)
 *               — ATR 기반 전략이 변동성 장에 최적화
 * TRANSITIONAL: 직전 Regime 전략 그룹 × 0.5 (포지션 축소)
 * </pre>
 *
 * <p>WeightOverrideStore에 해당 regime 오버라이드가 있으면 동적 가중치를 사용한다.
 * 오버라이드는 StrategyWeightOptimizer가 30일 신호 품질 데이터를 기반으로 주기적으로 갱신한다.
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
        // COMPOSITE_BREAKOUT: ATR×0.4 + VD×0.3 + RSI×0.2 + EMA×0.1 (3년 BTC +104%, SOL +65%, ETH +39%)
        // COMPOSITE_MOMENTUM: MACD×0.5 + VWAP×0.3 + GRID×0.2 (ETH +54%, SOL +60%)
        return List.of(
                ws(r, "COMPOSITE_BREAKOUT",  0.65),
                ws(r, "COMPOSITE_MOMENTUM",  0.35)
        );
    }

    private static List<WeightedStrategy> range() {
        final String r = "RANGE";
        // 레인지 구간: VWAP·GRID(평균회귀·레인지) 비중이 높은 MOMENTUM이 유리
        return List.of(
                ws(r, "COMPOSITE_MOMENTUM",  0.60),
                ws(r, "COMPOSITE_BREAKOUT",  0.40)
        );
    }

    private static List<WeightedStrategy> volatility() {
        final String r = "VOLATILITY";
        // 변동성 장: ATR 기반 BREAKOUT 전략이 핵심
        return List.of(
                ws(r, "COMPOSITE_BREAKOUT",  0.70),
                ws(r, "COMPOSITE_MOMENTUM",  0.30)
        );
    }

    /** WeightOverrideStore에서 가중치를 조회해 WeightedStrategy를 생성한다. */
    private static WeightedStrategy ws(String regime, String name, double defaultWeight) {
        double weight = WeightOverrideStore.get(regime, name, defaultWeight);
        return new WeightedStrategy(StrategyRegistry.get(name), weight);
    }
}
