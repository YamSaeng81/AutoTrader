package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 주문 생성 요청 DTO
 */
@Getter
@Setter
public class OrderRequest {

    /** 코인 페어 (예: "KRW-BTC") */
    @NotBlank(message = "coinPair는 필수입니다")
    private String coinPair;

    /** 매수/매도: "BUY" 또는 "SELL" */
    @NotBlank(message = "side는 필수입니다")
    private String side;

    /** 주문 유형: "MARKET" 또는 "LIMIT" */
    @NotBlank(message = "orderType은 필수입니다")
    private String orderType;

    /** LIMIT 주문 시 가격 */
    private BigDecimal price;

    /** 주문 수량 */
    @NotNull(message = "quantity는 필수입니다")
    private BigDecimal quantity;

    /** 주문 사유 (전략 신호, 수동 주문 등) */
    private String reason;
}
