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
}
