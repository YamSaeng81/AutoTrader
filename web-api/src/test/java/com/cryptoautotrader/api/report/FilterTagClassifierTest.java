package com.cryptoautotrader.api.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FilterTagClassifier — reason 문자열 필터 태그 분류")
class FilterTagClassifierTest {

    @Test
    @DisplayName("passTag — H4 통과 태그 분류")
    void passTags() {
        assertThat(FilterTagClassifier.passTag("[H4:중립] LTF매수")).isEqualTo("H4_중립통과");
        assertThat(FilterTagClassifier.passTag("[H4:데이터부족] LTF매수")).isEqualTo("H4_데이터부족통과");
        assertThat(FilterTagClassifier.passTag("[H4:BUY] 돌파")).isEqualTo("H4_추세확인");
        assertThat(FilterTagClassifier.passTag("[H4:SELL] 하락")).isEqualTo("H4_추세확인");
    }

    @Test
    @DisplayName("passTag — 필터 무관 신호/널은 null")
    void passTagNone() {
        assertThat(FilterTagClassifier.passTag("[VOLATILITY] 돌파 매수")).isNull(); // 레짐은 byRegime 소관
        assertThat(FilterTagClassifier.passTag("일반 매수 신호")).isNull();
        assertThat(FilterTagClassifier.passTag(null)).isNull();
    }

    @Test
    @DisplayName("blockTag — 필터 차단 사유 분류")
    void blockTags() {
        assertThat(FilterTagClassifier.blockTag("RSI Veto BUY차단: RSI(80) > 75")).isEqualTo("RSI_Veto차단");
        assertThat(FilterTagClassifier.blockTag("EMA200 레짐 필터 — 현재가 EMA200 이하")).isEqualTo("EMA200차단");
        assertThat(FilterTagClassifier.blockTag("MTF불일치: H4=SELL vs H1=BUY")).isEqualTo("MTF불일치차단");
        assertThat(FilterTagClassifier.blockTag("[RANGE] 횡보장 진입 금지 (ADX<20)")).isEqualTo("RANGE차단");
    }

    @Test
    @DisplayName("blockTag — strict 차단은 RANGE보다 우선해 별도 분류")
    void blockTagStrictPriority() {
        assertThat(FilterTagClassifier.blockTag("[H4:중립·strict] HTF 추세 미확인 진입 차단 [매수]"))
                .isEqualTo("H4_strict차단");
        assertThat(FilterTagClassifier.blockTag("[H4:데이터부족·strict] HTF 미확인 진입 차단 [매수]"))
                .isEqualTo("H4_strict차단");
    }

    @Test
    @DisplayName("blockTag — 필터 무관 HOLD/널은 null")
    void blockTagNone() {
        assertThat(FilterTagClassifier.blockTag("닫힌 캔들 미갱신 — 전략 평가 스킵")).isNull();
        assertThat(FilterTagClassifier.blockTag("신호 없음")).isNull();
        assertThat(FilterTagClassifier.blockTag(null)).isNull();
    }
}
