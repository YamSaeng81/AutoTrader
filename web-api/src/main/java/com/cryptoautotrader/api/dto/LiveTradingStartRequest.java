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

    /** 투자 비율 0.1 ~ 1.0 (기본 0.25 = 25%) — 매수 시 availableKrw × investRatio */
    private BigDecimal investRatio;

    /**
     * 1회 매수금 상한 (KRW, 선택) — 매수금 = min(availableKrw × investRatio, maxInvestment).
     * 엔티티·매수 계산에는 있었지만 생성 요청으로 넣을 길이 없었다. 미지정이면 종전대로 상한 없음.
     * 이익으로 세션 잔액이 포지션당 한도(STAGE_1 20,000)를 넘으면 매수가 PER_POSITION_CAP 으로 거절되므로,
     * 고정 금액으로 반복 진입하는 세션은 이 값을 한도에 맞춘다(docs/LIMITED_LIVE_TEST_PLAN.md §5-1, 2026-10-29).
     */
    @DecimalMin(value = "5000", message = "maxInvestment 는 최소 주문금액 5,000 KRW 이상이어야 합니다")
    private BigDecimal maxInvestment;

    /**
     * 최대 보유시간(시) — time stop. 초과 시 손익과 무관하게 시장가 청산.
     * 미지정·0 이하면 비활성(기본값). dynamic_session의 동일 필드와 짝을 맞춘다.
     */
    private Integer maxHoldHours;

    /**
     * EXPERIMENTAL(실험) 전략의 실전 세션 생성을 명시적으로 허용하는 안전 스위치.
     * 기본 false → ENABLED 전략만 실전 허용. 관찰 전용으로 실험 전략을 돌릴 때만 true.
     */
    private boolean allowExperimentalLive = false;

    /** 전략 파라미터 (선택) */
    private Map<String, Object> strategyParams;
}
