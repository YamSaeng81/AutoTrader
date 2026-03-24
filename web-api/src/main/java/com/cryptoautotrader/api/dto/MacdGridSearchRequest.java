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
public class MacdGridSearchRequest {

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

    /** fastPeriod 최솟값 (기본 8) */
    private int fastMin = 8;

    /** fastPeriod 최댓값 (기본 15) */
    private int fastMax = 15;

    /** slowPeriod 최솟값 (기본 20) */
    private int slowMin = 20;

    /** slowPeriod 최댓값 (기본 30) */
    private int slowMax = 30;

    /** signalPeriod 고정값 (기본 9) */
    private int signalPeriod = 9;

    private BigDecimal initialCapital;
    private BigDecimal slippagePct;
    private BigDecimal feePct;
}
