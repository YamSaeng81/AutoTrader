package com.cryptoautotrader.core.regime;

public enum MarketRegime {
    /** ADX > 25: 강한 추세 (상승/하락 모두 해당) */
    TREND,
    /** ADX < 20 + BB Bandwidth 좁음: 횡보 */
    RANGE,
    /** ATR > ATR SMA × 1.5 + ADX < 25: 변동성 급등 (추세 없는 급변) */
    VOLATILITY,
    /** ADX 20~25 구간: 전환 중 (직전 Regime 유지 + 포지션 축소) */
    TRANSITIONAL
}
