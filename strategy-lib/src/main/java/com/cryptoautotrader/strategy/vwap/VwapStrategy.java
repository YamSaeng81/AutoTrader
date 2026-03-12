package com.cryptoautotrader.strategy.vwap;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * VWAP 역추세 전략
 * - 현재가가 VWAP 대비 N% 이상 할인 → BUY
 * - 현재가가 VWAP 대비 N% 이상 프리미엄 → SELL
 */
public class VwapStrategy implements Strategy {

    private static final int SCALE = 8;

    @Override
    public String getName() {
        return "VWAP";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        double thresholdPct = getDouble(params, "thresholdPct", 1.0);
        int period = getInt(params, "period", 20);

        if (candles.size() < period) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + period);
        }

        BigDecimal vwap = calculateVwap(candles, period);
        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();
        BigDecimal deviationPct = currentPrice.subtract(vwap)
                .divide(vwap, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        BigDecimal threshold = BigDecimal.valueOf(thresholdPct);
        BigDecimal strength = deviationPct.abs()
                .divide(threshold, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(50))
                .min(BigDecimal.valueOf(100));

        if (deviationPct.compareTo(threshold.negate()) <= 0) {
            return StrategySignal.buy(strength,
                    String.format("VWAP 할인 %.2f%% (임계값 -%.1f%%)", deviationPct.doubleValue(), thresholdPct));
        }
        if (deviationPct.compareTo(threshold) >= 0) {
            return StrategySignal.sell(strength,
                    String.format("VWAP 프리미엄 %.2f%% (임계값 +%.1f%%)", deviationPct.doubleValue(), thresholdPct));
        }

        return StrategySignal.hold(String.format("VWAP 편차 %.2f%% (임계값 ±%.1f%%)", deviationPct.doubleValue(), thresholdPct));
    }

    @Override
    public int getMinimumCandleCount() {
        return 20;
    }

    private BigDecimal calculateVwap(List<Candle> candles, int period) {
        BigDecimal sumPriceVolume = BigDecimal.ZERO;
        BigDecimal sumVolume = BigDecimal.ZERO;

        int start = candles.size() - period;
        for (int i = start; i < candles.size(); i++) {
            Candle c = candles.get(i);
            BigDecimal typicalPrice = c.getHigh().add(c.getLow()).add(c.getClose())
                    .divide(BigDecimal.valueOf(3), SCALE, RoundingMode.HALF_UP);
            sumPriceVolume = sumPriceVolume.add(typicalPrice.multiply(c.getVolume()));
            sumVolume = sumVolume.add(c.getVolume());
        }

        if (sumVolume.compareTo(BigDecimal.ZERO) == 0) {
            return candles.get(candles.size() - 1).getClose();
        }
        return sumPriceVolume.divide(sumVolume, SCALE, RoundingMode.HALF_UP);
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
