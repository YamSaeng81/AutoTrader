package com.cryptoautotrader.strategy.rsi;

import com.cryptoautotrader.strategy.StrategyConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * RSI 전략 파라미터
 * - period: RSI 계산 기간 (기본 14)
 * - oversoldLevel: 과매도 기준 (기본 30)
 * - overboughtLevel: 과매수 기준 (기본 70)
 */
@Getter
@Setter
public class RsiConfig extends StrategyConfig {

    private int period = 14;
    private double oversoldLevel = 30.0;
    private double overboughtLevel = 70.0;

    @Override
    public String getStrategyType() {
        return "RSI";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "period", period,
                "oversoldLevel", oversoldLevel,
                "overboughtLevel", overboughtLevel
        );
    }
}
