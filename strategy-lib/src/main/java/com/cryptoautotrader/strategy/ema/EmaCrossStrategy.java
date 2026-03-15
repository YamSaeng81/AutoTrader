package com.cryptoautotrader.strategy.ema;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EMA 크로스 추세 추종 전략
 * - EMA(fast) > EMA(slow) 골든크로스 → BUY
 * - EMA(fast) < EMA(slow) 데드크로스 → SELL
 *
 * <p>S4-2 ADX 필터: ADX < adxThreshold(기본 25)이면 크로스 신호를 억제하여
 * 낮은 추세 환경에서의 Whipsaw를 방지한다.
 * adxThreshold = 0으로 설정하면 ADX 필터를 비활성화할 수 있다.
 */
public class EmaCrossStrategy implements Strategy {

    @Override
    public String getName() {
        return "EMA_CROSS";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int fastPeriod    = getInt(params, "fastPeriod", 20);
        int slowPeriod    = getInt(params, "slowPeriod", 50);
        int adxPeriod     = getInt(params, "adxPeriod", 14);
        double adxThreshold = getDouble(params, "adxThreshold", 25.0);

        if (candles.size() < slowPeriod + 1) {
            return StrategySignal.hold("데이터 부족");
        }

        // S4-2 ADX 필터: 추세 강도 확인 (데이터가 충분할 때만 적용)
        if (adxThreshold > 0 && candles.size() >= adxPeriod * 2 + 1) {
            BigDecimal adx = IndicatorUtils.adx(candles, adxPeriod);
            if (adx.compareTo(BigDecimal.valueOf(adxThreshold)) < 0) {
                return StrategySignal.hold(String.format(
                        "ADX 필터: 추세 약함 ADX=%.2f < %.0f (Whipsaw 방지)", adx, adxThreshold));
            }
        }

        List<BigDecimal> closes     = candles.stream().map(Candle::getClose).toList();
        List<BigDecimal> prevCloses = closes.subList(0, closes.size() - 1);

        BigDecimal fastEma     = IndicatorUtils.ema(closes, fastPeriod);
        BigDecimal slowEma     = IndicatorUtils.ema(closes, slowPeriod);
        BigDecimal prevFastEma = IndicatorUtils.ema(prevCloses, fastPeriod);
        BigDecimal prevSlowEma = IndicatorUtils.ema(prevCloses, slowPeriod);

        boolean currentAbove = fastEma.compareTo(slowEma) > 0;
        boolean prevAbove    = prevFastEma.compareTo(prevSlowEma) > 0;

        BigDecimal gap      = fastEma.subtract(slowEma).abs();
        BigDecimal strength = gap.divide(slowEma, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(1000))
                .min(BigDecimal.valueOf(100));

        if (currentAbove && !prevAbove) {
            return StrategySignal.buy(strength,
                    String.format("골든크로스: EMA(%d)=%.2f > EMA(%d)=%.2f", fastPeriod, fastEma, slowPeriod, slowEma));
        }
        if (!currentAbove && prevAbove) {
            return StrategySignal.sell(strength,
                    String.format("데드크로스: EMA(%d)=%.2f < EMA(%d)=%.2f", fastPeriod, fastEma, slowPeriod, slowEma));
        }

        return StrategySignal.hold(String.format("크로스 없음: EMA(%d)=%.2f, EMA(%d)=%.2f",
                fastPeriod, fastEma, slowPeriod, slowEma));
    }

    @Override
    public int getMinimumCandleCount() {
        return 51; // slowPeriod(50) + 1 (ADX 필터는 데이터가 충분할 때만 적용)
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : defaultVal;
    }
}
