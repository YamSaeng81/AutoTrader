package com.cryptoautotrader.api.service;

import com.cryptoautotrader.core.risk.ExitRuleConfig;
import com.cryptoautotrader.core.risk.RulesetFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매매 규칙 지문 검증 — 2026-08-19.
 *
 * <p><b>왜 필요한가</b>: 이 프로젝트의 전제는 "모의 데이터로 실전을 판단한다" 인데,
 * 그 전제는 데이터가 어떤 규칙 아래 만들어졌는지 알 수 있을 때만 성립한다.
 * V71 이전에는 알 수 없었고, 그래서 규칙 변경을 발견할 때마다 데이터를 통째로 버려야 했다.</p>
 *
 * <p>실제 사고: 07-09·07-31 세션은 워치리스트 필터가 완화돼 있었는데(ATR 0.30/스프레드 0.15/후보 50)
 * 08-07 세션 재생성 때 코드 기본값(0.50/0.10/30)으로 조용히 돌아갔다. 감시 코인이 주당
 * 62종 → 10종으로 붕괴했지만 <b>어느 데이터가 어느 규칙의 산물인지 알 방법이 없었다.</b></p>
 */
class RulesetFingerprintTest {

    private static RulesetFingerprint.Builder dynamic() {
        return RulesetFingerprint.builder("DYNAMIC")
                .putExitRules(ExitRuleConfig.defaults())
                .put("scan.minAtrPct", new BigDecimal("0.5000"))
                .put("scan.maxSpreadPct", new BigDecimal("0.1000"));
    }

