package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.core.metrics.PerformanceReport;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class BacktestResult {
    private final BacktestConfig config;
    private final List<TradeRecord> trades;
    private final PerformanceReport metrics;
}
