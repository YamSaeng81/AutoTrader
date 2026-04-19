package com.cryptoautotrader.strategy.fvg;

import com.cryptoautotrader.strategy.StrategyConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * Fair Value Gap 전략 파라미터
 *
 * <ul>
 *   <li>{@code emaPeriod}       — EMA 추세 필터 기간 (기본 20)</li>
 *   <li>{@code emaFilterEnabled} — EMA 필터 활성화 여부 (기본 true). false 시 순수 FVG 패턴만 사용</li>
 *   <li>{@code minGapPct}       — 유효 공백 최소 크기 (기준가 대비 %, 기본 0.1). 미세 노이즈 FVG 제거</li>
 * </ul>
 */
@Getter
@Setter
public class FairValueGapConfig extends StrategyConfig {

    private int emaPeriod = 20;
    private boolean emaFilterEnabled = true;
    private double minGapPct = 0.1;

    @Override
    public String getStrategyType() {
        return "FAIR_VALUE_GAP";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "emaPeriod", emaPeriod,
                "emaFilterEnabled", emaFilterEnabled,
                "minGapPct", minGapPct
        );
    }

    public static FairValueGapConfig fromParams(Map<String, Object> params) {
        FairValueGapConfig config = new FairValueGapConfig();
        if (params == null) return config;

        Object emaPeriodVal = params.get("emaPeriod");
        if (emaPeriodVal instanceof Number) {
            config.setEmaPeriod(((Number) emaPeriodVal).intValue());
        }
        Object emaFilterVal = params.get("emaFilterEnabled");
        if (emaFilterVal instanceof Boolean) {
            config.setEmaFilterEnabled((Boolean) emaFilterVal);
        }
        Object minGapVal = params.get("minGapPct");
        if (minGapVal instanceof Number) {
            config.setMinGapPct(((Number) minGapVal).doubleValue());
        }
        return config;
    }
}
