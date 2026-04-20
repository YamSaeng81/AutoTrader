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

    // ── 실행 중 세션 현황 ─────────────────────────────────────────────────────
    /** 현재 RUNNING 세션 목록 */
    private final List<ActiveSessionInfo> activeSessions;
    /** 실행 중 코인의 12h 가격 변화율 (%) — 코인 페어 → 변화율 */
    private final Map<String, BigDecimal> coinPriceChanges;
    /** 코인별 포지션 통계 — 코인 페어 → 통계 */
    private final Map<String, CoinPositionStat> coinPositionStats;

    // ── 레짐별 신호 품질 ──────────────────────────────────────────────────────
    /** 레짐별 BUY/SELL 신호 품질 — 레짐명 → RegimeSignalQuality */
    private final Map<String, RegimeSignalQuality> regimeSignalStats;

    // ── 시간대별 신호 품질 ────────────────────────────────────────────────────
    /** KST 4h 시간대별 신호 품질 (00-04, 04-08, …) */
    private final List<HourlySignalQuality> hourlySignalStats;

    // ── 전략 간 상관관계 ──────────────────────────────────────────────────────
    /**
     * 동일 코인·동일 4h 버킷에서 여러 전략 신호가 일치(컨센서스)했을 때와
     * 불일치(분산)했을 때의 적중률 비교 통계.
     */
    private final StrategyCorrelationStats correlationStats;

    @Getter
    @Builder
    public static class RegimeSignalQuality {
        /** 레짐명 */
        private final String regime;
        /** BUY/SELL 신호 수 */
        private final int signalCount;
        /** BUY 신호 4h 적중률 (%) */
        private final BigDecimal buyWinRate4h;
        /** SELL 신호 4h 적중률 (%) */
        private final BigDecimal sellWinRate4h;
        /** 4h 평균 수익률 (%) */
        private final BigDecimal avgReturn4h;
        /**
         * 4h 기대값 EV (%) — EV = win_rate × avg_win + loss_rate × avg_loss.
         * 양수면 이 레짐에서 기대수익 플러스, 음수면 수수료 포함 손실 우세.
         */
        private final BigDecimal expectedValue4h;
    }

    @Getter
    @Builder
    public static class HourlySignalQuality {
        /** KST 4h 시간대 버킷 (예: "00-04", "04-08", …) */
        private final String hourBucket;
        /** BUY/SELL 신호 수 */
        private final int signalCount;
        /** 4h 방향 적중률 (%) */
        private final BigDecimal accuracy4h;
        /** 4h 평균 수익률 (%) */
        private final BigDecimal avgReturn4h;
    }

    @Getter
    @Builder
    public static class StrategyCorrelationStats {
        /**
         * ≥2개 전략이 동일 코인·4h 버킷에서 BUY/SELL 신호를 낸 버킷 수.
         * 전략 간 합의/불일치를 판단하기 위한 전체 샘플 크기.
         */
        private final int totalBuckets;
        /** 모든 전략이 같은 방향(전부 BUY 또는 전부 SELL)을 낸 버킷 수 */
        private final int consensusBuckets;
        /** BUY/SELL 방향이 혼재한 버킷 수 */
        private final int divergentBuckets;
        /** 컨센서스 버킷 내 신호의 4h 방향 적중률 (%) */
        private final BigDecimal consensusAccuracy4h;
        /** 분산(불일치) 버킷 내 신호의 4h 방향 적중률 (%) */
        private final BigDecimal divergentAccuracy4h;
        /** 컨센서스 버킷 내 신호의 4h 평균 수익률 (%) */
        private final BigDecimal consensusAvgReturn4h;
        /** 분산(불일치) 버킷 내 신호의 4h 평균 수익률 (%) */
        private final BigDecimal divergentAvgReturn4h;
    }

    @Getter
    @Builder
    public static class ActiveSessionInfo {
        private final Long sessionId;
        private final String strategyType;
        private final String coinPair;
        private final String timeframe;
        /** 세션 수익률 (%) = (totalAsset - initialCapital) / initialCapital * 100 */
        private final BigDecimal returnPct;
        private final Instant startedAt;
    }

    @Getter
    @Builder
    public static class CoinPositionStat {
        private final int closedCount;
        private final int winCount;
        private final int lossCount;
        private final BigDecimal winRate;
        private final BigDecimal totalPnl;
    }

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
        /**
         * 고신뢰 신호(confidence ≥ 0.7)만 필터한 4h 적중률 (%).
         * 전체 적중률과 비교해 confidence 점수의 선별력을 평가한다.
         * 고신뢰 적중률 > 전체 적중률이면 confidence가 품질 예측에 유효함을 의미.
         */
        private final BigDecimal highConfAccuracy4h;
        /** 고신뢰 신호 건수 (confidence ≥ 0.7) */
        private final int highConfCount;
        /**
         * 4h 기대값 EV (%) — EV = win_rate × avg_win + loss_rate × avg_loss.
         * 양수: 수수료 공제 후 기대수익 플러스 / 음수: 기대손실
         */
        private final BigDecimal expectedValue4h;
    }
}
