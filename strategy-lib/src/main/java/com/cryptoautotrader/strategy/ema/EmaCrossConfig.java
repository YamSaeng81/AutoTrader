package com.cryptoautotrader.strategy.ema;

import com.cryptoautotrader.strategy.StrategyConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * EMA 크로스 전략 파라미터
 * - fastPeriod: 단기 EMA 기간 (기본 9)
 * - slowPeriod: 장기 EMA 기간 (기본 21)
 */
@Getter
@Setter
public class EmaCrossConfig extends StrategyConfig {

    private int fastPeriod = 9;
    private int slowPeriod = 21;

    @Override
    public String getStrategyType() {
        return "EMA_CROSS";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "fastPeriod", fastPeriod,
                "slowPeriod", slowPeriod
        );
    }

    /**
     * Map 파라미터에서 EmaCrossConfig를 생성한다.
     * 키가 없거나 타입이 맞지 않으면 기본값을 사용한다.
     */
    public static EmaCrossConfig fromParams(Map<String, Object> params) {
        EmaCrossConfig config = new EmaCrossConfig();
        if (params == null) {
            return config;
        }
        Object fastPeriodVal = params.get("fastPeriod");
        if (fastPeriodVal instanceof Number) {
            config.setFastPeriod(((Number) fastPeriodVal).intValue());
        }
        Object slowPeriodVal = params.get("slowPeriod");
        if (slowPeriodVal instanceof Number) {
            config.setSlowPeriod(((Number) slowPeriodVal).intValue());
        }
        return config;
    }
}
