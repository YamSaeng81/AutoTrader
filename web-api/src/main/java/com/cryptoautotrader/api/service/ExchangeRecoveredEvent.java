package com.cryptoautotrader.api.service;

import org.springframework.context.ApplicationEvent;

/**
 * 거래소 DOWN → UP 복구 시 발행되는 이벤트.
 * ExchangeHealthMonitor → LiveTradingService 순환 참조 방지용.
 */
public class ExchangeRecoveredEvent extends ApplicationEvent {

    private final String previousStatus;

    public ExchangeRecoveredEvent(Object source, String previousStatus) {
        super(source);
        this.previousStatus = previousStatus;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }
}
