package com.cryptoautotrader.strategy.volumedelta;

import com.cryptoautotrader.strategy.StrategyConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * Volume Delta 전략 파라미터
 * - lookback:        분석 기간 (기본 20)
 * - signalThreshold: 신호 발생 임계값 — 누적Delta비율이 이 값 이상/이하일 때 BUY/SELL (기본 0.10 = 10%)
 * - divergenceMode:  가격-Delta 다이버전스 필터 활성화 여부 (기본 true)
 */
@Getter
@Setter
public class VolumeDeltaConfig extends StrategyConfig {

    private int lookback = 20;
    private double signalThreshold = 0.10;
    private boolean divergenceMode = true;

    @Override
    public String getStrategyType() {
        return "VOLUME_DELTA";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "lookback",        lookback,
                "signalThreshold", signalThreshold,
                "divergenceMode",  divergenceMode
        );
    }

    public static VolumeDeltaConfig fromParams(Map<String, Object> params) {
        VolumeDeltaConfig config = new VolumeDeltaConfig();
        if (params == null) return config;

        Object lookbackVal = params.get("lookback");
        if (lookbackVal instanceof Number) config.setLookback(((Number) lookbackVal).intValue());

        Object thresholdVal = params.get("signalThreshold");
        if (thresholdVal instanceof Number) config.setSignalThreshold(((Number) thresholdVal).doubleValue());

        Object divVal = params.get("divergenceMode");
        if (divVal instanceof Boolean) config.setDivergenceMode((Boolean) divVal);

        return config;
    }
}
