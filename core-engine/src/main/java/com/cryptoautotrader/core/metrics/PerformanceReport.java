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

    // ── §13 데이터 스냅샷 편향 감지 지표 ──────────────────────────
    /** 월별 수익률의 표준편차(%). 높을수록 특정 달에 몰린 수익 가능성. */
    private final BigDecimal monthlyReturnStdDev;
    /** 월별 수익률의 왜도(skewness). 양수 = 우측 꼬리(급등 달 소수). */
    private final BigDecimal monthlyReturnSkewness;
    /** 가장 수익이 높은 단일 달이 전체 PnL 에서 차지하는 비율(%). 80% 이상이면 편향 의심. */
    private final BigDecimal topMonthConcentrationPct;
}
