package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarketDataSyncService#shouldFetchFullRange} 회귀 테스트.
 *
 * <p>2026-08-26: DYNAMIC 캔들 조회를 캐시 경유로 바꾼 직후, 캐시가 lookback 창을 못 덮는
 * 코인에서 전략이 500개가 아니라 캐시에 있는 만큼(KRW-LIT M15 180개)만 받는 회귀가 있었다.
 * EMA200 계열은 닫힌 캔들 201개 미만이면 구조적으로 신호를 못 낸다.</p>
 */
class MarketDataCacheCoverageTest {

    private static final long H1 = 60;
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
    /** H1 500개 창 = 500시간 전. */
    private static final Instant FULL_FROM = NOW.minus(500, ChronoUnit.HOURS);

    @Test
    @DisplayName("캐시가 없으면 전체 구간을 받는다")
    void noCache_fetchesFullRange() {
        assertThat(MarketDataSyncService.shouldFetchFullRange(null, FULL_FROM, H1, null, NOW))
                .isTrue();
    }

    @Test
    @DisplayName("캐시가 lookback 창을 덮으면 갭만 받는다")
    void cacheCoversWindow_usesGapPath() {
        Instant earliest = FULL_FROM.minus(10, ChronoUnit.HOURS); // 창보다 더 과거까지 있음
        assertThat(MarketDataSyncService.shouldFetchFullRange(earliest, FULL_FROM, H1, null, NOW))
                .isFalse();
    }

    @Test
    @DisplayName("캔들 2개분 여유 안쪽의 결측은 전량 재수집을 유발하지 않는다")
    void smallGapAtStart_toleratedAsCovered() {
        Instant earliest = FULL_FROM.plus(2 * H1, ChronoUnit.MINUTES); // 정확히 여유 경계
        assertThat(MarketDataSyncService.shouldFetchFullRange(earliest, FULL_FROM, H1, null, NOW))
                .isFalse();
    }

    @Test
    @DisplayName("🔴 캐시가 창 시작점을 못 덮으면 전체 구간을 다시 받는다 (KRW-LIT M15 180개 회귀)")
    void cacheTooShort_fetchesFullRange() {
        // 운영 실측 재현: 500시간 창인데 캐시는 46시간치(=334개 PROM H1)뿐
        Instant earliest = NOW.minus(46, ChronoUnit.HOURS);
        assertThat(MarketDataSyncService.shouldFetchFullRange(earliest, FULL_FROM, H1, null, NOW))
                .isTrue();
    }

    @Test
    @DisplayName("이력이 짧은 코인이라도 쿨다운 중이면 전량 재수집을 반복하지 않는다")
    void shortHistory_withinCooldown_doesNotRefetch() {
        Instant earliest = NOW.minus(46, ChronoUnit.HOURS);
        Instant lastAttempt = NOW.minus(5, ChronoUnit.MINUTES);
        assertThat(MarketDataSyncService.shouldFetchFullRange(earliest, FULL_FROM, H1, lastAttempt, NOW))
                .isFalse();
    }

    @Test
    @DisplayName("쿨다운이 지나면 전량 재수집을 다시 시도한다")
    void shortHistory_afterCooldown_retries() {
        Instant earliest = NOW.minus(46, ChronoUnit.HOURS);
        Instant lastAttempt = NOW.minus(
                MarketDataSyncService.FULL_BACKFILL_RETRY_MINUTES + 1, ChronoUnit.MINUTES);
        assertThat(MarketDataSyncService.shouldFetchFullRange(earliest, FULL_FROM, H1, lastAttempt, NOW))
                .isTrue();
    }

    @Test
    @DisplayName("캐시가 창을 덮으면 쿨다운 이력과 무관하게 갭 경로다")
    void covered_ignoresCooldownState() {
        Instant earliest = FULL_FROM.minus(10, ChronoUnit.HOURS);
        Instant lastAttempt = NOW.minus(999, ChronoUnit.MINUTES);
        assertThat(MarketDataSyncService.shouldFetchFullRange(earliest, FULL_FROM, H1, lastAttempt, NOW))
                .isFalse();
    }
}
