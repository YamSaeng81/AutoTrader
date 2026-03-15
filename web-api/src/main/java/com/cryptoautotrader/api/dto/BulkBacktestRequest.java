package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class BulkBacktestRequest {

    /** 테스트할 코인 목록 (예: ["KRW-BTC", "KRW-ETH"]) */
    @NotEmpty
    private List<String> coins;

    /** 타임프레임 (예: H1) */
    @NotNull
    private String timeframe;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    private BigDecimal initialCapital;
    private BigDecimal slippagePct;
    private BigDecimal feePct;
}
