package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.util.List;
import java.util.Map;

/**
 * Strategy 래퍼: 가중치(weight)를 부여하여 CompositeStrategy의 Weighted Voting에 사용한다.
 */
public class WeightedStrategy {

    private final Strategy strategy;
    private final double weight;

    public WeightedStrategy(Strategy strategy, double weight) {
        this.strategy = strategy;
        this.weight   = weight;
    }

    public Strategy getStrategy() { return strategy; }
    public double   getWeight()   { return weight; }

    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        return strategy.evaluate(candles, params);
    }

    /** weight를 factor 배율로 축소한 새 WeightedStrategy 반환 */
    public WeightedStrategy withReducedWeight(double factor) {
        return new WeightedStrategy(strategy, weight * factor);
    }
}
