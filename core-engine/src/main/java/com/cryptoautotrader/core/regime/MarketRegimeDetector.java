package com.cryptoautotrader.core.regime;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * ADX 기반 시장 상태 감지
 */
public class MarketRegimeDetector {

    private static final BigDecimal ADX_TREND_THRESHOLD = BigDecimal.valueOf(25);
    private static final BigDecimal ADX_RANGE_THRESHOLD = BigDecimal.valueOf(20);
    private static final int DEFAULT_PERIOD = 14;

    public MarketRegime detect(List<Candle> candles) {
        return detect(candles, DEFAULT_PERIOD);
    }

    public MarketRegime detect(List<Candle> candles, int period) {
        if (candles.size() < period * 2 + 1) {
            return MarketRegime.RANGE;
        }

        BigDecimal adx = IndicatorUtils.adx(candles, period);

        if (adx.compareTo(ADX_TREND_THRESHOLD) > 0) {
            return MarketRegime.TREND;
        }
        if (adx.compareTo(ADX_RANGE_THRESHOLD) < 0) {
            return MarketRegime.RANGE;
        }
        return MarketRegime.VOLATILE;
    }
}
