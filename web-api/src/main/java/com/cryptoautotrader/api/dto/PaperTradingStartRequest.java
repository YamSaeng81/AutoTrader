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

    /**
     * 손절률(%) — 미지정 시 {@code risk_config} 기본값(5.0).
     * LIVE 세션과 동일 조건으로 검증하려면 그 세션의 값을 그대로 넣을 것. (2026-08-06 LIVE 정렬)
     */
    private BigDecimal stopLossPct;

    /** 투자 비율(0.1~1.0) — 미지정 시 {@code risk_config} 기본값(0.80). */
    private BigDecimal investRatio;

    /** 최대 보유시간(시) — time stop. 미지정·0 이하면 비활성(LIVE 기본값과 동일). */
    private Integer maxHoldHours;

    private boolean enableTelegram = false;
}
