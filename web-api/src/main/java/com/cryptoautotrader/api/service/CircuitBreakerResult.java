package com.cryptoautotrader.api.service;

/**
 * 서킷 브레이커 체크 결과
 */
public class CircuitBreakerResult {

    private final boolean triggered;
    private final boolean entryBlocked;
    private final String reason;

    private CircuitBreakerResult(boolean triggered, boolean entryBlocked, String reason) {
        this.triggered = triggered;
        this.entryBlocked = entryBlocked;
        this.reason = reason;
    }

    public static CircuitBreakerResult pass() {
        return new CircuitBreakerResult(false, false, null);
    }

    public static CircuitBreakerResult triggered(String reason) {
        return new CircuitBreakerResult(true, true, reason);
    }

    /**
     * 연속 손실 <b>판정 불가</b> (총괄 v1.191 §2) — 확정 비용 기준 손익을 구하지 못한 청산이 연속 손실 줄에 걸렸다.
     *
     * <p>🔴 발동이 아니다({@link #isTriggered()} = false) — 비상 정지·보유 청산을 하지 않는다. 신규 진입만 막는다({@link #isEntryBlocked()}).
     * 손익 0·이익으로 보고 줄을 끊지도, 손실로 보고 발동시키지도 않는다.</p>
     */
    public static CircuitBreakerResult entryUndetermined(String reason) {
        return new CircuitBreakerResult(false, true, reason);
    }

    public boolean isTriggered() { return triggered; }
    /** 신규 진입을 막아야 하는가 — 발동 또는 판정 불가. */
    public boolean isEntryBlocked() { return entryBlocked; }
    public String getReason() { return reason; }
}
