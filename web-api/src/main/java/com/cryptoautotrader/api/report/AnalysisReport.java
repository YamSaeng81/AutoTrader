package com.cryptoautotrader.api.report;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 12h 분석 보고서 데이터 모델.
 * LogAnalyzerService가 생성하여 ReportComposer에 전달한다.
 */
@Getter
@Builder
public class AnalysisReport {

    private final Instant periodStart;
    private final Instant periodEnd;

    // ── 신호 통계 ─────────────────────────────────────────────────────────────
    private final int totalSignals;
    private final int buySignals;
    private final int sellSignals;
    private final int holdSignals;
    private final int executedSignals;
    private final int blockedSignals;

    /** 4h 방향 적중률 (%) — 평가된 신호 중 수익률 양수 비율 */
    private final BigDecimal accuracy4h;
    /** 24h 방향 적중률 (%) */
    private final BigDecimal accuracy24h;
    /** 4h 평균 수익률 (%) */
    private final BigDecimal avgReturn4h;
    /** 24h 평균 수익률 (%) */
    private final BigDecimal avgReturn24h;

    // ── 포지션 성과 ───────────────────────────────────────────────────────────
    /** 기간 내 청산된 포지션 수 */
    private final int closedPositions;
    private final BigDecimal totalRealizedPnl;
    private final BigDecimal winRate;
    private final int winCount;
    private final int lossCount;

    // ── 레짐 현황 ─────────────────────────────────────────────────────────────
    /** 현재 감지된 레짐 */
    private final String currentRegime;
    /** 기간 내 레짐 전환 이력 */
    private final List<RegimeTransition> regimeTransitions;

    // ── 전략별 신호 요약 ──────────────────────────────────────────────────────
    /** 전략명 → {buy, sell, hold, executed} */
    private final Map<String, StrategySignalStat> strategyStats;

    // ── 차단 사유 요약 ────────────────────────────────────────────────────────
    /** 차단 사유 → 건수 */
    private final Map<String, Integer> blockReasons;

    // ── 시장 가격 맥락 ────────────────────────────────────────────────────────
    /** BTC 분석 구간 12h 가격 변화율 (%) — null 이면 조회 실패 */
    private final BigDecimal btcPriceChange12h;
    /** ETH 분석 구간 12h 가격 변화율 (%) */
    private final BigDecimal ethPriceChange12h;

    // ── 포지션 현황 ───────────────────────────────────────────────────────────
    /** 분석 시점 기준 오픈 포지션 수 */
    private final int openPositionCount;
    /** 최근 청산 포지션 기준 연속 손실 횟수 */
    private final int consecutiveLosses;

    @Getter
    @Builder
    public static class RegimeTransition {
        private final String fromRegime;
        private final String toRegime;
        private final Instant detectedAt;
    }

    @Getter
    @Builder
    public static class StrategySignalStat {
        private final int buy;
        private final int sell;
        private final int hold;
        private final int executed;
        /** 4h 방향 적중률 (%) — BUY/SELL 신호 대상, 수수료 공제 후 실질 기준 */
        private final BigDecimal accuracy4h;
        /** 24h 방향 적중률 (%) */
        private final BigDecimal accuracy24h;
        /** 4h 평균 수익률 (%) */
        private final BigDecimal avgReturn4h;
        /** 24h 평균 수익률 (%) */
        private final BigDecimal avgReturn24h;
    }
}
