package com.cryptoautotrader.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

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

    private List<SessionPerformance> sessions;

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
    }
}
