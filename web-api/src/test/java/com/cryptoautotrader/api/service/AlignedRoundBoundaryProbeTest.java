package com.cryptoautotrader.api.service;

import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비정시 자료의 정시 경계 탐침 — 결정적(총괄 v1.168 §3-3, 2026-11-15). 스프링 없이 <b>제품 판정 함수</b>
 * {@link CandleFreshnessGuard#problem}·{@link EntryPriorityRounds#deadline}·{@link EntryPriorityRounds#expiry} 를 직접 부른다.
 *
 * <p>실제 실행 시각 W 를 대표 분(:52·:53·:55·:59·:00·:01, 초 :30)에 두고, 시험 자료 두 종류의 마지막 봉을 만든다 — 옛 공용 준비 코드
 * (W−61분, 초 절삭)와 정시 회차({@code useAlignedRound}: W 의 정시 −1시간). 각 회차의 접수 C+1초·마감 D·E−1ms 판단 시각에서 최신성을 본다.
 * 벽시계 탐침(p-1115-2053~2100)의 S04a 실패 창 [:53, :01) 과 판정이 맞아야 하고, 정시 회차는 모든 분에서 통과해야 한다.</p>
 */
@DisplayName("v1.168 비정시 자료 정시 경계 탐침 — 제품 최신성·회차 함수")
class AlignedRoundBoundaryProbeTest {

    private static List<Candle> h1(Instant last, int n) {
        List<Candle> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            BigDecimal p = BigDecimal.valueOf(1000 + i);
            out.add(Candle.builder().time(last.minus(i, ChronoUnit.HOURS)).open(p).high(p).low(p).close(p).volume(BigDecimal.ONE).build());
        }
        return out;
    }

    /** 판단 시각별 최신성 — 통과면 "통과", 아니면 사유 앞부분. */
    private static String fresh(Instant last, Instant judge) {
        String p = CandleFreshnessGuard.problem(h1(last, 30), "H1", judge);
        return p == null ? "통과" : p.substring(0, Math.min(10, p.length()));
    }

    @Test
    @DisplayName("대표 분 × 자료(옛 −61분 / 정시 회차) × 판단 시각(C+1초·D·E−1ms)")
    void boundaryTable() {
        Instant hour = Instant.parse("2026-11-15T11:00:00Z");
        Map<String, Instant> walls = new LinkedHashMap<>();
        for (int m : new int[]{52, 53, 55, 59}) walls.put(":" + m, hour.plus(m, ChronoUnit.MINUTES).plusSeconds(30));
        walls.put(":00", hour.plus(1, ChronoUnit.HOURS).plusSeconds(30));
        walls.put(":01", hour.plus(1, ChronoUnit.HOURS).plus(1, ChronoUnit.MINUTES).plusSeconds(30));

        List<String> oldE = new ArrayList<>();
        for (Map.Entry<String, Instant> w : walls.entrySet()) {
            Instant wall = w.getValue();
            Instant oldLast = wall.minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
            Instant alignedLast = wall.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
            for (String kind : new String[]{"옛", "정시"}) {
                Instant last = "옛".equals(kind) ? oldLast : alignedLast;
                EntryPriorityRounds.RoundKey k = new EntryPriorityRounds.RoundKey("H1", last);
                Instant c = last.plus(1, ChronoUnit.HOURS);
                Instant d = EntryPriorityRounds.deadline(k);
                Instant e = EntryPriorityRounds.expiry(k);
                // 옛 자료의 접수는 판단 시계 기본(실제 W) — 공용 준비 코드 그대로. 정시 회차는 C+1초.
                Instant intake = "옛".equals(kind) ? wall : c.plusSeconds(1);
                String rIntake = fresh(last, intake);
                String rDeadline = fresh(last, d);
                String rExpiry = fresh(last, e.minusMillis(1));
                boolean inWindow = !intake.isBefore(c) && intake.isBefore(d);
                System.out.printf("[BND168] W%s %s 마지막봉 %s · 접수 %s(창 안 %s) %s · D %s %s · E−1ms %s %s%n", w.getKey(), kind, last,
                        intake, inWindow, rIntake, d, rDeadline, e.minusMillis(1), rExpiry);
                assertThat(inWindow).as("접수 시각이 회차 창 [C, D) 안").isTrue();
                assertThat(rIntake).as("접수 최신성 %s %s", w.getKey(), kind).isEqualTo("통과");
                if ("정시".equals(kind)) {
                    assertThat(rDeadline).as("정시 D %s", w.getKey()).isEqualTo("통과");
                    assertThat(rExpiry).as("🔴 정시 회차 E−1ms 최신성 %s", w.getKey()).isEqualTo("통과");
                } else {
                    oldE.add(w.getKey() + "=" + rExpiry);
                }
            }
        }
        // 옛 자료의 E−1ms: 벽시계 탐침의 실패 창 [:53, :01) 과 같아야 한다(결함 재현)
        assertThat(oldE).containsExactly(":52=통과", ":53=최신 완료 봉 없음", ":55=최신 완료 봉 없음", ":59=최신 완료 봉 없음",
                ":00=최신 완료 봉 없음", ":01=통과");
    }
}
