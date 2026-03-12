package com.cryptoautotrader.strategy.atrbreakout;

import com.cryptoautotrader.strategy.StrategyConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * ATR Breakout 전략 파라미터
 * - atrPeriod: ATR 계산 기간 (기본 14)
 * - multiplier: 돌파 임계값 배수 (기본 1.5)
 * - useStopLoss: 손절 활성화 여부 (기본 true)
 */
@Getter
@Setter
public class AtrBreakoutConfig extends StrategyConfig {

    private int atrPeriod = 14;
    private double multiplier = 1.5;
    private boolean useStopLoss = true;

    @Override
    public String getStrategyType() {
        return "ATR_BREAKOUT";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "atrPeriod", atrPeriod,
                "multiplier", multiplier,
                "useStopLoss", useStopLoss
        );
    }
}
