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

    /**
     * 전체 청산 경로별 성과 요약 — 실제 청산이 SL/TP/전략SELL/강제청산 중 무엇으로 이뤄졌는지 분포.
     * 카테고리: STOP_LOSS(손절) / TAKE_PROFIT(익절) / STRATEGY_SELL(전략 신호) /
     * FORCED_STOP(세션정지·비상정지 강제청산) / PHANTOM(§15 거래소 잔고 대조 자동정리) /
     * BUY_FAILED(매수 실패로 인한 무효 포지션 — 실제 거래 아님) / OTHER(분류 불가)
     */
    private Map<String, RegimeStat> exitReasonBreakdown;

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

        /** 세션 내 청산 경로별 성과 */
        private Map<String, RegimeStat> exitReasonBreakdown;
    }
}
