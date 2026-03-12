package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Getter
@Setter
public class WalkForwardRequest {

    @NotBlank
    private String strategyType;

    @NotBlank
    private String coinPair;

    @NotBlank
    private String timeframe;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    private double inSampleRatio = 0.7;
    private int windowCount = 3;

    private BigDecimal initialCapital;
    private BigDecimal slippagePct;
    private BigDecimal feePct;
    private Map<String, Object> config;
}
