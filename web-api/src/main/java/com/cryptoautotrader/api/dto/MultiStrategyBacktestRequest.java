package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 동일 조건(코인/타임프레임/기간/투자금)으로 여러 전략을 한 번에 백테스트하는 요청 DTO.
 * 각 strategyType마다 독립 백테스트 결과가 저장되며, 전략별 성과 비교표를 반환한다.
 */
@Getter
@Setter
public class MultiStrategyBacktestRequest {

    /** 테스트할 전략 목록 (예: ["RSI", "EMA_CROSS", "BOLLINGER"]) */
    @NotEmpty(message = "전략 목록은 비어있을 수 없습니다")
    private List<String> strategyTypes;

    @NotBlank(message = "코인 페어를 입력하세요")
    private String coinPair;

    @NotBlank(message = "타임프레임을 입력하세요")
    private String timeframe;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    private BigDecimal initialCapital;
    private BigDecimal slippagePct;
    private BigDecimal feePct;
}
