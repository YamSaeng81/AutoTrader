package com.cryptoautotrader.core.selector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-19 — {@link SignalQualityDampenGate} 상수가 규칙 지문에 실리는지 잠근다.
 *
 * <p><b>왜</b>: 이 감쇠는 진입 신호 수를 직접 바꾼다. 2일치 운영 로그에서 TRANSITIONAL 감쇠가
 * 임계값을 넘겼을 매수 점수 <b>45건</b>을 죽였는데 같은 기간 실제 통과한 BUY 신호가 51건이었다 —
 * 통과분과 맞먹는 양이다. 지문 밖에 두면 이 값을 조정한 전후 거래가 한 표본에 합산된다.</p>
 *
 * <p>{@code CompositeStrategy} · {@code ExitRuleCalculator} 에 이미 적용한 것과 같은 가드다.
 * 세 번 반복됐다는 것은 <b>"동작 상수는 지문에 싣는다"</b> 가 이 코드베이스의 규칙이라는 뜻이다.</p>
 */
class SignalQualityDampenGateFingerprintTest {

    @Test
    @DisplayName("가드: SignalQualityDampenGate 의 모든 수치 상수가 지문에 실린다 (리플렉션)")
    void everyNumericConstantIsFingerprinted() {
        Map<String, String> exposed = SignalQualityDampenGate.behaviorParams();

        for (Field f : SignalQualityDampenGate.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod) || f.isSynthetic()) continue;
            Class<?> t = f.getType();
            // 동작을 바꾸는 건 수치 상수뿐이다. ZoneId 같은 것은 대상이 아니다.
            if (!(t == int.class || t == double.class || t == long.class || t == float.class)) continue;

            assertThat(exposed)
                    .as("SignalQualityDampenGate.%s 가 지문에 없다 — 이 값을 바꾸면 해시가 그대로라 "
                            + "변경 전후 거래가 한 표본에 합산된다. behaviorParams() 에 추가할 것.",
                            f.getName())
                    .containsKey(lowerCamel(f.getName()));
        }
        assertThat(exposed).isNotEmpty();
    }

    @Test
    @DisplayName("감쇠 계수 기본값이 실제 코드 동작과 일치한다 — 지문이 거짓말하면 안 된다")
    void exposedValuesMatchActualBehaviour() {
        Map<String, String> p = SignalQualityDampenGate.behaviorParams();

        assertThat(Double.parseDouble(p.get("defaultTransitionalDampenFactor")))
                .isEqualTo(SignalQualityDampenGate.DEFAULT_TRANSITIONAL_DAMPEN_FACTOR);
        assertThat(Double.parseDouble(p.get("defaultNightDampenFactor")))
                .isEqualTo(SignalQualityDampenGate.DEFAULT_NIGHT_DAMPEN_FACTOR);
    }

    /** {@code DEFAULT_NIGHT_DAMPEN_FACTOR} → {@code defaultNightDampenFactor} */
    private static String lowerCamel(String screamingSnake) {
        String[] parts = screamingSnake.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }
}
