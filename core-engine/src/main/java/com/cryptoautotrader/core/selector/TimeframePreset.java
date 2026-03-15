package com.cryptoautotrader.core.selector;

import java.util.HashMap;
import java.util.Map;

/**
 * 타임프레임별 전략 파라미터 프리셋
 *
 * <p>각 전략의 핵심 파라미터를 타임프레임 특성에 맞게 조정한다.
 * <ul>
 *   <li>짧은 TF(1m/5m): 빠른 응답, 작은 period — 노이즈 많음</li>
 *   <li>중간 TF(15m/1h): 균형 설정 (기본값 수준)</li>
 *   <li>긴 TF(4h/1d):  느린 응답, 큰 period — 추세 방향 필터용</li>
 * </ul>
 *
 * <p>사용 예:
 * <pre>{@code
 * Map<String, Object> htfParams = TimeframePreset.forStrategy("SUPERTREND", TimeframePreset.H1);
 * Map<String, Object> ltfParams = TimeframePreset.forStrategy("EMA_CROSS", TimeframePreset.M5);
 * }</pre>
 */
public class TimeframePreset {

    public static final String M1  = "1m";
    public static final String M5  = "5m";
    public static final String M15 = "15m";
    public static final String M30 = "30m";
    public static final String H1  = "1h";
    public static final String H4  = "4h";
    public static final String D1  = "1d";

    private TimeframePreset() {}

    /**
     * 지정한 전략 + 타임프레임에 맞는 파라미터 맵을 반환한다.
     * 알 수 없는 조합은 빈 맵 반환 (전략 내부 기본값 사용).
     */
    public static Map<String, Object> forStrategy(String strategyName, String timeframe) {
        return switch (strategyName) {
            case "SUPERTREND"          -> supertrend(timeframe);
            case "EMA_CROSS"           -> emaCross(timeframe);
            case "BOLLINGER"           -> bollinger(timeframe);
            case "RSI"                 -> rsi(timeframe);
            case "ATR_BREAKOUT"        -> atrBreakout(timeframe);
            case "ORDERBOOK_IMBALANCE" -> orderbookImbalance(timeframe);
            case "STOCHASTIC_RSI"      -> stochasticRsi(timeframe);
            default                    -> new HashMap<>();
        };
    }

    private static Map<String, Object> supertrend(String tf) {
        Map<String, Object> p = new HashMap<>();
        switch (tf) {
            case M1, M5  -> { p.put("atrPeriod", 7);  p.put("multiplier", 1.5); }
            case M15, M30 -> { p.put("atrPeriod", 10); p.put("multiplier", 2.0); }
            case H1       -> { p.put("atrPeriod", 14); p.put("multiplier", 2.5); }
            case H4, D1   -> { p.put("atrPeriod", 20); p.put("multiplier", 3.0); }
            default       -> { p.put("atrPeriod", 14); p.put("multiplier", 2.5); }
        }
        return p;
    }

    private static Map<String, Object> emaCross(String tf) {
        Map<String, Object> p = new HashMap<>();
        switch (tf) {
            case M1, M5  -> { p.put("fastPeriod", 5);  p.put("slowPeriod", 13); p.put("adxThreshold", 20.0); }
            case M15, M30 -> { p.put("fastPeriod", 9);  p.put("slowPeriod", 21); p.put("adxThreshold", 25.0); }
            case H1       -> { p.put("fastPeriod", 12); p.put("slowPeriod", 26); p.put("adxThreshold", 25.0); }
            case H4, D1   -> { p.put("fastPeriod", 20); p.put("slowPeriod", 50); p.put("adxThreshold", 20.0); }
            default       -> { p.put("fastPeriod", 9);  p.put("slowPeriod", 21); p.put("adxThreshold", 25.0); }
        }
        return p;
    }

