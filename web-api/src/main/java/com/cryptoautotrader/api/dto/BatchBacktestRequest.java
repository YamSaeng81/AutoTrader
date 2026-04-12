package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 다수 코인 × 다수 전략 배치 백테스트 요청 DTO.
 * 모든 (coinPair × strategyType) 조합에 대해 독립 백테스트가 실행된다.
 */
@Getter
@Setter
public class BatchBacktestRequest {

    /** 테스트할 코인 목록 (예: ["KRW-BTC", "KRW-ETH", "KRW-SOL"]) */
    @NotEmpty(message = "코인 목록은 비어있을 수 없습니다")
    private List<String> coinPairs;

    /** 테스트할 전략 목록 (예: ["RSI", "EMA_CROSS", "BOLLINGER"]) */
    @NotEmpty(message = "전략 목록은 비어있을 수 없습니다")
    private List<String> strategyTypes;

    @NotNull(message = "타임프레임을 입력하세요")
    private String timeframe;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    private BigDecimal initialCapital;
    private BigDecimal slippagePct;
    private BigDecimal feePct;
}
