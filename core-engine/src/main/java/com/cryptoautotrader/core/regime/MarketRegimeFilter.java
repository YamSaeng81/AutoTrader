package com.cryptoautotrader.core.regime;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 시장 상태(MarketRegime)에 따른 전략 적합성 필터.
 * <p>
 * 각 시장 상태에 맞는 전략(suitable)과 맞지 않는 전략(unsuitable)을 정의한다.
 * MarketRegimeAwareScheduler 가 이 정보를 참조해 전략을 자동 활성/비활성한다.
 *
 * <pre>
 * TREND    : 추세 추종 전략 활성 (EMA_CROSS, MACD, SUPERTREND, ATR_BREAKOUT)
 *            횡보/역추세 전략 비활성 (GRID, VWAP, BOLLINGER)
 * RANGE    : 역추세/횡보 전략 활성 (VWAP, BOLLINGER, GRID, RSI, ORDERBOOK_IMBALANCE, STOCHASTIC_RSI)
 *            추세 추종 전략 비활성 (EMA_CROSS, MACD, SUPERTREND, ATR_BREAKOUT)
 * VOLATILE : 변동성 돌파 + 역추세 활성 (ATR_BREAKOUT, RSI, ORDERBOOK_IMBALANCE, STOCHASTIC_RSI)
 *            안정적 진입 전략 비활성 (GRID, SUPERTREND)
 * </pre>
 */
public class MarketRegimeFilter {

    /** 시장 상태별 적합 전략 집합 */
    private static final Map<MarketRegime, Set<String>> SUITABLE = new EnumMap<>(MarketRegime.class);

    /** 시장 상태별 비활성화 전략 집합 */
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

        // VOLATILE
        SUITABLE.put(MarketRegime.VOLATILE, Set.of(
                "ATR_BREAKOUT", "RSI", "ORDERBOOK_IMBALANCE", "STOCHASTIC_RSI"
        ));
        UNSUITABLE.put(MarketRegime.VOLATILE, Set.of(
                "GRID", "SUPERTREND"
        ));
    }

    private MarketRegimeFilter() {}

    /**
     * 주어진 시장 상태에서 전략이 적합한지 확인한다.
     *
     * @param regime       현재 시장 상태
     * @param strategyName 전략 이름 (StrategyRegistry key)
     * @return 적합하면 true
     */
    public static boolean isSuitable(MarketRegime regime, String strategyName) {
        Set<String> suitable = SUITABLE.get(regime);
        return suitable != null && suitable.contains(strategyName);
    }

    /**
     * 주어진 시장 상태에서 전략을 비활성화해야 하는지 확인한다.
     *
     * @param regime       현재 시장 상태
     * @param strategyName 전략 이름
     * @return 비활성화 대상이면 true
     */
    public static boolean isUnsuitable(MarketRegime regime, String strategyName) {
        Set<String> unsuitable = UNSUITABLE.get(regime);
        return unsuitable != null && unsuitable.contains(strategyName);
    }

    /**
     * 주어진 시장 상태에 적합한 전략 집합을 반환한다.
     */
    public static Set<String> getSuitableStrategies(MarketRegime regime) {
        return SUITABLE.getOrDefault(regime, Set.of());
    }

    /**
     * 주어진 시장 상태에서 비활성화해야 할 전략 집합을 반환한다.
     */
    public static Set<String> getUnsuitableStrategies(MarketRegime regime) {
        return UNSUITABLE.getOrDefault(regime, Set.of());
    }
}
