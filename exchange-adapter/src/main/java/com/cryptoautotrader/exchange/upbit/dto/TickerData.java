package com.cryptoautotrader.exchange.upbit.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Upbit WebSocket ticker(실시간 시세) 응답 DTO
 */
@Data
@Builder
public class TickerData {

    /** 마켓 코드 (예: "KRW-BTC") */
    private String code;

    /** 현재가 */
    private BigDecimal tradePrice;

    /** 고가 */
    private BigDecimal highPrice;

    /** 저가 */
    private BigDecimal lowPrice;

    /** 전일 종가 */
    private BigDecimal prevClosingPrice;

    /** 24시간 누적 거래량 */
    private BigDecimal accTradeVolume24h;

    /** 전일 대비 변화율 */
    private BigDecimal signedChangeRate;

    /** 전일 대비: "RISE", "EVEN", "FALL" */
    private String change;

    /** 타임스탬프 */
    private Instant timestamp;
}
