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
 *
 * <p>S4-3 Squeeze 감지: BB Bandwidth가 최근 squeezeWindow 캔들의 최솟값 이하이면
 * 밴드가 압축 중(Squeeze)으로 판단하여 HOLD를 반환한다.
 * Squeeze 중에는 평균 회귀 신호보다 브레이크아웃 대기가 더 적합하기 때문이다.
 */
public class BollingerStrategy implements Strategy {

    private static final int SCALE = 8;

    @Override
    public String getName() {
        return "BOLLINGER";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int     period         = getInt(params,     "period",          20);
        double  multiplier     = getDouble(params,  "multiplier",      2.0);
        boolean squeezeEnabled = getBoolean(params, "squeezeEnabled",  true);
        int     squeezeWindow  = getInt(params,     "squeezeWindow",   30);
        int     adxPeriod      = getInt(params,     "adxPeriod",       14);
        double  adxMaxThreshold = getDouble(params, "adxMaxThreshold", 25.0);
        double  buyThreshold   = getDouble(params,  "buyThreshold",    0.2);
        double  sellThreshold  = getDouble(params,  "sellThreshold",   0.8);

        if (candles.size() < period) {
            return StrategySignal.hold("데이터 부족");
        }

        // ADX 상한선 필터: 볼린저 밴드는 평균 회귀 전략, 추세장에서 손실 방지
        if (adxMaxThreshold > 0 && candles.size() >= adxPeriod * 2 + 1) {
            BigDecimal adx = IndicatorUtils.adx(candles, adxPeriod);
            if (adx.compareTo(BigDecimal.valueOf(adxMaxThreshold)) >= 0) {
                return StrategySignal.hold(String.format(
                        "ADX 필터: 추세장 평균회귀 억제 ADX=%.2f >= %.0f", adx, adxMaxThreshold));
            }
        }

        List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();

        // S4-3 Squeeze 감지: 현재 Bandwidth가 최근 squeezeWindow 구간 최솟값 이하이면 HOLD
        if (squeezeEnabled && closes.size() >= period + squeezeWindow - 1) {
            BigDecimal currentBW = IndicatorUtils.bollingerBandwidth(closes, period, multiplier);
            List<BigDecimal> bwHistory = IndicatorUtils.bollingerBandwidths(closes, period, multiplier, squeezeWindow);
            if (!bwHistory.isEmpty()) {
                BigDecimal minBW = bwHistory.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                if (currentBW.compareTo(minBW) <= 0) {
                    return StrategySignal.hold(String.format(
                            "Squeeze: 밴드 폭 최저 BW=%.4f → 브레이크아웃 대기", currentBW));
                }
            }
        }

        BigDecimal sma    = IndicatorUtils.sma(closes, period);
        BigDecimal stdDev = IndicatorUtils.standardDeviation(closes, period);

        BigDecimal upperBand   = sma.add(stdDev.multiply(BigDecimal.valueOf(multiplier)));
        BigDecimal lowerBand   = sma.subtract(stdDev.multiply(BigDecimal.valueOf(multiplier)));
        BigDecimal currentPrice = closes.get(closes.size() - 1);

        BigDecimal bandWidth = upperBand.subtract(lowerBand);
        if (bandWidth.compareTo(BigDecimal.ZERO) == 0) {
            return StrategySignal.hold("밴드 폭 = 0");
        }

        // %B = (price - lowerBand) / (upperBand - lowerBand)
        BigDecimal percentB = currentPrice.subtract(lowerBand)
                .divide(bandWidth, SCALE, RoundingMode.HALF_UP);

        BigDecimal buyBD  = BigDecimal.valueOf(buyThreshold);
        BigDecimal sellBD = BigDecimal.valueOf(sellThreshold);

        BigDecimal strength;
        if (percentB.compareTo(buyBD) < 0) {
            strength = buyBD.subtract(percentB).multiply(BigDecimal.valueOf(100)).min(BigDecimal.valueOf(100));
            return StrategySignal.buy(strength,
                    String.format("하단 밴드 근접: %%B=%.4f < %.2f, 가격=%.2f, 하단=%.2f", percentB, buyThreshold, currentPrice, lowerBand));
        }
        if (percentB.compareTo(sellBD) > 0) {
            strength = percentB.subtract(sellBD).multiply(BigDecimal.valueOf(100)).min(BigDecimal.valueOf(100));
            return StrategySignal.sell(strength,
                    String.format("상단 밴드 근접: %%B=%.4f > %.2f, 가격=%.2f, 상단=%.2f", percentB, sellThreshold, currentPrice, upperBand));
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

    private boolean getBoolean(Map<String, Object> params, String key, boolean defaultVal) {
        Object v = params.get(key);
        return v instanceof Boolean ? (Boolean) v : defaultVal;
    }
}
