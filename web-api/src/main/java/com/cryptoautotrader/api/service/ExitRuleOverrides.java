package com.cryptoautotrader.api.service;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 세션별 청산 규칙 오버라이드 — {@code strategy_params} 로 주입되는 SL/TP 배수.
 *
 * <h3>왜 필요한가 (2026-08-24)</h3>
 * <p>{@link ExitRuleCalculator} 의 {@code SL_ATR_MULTIPLIER}/{@code TP_RR_MULTIPLIER} 는
 * {@code private static final} 상수라 전 세션이 같은 값을 쓴다. 손절폭 A/B 를 하려면
 * 세션마다 다른 값을 줄 수 있어야 한다 — 감쇠 A/B 가 {@code emaFilterDampenFactor} 를
 * {@code strategy_params} 로 넘긴 것과 같은 방식이다.</p>
 *
 * <h3>왜 두 값을 함께 움직여야 하나 (2026-07-31 실패 전례)</h3>
 * <p>TP 는 {@code SL폭 × TP_RR_MULTIPLIER} 로 <b>연동</b>돼 있다. 그래서 SL 만 넓히면
 * TP 도 같이 멀어져 도달 불가가 된다 — 07-31 개편이 정확히 그렇게 실패했다
 * ({@code ExitRuleCalculator.TP_PCT_MAX} javadoc: KRW-META2 TP +14.10%, 5일간 익절 0건/손절 3건).</p>
 *
 * <p>따라서 SL 을 넓히는 실험군은 {@code tpRrMultiplier} 를 함께 낮춰 TP 의 <b>절대 거리</b>를
 * 유지해야 한다. 예: {@code {"slAtrMultiplier": 2.5, "tpRrMultiplier": 1.2}}
 * → SL 2.5 ATR, TP 3.0 ATR (기존 1.5 ATR / 3.0 ATR 과 TP 거리가 같다).</p>
 *
 * <h3>지문</h3>
 * <p>이 값들은 {@code strategy_params} 에 실려 오고, {@code RulesetRegistry} 가 세 엔진 모두에서
 * {@code strategy.params} 키로 지문에 담는다 — 별도 등록 없이 arm 이 자동으로 갈린다.
 * ({@code exitcalc.*} 지문 키는 코드 기본값을 계속 가리키므로, 오버라이드 여부는
 * {@code strategy.params} 쪽에서 구분된다.)</p>
 */
@Slf4j
public final class ExitRuleOverrides {

    /** 오버라이드 없음 — 코드 기본값을 그대로 쓴다. */
    public static final ExitRuleOverrides NONE = new ExitRuleOverrides(null, null, null, null);

    static final String KEY_SL_ATR_MULTIPLIER = "slAtrMultiplier";
    static final String KEY_TP_RR_MULTIPLIER  = "tpRrMultiplier";
    static final String KEY_MIN_PNL_PCT_FOR_SIGNAL_EXIT = "minPnlPctForSignalExit";
    static final String KEY_LOSS_ESCAPE_THRESHOLD_PCT   = "lossEscapeThresholdPct";

    // 상한/하한 — 오타나 잘못된 실험 설정이 청산 규칙을 망가뜨리지 않게 막는다.
    // SL 0.5 ATR 미만은 사실상 즉시 손절, 6 ATR 초과는 SL_PCT_MAX(8%)에 항상 걸려 무의미하다.
    private static final BigDecimal SL_MULT_MIN = new BigDecimal("0.5");
    private static final BigDecimal SL_MULT_MAX = new BigDecimal("6.0");
    // TP 가 SL 보다 가까우면(<1.0) 손익비가 1 미만이 되어 어떤 승률로도 이길 수 없다.
    private static final BigDecimal TP_RR_MIN = BigDecimal.ONE;
    private static final BigDecimal TP_RR_MAX = new BigDecimal("5.0");

    // 전략 SELL 게이트 — 본전 차단 상한은 0%(차단 없음) ~ 5%.
    private static final BigDecimal MIN_PNL_MIN = BigDecimal.ZERO;
    private static final BigDecimal MIN_PNL_MAX = new BigDecimal("5.0");
    // 손실 탈출 하한은 음수만 유효. −100 이면 사실상 "손실 구간에서는 전략 SELL 금지"가 된다
    // (원화 포지션은 −100% 아래로 못 내려가므로 조건이 절대 참이 되지 않는다).
    private static final BigDecimal LOSS_ESCAPE_MIN = new BigDecimal("-100.0");
    private static final BigDecimal LOSS_ESCAPE_MAX = BigDecimal.ZERO;

    private final BigDecimal slAtrMultiplier;   // null = 기본값 사용
    private final BigDecimal tpRrMultiplier;    // null = 기본값 사용
    private final BigDecimal minPnlPctForSignalExit;  // null = 기본값 사용
    private final BigDecimal lossEscapeThresholdPct;  // null = 기본값 사용

    private ExitRuleOverrides(BigDecimal slAtrMultiplier, BigDecimal tpRrMultiplier,
                              BigDecimal minPnlPctForSignalExit, BigDecimal lossEscapeThresholdPct) {
        this.slAtrMultiplier = slAtrMultiplier;
        this.tpRrMultiplier  = tpRrMultiplier;
        this.minPnlPctForSignalExit = minPnlPctForSignalExit;
        this.lossEscapeThresholdPct = lossEscapeThresholdPct;
    }