    @Test
    @DisplayName("같은 파라미터는 항상 같은 지문 — 순서와 무관")
    void sameParamsSameHash() {
        String a = dynamic().build().hash();
        String b = RulesetFingerprint.builder("DYNAMIC")
                .put("scan.maxSpreadPct", new BigDecimal("0.1000"))
                .put("scan.minAtrPct", new BigDecimal("0.5000"))
                .putExitRules(ExitRuleConfig.defaults())
                .build().hash();

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("스케일 차이로 지문이 갈리지 않는다 (0.30 == 0.3)")
    void scaleDoesNotAffectHash() {
        String a = RulesetFingerprint.builder("X").put("v", new BigDecimal("0.30")).build().hash();
        String b = RulesetFingerprint.builder("X").put("v", new BigDecimal("0.3")).build().hash();

        assertThat(a)
                .as("같은 값을 다른 스케일로 저장했다고 다른 규칙이 되면 표본이 무의미하게 쪼개진다")
                .isEqualTo(b);
    }

    @Test
    @DisplayName("08-07 회귀를 지문이 잡아낸다 — 워치리스트 필터가 바뀌면 다른 규칙")
    void watchlistFilterRegressionChangesHash() {
        // 07-31 세션 (완화) vs 08-07 세션 (기본값 복귀)
        String july = RulesetFingerprint.builder("DYNAMIC")
                .putExitRules(ExitRuleConfig.defaults())
                .put("scan.minAtrPct", new BigDecimal("0.30"))
                .put("scan.maxSpreadPct", new BigDecimal("0.15"))
                .put("scan.maxCandidateSize", 30)
                .build().hash();
        String august = RulesetFingerprint.builder("DYNAMIC")
                .putExitRules(ExitRuleConfig.defaults())
                .put("scan.minAtrPct", new BigDecimal("0.50"))
                .put("scan.maxSpreadPct", new BigDecimal("0.10"))
                .put("scan.maxCandidateSize", 30)
                .build().hash();

        assertThat(july)
                .as("이 둘이 같은 지문이면 감시 코인 62종 → 10종 붕괴가 또 소리 없이 묻힌다")
                .isNotEqualTo(august);
    }

    @Test
    @DisplayName("청산 규칙이 바뀌면 지문이 갈린다 — 08-18 lossEscapeThreshold 변경 같은 것")
    void exitRuleChangeChangesHash() {
        String before = RulesetFingerprint.builder("LIVE")
                .putExitRules(ExitRuleConfig.builder()
                        .lossEscapeThresholdPct(new BigDecimal("-1.00")).build())
                .build().hash();
        String after = RulesetFingerprint.builder("LIVE")
                .putExitRules(ExitRuleConfig.builder()
                        .lossEscapeThresholdPct(new BigDecimal("-0.30")).build())
                .build().hash();

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("엔진이 다르면 파라미터가 같아도 다른 규칙이다")
    void engineIsPartOfIdentity() {
        String live = RulesetFingerprint.builder("LIVE").putExitRules(ExitRuleConfig.defaults()).build().hash();
        String paper = RulesetFingerprint.builder("PAPER").putExitRules(ExitRuleConfig.defaults()).build().hash();

        assertThat(live)
                .as("PAPER 는 체결을 시뮬레이션하고 DYNAMIC 은 종목을 스캔한다 — 같은 규칙이 아니다")
                .isNotEqualTo(paper);
    }

    @Test
    @DisplayName("지문에서 파라미터 원문을 역참조할 수 있다")
    void canRecoverParams() {
        RulesetFingerprint f = dynamic().build();

        assertThat(f.toCanonicalString())
                .contains("engine=DYNAMIC")
                .contains("scan.minAtrPct=0.5")
                .contains("exit.lossEscapeThresholdPct=-0.3");
        assertThat(f.hash()).hasSize(12);
    }

    @Test
    @DisplayName("null 값도 지문에 반영된다 — 미설정과 설정을 구분한다")
    void nullIsDistinctFromValue() {
        String withNull = RulesetFingerprint.builder("X").put("v", (BigDecimal) null).build().hash();
        String withZero = RulesetFingerprint.builder("X").put("v", BigDecimal.ZERO).build().hash();

        assertThat(withNull).isNotEqualTo(withZero);
    }

    @Test
    @DisplayName("가드: ExitRuleConfig 의 모든 필드가 지문에 담긴다 — 새 필드를 빠뜨리면 여기서 깨진다")
    void everyExitRuleFieldIsFingerprinted() throws Exception {
        // 지금까지 두 번 "다 넣었다" 고 했다가 두 번 다 빠진 게 있었다(진입 게이트, strategy_params).
        // 사람이 체크리스트를 눈으로 대조하는 방식이 실패하고 있으므로 리플렉션으로 강제한다.
        String canonical = RulesetFingerprint.builder("X")
                .putExitRules(ExitRuleConfig.defaults()).build().toCanonicalString();

        for (java.lang.reflect.Field f : ExitRuleConfig.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;
            assertThat(canonical)
                    .as("ExitRuleConfig.%s 가 지문에 없다 — RulesetFingerprint.putExitRules() 에 "
                            + "추가할 것. 빠뜨리면 이 값을 바꿔도 지문이 그대로라 서로 다른 규칙의 "
                            + "거래가 한 표본에 섞인다.", f.getName())
                    .contains("exit." + f.getName() + "=");
        }
    }

    /**
     * 가드: {@link ExitRuleCalculator} 의 상수가 하나도 빠짐없이 지문에 실린다.
     *
     * <p><b>왜 이게 중요한가</b>: 세 엔진이 SL/TP 를 실제로 계산하는 곳은 {@code ExitRuleConfig}
     * (DB 설정) 가 아니라 이 클래스의 static 상수다. DYNAMIC 은 {@code ExitRuleConfig} 를
     * 참조조차 하지 않는다. 배포 전 검토(08-19) 시점에 이 5개 상수가 지문에 <b>전혀 없어서</b>,
     * {@code SL_ATR_MULTIPLIER} 를 1.5 → 2.0 으로 바꿔 손절폭이 33% 넓어져도 지문이 그대로였다.</p>
     *
     * <p>리플렉션으로 검사하므로 새 상수를 추가하고 {@code behaviorParams()} 에 넣지 않으면
     * 빌드가 깨진다 — 사람이 목록을 대조할 필요가 없다.</p>
     */
    @Test
    @DisplayName("가드: ExitRuleCalculator 의 모든 상수가 지문에 실린다 (리플렉션)")
    void everyExitCalculatorConstantIsFingerprinted() {
        Map<String, String> exposed = ExitRuleCalculator.behaviorParams();

        for (Field f : ExitRuleCalculator.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;
            String key = lowerCamel(f.getName());
            assertThat(exposed)
                    .as("ExitRuleCalculator.%s 가 지문에 없다 — 이 값을 바꿔도 지문이 그대로다", f.getName())
                    .containsKey(key);
        }
        assertThat(exposed).as("상수가 하나도 노출되지 않았다").isNotEmpty();
    }

    /** {@code SL_ATR_MULTIPLIER} → {@code slAtrMultiplier} */
    private static String lowerCamel(String screamingSnake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : screamingSnake.toCharArray()) {
            if (c == '_') { up = true; continue; }
            sb.append(sb.length() == 0 ? Character.toLowerCase(c)
                    : up ? Character.toUpperCase(c) : Character.toLowerCase(c));
            up = false;
        }
        return sb.toString();
    }
}
