package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

@Getter
@Setter
public class PaperTradingStartRequest {

    @NotBlank
    private String strategyType;

    @NotBlank
    private String coinPair;

    @NotBlank
    private String timeframe;

    @NotNull
    @DecimalMin("100000")
    private BigDecimal initialCapital;

    private Map<String, Object> strategyParams;

    private boolean enableTelegram = false;
}
