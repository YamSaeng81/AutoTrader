package com.cryptoautotrader.core.regime;

public enum MarketRegime {
    TREND,    // ADX > 25: 강한 추세
    RANGE,    // ADX < 20: 횡보
    VOLATILE  // ADX 20~25 + 높은 변동성
}
