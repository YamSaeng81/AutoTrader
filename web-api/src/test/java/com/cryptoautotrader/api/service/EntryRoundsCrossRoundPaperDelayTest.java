package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서로 다른 회차 사이의 PAPER 승인 지연 (2026-11-11, 총괄 v1.145 §3-3).
 *
 * <p>회차 안에서는 PAPER 후보가 실자금 후보 뒤로 정렬된다({@link EntryPriorityRounds#ORDER}). 그러나 한 번의 {@code processDue} 에 마감이
 * 같은 회차가 여럿이면(예: H1 09:00 봉과 M15 09:45 봉은 둘 다 10:05 마감) 회차는 보관 지도의 순서로 하나씩 처리된다. PAPER 만 있는 회차가
 * 먼저 꺼내지면, 다른 회차의 실자금 후보는 그 PAPER 승인이 끝날 때까지 기다린다.</p>
 *
 * <p>입력: M15 회차(PAPER 3) + H1 회차(LIVE 1). 처리 순서가 M15 를 먼저 꺼내는 봉 시각을 결정적으로 고른다(같은 입력이면 같은 순서 — 반복해서
 * 녹색을 얻는 것이 아니라 그 순서를 보이는 입력을 고정한다). 첫 PAPER 승인을 latch 로 붙잡고, 그동안 LIVE 승인이 일어났는지 본다.
 * 시간 초과는 실패다. Spring·DB 없이 회차 객체만 쓴다.</p>
 */
@DisplayName("진입 회차 — 다른 회차의 느린 PAPER 승인이 실자금 후보를 늦추지 않는다")
class EntryRoundsCrossRoundPaperDelayTest {

    private static EntryPriorityRounds rounds(Instant now) {
        EntryClock clock = new EntryClock();
        clock.setForTest(Clock.fixed(now, ZoneOffset.UTC));
        EntryPriorityRounds r = new EntryPriorityRounds(Mockito.mock(CapitalPoolLimits.class), clock);
        r.setForcedForTest(true);
        return r;
    }

    /** M15 회차가 H1 회차보다 먼저 꺼내지는 봉 시각 — 빈 회차 객체로 실제 처리 순서를 관측해 고른다. */
    private static Instant[] pickM15First(Instant hourStart) {
        for (int h = 0; h < 24 * 365; h++) {
            Instant h1Open = hourStart.minus(h, ChronoUnit.HOURS);
            Instant m15Open = h1Open.plus(45, ChronoUnit.MINUTES);
            EntryPriorityRounds.RoundKey h1 = new EntryPriorityRounds.RoundKey("H1", h1Open);
            EntryPriorityRounds.RoundKey m15 = new EntryPriorityRounds.RoundKey("M15", m15Open);
            Instant due = EntryPriorityRounds.deadline(h1);
            if (!due.equals(EntryPriorityRounds.deadline(m15))) throw new AssertionError("마감이 같아야 한다");
            EntryPriorityRounds probe = rounds(h1Open.plus(30, ChronoUnit.MINUTES));
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            // 탐색은 두 후보 모두 PAPER 로 둔다 — 같은 단계라 승인 순서가 곧 지도 순서다(보완 전후 같은 시각을 고른다).
            probe.submit(new EntryPriorityRounds.Candidate("PAPER", 1L, "KRW-BTC", h1, () -> { order.add("H1"); return "probe"; }),
                    h1Open.plus(30, ChronoUnit.MINUTES));
            probe.submit(new EntryPriorityRounds.Candidate("PAPER", 2L, "KRW-ETH", m15, () -> { order.add("M15"); return "probe"; }),
                    h1Open.plus(30, ChronoUnit.MINUTES));
            probe.processDue(due);
            if (order.get(0).equals("M15")) return new Instant[] {h1Open, m15Open};
        }
        throw new AssertionError("1년 안에 M15 회차가 먼저 꺼내지는 시각이 없다 — 지도 순서가 바뀌었는지 확인");
    }

    @Test
    @DisplayName("PAPER 만 있는 회차가 먼저 꺼내져도, 그 PAPER 승인이 끝나기 전에 다른 회차의 LIVE 후보가 승인된다")
    void realCandidateIsNotHeldBehindPaperOfAnotherRound() throws Exception {
        Instant hourStart = Instant.parse("2026-11-11T00:00:00Z");
        Instant[] t = pickM15First(hourStart);
        EntryPriorityRounds.RoundKey h1 = new EntryPriorityRounds.RoundKey("H1", t[0]);
        EntryPriorityRounds.RoundKey m15 = new EntryPriorityRounds.RoundKey("M15", t[1]);
        Instant due = EntryPriorityRounds.deadline(h1);
        EntryPriorityRounds r = rounds(due);

        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch paperHeld = new CountDownLatch(1);
        CountDownLatch releasePaper = new CountDownLatch(1);
        CountDownLatch liveApproved = new CountDownLatch(1);
        Instant submitAt = t[0].plus(30, ChronoUnit.MINUTES);
        for (long id = 11; id <= 13; id++) {
            final long sid = id;
            r.submit(new EntryPriorityRounds.Candidate("PAPER", sid, "KRW-ETH", m15, () -> {
                events.add("PAPER#" + sid + " 시작");
                if (sid == 11) {
                    paperHeld.countDown();
                    try {
                        if (!releasePaper.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("PAPER 재개 신호 없음");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                events.add("PAPER#" + sid + " 끝");
                return null;
            }), submitAt);
        }
        r.submit(new EntryPriorityRounds.Candidate("LIVE", 1L, "KRW-BTC", h1, () -> {
            events.add("LIVE#1 승인");
            liveApproved.countDown();
            return null;
        }), submitAt);

        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<List<EntryPriorityRounds.Outcome>> f = ex.submit(() -> r.processDue(due));
            // LIVE 가 먼저 승인되면 PAPER#11 이 붙잡히기 전에 LIVE 가 끝난다. 아니면 PAPER#11 이 먼저 붙잡힌다.
            boolean liveBeforeRelease = liveApproved.await(3, TimeUnit.SECONDS);
            boolean paperHeldFirst = paperHeld.getCount() == 0 && !events.contains("LIVE#1 승인");
            releasePaper.countDown();
            List<EntryPriorityRounds.Outcome> out = f.get(20, TimeUnit.SECONDS);
            System.out.println("[회차 간 지연] H1=" + t[0] + " M15=" + t[1] + " PAPER 붙잡힘 중 LIVE 승인=" + liveBeforeRelease
                    + " PAPER 가 먼저 붙잡힘=" + paperHeldFirst + " 사건=" + events
                    + " 결과=" + out.stream().map(o -> o.candidate().kind() + "#" + o.candidate().sessionId()).toList());
            assertThat(out).hasSize(4);
            assertThat(liveBeforeRelease).as("🔴 다른 회차의 PAPER 승인이 끝날 때까지 실자금 후보가 기다렸다").isTrue();
            assertThat(events.indexOf("LIVE#1 승인")).as("LIVE 승인이 모든 PAPER 승인보다 앞").isLessThan(events.indexOf("PAPER#11 시작"));
        } finally {
            releasePaper.countDown();
            ex.shutdownNow();
        }
    }
}
