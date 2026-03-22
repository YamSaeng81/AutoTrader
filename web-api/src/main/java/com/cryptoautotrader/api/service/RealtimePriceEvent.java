package com.cryptoautotrader.api.service;

import java.math.BigDecimal;

/** WebSocket 실시간 시세 이벤트 — 손절 체크용 */
public class RealtimePriceEvent {

    private final String coinCode;
    private final BigDecimal price;

    public RealtimePriceEvent(String coinCode, BigDecimal price) {
        this.coinCode = coinCode;
        this.price = price;
    }

    public String getCoinCode() { return coinCode; }
    public BigDecimal getPrice() { return price; }
}
