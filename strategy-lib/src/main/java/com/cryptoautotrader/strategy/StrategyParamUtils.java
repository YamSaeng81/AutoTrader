package com.cryptoautotrader.strategy;

import java.util.Map;

/**
 * 전략 파라미터 파싱 유틸리티
 * Number, String 타입 모두 처리한다 (JSON 역직렬화 시 타입 불일치 방어).
 */
public final class StrategyParamUtils {

    private StrategyParamUtils() {}

    public static int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return defaultVal; }
    }

    public static double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object v = params.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return defaultVal; }
    }

    public static boolean getBoolean(Map<String, Object> params, String key, boolean defaultVal) {
        Object v = params.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return defaultVal;
    }

    /**
     * <b>퍼센트</b> 파라미터를 읽어 <b>비율</b>로 돌려준다 (1.5 → 0.015).
     *
     * <p>⚠️ 2026-09-22 (Wave 4-M) — 이 메서드가 있는 이유.
     *
     * <p>같은 이름의 파라미터가 곳에 따라 단위가 달랐다:
     * <ul>
     *   <li>{@code HeikinAshiStochStrategy} — {@code stopLossPct = 1.5} 를 {@code /100} 해서 씀 (퍼센트)</li>
     *   <li>{@code MacdStochBbStrategy} — {@code stopLossPct = 0.02} 를 그대로 {@code 1 - x} 에 씀 (비율)</li>
     * </ul>
     *
     * <p>둘 다 이름이 {@code stopLossPct} 다. 즉 <b>같은 params 맵을 두 전략에 넘기면
     * 손절 폭이 100배 달라진다.</b> 세션의 {@code strategy_params} 가 전략 평가 params 를
     * 그대로 시드하므로(LiveTradingService), {@code stopLossPct: 5.0}(5% 의도)을 저장해 두면
     * MacdStochBb 는 <b>500%</b> 로 읽는다. 예외도 경고도 나지 않는다.
     *
     * <p>이 프로젝트의 규약은 <b>퍼센트</b>다 — {@code ExitRuleConfig.stopLossPct = 5.0},
     * 모든 DTO·엔티티, {@code emaFilterDeadbandPct} 의 {@code band / 100.0} 까지 전부 그렇다.
     * {@code MacdStochBb} 하나만 달랐다.
     *
     * <p>퍼센트 파라미터는 {@code getDouble} 로 직접 읽지 말고 이 메서드를 쓸 것.
     * 나누기를 호출자가 하면 단위가 다시 갈라진다 — 그게 이 결함의 발생 경로였다.
     *
     * @param defaultPercent 기본값도 <b>퍼센트 단위</b>로 준다 (2% 면 {@code 2.0}, {@code 0.02} 아님)
     * @return 비율. 곱셈에 바로 쓸 수 있다 — {@code entry * (1 - ratio)}
     * @see StrategyParamSchema
     */
    public static double getPercentAsRatio(Map<String, Object> params, String key, double defaultPercent) {
        return getDouble(params, key, defaultPercent) / 100.0;
    }

    /**
     * {@link #getPercentAsRatio} 의 BigDecimal 판. 가격 계산에 그대로 곱할 수 있다.
     *
     * <p>돈 계산은 BigDecimal 로 한다는 프로젝트 규약 때문에 double 판과 나란히 둔다.
     * 단위 변환({@code /100})이 두 판 모두 <b>여기 한 곳</b>에서만 일어나는 것이 요점이다 —
     * 호출자가 각자 나누면 단위가 다시 갈라진다. 그게 Wave 4-M 결함의 발생 경로였다.
     *
     * @param defaultPercent 기본값도 <b>퍼센트 단위</b> (1.5% 면 {@code 1.5})
     * @param scale          나눗셈 결과 스케일 (HALF_UP)
     */
    public static java.math.BigDecimal getPercentAsRatio(
            Map<String, Object> params, String key, double defaultPercent, int scale) {
        return java.math.BigDecimal.valueOf(getDouble(params, key, defaultPercent))
                .divide(java.math.BigDecimal.valueOf(100), scale, java.math.RoundingMode.HALF_UP);
    }
}
