package com.cryptoautotrader.strategy.heikinashi;

import com.cryptoautotrader.strategy.StrategyConfig;

import java.util.Map;

/**
 * Heikin-Ashi + 200 EMA + Stochastic RSI 추세추종 전략 파라미터.
 *
 * <pre>
 * emaPeriod       : 장기 추세 기준선 EMA 기간 (기본 200)
 * rsiPeriod       : RSI 계산 기간 (기본 14)
 * stochPeriod     : Stochastic 계산 기간 — RSI 값 중 최고/최저 탐색 범위 (기본 14)
 * signalPeriod    : %D(Signal) 이동평균 기간 (기본 3)
 * maxWickRatio    : "꼬리 없는 캔들" 판정 허용 비율 (꼬리 길이 / 몸통 길이).
 *                   0.0 = 꼬리가 전혀 없어야 함(원작 룰), 값이 클수록 완화 (기본 0.0)
 * stopLossPct     : 고정 손절 비율 % (기본 1.5)
 * takeProfitPct   : 고정 익절 비율 % (기본 3.0) — 기본 손익비 1:2
 * </pre>
 */
public class HeikinAshiStochConfig extends StrategyConfig {

    private int    emaPeriod     = 200;
    private int    rsiPeriod     = 14;
    private int    stochPeriod   = 14;
    private int    signalPeriod  = 3;
    private double maxWickRatio  = 0.0;
    private double stopLossPct   = 1.5;
    private double takeProfitPct = 3.0;

    @Override
    public String getStrategyType() {
        return "HEIKIN_ASHI_STOCH";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "emaPeriod",     emaPeriod,
                "rsiPeriod",     rsiPeriod,
                "stochPeriod",   stochPeriod,
                "signalPeriod",  signalPeriod,
                "maxWickRatio",  maxWickRatio,
                "stopLossPct",   stopLossPct,
                "takeProfitPct", takeProfitPct
        );
    }

    public int getEmaPeriod()           { return emaPeriod; }
    public void setEmaPeriod(int v)     { this.emaPeriod = v; }

    public int getRsiPeriod()           { return rsiPeriod; }
    public void setRsiPeriod(int v)     { this.rsiPeriod = v; }

    public int getStochPeriod()         { return stochPeriod; }
    public void setStochPeriod(int v)   { this.stochPeriod = v; }

    public int getSignalPeriod()        { return signalPeriod; }
    public void setSignalPeriod(int v)  { this.signalPeriod = v; }

    public double getMaxWickRatio()         { return maxWickRatio; }
    public void setMaxWickRatio(double v)   { this.maxWickRatio = v; }

    public double getStopLossPct()          { return stopLossPct; }
    public void setStopLossPct(double v)    { this.stopLossPct = v; }

    public double getTakeProfitPct()        { return takeProfitPct; }
    public void setTakeProfitPct(double v)  { this.takeProfitPct = v; }

    public static HeikinAshiStochConfig fromParams(Map<String, Object> params) {
        HeikinAshiStochConfig config = new HeikinAshiStochConfig();
        if (params == null) {
            return config;
        }
        Object emaPeriodVal = params.get("emaPeriod");
        if (emaPeriodVal instanceof Number) {
            config.setEmaPeriod(((Number) emaPeriodVal).intValue());
        }
        Object rsiPeriodVal = params.get("rsiPeriod");
        if (rsiPeriodVal instanceof Number) {
            config.setRsiPeriod(((Number) rsiPeriodVal).intValue());
        }
        Object stochPeriodVal = params.get("stochPeriod");
        if (stochPeriodVal instanceof Number) {
            config.setStochPeriod(((Number) stochPeriodVal).intValue());
        }
        Object signalPeriodVal = params.get("signalPeriod");
        if (signalPeriodVal instanceof Number) {
            config.setSignalPeriod(((Number) signalPeriodVal).intValue());
        }
        Object maxWickRatioVal = params.get("maxWickRatio");
        if (maxWickRatioVal instanceof Number) {
            config.setMaxWickRatio(((Number) maxWickRatioVal).doubleValue());
        }
        Object stopLossPctVal = params.get("stopLossPct");
        if (stopLossPctVal instanceof Number) {
            config.setStopLossPct(((Number) stopLossPctVal).doubleValue());
        }
        Object takeProfitPctVal = params.get("takeProfitPct");
        if (takeProfitPctVal instanceof Number) {
            config.setTakeProfitPct(((Number) takeProfitPctVal).doubleValue());
        }
        return config;
    }
}
