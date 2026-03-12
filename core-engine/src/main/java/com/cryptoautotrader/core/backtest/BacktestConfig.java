package com.cryptoautotrader.core.backtest;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Getter
@Builder
public class BacktestConfig {
    private final String strategyName;
    private final String coinPair;
    private final String timeframe;
    private final Instant startDate;
    private final Instant endDate;

    @Builder.Default
    private final BigDecimal initialCapital = new BigDecimal("10000000");
    @Builder.Default
    private final BigDecimal slippagePct = new BigDecimal("0.1");
    @Builder.Default
    private final BigDecimal feePct = new BigDecimal("0.05");

    private final Map<String, Object> strategyParams;

    // Fill Simulation
    @Builder.Default
    private final boolean fillSimulationEnabled = false;
    @Builder.Default
    private final BigDecimal impactFactor = new BigDecimal("0.1");
    @Builder.Default
    private final BigDecimal fillRatio = new BigDecimal("0.3");
}
