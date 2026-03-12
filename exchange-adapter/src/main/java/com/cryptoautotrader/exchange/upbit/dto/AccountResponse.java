package com.cryptoautotrader.exchange.upbit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Upbit 계좌 조회 API 응답 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountResponse {

    /** 화폐 코드 (예: "KRW", "BTC") */
    private String currency;

    /** 주문 가능 잔고 */
    private BigDecimal balance;

    /** 주문 중 묶인 금액 */
    private BigDecimal locked;

    /** 매수 평균가 */
    @JsonProperty("avg_buy_price")
    private BigDecimal avgBuyPrice;
}
