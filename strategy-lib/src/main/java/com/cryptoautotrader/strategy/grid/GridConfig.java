package com.cryptoautotrader.strategy.grid;

import com.cryptoautotrader.strategy.StrategyConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 그리드 전략 파라미터
 * - lookbackPeriod: 가격 범위 산정 기간 (기본 100)
 * - gridCount: 그리드 분할 수 (기본 10)
 * - triggerPct: 그리드 레벨 근접 트리거 비율 (기본 0.5%)
 */
@Getter
@Setter
public class GridConfig extends StrategyConfig {

    private int lookbackPeriod = 100;
    private int gridCount = 10;
    private double triggerPct = 0.5;

    @Override
    public String getStrategyType() {
        return "GRID";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "lookbackPeriod", lookbackPeriod,
                "gridCount", gridCount,
                "triggerPct", triggerPct
        );
    }

    /**
     * Map 파라미터에서 GridConfig를 생성한다.
     * 키가 없거나 타입이 맞지 않으면 기본값을 사용한다.
     */
    public static GridConfig fromParams(Map<String, Object> params) {
        GridConfig config = new GridConfig();
        if (params == null) {
            return config;
        }
        Object lookbackPeriodVal = params.get("lookbackPeriod");
        if (lookbackPeriodVal instanceof Number) {
            config.setLookbackPeriod(((Number) lookbackPeriodVal).intValue());
        }
        Object gridCountVal = params.get("gridCount");
        if (gridCountVal instanceof Number) {
            config.setGridCount(((Number) gridCountVal).intValue());
        }
        Object triggerPctVal = params.get("triggerPct");
        if (triggerPctVal instanceof Number) {
            config.setTriggerPct(((Number) triggerPctVal).doubleValue());
        }
        return config;
    }
}
