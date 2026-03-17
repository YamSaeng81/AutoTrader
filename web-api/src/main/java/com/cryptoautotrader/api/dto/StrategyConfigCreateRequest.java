package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 전략 설정 생성 요청 DTO
 */
@Getter
@Setter
public class StrategyConfigCreateRequest {

    @NotBlank(message = "name은 필수입니다")
    private String name;

    @NotBlank(message = "strategyType은 필수입니다")
    private String strategyType;

    @NotBlank(message = "coinPair는 필수입니다")
    private String coinPair;

    @NotBlank(message = "timeframe은 필수입니다")
    private String timeframe;

    private Map<String, Object> configJson;

    @DecimalMin(value = "0", inclusive = false, message = "maxInvestment는 0보다 커야 합니다")
    private BigDecimal maxInvestment;

    @DecimalMin(value = "0", inclusive = false, message = "stopLossPct는 0보다 커야 합니다")
    private BigDecimal stopLossPct;

    @DecimalMin(value = "0", inclusive = false, message = "reinvestPct는 0보다 커야 합니다")
    private BigDecimal reinvestPct;
}
