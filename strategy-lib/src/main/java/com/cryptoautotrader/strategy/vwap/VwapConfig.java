package com.cryptoautotrader.strategy.vwap;

import com.cryptoautotrader.strategy.StrategyConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * VWAP 역추세 전략 파라미터
 * - thresholdPct: VWAP 대비 이탈 임계값 (기본 1.0%)
 * - period: VWAP 계산 기간 (기본 20)
 */
@Getter
@Setter
public class VwapConfig extends StrategyConfig {

    private double thresholdPct = 1.0;
    private int period = 20;

    @Override
    public String getStrategyType() {
        return "VWAP";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "thresholdPct", thresholdPct,
                "period", period
        );
    }

    /**
     * Map 파라미터에서 VwapConfig를 생성한다.
     * 키가 없거나 타입이 맞지 않으면 기본값을 사용한다.
     */
    public static VwapConfig fromParams(Map<String, Object> params) {
        VwapConfig config = new VwapConfig();
        if (params == null) {
            return config;
        }
        Object thresholdPctVal = params.get("thresholdPct");
        if (thresholdPctVal instanceof Number) {
            config.setThresholdPct(((Number) thresholdPctVal).doubleValue());
        }
        Object periodVal = params.get("period");
        if (periodVal instanceof Number) {
            config.setPeriod(((Number) periodVal).intValue());
        }
        return config;
    }
}
