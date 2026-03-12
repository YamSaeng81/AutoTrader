package com.cryptoautotrader.api.service;

import org.springframework.context.ApplicationEvent;

/**
 * 거래소 DOWN 감지 시 발행되는 이벤트.
 * ExchangeHealthMonitor → LiveTradingService 순환 참조 방지용.
 */
public class ExchangeDownEvent extends ApplicationEvent {
    public ExchangeDownEvent(Object source) {
        super(source);
    }
}
