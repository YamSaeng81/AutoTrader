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

    /** 최대 레버리지 배수 */
    @Builder.Default
    private double maxLeverage = 3.0;

    /** 이 값 초과 상관계수를 가진 자산 쌍은 유효 슬롯을 1개 추가 소비 */
    @Builder.Default
    private double correlationThreshold = 0.7;

    /** Fixed Fractional 기준 계좌 대비 리스크 비율 (1% = 0.01) */
    @Builder.Default
    private BigDecimal defaultRiskPercentage = new BigDecimal("0.01");
}
