package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 동일 조건(코인/타임프레임/투자금)으로 여러 전략을 한 번에 실전매매 세션으로 등록하는 요청 DTO.
 * 각 strategyType마다 독립 세션(CREATED 상태)이 생성된다.
 */
@Getter
@Setter
public class MultiStrategyLiveTradingRequest {

    /** 등록할 전략 목록 (예: ["RSI", "EMA_CROSS", "BOLLINGER"]) */
    @NotEmpty(message = "전략 목록은 비어있을 수 없습니다")
    private List<String> strategyTypes;

    @NotBlank(message = "코인 페어를 입력하세요")
    private String coinPair;

    @NotBlank(message = "타임프레임을 입력하세요")
    private String timeframe;

    @NotNull(message = "투자 원금을 입력하세요")
    @DecimalMin(value = "10000", message = "최소 투자금은 10,000 KRW입니다")
    private BigDecimal initialCapital;

    /** 손절률 (기본 5%) */
    private BigDecimal stopLossPct;

    /** 투자 비율 1~100 (기본 80) */
    private BigDecimal investRatio;
}
