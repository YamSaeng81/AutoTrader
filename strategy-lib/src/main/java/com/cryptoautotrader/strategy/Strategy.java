package com.cryptoautotrader.strategy;

import java.util.List;
import java.util.Map;

public interface Strategy {

    String getName();

    StrategySignal evaluate(List<Candle> candles, Map<String, Object> params);

    int getMinimumCandleCount();
}
