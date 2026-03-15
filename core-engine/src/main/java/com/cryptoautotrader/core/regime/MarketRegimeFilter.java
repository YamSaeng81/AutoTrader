package com.cryptoautotrader.core.regime;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 시장 상태(MarketRegime)에 따른 전략 적합성 필터.
 *
 * <pre>
 * TREND       : 추세 추종 전략 활성 (EMA_CROSS, MACD, SUPERTREND, ATR_BREAKOUT)
 *               횡보/역추세 전략 비활성 (GRID, VWAP, BOLLINGER)
 * RANGE       : 역추세/횡보 전략 활성 (VWAP, BOLLINGER, GRID, RSI, ORDERBOOK_IMBALANCE, STOCHASTIC_RSI)
 *               추세 추종 전략 비활성 (EMA_CROSS, MACD, SUPERTREND, ATR_BREAKOUT)
 * VOLATILITY  : 변동성 돌파 + 역추세 활성 (ATR_BREAKOUT, RSI, ORDERBOOK_IMBALANCE, STOCHASTIC_RSI)
 *               안정적 진입 전략 비활성 (GRID, SUPERTREND)
 * TRANSITIONAL: 직전 상태 유지 (suitable/unsuitable 없음 → 변경 없이 현 상태 유지)
 * </pre>
 */
public class MarketRegimeFilter {

    private static final Map<MarketRegime, Set<String>> SUITABLE   = new EnumMap<>(MarketRegime.class);
    private static final Map<MarketRegime, Set<String>> UNSUITABLE = new EnumMap<>(MarketRegime.class);

    static {
        // TREND
        SUITABLE.put(MarketRegime.TREND, Set.of(
                "EMA_CROSS", "MACD", "SUPERTREND", "ATR_BREAKOUT"
        ));
        UNSUITABLE.put(MarketRegime.TREND, Set.of(
                "GRID", "VWAP", "BOLLINGER"
        ));

        // RANGE
        SUITABLE.put(MarketRegime.RANGE, Set.of(
                "VWAP", "BOLLINGER", "GRID", "RSI", "ORDERBOOK_IMBALANCE", "STOCHASTIC_RSI"
        ));
        UNSUITABLE.put(MarketRegime.RANGE, Set.of(
                "EMA_CROSS", "MACD", "SUPERTREND", "ATR_BREAKOUT"
        ));

        // VOLATILITY (이전 VOLATILE)
        SUITABLE.put(MarketRegime.VOLATILITY, Set.of(
                "ATR_BREAKOUT", "RSI", "ORDERBOOK_IMBALANCE", "STOCHASTIC_RSI"
        ));
        UNSUITABLE.put(MarketRegime.VOLATILITY, Set.of(
                "GRID", "SUPERTREND"
        ));

        // TRANSITIONAL: 직전 Regime 유지 → 전략 자동 전환 없음 (빈 집합)
        SUITABLE.put(MarketRegime.TRANSITIONAL, Set.of());
        UNSUITABLE.put(MarketRegime.TRANSITIONAL, Set.of());
    }

    private MarketRegimeFilter() {}

    public static boolean isSuitable(MarketRegime regime, String strategyName) {
        Set<String> suitable = SUITABLE.get(regime);
        return suitable != null && suitable.contains(strategyName);
    }

    public static boolean isUnsuitable(MarketRegime regime, String strategyName) {
        Set<String> unsuitable = UNSUITABLE.get(regime);
        return unsuitable != null && unsuitable.contains(strategyName);
    }

    public static Set<String> getSuitableStrategies(MarketRegime regime) {
        return SUITABLE.getOrDefault(regime, Set.of());
    }

    public static Set<String> getUnsuitableStrategies(MarketRegime regime) {
        return UNSUITABLE.getOrDefault(regime, Set.of());
    }
}
