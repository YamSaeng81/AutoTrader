package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>1회 제한 옵션 {@code strategy_params.maxEntries} — 순수 계약</b> (총괄 v1.241 §3). 기점(d81c3f2f)·후보 공통 시험(후보 전용 기호 없음).
 * <ul>
 *   <li>옵션 미지정 세션의 지문 입력(canonicalParams)은 기존 값 그대로다 — 키를 넣지 않는다</li>
 *   <li>옵션 있는 세션은 canonicalParams 가 달라져 지문이 갈린다(RulesetRegistry 변경 없음)</li>
 *   <li>청산 오버라이드 해석은 추가 키를 무시한다 — 다른 전략 파라미터의 의미가 바뀌지 않는다</li>
 * </ul>
 */
@DisplayName("1회 제한 옵션 — 지문 입력 불변/분리 · 청산 해석 무시")
class LiveOneEntryParamsTest {

    private static Map<String, Object> base() {
        Map<String, Object> m = new HashMap<>();
        m.put("timeframe", "H1");
        m.put("slAtrMultiplier", "2.0");
        return m;
    }

    @Test
    @DisplayName("옵션 미지정 — canonicalParams 는 기존 문자열 그대로(빈 맵·null 포함)")
    void noOptionCanonicalUnchanged() {
        assertThat(RulesetRegistry.canonicalParams(base())).isEqualTo(RulesetRegistry.canonicalParams(Map.of("slAtrMultiplier", "2.0", "timeframe", "H1")));
        System.out.println("[OEU] canonical none=" + RulesetRegistry.canonicalParams(base())
                + " empty=" + RulesetRegistry.canonicalParams(Map.of()) + " null=" + RulesetRegistry.canonicalParams(null));
    }

    @Test
    @DisplayName("옵션 1 — canonicalParams 가 달라진다(지문 분리)")
    void optionChangesCanonical() {
        Map<String, Object> one = base();
        one.put("maxEntries", 1);
        String a = RulesetRegistry.canonicalParams(base());
        String b = RulesetRegistry.canonicalParams(one);
        System.out.println("[OEU] canonical one=" + b);
        assertThat(b).isNotEqualTo(a).contains("maxEntries");
    }

    @Test
    @DisplayName("청산 오버라이드 해석은 maxEntries 를 무시한다")
    void exitOverridesIgnoreKey() {
        Map<String, Object> one = base();
        one.put("maxEntries", 1);
        ExitRuleOverrides x = ExitRuleOverrides.from(base());
        ExitRuleOverrides y = ExitRuleOverrides.from(one);
        assertThat(y.toString()).isEqualTo(x.toString());
        assertThat(ExitRuleOverrides.from(Map.of("maxEntries", 1))).isSameAs(ExitRuleOverrides.from(Map.of()));
    }
}
