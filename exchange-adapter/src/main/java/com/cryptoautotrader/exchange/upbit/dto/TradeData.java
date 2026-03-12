package com.cryptoautotrader.exchange.upbit.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Upbit WebSocket trade(실시간 체결) 응답 DTO
 */
@Data
@Builder
public class TradeData {

    /** 마켓 코드 (예: "KRW-BTC") */
    private String code;

    /** 체결 가격 */
    private BigDecimal tradePrice;

    /** 체결량 */
    private BigDecimal tradeVolume;

    /** 매수/매도 구분: "ASK"(매도) 또는 "BID"(매수) */
    private String askBid;

    /** 체결 타임스탬프 */
    private Instant timestamp;
}
