package com.cryptoautotrader.strategy.grid;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 그리드 전략
 * - 가격 범위를 N등분하여 그리드 레벨 설정
 * - 그리드 하단 근접 → BUY, 그리드 상단 근접 → SELL
 */
public class GridStrategy implements Strategy {

    private static final int SCALE = 8;

    @Override
    public String getName() {
        return "GRID";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int lookbackPeriod = getInt(params, "lookbackPeriod", 100);
        int gridCount = getInt(params, "gridCount", 10);
        double triggerPct = getDouble(params, "triggerPct", 0.5);

        if (candles.size() < lookbackPeriod) {
            return StrategySignal.hold("데이터 부족");
        }

        // lookback 기간 동안의 고저가로 그리드 범위 설정
        BigDecimal highest = BigDecimal.ZERO;
        BigDecimal lowest = new BigDecimal("999999999999");
        int start = candles.size() - lookbackPeriod;
        for (int i = start; i < candles.size(); i++) {
            highest = highest.max(candles.get(i).getHigh());
            lowest = lowest.min(candles.get(i).getLow());
        }

        BigDecimal range = highest.subtract(lowest);
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return StrategySignal.hold("가격 범위 = 0");
        }

        BigDecimal gridSize = range.divide(BigDecimal.valueOf(gridCount), SCALE, RoundingMode.HALF_UP);
        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();

        // 현재가가 어떤 그리드 레벨에 위치하는지
        BigDecimal gridPosition = currentPrice.subtract(lowest)
                .divide(gridSize, SCALE, RoundingMode.HALF_UP);

        // 가장 가까운 그리드 레벨과의 거리
        BigDecimal nearestLevel = gridPosition.setScale(0, RoundingMode.HALF_UP);
        BigDecimal distanceFromLevel = gridPosition.subtract(nearestLevel).abs();
        BigDecimal triggerThreshold = BigDecimal.valueOf(triggerPct / 100.0);

        // 하단 그리드 (하위 30%) 근접 → BUY
        BigDecimal positionRatio = gridPosition.divide(BigDecimal.valueOf(gridCount), SCALE, RoundingMode.HALF_UP);
        BigDecimal strength = BigDecimal.valueOf(50);

        if (positionRatio.compareTo(BigDecimal.valueOf(0.3)) <= 0 && distanceFromLevel.compareTo(triggerThreshold) <= 0) {
            strength = BigDecimal.ONE.subtract(positionRatio).multiply(BigDecimal.valueOf(100));
            return StrategySignal.buy(strength,
                    String.format("그리드 하단: 레벨 %.1f/%d, 가격=%.2f", gridPosition, gridCount, currentPrice));
        }
        // 상단 그리드 (상위 30%) 근접 → SELL
        if (positionRatio.compareTo(BigDecimal.valueOf(0.7)) >= 0 && distanceFromLevel.compareTo(triggerThreshold) <= 0) {
            strength = positionRatio.multiply(BigDecimal.valueOf(100));
            return StrategySignal.sell(strength,
                    String.format("그리드 상단: 레벨 %.1f/%d, 가격=%.2f", gridPosition, gridCount, currentPrice));
        }

        return StrategySignal.hold(String.format("그리드 중립: 레벨 %.1f/%d", gridPosition, gridCount));
    }

    @Override
    public int getMinimumCandleCount() {
        return 100;
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
