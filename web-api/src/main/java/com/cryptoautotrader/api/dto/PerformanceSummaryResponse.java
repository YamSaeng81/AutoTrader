package com.cryptoautotrader.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class PerformanceSummaryResponse {

    private BigDecimal totalRealizedPnl;
    private BigDecimal totalUnrealizedPnl;
    private BigDecimal totalPnl;
    private BigDecimal totalInitialCapital;
    private BigDecimal returnRatePct;
    private BigDecimal totalFee;
    private int totalTrades;
    private int winCount;
    private int lossCount;
    private BigDecimal winRatePct;

    // 리스크 조정 지표
    private BigDecimal mddPct;
    private BigDecimal sharpeRatio;
    private BigDecimal sortinoRatio;
    private BigDecimal calmarRatio;
    private BigDecimal winLossRatio;
    private BigDecimal recoveryFactor;
    private BigDecimal avgProfitPct;
    private BigDecimal avgLossPct;
    private int maxConsecutiveLoss;
    private Map<String, BigDecimal> monthlyReturns;

    /** 전체 레짐별 성과 요약 */
    private Map<String, RegimeStat> regimeBreakdown;

    private List<SessionPerformance> sessions;

    /** 레짐별 집계 통계 */
    @Data
    @Builder
    public static class RegimeStat {
        private int trades;
        private int wins;
        private BigDecimal winRatePct;
        private BigDecimal totalPnl;
    }

    @Data
    @Builder
    public static class SessionPerformance {
        private Long sessionId;
        private String strategyType;
        private String coinPair;
        private String timeframe;
        private String status;
        private BigDecimal initialCapital;
        private BigDecimal currentAsset;
        private BigDecimal realizedPnl;
        private BigDecimal unrealizedPnl;
        private BigDecimal totalPnl;
        private BigDecimal returnRatePct;
        private BigDecimal totalFee;
        private int totalTrades;
        private int winCount;
        private BigDecimal winRatePct;
        private String startedAt;
        private String stoppedAt;

        // 리스크 조정 지표
        private BigDecimal mddPct;
        private BigDecimal sharpeRatio;
        private BigDecimal sortinoRatio;
        private BigDecimal winLossRatio;
        private BigDecimal avgProfitPct;
        private BigDecimal avgLossPct;
        private int maxConsecutiveLoss;
        private Map<String, BigDecimal> monthlyReturns;

        /** 세션 내 레짐별 성과 */
        private Map<String, RegimeStat> regimeBreakdown;
    }
}
