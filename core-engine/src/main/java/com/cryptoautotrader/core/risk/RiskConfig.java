package com.cryptoautotrader.core.risk;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
public class RiskConfig {
    @Builder.Default
    private BigDecimal maxDailyLossPct = new BigDecimal("3.0");
    @Builder.Default
    private BigDecimal maxWeeklyLossPct = new BigDecimal("7.0");
    @Builder.Default
    private BigDecimal maxMonthlyLossPct = new BigDecimal("15.0");
    @Builder.Default
    private int maxPositions = 3;
    @Builder.Default
    private int cooldownMinutes = 60;
    private BigDecimal portfolioLimitKrw;
}
