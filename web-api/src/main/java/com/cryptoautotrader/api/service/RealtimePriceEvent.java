package com.cryptoautotrader.api.service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 실시간 시세 이벤트 — 손절 체크·시세 장부({@link MarkPriceBook})용.
 *
 * <p>🔴 2026-10-15: 가격이 <b>언제의 값인지</b>를 함께 싣는다. 웹소켓 틱이든 REST 조회든 업비트가 주는 가격은 마지막 체결가이고, 체결이
 * 드문 코인은 오래전 체결가가 지금 다시 온다. 받은 시각으로 최신성을 판단하면 낡은 가격을 신선한 가격으로 인정하게 된다 —
 * {@code observedAt} 은 체결 시각이다(없으면 {@code null}).</p>
 */
public class RealtimePriceEvent {

    /** 가격 출처. */
    public static final String WS_TRADE = "WS_TRADE";
    public static final String REST_TRADE = "REST_TRADE";

    private final String coinCode;
    private final BigDecimal price;
    private final Instant observedAt;
    private final String source;

    /** 출처·체결 시각을 모르는 이벤트 — 시험용. 장부는 받은 시각을 기준으로 적는다. */
    public RealtimePriceEvent(String coinCode, BigDecimal price) {
        this(coinCode, price, null, null);
    }

    public RealtimePriceEvent(String coinCode, BigDecimal price, Instant observedAt, String source) {
        this.coinCode = coinCode;
        this.price = price;
        this.observedAt = observedAt;
        this.source = source;
    }

    public String getCoinCode() { return coinCode; }
    public BigDecimal getPrice() { return price; }
    /** 이 가격의 체결 시각 — 모르면 {@code null}. */
    public Instant getObservedAt() { return observedAt; }
    /** {@link #WS_TRADE}·{@link #REST_TRADE} — 모르면 {@code null}. */
    public String getSource() { return source; }

    /** 업비트 REST 시세 응답의 {@code trade_timestamp}(밀리초) — 없으면 {@code null}. */
    public static Instant tradeTime(java.util.Map<String, Object> ticker) {
        Object ts = ticker.get("trade_timestamp");
        if (ts instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        if (ts != null) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(ts.toString()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
