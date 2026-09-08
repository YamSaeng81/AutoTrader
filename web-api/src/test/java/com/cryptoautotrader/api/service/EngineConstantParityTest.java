package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엔진 간 상수 드리프트 감사 — 2026-09-08 신설.
 *
 * <h3>왜 또 만드나 (기존 두 테스트로 부족했던 이유)</h3>
 * <p>이 저장소의 반복 결함은 <b>"규칙을 한 엔진에 적용하고 나머지를 잊는다"</b> 하나다.
 * 그래서 가드를 두 번 세웠는데, 둘 다 <b>같은 병에 걸렸다 — 가드가 그 수정의 범위를
 * 그대로 물려받았다.</b></p>
 *
 * <ul>
 *   <li>{@code PaperLiveAlignmentTest}(08-06) — <b>이름을 손으로 나열</b>한다. 나열하지 않은
 *       상수는 갈려도 아무도 모른다. 실제로 {@code FEE_RATE}·{@code CLOSING_TIMEOUT_MINUTES}·
 *       {@code SL_STALE_WARN_MINUTES}·{@code ACTIVE_ORDER_STATES} 가 감시 밖에 있었다.</li>
 *   <li>{@code EngineParityTest}(08-19) — <b>소스 문자열 검사</b>라 "호출된다"까지만 본다.
 *       09-08 에 LIVE 가 자체 공식으로 TP 를 올리고 있었는데
 *       {@code has("Live", "Trailing;trailing")} 이 부분 문자열만으로 통과했다.</li>
 * </ul>
 *
 * <h3>이 테스트가 다른 점 — 목록을 사람이 관리하지 않는다</h3>
 * <p>세 엔진의 {@code static final} 필드를 <b>리플렉션으로 열거해</b> 이름이 겹치는 것을
 * 스스로 찾아내고 값이 같은지 본다. 새 상수를 두 엔진에 복제하는 순간, 아무도 이 파일을
 * 건드리지 않아도 감시 대상이 된다 — 가드가 수정 범위를 물려받는 고리를 끊는 지점이다.</p>
 *
 * <p>의도적으로 달라야 하는 값은 {@link #INTENTIONALLY_DIFFERENT} 에 사유와 함께 적는다.
 * 그 목록이 낡는 것도 검사한다({@link #면제목록이_낡지_않았다}).</p>
 *
 * <h3>중복 자체를 줄이는 쪽이 근본이다</h3>
 * <p>값이 같은지 <b>검사</b>하는 것보다 애초에 한 곳에만 두는 것이 낫다 —
 * {@code TradingConstants} 가 그 자리다. 이 테스트는 안전망이지, 복제를 허가하는 장치가 아니다.</p>
 */
class EngineConstantParityTest {

    private static final List<Class<?>> ENGINES = List.of(
            LiveTradingService.class,
            DynamicTradingService.class,
            PaperTradingService.class);

    /**
     * 이름이 같아도 값이 달라야 하는 상수 — <b>사유 필수</b>.
     *
     * <p>여기 추가하는 것은 "이 축에서는 엔진이 달라도 된다"는 선언이다. 근거 없이 넣으면
     * 그 순간 이 테스트가 무력해진다.
     */
    private static final Map<String, String> INTENTIONALLY_DIFFERENT = Map.of(
            "MAX_CONCURRENT_SESSIONS",
            "LIVE 10 / PAPER 120 — 페이퍼는 코인 N × 전략 M 격자 실험이 목적이라 동시 세션 수가 "
                    + "많아야 한다(PaperLiveAlignmentTest §격자 실험 참조). 실자본은 반대로 "
                    + "노출을 좁혀야 하므로 두 값이 같아지면 오히려 이상하다.");

    /** 엔진별 {@code static final} 상수를 (이름 → 값)으로 읽는다. */
    private static Map<String, Object> constantsOf(Class<?> engine) {
        Map<String, Object> out = new TreeMap<>();
        for (Field f : engine.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;
            // 로거 등 매매 거동과 무관한 인프라 필드는 제외
            if (f.getName().equals("log") || f.getName().startsWith("$")) continue;
            try {
                f.setAccessible(true);
                out.put(f.getName(), f.get(null));
            } catch (Exception e) {
                // 읽을 수 없는 필드는 비교 대상에서 뺀다 — 실패로 만들면 무관한 이유로 깨진다
            }
        }
        return out;
    }

    /** BigDecimal 은 스케일 차이로 갈리지 않게 값으로 비교한다 (0.30 == 0.3). */
    private static boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return a == null ? b == null : a.equals(b);
    }

    private static String render(Object v) {
        return v instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : String.valueOf(v);
    }

    /** 이름 → (엔진명 → 값). 두 엔진 이상에 나타나는 것만. */
    private static Map<String, Map<String, Object>> sharedConstants() {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Class<?> engine : ENGINES) {
            String label = engine.getSimpleName().replace("TradingService", "");
            constantsOf(engine).forEach((name, value) ->
                    byName.computeIfAbsent(name, k -> new LinkedHashMap<>()).put(label, value));
        }
        byName.values().removeIf(perEngine -> perEngine.size() < 2);
        return byName;
    }

    @Test
    @DisplayName("두 엔진 이상에 복제된 상수는 값이 같아야 한다 — 목록을 손으로 관리하지 않는다")
    void 복제된_상수는_값이_같다() {
        List<String> drifted = new ArrayList<>();

        sharedConstants().forEach((name, perEngine) -> {
            if (INTENTIONALLY_DIFFERENT.containsKey(name)) return;
            Object first = perEngine.values().iterator().next();
            boolean allSame = perEngine.values().stream().allMatch(v -> sameValue(first, v));
            if (!allSame) {
                StringBuilder sb = new StringBuilder(name).append(" → ");
                perEngine.forEach((eng, v) -> sb.append(eng).append("=").append(render(v)).append("  "));
                drifted.add(sb.toString().trim());
            }
        });

        assertThat(drifted)
                .as("엔진마다 값이 다른 상수가 있다. 한쪽만 튜닝하면 페이퍼·백테스트 성적이 "
                        + "실전 예측력을 잃는다(2026-08-18 LOSS_ESCAPE_THRESHOLD 4중복 드리프트가 그 사례).%n"
                        + "고칠 방법은 셋 중 하나다:%n"
                        + "  1) 값을 맞춘다 (대개 이쪽)%n"
                        + "  2) TradingConstants 로 옮겨 복제 자체를 없앤다 (가장 좋다)%n"
                        + "  3) 의도된 차이라면 INTENTIONALLY_DIFFERENT 에 사유와 함께 등록한다%n"
                        + "드리프트 목록:%n  %s", String.join("\n  ", drifted))
                .isEmpty();
    }

    @Test
    @DisplayName("면제 목록이 낡지 않았다 — 더는 복제되지 않는 이름이 남아 있으면 지운다")
    void 면제목록이_낡지_않았다() {
        Set<String> shared = new LinkedHashSet<>(sharedConstants().keySet());
        List<String> stale = INTENTIONALLY_DIFFERENT.keySet().stream()
                .filter(name -> !shared.contains(name))
                .toList();

        assertThat(stale)
                .as("면제 목록에 있으나 더 이상 두 엔진 이상에 복제되지 않은 상수다. "
                        + "그대로 두면 나중에 같은 이름이 다시 복제될 때 조용히 면제된다 — 항목을 지울 것: %s", stale)
                .isEmpty();
    }

    @Test
    @DisplayName("감사 대상이 비어 있지 않다 — 리플렉션이 조용히 0개를 훑는 것을 막는다")
    void 감사가_실제로_동작한다() {
        // 필드 접근 정책이 바뀌거나 상수가 전부 옮겨지면 위 두 테스트가 '검사할 것이 없어서'
        // 통과할 수 있다. 그 상태를 성공으로 착각하지 않도록 최소한의 신호를 고정한다.
        for (Class<?> engine : ENGINES) {
            assertThat(constantsOf(engine))
                    .as("%s 의 static final 상수를 하나도 읽지 못했다 — 리플렉션이 무력화됐는지 확인할 것",
                            engine.getSimpleName())
                    .isNotEmpty();
        }
    }
}
