package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.strategy.Candle;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fill Simulation: Market Impact + Partial Fill
 */
@RequiredArgsConstructor
public class FillSimulator {

    private static final int SCALE = 8;

    private final BigDecimal impactFactor;
    private final BigDecimal fillRatio;

    /**
     * Market Impact에 의한 추가 슬리피지 계산
     * impact = (orderVolume / candleVolume) * impactFactor
     */
    public BigDecimal calculateMarketImpact(BigDecimal orderVolume, BigDecimal candleVolume) {
        if (candleVolume.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return orderVolume.divide(candleVolume, SCALE, RoundingMode.HALF_UP)
                .multiply(impactFactor);
    }

    /**
     * 한 캔들에서 체결 가능한 최대 수량 계산
     * maxFill = candleVolume * fillRatio
     */
    public BigDecimal calculateMaxFillQuantity(BigDecimal candleVolume) {
        return candleVolume.multiply(fillRatio).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Partial Fill 여부 판단
     */
    public boolean isPartialFill(BigDecimal orderQuantity, BigDecimal candleVolume) {
        BigDecimal maxFill = calculateMaxFillQuantity(candleVolume);
        return orderQuantity.compareTo(maxFill) > 0;
    }
}
