package com.cryptoautotrader.api.service;

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
 */
public record ForceRefreshResult(Outcome outcome, Instant observedAt) {

    public enum Outcome {
        /** 조회 실패·응답 없음 — 발행하지 않았다. */
        FAILED,
        /** 조회는 됐으나 체결 시각이 유효기간을 넘었다 — 처리기가 거절하므로 SL 점검·매도가 없다. */
        STALE,
        /** 신선한 시세를 발행했다 — 점검은 처리기가 비동기로 한다(아직 확인 전). */
        PUBLISHED
    }

    static ForceRefreshResult failed() {
        return new ForceRefreshResult(Outcome.FAILED, null);
    }

    /** 알림 문구용 시세 나이 — "체결 n초 전" 또는 "체결 시각 없음". */
    String describeAge(Instant now) {
        if (observedAt == null || Instant.EPOCH.equals(observedAt)) return "체결 시각 없음";
        return "체결 " + Duration.between(observedAt, now).getSeconds() + "초 전";
    }
}
