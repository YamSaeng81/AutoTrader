package com.cryptoautotrader.core.risk;

import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 리스크 엔진: 일일/주간/월간 손실 한도, 최대 포지션 수 제한
 */
@RequiredArgsConstructor
public class RiskEngine {

    private final RiskConfig config;

    public RiskCheckResult check(BigDecimal dailyLossPct, BigDecimal weeklyLossPct,
                                  BigDecimal monthlyLossPct, int currentPositions) {
        if (dailyLossPct.abs().compareTo(config.getMaxDailyLossPct()) > 0) {
            return RiskCheckResult.reject(
                    String.format("일일 손실 한도 초과: %.2f%% > %.2f%%", dailyLossPct.abs(), config.getMaxDailyLossPct()));
        }
        if (weeklyLossPct.abs().compareTo(config.getMaxWeeklyLossPct()) > 0) {
            return RiskCheckResult.reject(
                    String.format("주간 손실 한도 초과: %.2f%% > %.2f%%", weeklyLossPct.abs(), config.getMaxWeeklyLossPct()));
        }
        if (monthlyLossPct.abs().compareTo(config.getMaxMonthlyLossPct()) > 0) {
            return RiskCheckResult.reject(
                    String.format("월간 손실 한도 초과: %.2f%% > %.2f%%", monthlyLossPct.abs(), config.getMaxMonthlyLossPct()));
        }
        if (currentPositions >= config.getMaxPositions()) {
            return RiskCheckResult.reject(
                    String.format("최대 포지션 수 초과: %d >= %d", currentPositions, config.getMaxPositions()));
        }
        return RiskCheckResult.approve();
    }
}
