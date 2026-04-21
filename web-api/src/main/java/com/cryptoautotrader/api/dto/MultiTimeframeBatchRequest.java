package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 전략 N × 코인 M × 타임프레임 K 조합 배치 백테스트 요청 DTO.
 * 모든 (strategyType × coinPair × timeframe) 조합에 대해 독립 백테스트가 순차 실행된다.
 */
@Getter
@Setter
public class MultiTimeframeBatchRequest {

    @NotEmpty(message = "전략 목록은 비어있을 수 없습니다")
    private List<String> strategyTypes;

    @NotEmpty(message = "코인 목록은 비어있을 수 없습니다")
    private List<String> coinPairs;

    /** 테스트할 타임프레임 목록 (예: ["H1", "M15"]) */
    @NotEmpty(message = "타임프레임 목록은 비어있을 수 없습니다")
    private List<String> timeframes;

    @NotNull(message = "시작일을 입력하세요")
    private LocalDate startDate;

    @NotNull(message = "종료일을 입력하세요")
    private LocalDate endDate;

    private BigDecimal initialCapital;
    private BigDecimal slippagePct;
    private BigDecimal feePct;
}
