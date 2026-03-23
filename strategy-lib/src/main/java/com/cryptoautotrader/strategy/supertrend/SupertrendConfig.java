package com.cryptoautotrader.strategy.supertrend;

import com.cryptoautotrader.strategy.StrategyConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * Supertrend 전략 파라미터
 * - atrPeriod: ATR 계산 기간 (기본 10)
 * - multiplier: ATR 배수 - 밴드 너비 결정 (기본 3.0)
 */
@Getter
@Setter
public class SupertrendConfig extends StrategyConfig {

    private int atrPeriod = 10;
    private double multiplier = 3.0;

    @Override
    public String getStrategyType() {
        return "SUPERTREND";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "atrPeriod", atrPeriod,
                "multiplier", multiplier
        );
    }

    public static SupertrendConfig fromParams(Map<String, Object> params) {
        SupertrendConfig config = new SupertrendConfig();
        if (params == null) {
            return config;
        }
        Object atrPeriodVal = params.get("atrPeriod");
        if (atrPeriodVal instanceof Number) {
            config.setAtrPeriod(((Number) atrPeriodVal).intValue());
        }
        Object multiplierVal = params.get("multiplier");
        if (multiplierVal instanceof Number) {
            config.setMultiplier(((Number) multiplierVal).doubleValue());
        }
        return config;
    }
}
