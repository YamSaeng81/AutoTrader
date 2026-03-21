package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 실전매매 세션 생성 요청 DTO
 */
@Getter
@Setter
public class LiveTradingStartRequest {

    /** 전략 유형: "VWAP", "EMA_CROSS" 등 */
    @NotBlank(message = "strategyType은 필수입니다")
    private String strategyType;

    /** 코인 페어: "KRW-BTC" */
    @NotBlank(message = "coinPair는 필수입니다")
    private String coinPair;

    /** 타임프레임: "M5", "H1" 등 */
    @NotBlank(message = "timeframe은 필수입니다")
    private String timeframe;

    /** 투자 원금 (KRW) */
    @NotNull(message = "initialCapital은 필수입니다")
    @DecimalMin(value = "10000", message = "최소 투자금은 10,000 KRW입니다")
    private BigDecimal initialCapital;

    /** 손절률 (기본 5%) */
    private BigDecimal stopLossPct;

    /** 투자 비율 0.1 ~ 1.0 (기본 0.80 = 80%) — 매수 시 availableKrw × investRatio */
    private BigDecimal investRatio;

    /** 전략 파라미터 (선택) */
    private Map<String, Object> strategyParams;
}
