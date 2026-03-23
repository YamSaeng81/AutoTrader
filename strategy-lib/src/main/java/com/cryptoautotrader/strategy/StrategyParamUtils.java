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
}
