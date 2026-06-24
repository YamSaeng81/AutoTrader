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
 *                   0.0 = 꼬리가 전혀 없어야 함(원작 룰), 값이 클수록 완화 (기본 0.25, 보완안 1)
 * stopLossPct     : 고정 손절 비율 % (기본 1.5)
 * takeProfitPct   : 고정 익절 비율 % (기본 3.0) — 기본 손익비 1:2
 * requireBodyGrowth : "직전보다 몸통이 길어짐"을 진입 필수로 둘지 (기본 true=원작 필수;
 *                     false=가산점. 보완안 4는 백테스트상 4코인 전부 악화로 기각 → 기본 true 유지)
 * bodyGrowthBonus   : 몸통 길어진 캔들 strength 가산점 (기본 10)
 * volumeFilterRatio : 현재 거래량 ≥ 직전 N캔들 평균 × 이 값일 때만 BUY (기본 0.8, 0=비활성, 보완안 8)
 * volumeAvgPeriod   : 거래량 평균 산출 캔들 수 (기본 20)
 * </pre>
 */
public class HeikinAshiStochConfig extends StrategyConfig {

    private int     emaPeriod         = 200;
    private int     rsiPeriod         = 14;
    private int     stochPeriod       = 14;
    private int     signalPeriod      = 3;
    private double  maxWickRatio      = 0.25;
    private double  stopLossPct       = 1.5;
    private double  takeProfitPct     = 3.0;
    private boolean requireBodyGrowth = true;
    private double  bodyGrowthBonus   = 10.0;
    private double  volumeFilterRatio = 0.8;
    private int     volumeAvgPeriod   = 20;

    @Override
    public String getStrategyType() {
        return "HEIKIN_ASHI_STOCH";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.ofEntries(
                Map.entry("emaPeriod",         emaPeriod),
                Map.entry("rsiPeriod",         rsiPeriod),
                Map.entry("stochPeriod",       stochPeriod),
                Map.entry("signalPeriod",      signalPeriod),
                Map.entry("maxWickRatio",      maxWickRatio),
                Map.entry("stopLossPct",       stopLossPct),
                Map.entry("takeProfitPct",     takeProfitPct),
                Map.entry("requireBodyGrowth", requireBodyGrowth),
                Map.entry("bodyGrowthBonus",   bodyGrowthBonus),
                Map.entry("volumeFilterRatio", volumeFilterRatio),
                Map.entry("volumeAvgPeriod",   volumeAvgPeriod)
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

    public boolean isRequireBodyGrowth()        { return requireBodyGrowth; }
    public void setRequireBodyGrowth(boolean v) { this.requireBodyGrowth = v; }

    public double getBodyGrowthBonus()          { return bodyGrowthBonus; }
    public void setBodyGrowthBonus(double v)    { this.bodyGrowthBonus = v; }

    public double getVolumeFilterRatio()        { return volumeFilterRatio; }
    public void setVolumeFilterRatio(double v)  { this.volumeFilterRatio = v; }

    public int getVolumeAvgPeriod()             { return volumeAvgPeriod; }
    public void setVolumeAvgPeriod(int v)       { this.volumeAvgPeriod = v; }

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
        Object requireBodyGrowthVal = params.get("requireBodyGrowth");
        if (requireBodyGrowthVal instanceof Boolean) {
            config.setRequireBodyGrowth((Boolean) requireBodyGrowthVal);
        }
        Object bodyGrowthBonusVal = params.get("bodyGrowthBonus");
        if (bodyGrowthBonusVal instanceof Number) {
            config.setBodyGrowthBonus(((Number) bodyGrowthBonusVal).doubleValue());
        }
        Object volumeFilterRatioVal = params.get("volumeFilterRatio");
        if (volumeFilterRatioVal instanceof Number) {
            config.setVolumeFilterRatio(((Number) volumeFilterRatioVal).doubleValue());
        }
        Object volumeAvgPeriodVal = params.get("volumeAvgPeriod");
        if (volumeAvgPeriodVal instanceof Number) {
            config.setVolumeAvgPeriod(((Number) volumeAvgPeriodVal).intValue());
        }
        return config;
    }
}
