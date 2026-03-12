package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class DataCollectRequest {

    @NotBlank
    private String coinPair;

    @NotBlank
    private String timeframe;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;
}
