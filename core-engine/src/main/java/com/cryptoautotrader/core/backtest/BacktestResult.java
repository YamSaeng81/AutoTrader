package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.core.metrics.PerformanceReport;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class BacktestResult {
    private final BacktestConfig config;
    private final List<TradeRecord> trades;
    /** 청산된 거래만 반영한 성과 지표 (realized). */
    private final PerformanceReport metrics;

    // ── 미청산 포지션 mark-to-market ───────────────────────────────
    /** 종료 시점에 열려 있던 포지션을 마지막 종가로 평가한 미실현 손익. 없으면 0. */
    private final BigDecimal unrealizedPnl;
    /** 미청산 포지션의 마지막 종가 기준 평가액. 없으면 0. */
    private final BigDecimal openPositionValue;
    /** realized capital + 미청산 포지션 평가액 (강제청산 없이 유지했을 때의 최종 자산). */
    private final BigDecimal finalEquity;

    // ── 구간 길이 (2026-10-28 리뷰 12차) ───────────────────────────
    /**
     * 평가 대상 봉 수 — 엔진 루프가 돈 봉 전부(HOLD·청산으로 전략 호출을 건너뛴 봉 포함).
     * 예열 이력은 들어가지 않는다. Walk-Forward 기간 보정의 분모.
     */
    private final int evaluatedBars;
    /** 거래 없이 지표 이력으로만 받은 구간 직전 봉 수. 예열이 없으면 0. */
    private final int warmupBars;
}
