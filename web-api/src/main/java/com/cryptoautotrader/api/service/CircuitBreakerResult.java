package com.cryptoautotrader.api.service;

/**
 * 서킷 브레이커 체크 결과
 */
public class CircuitBreakerResult {

    private final boolean triggered;
    private final String reason;

    private CircuitBreakerResult(boolean triggered, String reason) {
        this.triggered = triggered;
        this.reason = reason;
    }

    public static CircuitBreakerResult pass() {
        return new CircuitBreakerResult(false, null);
    }

    public static CircuitBreakerResult triggered(String reason) {
        return new CircuitBreakerResult(true, reason);
    }

    public boolean isTriggered() { return triggered; }
    public String getReason() { return reason; }
}
