package com.cryptoautotrader.core.metrics;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Map;

@Getter
@Builder
public class PerformanceReport {
    private final BigDecimal totalReturnPct;
    private final BigDecimal winRatePct;
    private final BigDecimal mddPct;
    private final BigDecimal sharpeRatio;
    private final BigDecimal sortinoRatio;
    private final BigDecimal calmarRatio;
    private final BigDecimal winLossRatio;
    private final BigDecimal recoveryFactor;
    private final int totalTrades;
    private final int winningTrades;
    private final int losingTrades;
    private final BigDecimal avgProfitPct;
    private final BigDecimal avgLossPct;
    private final int maxConsecutiveLoss;
    private final Map<String, BigDecimal> monthlyReturns;
    private final String segment; // FULL, IN_SAMPLE, OUT_SAMPLE
}
