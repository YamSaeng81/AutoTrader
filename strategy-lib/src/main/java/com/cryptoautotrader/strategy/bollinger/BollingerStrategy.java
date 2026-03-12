package com.cryptoautotrader.strategy.bollinger;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 볼린저 밴드 평균 회귀 전략
 * - %B < 0 (하단 밴드 이탈) → BUY
 * - %B > 1 (상단 밴드 이탈) → SELL
 */
public class BollingerStrategy implements Strategy {

    private static final int SCALE = 8;

    @Override
    public String getName() {
        return "BOLLINGER";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int period = getInt(params, "period", 20);
        double multiplier = getDouble(params, "multiplier", 2.0);

        if (candles.size() < period) {
            return StrategySignal.hold("데이터 부족");
        }

        List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();
        BigDecimal sma = IndicatorUtils.sma(closes, period);
        BigDecimal stdDev = IndicatorUtils.standardDeviation(closes, period);

        BigDecimal upperBand = sma.add(stdDev.multiply(BigDecimal.valueOf(multiplier)));
        BigDecimal lowerBand = sma.subtract(stdDev.multiply(BigDecimal.valueOf(multiplier)));
        BigDecimal currentPrice = closes.get(closes.size() - 1);

        BigDecimal bandWidth = upperBand.subtract(lowerBand);
        if (bandWidth.compareTo(BigDecimal.ZERO) == 0) {
            return StrategySignal.hold("밴드 폭 = 0");
        }

        // %B = (price - lowerBand) / (upperBand - lowerBand)
        BigDecimal percentB = currentPrice.subtract(lowerBand)
                .divide(bandWidth, SCALE, RoundingMode.HALF_UP);

        BigDecimal strength;
        if (percentB.compareTo(BigDecimal.ZERO) < 0) {
            strength = percentB.abs().multiply(BigDecimal.valueOf(100)).min(BigDecimal.valueOf(100));
            return StrategySignal.buy(strength,
                    String.format("하단 밴드 이탈: %%B=%.4f, 가격=%.2f, 하단=%.2f", percentB, currentPrice, lowerBand));
        }
        if (percentB.compareTo(BigDecimal.ONE) > 0) {
            strength = percentB.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).min(BigDecimal.valueOf(100));
            return StrategySignal.sell(strength,
                    String.format("상단 밴드 이탈: %%B=%.4f, 가격=%.2f, 상단=%.2f", percentB, currentPrice, upperBand));
        }

        return StrategySignal.hold(String.format("밴드 내: %%B=%.4f", percentB));
    }

    @Override
    public int getMinimumCandleCount() {
        return 20;
    }

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : defaultVal;
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }
}
