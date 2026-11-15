package com.cryptoautotrader.api.support;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시험 JVM 전체의 진입 회차 키(H1 봉 시작) 등록부 — <b>시험 전용</b> (총괄 v1.168 §3-3, 2026-11-15).
 *
 * <p>{@code EntryPriorityRounds} 는 한 번 처리한 회차 키의 접수를 "회차 마감 뒤 도착" 으로 거절한다. 같은 스프링 컨텍스트(같은 회차 빈)를
 * 쓰는 시험 클래스가 "실제 현재 −61분(초 절삭)" 으로 키를 만들면, 앞 클래스가 같은 초에 처리한 키와 겹친다 — 탐침
 * p-1115-221620: B01RealNextSellBarTest 가 22:16:54.114 에 {@code 12:15:54Z} 를 확정, 같은 초 BtcGateUnknownEntryTest 의 승인 시점
 * 시험이 같은 키로 접수 거절(회차 0). 키를 받는 시험은 여기서 받는다 — 이미 나간 키면 1ms 씩 옮긴다(최신성·마감 판정은 같은 봉).</p>
 */
public final class TestRoundKeys {

    private static final Set<Instant> USED = ConcurrentHashMap.newKeySet();

    private TestRoundKeys() { }

    /** {@code candidate} 이상에서 아직 나가지 않은 가장 이른 키를 받는다. */
    public static Instant claim(Instant candidate) {
        Instant k = candidate;
        while (!USED.add(k)) k = k.plusMillis(1);
        return k;
    }
}
