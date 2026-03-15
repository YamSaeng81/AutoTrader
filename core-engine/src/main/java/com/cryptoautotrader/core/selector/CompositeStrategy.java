package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Weighted Voting 기반 복합 전략.
 *
 * <pre>
 * buyScore  = Σ(weight × confidence)  (BUY  신호 전략 합산)
 * sellScore = Σ(weight × confidence)  (SELL 신호 전략 합산)
 *
 * buyScore  > 0.6                         → BUY  (strength ≈ score × 100)
 * sellScore > 0.6                         → SELL
 * buyScore  > 0.4                         → BUY  (weak)
 * sellScore > 0.4                         → SELL (weak)
 * 양쪽 모두 > 0.4 (상충)                  → HOLD
 * 그 외                                   → HOLD
 * </pre>
 */
public class CompositeStrategy implements Strategy {

    private static final double STRONG_THRESHOLD = 0.6;
    private static final double WEAK_THRESHOLD   = 0.4;

    private final List<WeightedStrategy> strategies;

    public CompositeStrategy(List<WeightedStrategy> strategies) {
        this.strategies = strategies;
    }

    @Override
    public String getName() {
        return "COMPOSITE";
    }

    @Override
    public int getMinimumCandleCount() {
        return strategies.stream()
                .mapToInt(ws -> ws.getStrategy().getMinimumCandleCount())
                .max()
                .orElse(0);
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        double buyScore  = 0.0;
        double sellScore = 0.0;
        StringBuilder reasons = new StringBuilder();

        for (WeightedStrategy ws : strategies) {
            StrategySignal signal = ws.evaluate(candles, params);
            double w    = ws.getWeight();
            double conf = signal.getConfidence().doubleValue();

            switch (signal.getAction()) {
                case BUY  -> buyScore  += w * conf;
                case SELL -> sellScore += w * conf;
                case HOLD -> {}
            }
            reasons.append(ws.getStrategy().getName())
                   .append(':').append(signal.getAction())
                   .append('(').append(String.format("%.0f", signal.getStrength().doubleValue())).append(')')
                   .append(' ');
        }

        String detail = reasons.toString().trim();

        // 상충 감지
        if (buyScore > WEAK_THRESHOLD && sellScore > WEAK_THRESHOLD) {
            return StrategySignal.hold(String.format("상충 신호 buy=%.2f sell=%.2f [%s]",
                    buyScore, sellScore, detail));
        }

        if (buyScore > STRONG_THRESHOLD) {
            return StrategySignal.buy(BigDecimal.valueOf(buyScore * 100),
                    String.format("STRONG_BUY score=%.2f [%s]", buyScore, detail));
        }
        if (sellScore > STRONG_THRESHOLD) {
            return StrategySignal.sell(BigDecimal.valueOf(sellScore * 100),
                    String.format("STRONG_SELL score=%.2f [%s]", sellScore, detail));
        }
        if (buyScore > WEAK_THRESHOLD) {
            return StrategySignal.buy(BigDecimal.valueOf(buyScore * 100),
                    String.format("BUY score=%.2f [%s]", buyScore, detail));
        }
        if (sellScore > WEAK_THRESHOLD) {
            return StrategySignal.sell(BigDecimal.valueOf(sellScore * 100),
                    String.format("SELL score=%.2f [%s]", sellScore, detail));
        }

        return StrategySignal.hold(String.format("점수 미달 buy=%.2f sell=%.2f [%s]",
                buyScore, sellScore, detail));
    }
}
