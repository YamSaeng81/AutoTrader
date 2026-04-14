package com.cryptoautotrader.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 다수 코인 × 다수 전략 Walk-Forward 배치 실행 요청 DTO.
 * 모든 (coinPair × strategyType) 조합에 대해 순차적으로 Walk-Forward가 실행된다.
 */
@Getter
@Setter
public class WalkForwardBatchRequest {

    /** 테스트할 코인 목록 (예: ["KRW-BTC", "KRW-ETH"]) */
    private List<String> coinPairs;

    /** 테스트할 전략 목록 (예: ["COMPOSITE_BREAKOUT", "COMPOSITE_MOMENTUM"]) */
    private List<String> strategyTypes;

    private String timeframe;

    private LocalDate startDate;

    private LocalDate endDate;

    /** 학습 기간 비율 (기본 0.7 = 70%) */
    private double inSampleRatio = 0.7;

    /** 윈도우 분할 수 (기본 5) */
    private int windowCount = 5;

    private BigDecimal initialCapital;
    private BigDecimal slippagePct;
    private BigDecimal feePct;
}