    private static Map<String, Object> bollinger(String tf) {
        Map<String, Object> p = new HashMap<>();
        switch (tf) {
            case M1, M5  -> { p.put("period", 10); p.put("multiplier", 1.5); p.put("squeezeWindow", 15); }
            case M15, M30 -> { p.put("period", 20); p.put("multiplier", 2.0); p.put("squeezeWindow", 20); }
            case H1       -> { p.put("period", 20); p.put("multiplier", 2.0); p.put("squeezeWindow", 30); }
            case H4, D1   -> { p.put("period", 20); p.put("multiplier", 2.5); p.put("squeezeWindow", 30); }
            default       -> { p.put("period", 20); p.put("multiplier", 2.0); p.put("squeezeWindow", 30); }
        }
        return p;
    }

    private static Map<String, Object> rsi(String tf) {
        Map<String, Object> p = new HashMap<>();
        switch (tf) {
            case M1, M5  -> { p.put("period", 7);  p.put("oversoldLevel", 25.0); p.put("overboughtLevel", 75.0); p.put("pivotWindow", 7); }
            case M15, M30 -> { p.put("period", 14); p.put("oversoldLevel", 30.0); p.put("overboughtLevel", 70.0); p.put("pivotWindow", 10); }
            case H1       -> { p.put("period", 14); p.put("oversoldLevel", 30.0); p.put("overboughtLevel", 70.0); p.put("pivotWindow", 10); }
            case H4, D1   -> { p.put("period", 21); p.put("oversoldLevel", 35.0); p.put("overboughtLevel", 65.0); p.put("pivotWindow", 14); }
            default       -> { p.put("period", 14); p.put("oversoldLevel", 30.0); p.put("overboughtLevel", 70.0); p.put("pivotWindow", 10); }
        }
        return p;
    }

    private static Map<String, Object> atrBreakout(String tf) {
        Map<String, Object> p = new HashMap<>();
        switch (tf) {
            case M1, M5  -> { p.put("atrPeriod", 7);  p.put("multiplier", 1.0); p.put("volumeMultiplier", 1.2); }
            case M15, M30 -> { p.put("atrPeriod", 10); p.put("multiplier", 1.5); p.put("volumeMultiplier", 1.5); }
            case H1       -> { p.put("atrPeriod", 14); p.put("multiplier", 1.5); p.put("volumeMultiplier", 1.5); }
            case H4, D1   -> { p.put("atrPeriod", 14); p.put("multiplier", 2.0); p.put("volumeMultiplier", 2.0); }
            default       -> { p.put("atrPeriod", 14); p.put("multiplier", 1.5); p.put("volumeMultiplier", 1.5); }
        }
        return p;
    }

    private static Map<String, Object> orderbookImbalance(String tf) {
        Map<String, Object> p = new HashMap<>();
        switch (tf) {
            case M1, M5  -> { p.put("lookback", 3);  p.put("imbalanceThreshold", 0.60); }
            case M15, M30 -> { p.put("lookback", 5);  p.put("imbalanceThreshold", 0.65); }
            case H1       -> { p.put("lookback", 5);  p.put("imbalanceThreshold", 0.65); }
            case H4, D1   -> { p.put("lookback", 10); p.put("imbalanceThreshold", 0.70); }
            default       -> { p.put("lookback", 5);  p.put("imbalanceThreshold", 0.65); }
        }
        return p;
    }

    private static Map<String, Object> stochasticRsi(String tf) {
        Map<String, Object> p = new HashMap<>();
        switch (tf) {
            case M1, M5  -> { p.put("rsiPeriod", 7);  p.put("stochPeriod", 7);  p.put("smoothK", 3); p.put("smoothD", 3); }
            case M15, M30 -> { p.put("rsiPeriod", 14); p.put("stochPeriod", 14); p.put("smoothK", 3); p.put("smoothD", 3); }
            case H1       -> { p.put("rsiPeriod", 14); p.put("stochPeriod", 14); p.put("smoothK", 3); p.put("smoothD", 3); }
            case H4, D1   -> { p.put("rsiPeriod", 21); p.put("stochPeriod", 21); p.put("smoothK", 5); p.put("smoothD", 5); }
            default       -> { p.put("rsiPeriod", 14); p.put("stochPeriod", 14); p.put("smoothK", 3); p.put("smoothD", 3); }
        }
        return p;
    }
}
