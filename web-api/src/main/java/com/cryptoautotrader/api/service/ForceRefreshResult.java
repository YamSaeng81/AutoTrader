package com.cryptoautotrader.api.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * SL 미점검 감시기의 REST 강제 갱신 결과 (N02 보완, 2026-11-01 총괄).
 *
 * <p>🔴 이것은 <b>조회·발행 결과</b>일 뿐 SL 점검 결과가 아니다. 실시간 처리기는 비동기({@code marketDataExecutor})라 발행한 순간에는
 * 점검이 아직 일어나지 않았고, 낡은 시세면 처리기가 거절한다({@link MarkPriceBook#isStale}). 실제 점검 여부는 각 서비스의
 * {@code lastSlCheckAt} 갱신으로만 안다 — 감시기는 다음 주기에 그것을 다시 본다.</p>
 *
 * @param outcome    {@link Outcome}
 * @param observedAt 받은 시세의 체결 시각 — 실패면 {@code null}, 응답에 없으면 {@link Instant#EPOCH}
 * @param price      {@link Outcome#INVALID_PRICE} 일 때 받은 값(알림 문구용) — 그 밖에는 {@code null}
 */
public record ForceRefreshResult(Outcome outcome, Instant observedAt, BigDecimal price) {

    public ForceRefreshResult(Outcome outcome, Instant observedAt) {
        this(outcome, observedAt, null);
    }

    public enum Outcome {
        /** 조회 실패·응답 없음 — 발행하지 않았다. */
        FAILED,
        /** 조회는 됐으나 체결 시각이 유효기간을 넘었다 — 처리기가 거절하므로 SL 점검·매도가 없다. */
        STALE,
        /** 신선한 시세를 발행했다 — 점검은 처리기가 비동기로 한다(아직 확인 전). */
        PUBLISHED,
        /**
         * 🔴 N17 (2026-11-05): 조회는 됐으나 체결가가 가격이 아니다(0·음수, {@link MarkPriceBook#isPrice}) — <b>발행하지 않았다</b>.
         * 신선함과 무관하다. SL 점검·매도 없음. 통신 실패({@link #FAILED})와 같은 원인으로 보지 않는다.
         */
        INVALID_PRICE
    }

    static ForceRefreshResult failed() {
        return new ForceRefreshResult(Outcome.FAILED, null);
    }

    static ForceRefreshResult invalidPrice(Instant observedAt, BigDecimal price) {
        return new ForceRefreshResult(Outcome.INVALID_PRICE, observedAt, price);
    }

    /** 알림 문구용 받은 값 — {@code toPlainString}. */
    String describePrice() {
        return price == null ? "없음" : price.toPlainString();
    }

    /** 알림 문구용 시세 나이 — "체결 n초 전" 또는 "체결 시각 없음". */
    String describeAge(Instant now) {
        if (observedAt == null || Instant.EPOCH.equals(observedAt)) return "체결 시각 없음";
        return "체결 " + Duration.between(observedAt, now).getSeconds() + "초 전";
    }
}
