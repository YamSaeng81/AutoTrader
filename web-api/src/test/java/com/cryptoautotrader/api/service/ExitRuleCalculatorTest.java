package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-06 신규 — LIVE/DYNAMIC 청산 엔진 통합(P1) 중 time stop 판정 로직의 단위 테스트.
 *
 * <p>{@code resolveStopLossPct}/{@code resolveTakeProfitPrice}는 원래 DynamicTradingService에
 * 있던 로직을 그대로 옮긴 것이라 기존 회귀 테스트({@link DynamicStopLossWidthTest},
 * {@link DynamicTakeProfitCapTest})가 계속 잠근다. {@code shouldTimeStop}은 이번에 LIVE에도
 * 처음 적용되는 신규 로직이라 여기서 별도로 검증한다.</p>
 */
class ExitRuleCalculatorTest {

    private final Instant now = Instant.parse("2026-08-06T12:00:00Z");

    @Test
    @DisplayName("maxHoldHours가 null이면 비활성 — 절대 트리거되지 않는다")
    void 미설정이면_비활성() {
        Instant openedAt = now.minus(1000, ChronoUnit.HOURS);
        assertThat(ExitRuleCalculator.shouldTimeStop(null, openedAt, now)).isFalse();
    }

    @Test
    @DisplayName("maxHoldHours가 0이면 비활성 (LIVE·DYNAMIC 공통 기본값)")
    void 영이면_비활성() {
        Instant openedAt = now.minus(1000, ChronoUnit.HOURS);
        assertThat(ExitRuleCalculator.shouldTimeStop(0, openedAt, now)).isFalse();
    }

    @Test
    @DisplayName("maxHoldHours가 음수여도 비활성 (방어적)")
    void 음수여도_비활성() {
        Instant openedAt = now.minus(1000, ChronoUnit.HOURS);
        assertThat(ExitRuleCalculator.shouldTimeStop(-1, openedAt, now)).isFalse();
    }

    @Test
    @DisplayName("openedAt이 null이면 비활성 — 포지션 개설 시각을 모르면 트리거하지 않는다")
    void 개설시각없으면_비활성() {
        assertThat(ExitRuleCalculator.shouldTimeStop(24, null, now)).isFalse();
    }

    @Test
    @DisplayName("보유시간이 한도 미만이면 통과(청산 아님)")
    void 한도미만이면_통과() {
        Instant openedAt = now.minus(23, ChronoUnit.HOURS);
        assertThat(ExitRuleCalculator.shouldTimeStop(24, openedAt, now)).isFalse();
    }

    @Test
    @DisplayName("보유시간이 한도를 정확히 채우면 트리거")
    void 경계값에서_트리거() {
        Instant openedAt = now.minus(24, ChronoUnit.HOURS);
        assertThat(ExitRuleCalculator.shouldTimeStop(24, openedAt, now)).isTrue();
    }

    @Test
    @DisplayName("보유시간이 한도를 초과하면 트리거 — LIVE 세션 194 BTC 136시간 고착 재현 시나리오")
    void 한도초과하면_트리거() {
        Instant openedAt = now.minus(136, ChronoUnit.HOURS);
        assertThat(ExitRuleCalculator.shouldTimeStop(24, openedAt, now)).isTrue();
    }
}
