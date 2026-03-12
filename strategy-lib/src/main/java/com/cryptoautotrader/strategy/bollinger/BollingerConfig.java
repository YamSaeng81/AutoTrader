package com.cryptoautotrader.strategy.bollinger;

import com.cryptoautotrader.strategy.StrategyConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 볼린저 밴드 전략 파라미터
 * - period: 이동 평균 기간 (기본 20)
 * - multiplier: 표준편차 배수 (기본 2.0)
 */
@Getter
@Setter
public class BollingerConfig extends StrategyConfig {

    private int period = 20;
    private double multiplier = 2.0;

    @Override
    public String getStrategyType() {
        return "BOLLINGER";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "period", period,
                "multiplier", multiplier
        );
    }

    /**
     * Map 파라미터에서 BollingerConfig를 생성한다.
     * 키가 없거나 타입이 맞지 않으면 기본값을 사용한다.
     */
    public static BollingerConfig fromParams(Map<String, Object> params) {
        BollingerConfig config = new BollingerConfig();
        if (params == null) {
            return config;
        }
        Object periodVal = params.get("period");
        if (periodVal instanceof Number) {
            config.setPeriod(((Number) periodVal).intValue());
        }
        Object multiplierVal = params.get("multiplier");
        if (multiplierVal instanceof Number) {
            config.setMultiplier(((Number) multiplierVal).doubleValue());
        }
        return config;
    }
}
