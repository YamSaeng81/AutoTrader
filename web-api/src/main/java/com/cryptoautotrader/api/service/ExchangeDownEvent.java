package com.cryptoautotrader.api.service;

import org.springframework.context.ApplicationEvent;

/**
 * 거래소 DOWN 감지 시 발행되는 이벤트.
 * ExchangeHealthMonitor → LiveTradingService 순환 참조 방지용.
 */
public class ExchangeDownEvent extends ApplicationEvent {

    private final String reason;

    public ExchangeDownEvent(Object source, String reason) {
        super(source);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
