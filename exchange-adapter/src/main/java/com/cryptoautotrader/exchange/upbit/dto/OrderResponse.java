package com.cryptoautotrader.exchange.upbit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Upbit 주문 API 응답 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderResponse {

    /** 주문 고유 ID */
    private String uuid;

    /** 매수/매도: "bid" 또는 "ask" */
    private String side;

    /** 주문 유형: "limit", "price", "market" */
    @JsonProperty("ord_type")
    private String ordType;

    /** 주문 가격 */
    private BigDecimal price;

    /** 주문 상태: "wait", "watch", "done", "cancel" */
    private String state;

    /** 마켓 코드 (예: "KRW-BTC") */
    private String market;

    /** 주문량 */
    private BigDecimal volume;

    /** 잔여 주문량 */
    @JsonProperty("remaining_volume")
    private BigDecimal remainingVolume;

    /** 체결 수량 */
    @JsonProperty("executed_volume")
    private BigDecimal executedVolume;

    /** 체결 금액 합계 (KRW) — market 타입 매도 시 평균 단가 산출에 사용 */
    @JsonProperty("executed_funds")
    private BigDecimal executedFunds;

    /** 지불 수수료 */
    @JsonProperty("paid_fee")
    private BigDecimal paidFee;

    /** 주문 생성 시각 */
    @JsonProperty("created_at")
    private Instant createdAt;
}
