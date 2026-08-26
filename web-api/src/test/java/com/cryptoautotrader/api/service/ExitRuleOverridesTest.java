package com.cryptoautotrader.api.service;

import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 손절폭 A/B 오버라이드 — {@link ExitRuleOverrides} + {@link ExitRuleCalculator} 연동.
 *
 * <p>핵심 계약은 두 가지다:
 * <ol>
 *   <li>오버라이드가 없으면 기존 동작과 <b>완전히</b> 같다 (회귀 방지)</li>
 *   <li>SL 만 넓히면 TP 가 따라 멀어진다 — 2026-07-31 실패를 재현하는 테스트로 고정한다</li>
 * </ol>
 */
class ExitRuleOverridesTest {

    /** ATR 이 계산되도록 일정 진폭을 가진 캔들 — 종가는 100,000 고정. */
    private static List<Candle> candles(int n, double range) {
        List<Candle> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(Candle.builder()
                    .time(Instant.ofEpochSecond(i * 3600L))
                    .open(BigDecimal.valueOf(100_000))
                    .high(BigDecimal.valueOf(100_000 + range / 2))
                    .low(BigDecimal.valueOf(100_000 - range / 2))
                    .close(BigDecimal.valueOf(100_000))
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return list;
    }

    private static final BigDecimal PRICE = BigDecimal.valueOf(100_000);
    private static final BigDecimal FLOOR = new BigDecimal("0.1");   // 하한을 낮춰 ATR 이 지배하게

    private static Map<String, Object> params(Object sl, Object tp) {
        Map<String, Object> m = new HashMap<>();
        if (sl != null) m.put("slAtrMultiplier", sl);
        if (tp != null) m.put("tpRrMultiplier", tp);
        return m;
    }

    // ── 계약 1: 오버라이드 없음 = 기존 동작 ──────────────────────────────────

    @Test
    @DisplayName("오버라이드가 없으면 기존 3인자 메서드와 완전히 같은 값을 낸다")
    void noOverrideMatchesLegacyBehaviour() {
        List<Candle> cs = candles(40, 2_000);

        BigDecimal legacy = ExitRuleCalculator.resolveStopLossPct(FLOOR, cs, PRICE);
        BigDecimal viaNone = ExitRuleCalculator.resolveStopLossPct(FLOOR, cs, PRICE, ExitRuleOverrides.NONE);
        BigDecimal viaEmpty = ExitRuleCalculator.resolveStopLossPct(FLOOR, cs, PRICE,
                ExitRuleOverrides.from(Map.of()));

        assertThat(viaNone).isEqualByComparingTo(legacy);
        assertThat(viaEmpty).isEqualByComparingTo(legacy);

        BigDecimal slPrice = PRICE.multiply(BigDecimal.valueOf(0.99));
        assertThat(ExitRuleCalculator.resolveTakeProfitPrice(PRICE, slPrice, null, ExitRuleOverrides.NONE))
                .isEqualByComparingTo(ExitRuleCalculator.resolveTakeProfitPrice(PRICE, slPrice, null));
    }

    @Test
    @DisplayName("null·빈 맵·관련 키 없는 맵은 모두 NONE 으로 수렴한다")
    void unrelatedParamsYieldNone() {
        assertThat(ExitRuleOverrides.from(null).isPresent()).isFalse();
        assertThat(ExitRuleOverrides.from(Map.of()).isPresent()).isFalse();
        assertThat(ExitRuleOverrides.from(Map.of("emaFilterDampenFactor", 1.0)).isPresent()).isFalse();
    }

    // ── 계약 2: SL 배수가 실제로 손절폭을 바꾼다 ─────────────────────────────

    @Test
    @DisplayName("slAtrMultiplier 를 1.5 → 2.5 로 올리면 손절폭이 정확히 비례해 넓어진다")
    void slMultiplierWidensStop() {
        List<Candle> cs = candles(40, 2_000);

        BigDecimal base = ExitRuleCalculator.resolveStopLossPct(FLOOR, cs, PRICE, ExitRuleOverrides.NONE);
        BigDecimal wide = ExitRuleCalculator.resolveStopLossPct(FLOOR, cs, PRICE,
                ExitRuleOverrides.from(params(2.5, null)));

        // 기본 1.5 → 2.5 이므로 5/3 배
        assertThat(wide).isGreaterThan(base);
        assertThat(wide.divide(base, 4, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("1.6667"));
    }

    @Test
    @DisplayName("SL 상한(8%)은 오버라이드로도 못 넘는다 — 초저유동 종목 안전판 유지")
    void slPctMaxStillCaps() {
        List<Candle> volatile_ = candles(40, 40_000);   // ATR 이 매우 큼
        BigDecimal wide = ExitRuleCalculator.resolveStopLossPct(FLOOR, volatile_, PRICE,
                ExitRuleOverrides.from(params(6.0, null)));
        assertThat(wide).isEqualByComparingTo(new BigDecimal("8.0"));
    }

    // ── 계약 3: 07-31 실패 재현 — SL 만 넓히면 TP 가 멀어진다 ────────────────

    @Test
    @DisplayName("⚠️ SL 만 넓히면 TP 도 따라 멀어진다 (2026-07-31 실패 패턴 고정)")
    void wideningSlAloneAlsoPushesTakeProfitAway() {
        // SL 1% → TP = 1% × 2.0 = 2%
        BigDecimal narrowSl = PRICE.multiply(BigDecimal.valueOf(0.99));
        BigDecimal tpNarrow = ExitRuleCalculator.resolveTakeProfitPrice(
                PRICE, narrowSl, null, ExitRuleOverrides.NONE);

        // SL 3% → TP = 3% × 2.0 = 6% ← 도달 확률이 급락한 지점
        BigDecimal wideSl = PRICE.multiply(BigDecimal.valueOf(0.97));
        BigDecimal tpWide = ExitRuleCalculator.resolveTakeProfitPrice(
                PRICE, wideSl, null, ExitRuleOverrides.NONE);

        assertThat(tpWide)
                .as("SL 을 넓히면 TP 도 멀어진다 — 이것이 07-31 익절 0건의 원인")
                .isGreaterThan(tpNarrow);
        assertThat(tpNarrow).isEqualByComparingTo(BigDecimal.valueOf(102_000).setScale(8));
        assertThat(tpWide).isEqualByComparingTo(BigDecimal.valueOf(106_000).setScale(8));
    }

    @Test
    @DisplayName("✅ tpRrMultiplier 를 함께 낮추면 TP 절대거리를 유지할 수 있다 (실험군 설계)")
    void loweringTpRrKeepsTakeProfitReachable() {
        // 대조군: SL 1%, TP_RR 2.0 → TP +2%
        BigDecimal tpControl = ExitRuleCalculator.resolveTakeProfitPrice(
                PRICE, PRICE.multiply(BigDecimal.valueOf(0.99)), null, ExitRuleOverrides.NONE);

        // 실험군: SL 을 1.667배로 넓히고(1% → 1.667%) TP_RR 을 2.0 → 1.2 로 낮춘다
        //         → TP = 1.667% × 1.2 = 2.0% — 대조군과 같은 거리
        BigDecimal tpArm = ExitRuleCalculator.resolveTakeProfitPrice(
                PRICE, PRICE.multiply(BigDecimal.valueOf(0.98333)), null,
                ExitRuleOverrides.from(params(null, 1.2)));

        assertThat(tpArm.subtract(tpControl).abs())
                .as("TP 절대거리 차이 (원). 손절만 넓히고 익절 거리는 유지하는 것이 실험 목적")
                .isLessThan(BigDecimal.valueOf(50));
    }

    // ── 계약 4: 잘못된 값 방어 ───────────────────────────────────────────────

    @Test
    @DisplayName("숫자가 아니거나 범위를 벗어난 값은 그 항목만 무시하고 기본값을 쓴다")
    void invalidValuesFallBackToDefaults() {
        assertThat(ExitRuleOverrides.from(params("abc", null)).isPresent()).isFalse();
        assertThat(ExitRuleOverrides.from(params(0.1, null)).isPresent()).isFalse();   // < 0.5
        assertThat(ExitRuleOverrides.from(params(99, null)).isPresent()).isFalse();    // > 6.0
        assertThat(ExitRuleOverrides.from(params(null, 0.5)).isPresent()).isFalse();   // TP_RR < 1.0

        // 한쪽만 잘못된 경우 — 나머지 한쪽은 살아남는다
        ExitRuleOverrides partial = ExitRuleOverrides.from(params("bad", 1.2));
        assertThat(partial.isPresent()).isTrue();
        assertThat(partial.slAtrMultiplierOr(new BigDecimal("1.5"))).isEqualByComparingTo("1.5");
        assertThat(partial.tpRrMultiplierOr(new BigDecimal("2.0"))).isEqualByComparingTo("1.2");
    }

    @Test
    @DisplayName("JSONB 역직렬화가 Integer/Double/String 무엇으로 주든 같게 읽는다")
    void acceptsMixedJsonNumericTypes() {
        BigDecimal expected = new BigDecimal("2.5");
        for (Object raw : List.of(2.5d, "2.5", new BigDecimal("2.5"))) {
            assertThat(ExitRuleOverrides.from(params(raw, null))
                    .slAtrMultiplierOr(BigDecimal.ONE))
                    .as("raw=%s (%s)", raw, raw.getClass().getSimpleName())
                    .isEqualByComparingTo(expected);
        }
        assertThat(ExitRuleOverrides.from(params(3, null)).slAtrMultiplierOr(BigDecimal.ONE))
                .isEqualByComparingTo("3");
    }

    // ── 계약 5: 전략 SELL 청산 게이트 오버라이드 (2026-08-26 A/B) ─────────────
    //
    // 운영 실측: PULLBACK_MTF 의 STRATEGY_SIGNAL 청산 39건이 승률 23.1%·합계 −4,594원으로
    // 이 전략 최대 손실원이었다(익절 10건 +6,830원을 거의 다 상쇄). 39건 중 21건이 −1.0%
    // 아래에서 청산돼, 손절선에 닿기 전에 전략이 먼저 항복하는 패턴이었다.

    private static final BigDecimal MIN_PNL_DEFAULT = new BigDecimal("0.30");
    private static final BigDecimal LOSS_ESCAPE_DEFAULT = new BigDecimal("-0.30");

    /** 운영 게이트와 같은 판정 — pnl 이 데드밴드 안이면 전략 SELL 이 막힌다. */
    private static boolean blocked(ExitRuleOverrides o, String pnlPct) {
        BigDecimal pnl = new BigDecimal(pnlPct);
        return pnl.compareTo(o.minPnlPctForSignalExitOr(MIN_PNL_DEFAULT)) < 0
                && pnl.compareTo(o.lossEscapeThresholdPctOr(LOSS_ESCAPE_DEFAULT)) >= 0;
    }

    @Test
    @DisplayName("청산 게이트 키가 없으면 기본 데드밴드(−0.30 ~ +0.30)가 그대로다")
    void signalExitGate_defaultsUnchanged() {
        ExitRuleOverrides none = ExitRuleOverrides.NONE;
        assertThat(blocked(none, "0.10")).as("본전 근처는 막힌다").isTrue();
        assertThat(blocked(none, "-0.10")).as("작은 손실도 막힌다").isTrue();
        assertThat(blocked(none, "0.50")).as("+0.30 이상은 나간다").isFalse();
        assertThat(blocked(none, "-1.00")).as("−0.30 아래는 손실 탈출로 나간다").isFalse();
    }

    @Test
    @DisplayName("🔴 lossEscapeThresholdPct=−100 이면 손실 구간에서 전략 SELL 이 전부 막힌다 (실험군)")
    void lossEscapeDisabled_blocksAllLosingSignalExits() {
        Map<String, Object> m = new HashMap<>();
        m.put("lossEscapeThresholdPct", -100.0);
        ExitRuleOverrides arm = ExitRuleOverrides.from(m);

        // 운영에서 실제로 나갔던 손실 구간이 이제 전부 막힌다 — SL/TP/time stop 만 청산한다
        assertThat(blocked(arm, "-0.50")).isTrue();
        assertThat(blocked(arm, "-1.00")).isTrue();
        assertThat(blocked(arm, "-5.00")).isTrue();

        // 수익 구간은 그대로 나간다 — 익절을 막으면 안 된다
        assertThat(blocked(arm, "0.50")).isFalse();
    }

    @Test
    @DisplayName("minPnlPctForSignalExit 로 익절 쪽 문턱도 따로 움직일 수 있다")
    void minPnlOverrideMovesProfitSideThreshold() {
        Map<String, Object> m = new HashMap<>();
        m.put("minPnlPctForSignalExit", 1.0);
        ExitRuleOverrides arm = ExitRuleOverrides.from(m);

        assertThat(blocked(arm, "0.50")).as("+0.5% 는 이제 문턱 미달이라 막힌다").isTrue();
        assertThat(blocked(arm, "1.50")).as("+1.5% 는 나간다").isFalse();
    }

    @Test
    @DisplayName("청산 게이트 오버라이드는 isPresent() 를 켜지 않는다 — 대조군 SL 이 이유 없이 달라지면 안 된다")
    void signalExitOverrideDoesNotAffectStopLossPath() {
        Map<String, Object> m = new HashMap<>();
        m.put("lossEscapeThresholdPct", -100.0);
        ExitRuleOverrides arm = ExitRuleOverrides.from(m);

        assertThat(arm.hasSignalExitOverride()).isTrue();
        assertThat(arm.isPresent())
                .as("isPresent() 는 SL/TP 계산 경로 판정용 — 여기 섞이면 suggestedStopLoss min() 경로가 바뀐다")
                .isFalse();

        // SL 폭이 오버라이드 없을 때와 정확히 같아야 한다
        List<Candle> cs = candles(40, 2_000);
        assertThat(ExitRuleCalculator.resolveStopLossPct(FLOOR, cs, PRICE, arm))
                .isEqualByComparingTo(ExitRuleCalculator.resolveStopLossPct(FLOOR, cs, PRICE,
                        ExitRuleOverrides.NONE));
    }

    @Test
    @DisplayName("범위를 벗어난 청산 게이트 값은 무시하고 기본값을 쓴다")
    void signalExitGate_invalidValuesIgnored() {
        Map<String, Object> tooLow = new HashMap<>();
        tooLow.put("lossEscapeThresholdPct", -200.0);      // < −100
        assertThat(ExitRuleOverrides.from(tooLow).hasSignalExitOverride()).isFalse();

        Map<String, Object> positive = new HashMap<>();
        positive.put("lossEscapeThresholdPct", 1.0);        // 손실 하한이 양수일 수 없다
        assertThat(ExitRuleOverrides.from(positive).hasSignalExitOverride()).isFalse();

        Map<String, Object> tooHigh = new HashMap<>();
        tooHigh.put("minPnlPctForSignalExit", 9.0);         // > 5.0
        assertThat(ExitRuleOverrides.from(tooHigh).hasSignalExitOverride()).isFalse();
    }

    @Test
    @DisplayName("SL/TP 오버라이드와 청산 게이트 오버라이드를 한 세션에 같이 걸 수 있다")
    void signalExitAndStopLossOverridesCoexist() {
        Map<String, Object> m = new HashMap<>();
        m.put("slAtrMultiplier", 2.5);
        m.put("lossEscapeThresholdPct", -100.0);
        ExitRuleOverrides both = ExitRuleOverrides.from(m);

        assertThat(both.isPresent()).isTrue();
        assertThat(both.hasSignalExitOverride()).isTrue();
        assertThat(both.slAtrMultiplierOr(new BigDecimal("1.5"))).isEqualByComparingTo("2.5");
        assertThat(blocked(both, "-1.00")).isTrue();
    }
}
