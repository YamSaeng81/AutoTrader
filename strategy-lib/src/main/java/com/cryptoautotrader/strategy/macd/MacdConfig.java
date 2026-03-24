package com.cryptoautotrader.strategy.macd;

import com.cryptoautotrader.strategy.StrategyConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * MACD 전략 파라미터
 * - fastPeriod: 단기 EMA 기간 (기본 12)
 * - slowPeriod: 장기 EMA 기간 (기본 26)
 * - signalPeriod: 시그널 EMA 기간 (기본 9)
 */
@Getter
@Setter
public class MacdConfig extends StrategyConfig {

    // 기본값: 그외 코인용 (12,24,9)
    // BTC → (14,22,9), ETH → (10,26,9) 는 MacdStrategy.coinDefaults()에서 자동 적용
    private int fastPeriod = 12;
    private int slowPeriod = 24;
    private int signalPeriod = 9;

    @Override
    public String getStrategyType() {
        return "MACD";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "fastPeriod", fastPeriod,
                "slowPeriod", slowPeriod,
                "signalPeriod", signalPeriod
        );
    }

    public static MacdConfig fromParams(Map<String, Object> params) {
        MacdConfig config = new MacdConfig();
        if (params == null) {
            return config;
        }
        Object fastVal = params.get("fastPeriod");
        if (fastVal instanceof Number) {
            config.setFastPeriod(((Number) fastVal).intValue());
        }
        Object slowVal = params.get("slowPeriod");
        if (slowVal instanceof Number) {
            config.setSlowPeriod(((Number) slowVal).intValue());
        }
        Object signalVal = params.get("signalPeriod");
        if (signalVal instanceof Number) {
            config.setSignalPeriod(((Number) signalVal).intValue());
        }
        return config;
    }
}
