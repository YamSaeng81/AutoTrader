package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-06 신규 — 신호 기대값 검증 게이트(WalkForwardValidationGate)의 순수 판정 로직.
 *
 * <p>배경: 운영 DB 분석에서 동적 세션 BUY 신호(n=50)의 사후수익률이 4h -2.17%/24h -4.47%로
 * 기대값이 음수인 채로 실자본을 계속 쓰고 있었다. {@link StrategyLiveStatusRegistry}의
 * ENABLED/BLOCKED 매트릭스는 특정 시점 백테스트를 사람이 손으로 기록한 것이라 최신 검증과
 * 무관하게 고정돼 있어, 이 게이트가 Walk Forward 실행 결과를 세션 생성에 실제로 연결한다.</p>
 */
class WalkForwardValidationGateTest {

    @Test
    @DisplayName("실행 이력이 없으면 차단 — 아직 증명되지 않은 전략")
    void 이력없음_차단() {
        var d = WalkForwardValidationGate.decide("FOO", null, null, null, null);
        assertThat(d.passed()).isFalse();
        assertThat(d.reason()).contains("이력 없음");
    }

    @Test
    @DisplayName("verdict=OVERFITTING 이면 기대값이 양수여도 차단")
    void 오버피팅_차단() {
        var d = WalkForwardValidationGate.decide("FOO", "OVERFITTING", new BigDecimal("2.5"), 20, null);
        assertThat(d.passed()).isFalse();
        assertThat(d.reason()).contains("OVERFITTING");
    }

    @Test
    @DisplayName("OOS 거래 표본이 최소 기준 미만이면 차단")
    void 표본부족_차단() {
        var d = WalkForwardValidationGate.decide("FOO", "ACCEPTABLE", new BigDecimal("1.0"), 3, null);
        assertThat(d.passed()).isFalse();
        assertThat(d.reason()).contains("표본 부족");
    }

    @Test
    @DisplayName("OOS 기대값이 0 이하면 차단 (verdict·표본이 정상이어도)")
    void 기대값_음수_차단() {
        var d1 = WalkForwardValidationGate.decide("FOO", "ACCEPTABLE", new BigDecimal("-0.5"), 20, null);
        assertThat(d1.passed()).isFalse();
        assertThat(d1.reason()).contains("0 이하");

        var d2 = WalkForwardValidationGate.decide("FOO", "ACCEPTABLE", BigDecimal.ZERO, 20, null);
        assertThat(d2.passed()).isFalse();
    }

    @Test
    @DisplayName("verdict=ACCEPTABLE + 기대값 양수 + 표본 충분 → 통과")
    void 정상조건_통과() {
        var d = WalkForwardValidationGate.decide("FOO", "ACCEPTABLE", new BigDecimal("1.8"), 42, "2026-08-01T00:00:00Z");
        assertThat(d.passed()).isTrue();
        assertThat(d.reason()).contains("ACCEPTABLE").contains("1.8");
        assertThat(d.lastValidatedAt()).isEqualTo("2026-08-01T00:00:00Z");
    }

    @Test
    @DisplayName("verdict=CAUTION 도 통과 가능 — OVERFITTING만 하드 차단")
    void 코션_통과가능() {
        var d = WalkForwardValidationGate.decide("FOO", "CAUTION", new BigDecimal("0.3"), 10, null);
        assertThat(d.passed()).isTrue();
    }

    @Test
    @DisplayName("표본 경계값 — 정확히 최소 기준이면 통과, 하나 모자라면 차단")
    void 표본_경계값() {
        assertThat(WalkForwardValidationGate.decide("FOO", "ACCEPTABLE", new BigDecimal("0.1"),
                WalkForwardValidationGate.MIN_TRADES, null).passed()).isTrue();
        assertThat(WalkForwardValidationGate.decide("FOO", "ACCEPTABLE", new BigDecimal("0.1"),
                WalkForwardValidationGate.MIN_TRADES - 1, null).passed()).isFalse();
    }
}
