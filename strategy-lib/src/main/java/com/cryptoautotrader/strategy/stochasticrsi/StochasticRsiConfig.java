package com.cryptoautotrader.strategy.stochasticrsi;

import com.cryptoautotrader.strategy.StrategyConfig;

import java.util.Map;

/**
 * Stochastic RSI 전략 파라미터.
 *
 * <pre>
 * rsiPeriod        : RSI 계산 기간 (기본 14)
 * stochPeriod      : Stochastic 계산 기간 — RSI 값들 중 최고/최저 탐색 범위 (기본 14)
 * signalPeriod     : %D(Signal) 이동평균 기간 (기본 3)
 * oversoldLevel    : 과매도 기준 (기본 20)
 * overboughtLevel  : 과매수 기준 (기본 80)
 * </pre>
 */
public class StochasticRsiConfig extends StrategyConfig {

    private int    rsiPeriod       = 14;
    private int    stochPeriod     = 14;
    private int    signalPeriod    = 3;
    private double oversoldLevel   = 20.0;
    private double overboughtLevel = 80.0;

    @Override
    public String getStrategyType() {
        return "STOCHASTIC_RSI";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "rsiPeriod",       rsiPeriod,
                "stochPeriod",     stochPeriod,
                "signalPeriod",    signalPeriod,
                "oversoldLevel",   oversoldLevel,
                "overboughtLevel", overboughtLevel
        );
    }

    public int getRsiPeriod()          { return rsiPeriod; }
    public void setRsiPeriod(int v)    { this.rsiPeriod = v; }

    public int getStochPeriod()        { return stochPeriod; }
    public void setStochPeriod(int v)  { this.stochPeriod = v; }

    public int getSignalPeriod()       { return signalPeriod; }
    public void setSignalPeriod(int v) { this.signalPeriod = v; }

    public double getOversoldLevel()          { return oversoldLevel; }
    public void setOversoldLevel(double v)    { this.oversoldLevel = v; }

    public double getOverboughtLevel()        { return overboughtLevel; }
    public void setOverboughtLevel(double v)  { this.overboughtLevel = v; }
}
