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
public class BacktestRequest {

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

    private BigDecimal initialCapital;
    private BigDecimal slippagePct;
    private BigDecimal feePct;
    private Map<String, Object> config;

    // Fill Simulation
    private FillSimulationConfig fillSimulation;

    @Getter
    @Setter
    public static class FillSimulationConfig {
        private boolean enabled;
        private BigDecimal impactFactor;
        private BigDecimal fillRatio;
    }
}
