package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.OrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 판별 불가 매도의 경계 시각 (2026-10-19 리뷰) — 🔴 경계를 정하지 못하면 자동 반영을 <b>보류</b>한다(종전: ERROR 만 남기고 모두 자동 반영 후보).
 */
@DisplayName("판별 불가 매도 — 경계 시각과 경계 불명 시 보류")
class LegacySellPolicyTest {

    private static final String SQL = "SELECT MIN(installed_on) FROM flyway_schema_history WHERE version = '87' AND success";
    private static final Instant V87 = Instant.parse("2026-10-16T05:07:54Z");

    private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    private final TelegramNotificationService telegram = Mockito.mock(TelegramNotificationService.class);
    private LegacySellPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new LegacySellPolicy();
        ReflectionTestUtils.setField(policy, "jdbc", jdbc);
        ReflectionTestUtils.setField(policy, "telegram", telegram);
        ReflectionTestUtils.setField(policy, "configuredCutoff", "");
        policy.resetForTest(false);   // 운영처럼 — 경계 없음 불허
    }

    private static OrderEntity sell(Instant createdAt) {
        return OrderEntity.builder().side("SELL").state("FILLED").createdAt(createdAt).build();
    }

    private static final OrderEntity OLD = sell(V87.minus(3, ChronoUnit.DAYS));
    private static final OrderEntity RECENT = sell(Instant.now());

    @Test
    @DisplayName("Flyway 이력의 V87 설치 시각이 경계 — 그 전 매도만 판별 불가")
    void flywayCutoff() {
        when(jdbc.queryForObject(SQL, Timestamp.class)).thenReturn(Timestamp.from(V87));
        assertThat(policy.isUnverifiableLegacy(OLD)).isTrue();
        assertThat(policy.isUnverifiableLegacy(RECENT)).isFalse();
        assertThat(policy.cutoff()).isEqualTo(V87);
        verify(telegram, never()).notifyRiskLimitBreached(anyString());
    }

    @Test
    @DisplayName("🔴 이력 조회 실패 + 설정 없음 → 경계 불명: 최근 매도까지 모두 보류, ERROR·경보 한 번")
    void unknownHoldsEverything() {
        when(jdbc.queryForObject(SQL, Timestamp.class)).thenThrow(new RuntimeException("relation \"flyway_schema_history\" does not exist"));
        assertThat(policy.isUnverifiableLegacy(RECENT)).as("판정할 수 없으면 보류").isTrue();
        assertThat(policy.isUnverifiableLegacy(OLD)).isTrue();
        assertThat(policy.isAutoApplicableUnapplied(RECENT)).isFalse();
        assertThat(policy.cutoff()).isNull();
        assertThat(policy.state()).isEqualTo(LegacySellPolicy.State.UNKNOWN);
        verify(telegram, times(1)).notifyRiskLimitBreached(anyString());
        verify(jdbc, times(1)).queryForObject(SQL, Timestamp.class);   // 재시도 간격 안에서는 다시 읽지 않는다
    }

    @Test
    @DisplayName("🔴 V87 행이 없음(이력은 읽힘) + 설정 없음 → 경계 불명: 보류")
    void missingV87RowHolds() {
        when(jdbc.queryForObject(SQL, Timestamp.class)).thenReturn(null);
        assertThat(policy.isUnverifiableLegacy(RECENT)).isTrue();
    }

    @Test
    @DisplayName("이력을 읽지 못해도 설정 trading.sell-quarantine.legacy-before 가 있으면 그것이 경계")
    void configuredFallback() {
        when(jdbc.queryForObject(SQL, Timestamp.class)).thenThrow(new RuntimeException("x"));
        ReflectionTestUtils.setField(policy, "configuredCutoff", "2026-10-16T05:07:54Z");
        assertThat(policy.isUnverifiableLegacy(OLD)).isTrue();
        assertThat(policy.isUnverifiableLegacy(RECENT)).isFalse();
    }

    @Test
    @DisplayName("🔴 설정값을 읽지 못하면(형식 오류) 경계 불명 — 보류")
    void badConfigHolds() {
        when(jdbc.queryForObject(SQL, Timestamp.class)).thenThrow(new RuntimeException("x"));
        ReflectionTestUtils.setField(policy, "configuredCutoff", "어제");
        assertThat(policy.isUnverifiableLegacy(RECENT)).isTrue();
    }

    @Test
    @DisplayName("경계 불명 뒤 다시 읽히면 풀린다 — 재시도 간격이 지난 다음 호출에서")
    void recoversAfterRetry() {
        when(jdbc.queryForObject(SQL, Timestamp.class)).thenThrow(new RuntimeException("x")).thenReturn(Timestamp.from(V87));
        assertThat(policy.isUnverifiableLegacy(RECENT)).isTrue();
        ReflectionTestUtils.setField(policy, "lastAttempt", Instant.now().minus(LegacySellPolicy.RETRY).minusSeconds(1));

        assertThat(policy.isUnverifiableLegacy(RECENT)).as("다시 읽혀 경계가 생겼다").isFalse();
        assertThat(policy.isUnverifiableLegacy(OLD)).isTrue();
        verify(telegram, times(1)).notifyRiskLimitBreached(anyString());   // 경보는 불명이 시작될 때 한 번
    }

    @Test
    @DisplayName("시험 기반(경계 없음 허용)에서 설정이 없으면 종전 동작 — 모두 자동 반영 후보")
    void testRuntimeNoBoundary() {
        when(jdbc.queryForObject(SQL, Timestamp.class)).thenThrow(new RuntimeException("x"));
        policy.resetForTest(true);
        assertThat(policy.isUnverifiableLegacy(OLD)).isFalse();
        verify(telegram, never()).notifyRiskLimitBreached(anyString());
    }

    @Test
    @DisplayName("반영 표시가 있거나 매수면 판별 대상이 아니다")
    void notApplicable() {
        when(jdbc.queryForObject(eq(SQL), eq(Timestamp.class))).thenThrow(new RuntimeException("x"));
        assertThat(policy.isUnverifiableLegacy(OrderEntity.builder().side("BUY").createdAt(V87.minusSeconds(10)).build())).isFalse();
    }
}
