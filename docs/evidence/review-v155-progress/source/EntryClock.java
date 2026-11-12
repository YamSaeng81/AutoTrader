package com.cryptoautotrader.api.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 진입 승인 실행 만료(N15, 2026-11-05)를 판정하는 <b>하나의 시계</b>.
 *
 * <p>회차 처리({@link EntryPriorityRounds#processDue})의 후보별 검사와 계좌 잠금 안의 예약 직전 검사
 * ({@link CapitalReservationService#reserveForBuy(String, Long, java.math.BigDecimal, Long, Instant)})가 같은 시계를 본다.
 * 운영은 시스템 시계(UTC)다. 시험은 {@link #setForTest} 로 가짜 시계를 연결해 대기를 긴 sleep 없이 만든다.</p>
 */
@Component
public class EntryClock {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return clock.instant();
    }

    /** 시험용 — {@code null} 이면 시스템 시계로 되돌린다. */
    public void setForTest(Clock c) {
        this.clock = c != null ? c : Clock.systemUTC();
    }
}
