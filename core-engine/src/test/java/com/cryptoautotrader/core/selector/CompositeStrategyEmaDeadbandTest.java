package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-19 — <b>EMA 추세 판정 데드밴드</b> 회귀 테스트.
 *
 * <p><b>발견 경위</b>: 페이퍼 함대 112세션 중 93개가 12일간 거래를 한 번도 하지 않아 원인을
 * 분해하던 중 나왔다. EMA 필터가 매수 점수를 0으로 만든 424건 가운데 <b>192건(45%)이
 * EMA20/EMA50 격차 0.1% 미만</b>이었고, 91건은 로그 정밀도에서 아예 동일했다
 * ({@code EMA20=244 < EMA50=244}). 사실상 횡보인데 "하락추세" 로 분류돼 점수가 사라진 것이다.</p>
 *
 * <p>원인은 방향 판정이 {@code emaShort.compareTo(emaLong) > 0} 단순 대소 비교였다는 점이다.
 * 격차가 1원이든 0.0001원이든 추세로 친다.</p>
 *
 * <p><b>주의</b>: 이 수정으로 살아나는 신호가 <b>수익성 있다는 근거는 없다.</b> 알아낸 것은
 * 필터가 죽이던 신호의 <b>양</b>이지 그 신호의 <b>질</b>이 아니다. 판단은 A/B 로만 가능하다.</p>
 */
class CompositeStrategyEmaDeadbandTest {

    @Test
    @DisplayName("가드: 데드밴드 기본값이 0보다 크다 — 0이면 수정 전 동작(단순 대소 비교)으로 되돌아간다")
    void deadbandDefaultIsPositive() throws Exception {
        Field f = CompositeStrategy.class.getDeclaredField("DEFAULT_EMA_DEADBAND_PCT");
        f.setAccessible(true);
        assertThat(Modifier.isStatic(f.getModifiers())).isTrue();
        assertThat(f.getDouble(null))
                .as("0 이면 격차 0.0001%% 도 추세로 판정해 매수 점수를 통째로 죽인다")
                .isGreaterThan(0.0);
    }

    @Test
    @DisplayName("평탄 구간에서는 감쇠하지 않는다 — 방향 없음으로 판정한다")
    void flatMarketIsNotTreatedAsDowntrend() {
        String reason = buySignalOn(series(200, i -> 10_000.0));

        assertThat(reason)
                .as("사실상 횡보인데 하락추세로 분류되면 매수 점수가 사라진다")
                .doesNotContain("하락추세");
        assertThat(reason).contains("방향없음");
    }

    @Test
    @DisplayName("확실한 하락 구간에서는 여전히 필터가 동작한다 — 데드밴드가 역추세 보호를 무력화하면 안 된다")
    void realDowntrendStillFilters() {
        String reason = buySignalOn(series(200, i -> 10_000.0 - i * 25.0));

        assertThat(reason)
                .as("데드밴드를 넣느라 역추세 보호가 통째로 사라지면 그게 더 큰 문제다")
                .doesNotContain("방향없음");
        assertThat(reason).contains("하락추세");
    }

    @Test
    @DisplayName("데드밴드를 0으로 주면 수정 전 동작으로 되돌아간다 — A/B 의 대조군이 된다")
    void zeroDeadbandRestoresOldBehaviour() {
        String reason = buySignalOn(series(200, i -> 10_000.0),
                Map.of("emaFilterDeadbandPct", 0.0));

        // 완전 평탄이라 EMA 가 정확히 같아 uptrend=false → 기존 동작에서는 하락추세로 감쇠된다.
        assertThat(reason)
                .as("파라미터로 옛 동작을 재현할 수 없으면 A/B 대조군을 만들 수 없다")
                .doesNotContain("방향없음");
    }

    @Test
    @DisplayName("가드: CompositeStrategy 의 모든 동작 상수가 지문에 실린다 (리플렉션)")
    void everyBehaviourConstantIsFingerprinted() {
        java.util.Map<String, String> exposed = CompositeStrategy.behaviorParams();

        for (Field f : CompositeStrategy.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (!Modifier.isStatic(m) || !Modifier.isFinal(m) || f.isSynthetic()) continue;
            Class<?> t = f.getType();
            // 동작을 바꾸는 건 수치 상수뿐이다. 로거·이름 문자열 등은 대상이 아니다.
            if (!(t == int.class || t == double.class || t == long.class || t == float.class)) continue;

            assertThat(exposed)
                    .as("CompositeStrategy.%s 가 지문에 없다 — 이 값을 바꾸면 해시가 그대로라 "
                            + "변경 전후 거래가 한 표본에 합산된다. behaviorParams() 에 추가할 것.",
                            f.getName())
                    .containsKey(lowerCamel(f.getName()));
        }
        assertThat(exposed).isNotEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String buySignalOn(List<Candle> candles) {
        return buySignalOn(candles, Map.of());
    }

    /** EMA 필터를 켠 복합 전략에 만장일치 BUY 를 먹이고, 필터가 남긴 사유를 돌려준다. */
    private String buySignalOn(List<Candle> candles, Map<String, Object> params) {
        CompositeStrategy cs = new CompositeStrategy("EMA_FILTER_TEST", List.of(
                new WeightedStrategy(stubBuy("A"), 0.5),
                new WeightedStrategy(stubBuy("B"), 0.3)
        ), true);

        String reason = cs.evaluate(candles, params).getReason();
        assertThat(reason).as("사유가 비어 있으면 이 테스트는 아무것도 검증하지 못한다").isNotBlank();
        assertThat(reason).as("EMA 필터가 아예 안 돌면 판정 문구가 없다").contains("EMA필터");
        return reason;
    }

    private static Strategy stubBuy(String name) {
        return new Strategy() {
            @Override public String getName()               { return name; }
            @Override public int    getMinimumCandleCount() { return 1; }
            @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) {
                return StrategySignal.buy(BigDecimal.valueOf(100), name + "-BUY");
            }
        };
    }

    /** {@code DEFAULT_EMA_DEADBAND_PCT} → {@code defaultEmaDeadbandPct} */
    private static String lowerCamel(String screamingSnake) {
        String[] parts = screamingSnake.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    private static List<Candle> series(int n, IntToDoubleFunction priceAt) {
        List<Candle> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BigDecimal p = BigDecimal.valueOf(priceAt.applyAsDouble(i));
            out.add(Candle.builder()
                    .time(Instant.EPOCH.plusSeconds((long) i * 3600))
                    .open(p).high(p).low(p).close(p)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return out;
    }
}
