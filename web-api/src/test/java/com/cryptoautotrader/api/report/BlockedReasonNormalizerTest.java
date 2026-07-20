package com.cryptoautotrader.api.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BlockedReasonNormalizer — 차단 사유 그룹핑 키 정규화 (2026-07-20)")
class BlockedReasonNormalizerTest {

    @Test
    @DisplayName("BLACK_SWAN_GUARD 급락 사유 — 수치 다른 두 건이 동일 키로 정규화")
    void blackswan_급락_수치제거() {
        String a = "BLACK_SWAN_GUARD 발동 — 1시간 내 급락 -17.39% (현재 727.00000000)";
        String b = "BLACK_SWAN_GUARD 발동 — 1시간 내 급락 -6.80% (현재 6.72000000)";
        assertThat(BlockedReasonNormalizer.normalize(a)).isEqualTo(BlockedReasonNormalizer.normalize(b));
        assertThat(BlockedReasonNormalizer.normalize(a)).isEqualTo("BLACK_SWAN_GUARD 발동 — 1시간 내 급락");
    }

    @Test
    @DisplayName("BLACK_SWAN_GUARD 거래량 급증 사유는 급락 사유와 다른 키로 구분")
    void blackswan_거래량급증_별도그룹() {
        String volume = "BLACK_SWAN_GUARD 발동 — 거래량 급증 26.1배 + 1시간 내 하락 -2.05% "
                + "(최근 20캔들 평균 134356510.53 → 현재 3511895510.42)";
        String crash = "BLACK_SWAN_GUARD 발동 — 1시간 내 급락 -6.80% (현재 6.72000000)";
        assertThat(BlockedReasonNormalizer.normalize(volume))
                .isNotEqualTo(BlockedReasonNormalizer.normalize(crash));
    }

    @Test
    @DisplayName("이미 포지션 보유 중 — 보유시간·pnl 다른 두 건이 동일 키로 정규화")
    void 포지션보유중_상세제거() {
        String a = "이미 포지션 보유 중 (신규신호강도=100, 보유포지션 pnl=0.00%, 보유시간=1분)";
        String b = "이미 포지션 보유 중 (신규신호강도=31.15, 보유포지션 pnl=-0.64%, 보유시간=124분)";
        assertThat(BlockedReasonNormalizer.normalize(a)).isEqualTo(BlockedReasonNormalizer.normalize(b));
        assertThat(BlockedReasonNormalizer.normalize(a)).isEqualTo("이미 포지션 보유 중");
    }

    @Test
    @DisplayName("EMA200 레짐 필터 — 괄호 제거 후 괄호 뒤 텍스트('이하')는 보존")
    void ema200_괄호제거_뒤텍스트_보존() {
        assertThat(BlockedReasonNormalizer.normalize("EMA200 레짐 필터 — 현재가 EMA200(-3.0%) 이하"))
                .isEqualTo("EMA200 레짐 필터 — 현재가 EMA200 이하");
    }

    @Test
    @DisplayName("콜론 포함 사유는 콜론 앞부분만 사용 (기존 동작 유지)")
    void 콜론_앞부분만_사용() {
        assertThat(BlockedReasonNormalizer.normalize("리스크 한도 초과: 일일 손실 -3.5%"))
                .isEqualTo("리스크 한도 초과");
    }

    @Test
    @DisplayName("정규화 후 빈 문자열이 되면 원본 trim 값을 반환 (안전망)")
    void 정규화후_빈문자열이면_원본반환() {
        assertThat(BlockedReasonNormalizer.normalize("(1.5%)")).isEqualTo("(1.5%)");
    }
}
