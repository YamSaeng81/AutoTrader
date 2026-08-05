package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.service.DynamicTradingService.BlackSwanGateDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-05 회귀 테스트 — <b>BLACK_SWAN 진입가 가드</b>.
 *
 * <p><b>사고 재현 (동일 패턴 2건)</b>: 쿨다운(240분)은 진입을 <b>지연</b>시킬 뿐 가격을 보지
 * 않는다. 그래서 "가드가 거부한 가격에는 안 사고, 기다렸다가 더 비싸게 사서, 거부당한 가격
 * 아래로 손절"이 그대로 반복됐다.</p>
 * <ul>
 *   <li>KRW-META2: 08-04 01:06 차단 시점가 <b>8,630</b> → 06:00 <b>9,150</b>(+6.0%) 진입
 *       → 18:32 <b>8,495</b> 손절(−7.05%). 차단가보다 낮은 값에 팔았다.</li>
 *   <li>KRW-ELSA: 08-03 4회 차단 → 해제 직후 80.90 진입 → 74.20 손절(−8.33%).</li>
 * </ul>
 *
 * <p>가드가 "이 가격은 위험하다"고 봤으면 <b>그보다 비싼 가격은 더 위험하다</b>는 것이
 * 이 테스트가 잠그는 불변식이다.</p>
 */
class DynamicBlackSwanPriceGuardTest {

    private static final BigDecimal META2_BLOCKED_PRICE = new BigDecimal("8630");
    private static final BigDecimal META2_ENTRY_PRICE   = new BigDecimal("9150");

    private static Instant minutesAgo(long m) {
        return Instant.now().minus(m, ChronoUnit.MINUTES);
    }

    private static BlackSwanGateDecision gate(Instant blockedAt, BigDecimal blockedPrice, BigDecimal now) {
        return DynamicTradingService.evaluateBlackSwanGate(blockedAt, blockedPrice, now, Instant.now());
    }

    @Test
    @DisplayName("차단 이력이 없으면 통과한다 — 정상 종목에 부작용 없음")
    void 이력_없으면_통과() {
        BlackSwanGateDecision d = gate(null, null, META2_ENTRY_PRICE);

        assertThat(d.blockReason()).isNull();
        assertThat(d.expired()).isFalse();
    }

    @Test
    @DisplayName("쿨다운 구간(240분 이내)은 가격과 무관하게 차단된다")
    void 쿨다운_구간_차단() {
        // 가격이 차단가보다 훨씬 낮아도 쿨다운 중이면 막아야 한다
        BlackSwanGateDecision d = gate(minutesAgo(60), META2_BLOCKED_PRICE, new BigDecimal("7000"));

        assertThat(d.blockReason()).contains("BLACK_SWAN 쿨다운");
        assertThat(d.expired()).as("쿨다운 중에는 이력을 지우면 안 된다").isFalse();
    }

    @Test
    @DisplayName("★ 쿨다운이 끝나도 차단 시점가보다 비싸면 차단된다 — META2 사고 재발 방지")
    void 쿨다운_해제_후_고가_진입_차단() {
        // 실제 사고와 동일한 조건: 차단 294분 후(쿨다운 240분 경과), 차단가 8,630 → 9,150
        BlackSwanGateDecision d = gate(minutesAgo(294), META2_BLOCKED_PRICE, META2_ENTRY_PRICE);

        assertThat(d.blockReason())
                .as("이 진입이 −7.05% 손절로 이어졌다")
                .contains("BLACK_SWAN 진입가 가드")
                .contains("8630");
        assertThat(d.expired()).isFalse();
    }

    @Test
    @DisplayName("쿨다운이 끝나고 차단 시점가 이하로 내려오면 진입을 허용하고 이력을 폐기한다")
    void 쿨다운_해제_후_저가_진입_허용() {
        BlackSwanGateDecision d = gate(minutesAgo(294), META2_BLOCKED_PRICE, new BigDecimal("8500"));

        assertThat(d.blockReason()).isNull();
        assertThat(d.expired()).as("허용됐으면 이력도 지워야 재차단이 남지 않는다").isTrue();
    }

    @Test
    @DisplayName("차단 시점가와 같으면 허용한다 — 가드는 '초과'만 막는다")
    void 동일가는_허용() {
        BlackSwanGateDecision d = gate(minutesAgo(294), META2_BLOCKED_PRICE, META2_BLOCKED_PRICE);

        assertThat(d.blockReason()).isNull();
        assertThat(d.expired()).isTrue();
    }

    @Test
    @DisplayName("24시간이 지나면 가격과 무관하게 이력이 만료된다 — 영구 차단 방지")
    void 유효기간_경과시_만료() {
        // 가드가 계속 걸릴 가격(차단가 초과)이어도 1440분이 지나면 풀려야 한다
        BlackSwanGateDecision d = gate(minutesAgo(1441), META2_BLOCKED_PRICE, META2_ENTRY_PRICE);

        assertThat(d.blockReason())
                .as("상승 추세로 돌아선 종목이 영구 차단되면 안 된다")
                .isNull();
        assertThat(d.expired()).isTrue();
    }

    @Test
    @DisplayName("기준가가 없으면(구 로그 복원) 진입가 가드는 비활성 — 쿨다운만 작동한다")
    void 기준가_없으면_쿨다운만() {
        assertThat(gate(minutesAgo(60), null, META2_ENTRY_PRICE).blockReason())
                .as("쿨다운은 기준가 없이도 작동")
                .contains("BLACK_SWAN 쿨다운");

        BlackSwanGateDecision afterCooldown = gate(minutesAgo(294), null, META2_ENTRY_PRICE);
        assertThat(afterCooldown.blockReason())
                .as("기준가가 없으면 막을 근거가 없다 — 진입 차단으로 굳으면 안 된다")
                .isNull();
        assertThat(afterCooldown.expired()).isTrue();

        // 0원 기준가(비정상 데이터)도 같은 취급
        assertThat(gate(minutesAgo(294), BigDecimal.ZERO, META2_ENTRY_PRICE).blockReason()).isNull();
    }
}
