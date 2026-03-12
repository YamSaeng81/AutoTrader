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
 */
public class EmaCrossStrategy implements Strategy {

    @Override
    public String getName() {
        return "EMA_CROSS";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int fastPeriod = getInt(params, "fastPeriod", 9);
        int slowPeriod = getInt(params, "slowPeriod", 21);

        if (candles.size() < slowPeriod + 1) {
            return StrategySignal.hold("데이터 부족");
        }

        List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();
        List<BigDecimal> prevCloses = closes.subList(0, closes.size() - 1);

        BigDecimal fastEma = IndicatorUtils.ema(closes, fastPeriod);
        BigDecimal slowEma = IndicatorUtils.ema(closes, slowPeriod);
        BigDecimal prevFastEma = IndicatorUtils.ema(prevCloses, fastPeriod);
        BigDecimal prevSlowEma = IndicatorUtils.ema(prevCloses, slowPeriod);

        boolean currentAbove = fastEma.compareTo(slowEma) > 0;
        boolean prevAbove = prevFastEma.compareTo(prevSlowEma) > 0;

        BigDecimal gap = fastEma.subtract(slowEma).abs();
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
        return 22; // slowPeriod(21) + 1
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }
}
