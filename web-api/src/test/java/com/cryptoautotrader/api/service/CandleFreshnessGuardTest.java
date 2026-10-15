package com.cryptoautotrader.api.service;

import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캔들 최신성·결측 판정 (2026-10-15 리뷰 7회차) — 봉 수만이 아니라 최신 완료 봉의 존재와 결측 조건을 본다.
 */
@DisplayName("캔들 최신성 — 최신 완료 봉 · 결측 · 진행 중 봉")
class CandleFreshnessGuardTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:05:00Z");

    /** H1 봉 — {@code lastStart} 부터 거꾸로 n 개, {@code skip} 의 시각은 뺀다. */
    private static List<Candle> h1(Instant lastStart, int n, Set<Integer> skip) {
        List<Candle> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            if (skip.contains(i)) continue;
            BigDecimal px = new BigDecimal("310");
            out.add(Candle.builder().time(lastStart.minus(i, ChronoUnit.HOURS))
                    .open(px).high(px).low(px).close(px).volume(BigDecimal.ONE).build());
        }
        return out;
    }

    @Test
    @DisplayName("직전 완료 봉(09:00)이 있으면 판정 가능 — 진행 중 봉(10:00)은 있든 없든 같다")
    void latestClosedPresent() {
        assertThat(CandleFreshnessGuard.problem(h1(Instant.parse("2026-10-15T09:00:00Z"), 500, Set.of()), "H1", NOW)).isNull();
        assertThat(CandleFreshnessGuard.problem(h1(Instant.parse("2026-10-15T10:00:00Z"), 500, Set.of()), "H1", NOW)).isNull();
    }

    @Test
    @DisplayName("🔴 봉 수가 충분해도 마지막 완료 봉이 오래되면 판정 불가 — 수집이 멎은 캐시")
    void staleCacheIsNotUsable() {
        String p = CandleFreshnessGuard.problem(h1(Instant.parse("2026-10-15T07:00:00Z"), 500, Set.of()), "H1", NOW);
        assertThat(p).contains("최신 완료 봉 없음").contains("120분 늦음");
        // 진행 중 봉만 새것이고 직전 완료 봉이 빠진 경우도 판정 불가(10:00 진행 중, 09:00 없음)
        assertThat(CandleFreshnessGuard.problem(h1(Instant.parse("2026-10-15T10:00:00Z"), 500, Set.of(1)), "H1", NOW))
                .contains("최신 완료 봉 없음");
    }

    @Test
    @DisplayName("경계 직후 2분 유예 — 10:01 에는 08:00 봉까지 허용, 10:03 에는 09:00 봉이 있어야 한다")
    void graceAfterBoundary() {
        List<Candle> upTo08 = h1(Instant.parse("2026-10-15T08:00:00Z"), 500, Set.of());
        assertThat(CandleFreshnessGuard.problem(upTo08, "H1", Instant.parse("2026-10-15T10:01:00Z"))).isNull();
        assertThat(CandleFreshnessGuard.problem(upTo08, "H1", Instant.parse("2026-10-15T10:03:00Z"))).contains("최신 완료 봉 없음");
    }

    @Test
    @DisplayName("결측 2칸까지 허용, 3칸이면 판정 불가")
    void gaps() {
        Instant last = Instant.parse("2026-10-15T09:00:00Z");
        assertThat(CandleFreshnessGuard.problem(h1(last, 500, Set.of(10, 200)), "H1", NOW)).isNull();
        assertThat(CandleFreshnessGuard.problem(h1(last, 500, Set.of(10, 200, 300)), "H1", NOW))
                .contains("결측 3칸").contains("허용 2");
    }

    @Test
    @DisplayName("M15 도 같은 규칙 — 10:05 에는 09:45 봉이 있어야 한다")
    void m15() {
        List<Candle> out = new ArrayList<>();
        Instant last = Instant.parse("2026-10-15T09:45:00Z");
        for (int i = 199; i >= 0; i--) {
            out.add(Candle.builder().time(last.minus(15L * i, ChronoUnit.MINUTES)).open(BigDecimal.ONE).high(BigDecimal.ONE)
                    .low(BigDecimal.ONE).close(BigDecimal.ONE).volume(BigDecimal.ONE).build());
        }
        assertThat(CandleFreshnessGuard.problem(out, "M15", NOW)).isNull();
        assertThat(CandleFreshnessGuard.problem(out.subList(0, 199), "M15", NOW)).contains("최신 완료 봉 없음");
    }

    @Test
    @DisplayName("판정 불가면 빈 목록 — 시작 때 한 번만 기록·경보(보유 코인일 때), 회복되면 목록에서 빠진다")
    void filterReturnsEmptyAndAlertsOncePerEpisode() {
        CandleFreshnessGuard g = new CandleFreshnessGuard();
        TelegramNotificationService telegram = Mockito.mock(TelegramNotificationService.class);
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForObject(Mockito.anyString(), Mockito.eq(Integer.class), Mockito.eq("KRW-FRS"))).thenReturn(1);
        ReflectionTestUtils.setField(g, "telegram", telegram);
        ReflectionTestUtils.setField(g, "jdbc", jdbc);

        Instant now = Instant.now();
        List<Candle> stale = h1(now.truncatedTo(ChronoUnit.HOURS).minus(5, ChronoUnit.HOURS), 500, Set.of());
        assertThat(g.filter("DYNAMIC", "KRW-FRS", "H1", stale)).isEmpty();
        assertThat(g.filter("DYNAMIC", "KRW-FRS", "H1", stale)).isEmpty();
        assertThat(g.staleKeys()).containsKey("KRW-FRS:H1");
        Mockito.verify(telegram, Mockito.times(1)).notifyRiskLimitBreached(Mockito.argThat(m -> m.contains("KRW-FRS") && m.contains("60초 평가 청산")));

        List<Candle> fresh = h1(now.truncatedTo(ChronoUnit.HOURS), 500, Set.of());
        assertThat(g.filter("DYNAMIC", "KRW-FRS", "H1", fresh)).hasSize(500);
        assertThat(g.staleKeys()).doesNotContainKey("KRW-FRS:H1");
    }
}