    /**
     * {@code strategy_params} 맵에서 오버라이드를 읽는다.
     *
     * <p>값이 없거나·숫자가 아니거나·허용 범위를 벗어나면 <b>그 항목만</b> 무시하고 기본값을
     * 쓴다. 잘못된 실험 설정 하나로 진입 자체가 막히는 것보다, 기본 규칙으로 도는 편이 낫다.
     * 다만 무시할 때는 로그를 남긴다 — 조용히 기본값으로 도는 A/B 는 데이터를 오염시킨다.</p>
     */
    public static ExitRuleOverrides from(Map<String, Object> strategyParams) {
        if (strategyParams == null || strategyParams.isEmpty()) return NONE;
        BigDecimal sl = read(strategyParams, KEY_SL_ATR_MULTIPLIER, SL_MULT_MIN, SL_MULT_MAX);
        BigDecimal tp = read(strategyParams, KEY_TP_RR_MULTIPLIER,  TP_RR_MIN,   TP_RR_MAX);
        BigDecimal minPnl = read(strategyParams, KEY_MIN_PNL_PCT_FOR_SIGNAL_EXIT,
                MIN_PNL_MIN, MIN_PNL_MAX);
        BigDecimal lossEscape = read(strategyParams, KEY_LOSS_ESCAPE_THRESHOLD_PCT,
                LOSS_ESCAPE_MIN, LOSS_ESCAPE_MAX);
        return (sl == null && tp == null && minPnl == null && lossEscape == null)
                ? NONE : new ExitRuleOverrides(sl, tp, minPnl, lossEscape);
    }

    private static BigDecimal read(Map<String, Object> params, String key,
                                   BigDecimal min, BigDecimal max) {
        Object raw = params.get(key);
        if (raw == null) return null;
        BigDecimal v;
        try {
            v = new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("[ExitRule] {} 가 숫자가 아니라 무시한다 — 기본값 사용 (값={})", key, raw);
            return null;
        }
        if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            log.warn("[ExitRule] {}={} 가 허용 범위 [{}, {}] 를 벗어나 무시한다 — 기본값 사용",
                    key, v.toPlainString(), min.toPlainString(), max.toPlainString());
            return null;
        }
        return v;
    }

    /** 오버라이드가 있으면 그 값, 없으면 {@code fallback}. */
    BigDecimal slAtrMultiplierOr(BigDecimal fallback) {
        return slAtrMultiplier != null ? slAtrMultiplier : fallback;
    }

    /** 오버라이드가 있으면 그 값, 없으면 {@code fallback}. */
    BigDecimal tpRrMultiplierOr(BigDecimal fallback) {
        return tpRrMultiplier != null ? tpRrMultiplier : fallback;
    }

    /** 본전 청산 차단 상한(%) — 오버라이드가 있으면 그 값, 없으면 {@code fallback}. */
    public BigDecimal minPnlPctForSignalExitOr(BigDecimal fallback) {
        return minPnlPctForSignalExit != null ? minPnlPctForSignalExit : fallback;
    }

    /** 손실 탈출 하한(%) — 오버라이드가 있으면 그 값, 없으면 {@code fallback}. */
    public BigDecimal lossEscapeThresholdPctOr(BigDecimal fallback) {
        return lossEscapeThresholdPct != null ? lossEscapeThresholdPct : fallback;
    }

    /**
     * SL/TP 계산에 영향을 주는 오버라이드가 걸려 있는가.
     *
     * <p>전략 SELL 게이트 키({@link #KEY_MIN_PNL_PCT_FOR_SIGNAL_EXIT} /
     * {@link #KEY_LOSS_ESCAPE_THRESHOLD_PCT})는 <b>일부러 제외</b>한다 — 이 플래그는
     * "suggestedStopLoss 와 min() 을 타면 오버라이드가 무효화되는가"를 판정하는 데 쓰이는데,
     * 청산 게이트 키는 SL 가격 계산에 관여하지 않으므로 여기에 섞이면 A/B 대조군의 SL 이
     * 이유 없이 달라진다.</p>
     */
    public boolean isPresent() {
        return slAtrMultiplier != null || tpRrMultiplier != null;
    }

    /** 전략 SELL 게이트 오버라이드가 걸려 있는가 — 로깅·검증용. */
    public boolean hasSignalExitOverride() {
        return minPnlPctForSignalExit != null || lossEscapeThresholdPct != null;
    }

    @Override
    public String toString() {
        if (!isPresent() && !hasSignalExitOverride()) return "ExitRuleOverrides(기본값)";
        return String.format("ExitRuleOverrides(sl=%s, tpRr=%s, minPnl=%s, lossEscape=%s)",
                slAtrMultiplier != null ? slAtrMultiplier.toPlainString() : "기본",
                tpRrMultiplier  != null ? tpRrMultiplier.toPlainString()  : "기본",
                minPnlPctForSignalExit != null ? minPnlPctForSignalExit.toPlainString() : "기본",
                lossEscapeThresholdPct != null ? lossEscapeThresholdPct.toPlainString() : "기본");
    }
}
