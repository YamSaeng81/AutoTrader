package com.cryptoautotrader.core.selector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RangeRegimeGate} — 전향 검증 세 팔의 RANGE 처우가 같은지 (2026-09-25).
 *
 * <p>🔴 <b>왜 필요한가</b><br>
 * {@code RANGE_BLOCKED} 은 <b>전략명 문자열 집합</b>이고 부재 = 허용이다. 팔 A 프리셋
 * {@code COMPOSITE_MTF_MOMENTUM_CLOSED} 를 추가할 때 이 목록에 넣지 않아서,
 * <b>A 만 RANGE 레짐에서 BUY 진입이 허용되는</b> 상태였다.
 *
 * <p>그대로 시작했다면 A−B 는 "HTF 완결봉만 쓰는 변경"이 아니라 <b>그 변경 + RANGE 진입 허용</b>
 * 두 가지를 섞어 잰 값이 된다. 사전 등록 §2 의 "확인 필터만 변경한다"를 위반한다.
 *
 * <p>게이트는 세 서비스(LIVE {@code :1017} · DYNAMIC {@code :939} · PAPER {@code :721})와
 * {@code BacktestEngine:321} 이 모두 전략명으로 호출한다 — 한 군데만 맞춰도 되는 문제가 아니다.
 */
class RangeRegimeGateArmParityTest {

    private static final String OFF = "COMPOSITE_MOMENTUM_ICHIMOKU_V2";
    private static final String B   = "COMPOSITE_MTF_MOMENTUM";
    private static final String A   = "COMPOSITE_MTF_MOMENTUM_CLOSED";

    @Test
    @DisplayName("🔴 OFF·B·A 세 팔은 RANGE 게이트 처우가 같다")
    void threeArmsShareRangeTreatment() {
        assertThat(RangeRegimeGate.isBlocked(OFF)).as("OFF").isTrue();
        assertThat(RangeRegimeGate.isBlocked(B)).as("B").isTrue();
        assertThat(RangeRegimeGate.isBlocked(A))
                .as("팔 A 가 빠지면 A 만 RANGE 에서 진입할 수 있어 A−B 가 두 변경을 섞는다")
                .isTrue();
    }

    @Test
    @DisplayName("부재 = 허용 — 목록에 없는 전략은 차단되지 않는다")
    void absenceMeansAllowed() {
        // 이 단정이 깨지면 게이트가 차단 목록에서 허용 목록으로 뒤집힌 것이다.
        assertThat(RangeRegimeGate.isBlocked("COMPOSITE_MOMENTUM_ICHIMOKU")).isFalse();
        assertThat(RangeRegimeGate.isBlocked("이런_전략_없음")).isFalse();
        // ⚠️ isBlocked(null) 은 NPE 다 (Set.of().contains(null)). 세 호출자 모두 세션의
        //    전략명(NOT NULL)을 넘기므로 도달하지 않는다 — 전향 검증 중에는 손대지 않는다.
    }
}
