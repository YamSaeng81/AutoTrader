package com.cryptoautotrader.exchange.upbit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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

    /**
     * 개별 체결 내역 — GET /v1/order(단건) 응답에만 포함된다.
     * Upbit은 시장가 매도의 체결 금액을 최상위 executed_funds 가 아니라
     * trades[].funds 로 내려주는 경우가 있어, 평균 단가 산출의 신뢰 소스로 사용한다.
     */
    @JsonProperty("trades")
    private List<Trade> trades;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Trade {
        private BigDecimal price;
        private BigDecimal volume;
        /** 체결 금액 (= price * volume) */
        private BigDecimal funds;
    }

    /**
     * 체결 금액 합계를 반환한다.
     * 최상위 executed_funds 가 있으면 그대로, 없으면 trades[].funds(또는 price*volume) 합산으로 산출한다.
     * 둘 다 없으면 null (= 아직 정산 전).
     */
    public BigDecimal resolveExecutedFunds() {
        if (executedFunds != null) {
            return executedFunds;
        }
        if (trades == null || trades.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        boolean any = false;
        for (Trade t : trades) {
            if (t.getFunds() != null) {
                sum = sum.add(t.getFunds());
                any = true;
            } else if (t.getPrice() != null && t.getVolume() != null) {
                sum = sum.add(t.getPrice().multiply(t.getVolume()));
                any = true;
            }
        }
        return any ? sum : null;
    }
}
