package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.risk.ExitRuleConfig;
import com.cryptoautotrader.strategy.Candle;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class BacktestConfig {
    private final String strategyName;
    private final String coinPair;
    private final String timeframe;
    private final Instant startDate;
    private final Instant endDate;

    @Builder.Default
    private final BigDecimal initialCapital = new BigDecimal("10000000");
    @Builder.Default
    private final BigDecimal slippagePct = new BigDecimal("0.1");
    @Builder.Default
    private final BigDecimal feePct = new BigDecimal("0.05");

    private final Map<String, Object> strategyParams;

    // Fill Simulation
    @Builder.Default
    private final boolean fillSimulationEnabled = false;
    @Builder.Default
    private final BigDecimal impactFactor = new BigDecimal("0.1");
    @Builder.Default
    private final BigDecimal fillRatio = new BigDecimal("0.3");

    // ── 통합 리스크/청산 규칙 (실전매매 기본값과 동일) ─────────
    /** null이면 ExitRuleConfig.defaults() 사용 */
    private final ExitRuleConfig exitRuleConfig;

    /**
     * BTC_MARKET_GUARD 판정용 BTC 캔들 (coinPair와 동일 timeframe, 시간 오름차순).
     * null이면 게이트를 적용하지 않는다 (실전매매 LiveTradingService/DynamicTradingService와
     * 동일하게 맞추려면 호출측에서 반드시 주입해야 한다 — 2026-07-02 codex 분석 §6).
     */
    private final List<Candle> btcCandles;

    public ExitRuleConfig getExitRuleConfig() {
        return exitRuleConfig != null ? exitRuleConfig : ExitRuleConfig.defaults();
    }
}
