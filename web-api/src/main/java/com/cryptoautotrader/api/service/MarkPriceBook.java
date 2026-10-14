package com.cryptoautotrader.api.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 코인별 <b>마지막 시세</b> — 공유 풀 순자산 평가용 (2026-10-14).
 *
 * <p>실시간 시세 이벤트(웹소켓 틱 · REST 대체 조회 · 코인별 강제 갱신)를 그대로 받아 적는다. 새 조회를 하지 않는다 —
 * 평가가 계좌 잠금 안에서 일어나므로 거기서 거래소를 부르면 잠금이 그 호출 동안 묶인다.</p>
 *
 * <p>🔴 체결이 드문 코인은 마지막 시세가 오래될 수 있다. 시세는 "마지막 체결가" 이므로 오래됐다고 틀린 값은 아니지만,
 * 구독이 조용히 끊긴 경우와 구분되지 않는다 — 시각을 함께 남겨 판정 기록에 적는다.</p>
 */
@Component
public class MarkPriceBook {

    public record Mark(BigDecimal price, Instant at) {}

    private final Map<String, Mark> marks = new ConcurrentHashMap<>();

    @EventListener
    public void onPrice(RealtimePriceEvent event) {
        record(event.getCoinCode(), event.getPrice(), Instant.now());
    }

    public void record(String coinPair, BigDecimal price, Instant at) {
        if (coinPair == null || price == null || price.signum() <= 0) return;
        marks.put(coinPair, new Mark(price, at));
    }

    /** 없으면 {@code null}. */
    public Mark get(String coinPair) {
        return coinPair == null ? null : marks.get(coinPair);
    }

    /** 시험용 — 기록을 지운다. */
    public void clear() {
        marks.clear();
    }
}
