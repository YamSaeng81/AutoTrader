package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.DecimalMin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 전략 설정 수정 요청 DTO (모든 필드 선택적)
 */
@Getter
@Setter
public class StrategyConfigUpdateRequest {

    private String name;
    private String strategyType;
    private String coinPair;
    private String timeframe;
    private Map<String, Object> configJson;

    @DecimalMin(value = "0", inclusive = false, message = "maxInvestment는 0보다 커야 합니다")
    private BigDecimal maxInvestment;

    @DecimalMin(value = "0", inclusive = false, message = "stopLossPct는 0보다 커야 합니다")
    private BigDecimal stopLossPct;

    @DecimalMin(value = "0", inclusive = false, message = "reinvestPct는 0보다 커야 합니다")
    private BigDecimal reinvestPct;
}
