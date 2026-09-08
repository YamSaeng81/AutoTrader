package com.cryptoautotrader.core.selector;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 신호 품질 데이터 기반 동적 전략 가중치 저장소 (스레드 안전).
 *
 * <p>StrategyWeightOptimizer(web-api)가 주기적으로 {@link #update}를 호출해 갱신하고,
 * StrategySelector가 {@link #get}으로 읽는다.
 * 데이터가 없으면 StrategySelector의 하드코딩 기본값이 그대로 사용된다.
 *
 * <p>구조 (2계층):
 * <ul>
 *   <li>레짐 레벨: regime → (strategyName → weight)  [글로벌 기본]</li>
 *   <li>코인 레벨: "regime:coin" → (strategyName → weight)  [코인별 특화, 우선 적용]</li>
 * </ul>
 * 각 그룹 내 가중치 합계는 optimizer가 1.0으로 정규화해서 저장한다.
 */
public final class WeightOverrideStore {

    private WeightOverrideStore() {}

    /** regime → (strategyName → weight) */
    private static final Map<String, Map<String, Double>> store = new ConcurrentHashMap<>();

    /** "regime:coinPair" → (strategyName → weight) */
    private static final Map<String, Map<String, Double>> coinStore = new ConcurrentHashMap<>();

    // ── 레짐 레벨 ─────────────────────────────────────────────────────────────

    /**
     * 특정 regime의 전략 가중치를 갱신한다.
     *
     * @param regime  "TREND" / "RANGE" / "VOLATILITY"
     * @param weights strategyName → weight (합계 1.0)
     */
    public static void update(String regime, Map<String, Double> weights) {
        store.put(regime, Map.copyOf(weights));
    }

    /**
     * 특정 regime × timeframe 의 전략 가중치를 갱신한다 (2026-09-08).
     *
     * <p>{@code timeframe} 이 null 이면 타임프레임 무관 키에 저장된다 — 아래 조회 폴백의 2단계다.</p>
     */
    public static void update(String regime, String timeframe, Map<String, Double> weights) {
        store.put(key(regime, timeframe), Map.copyOf(weights));
    }

    /**
     * 전략의 동적 가중치를 반환한다.
     * 해당 regime/strategy 데이터가 없으면 defaultWeight를 반환한다.
     */
    public static double get(String regime, String strategyName, double defaultWeight) {
        return get(regime, null, strategyName, defaultWeight);
    }

    /**
     * 타임프레임을 포함한 조회 — {@code regime@tf} → {@code regime} → defaultWeight 순 폴백 (2026-09-08).
     *
     * <p><b>왜 타임프레임이 키여야 하나</b>: 같은 전략·코인·레짐이라도 H1 과 M15 의 성적은
     * <b>부호가 반대인 경우가 실제로 있다</b> (2026-08-01~ BUY 신호 사후 4h,
     * {@code COMPOSITE_MTF_BTC} H1 −1.428% / M15 +0.046%). 한 가중치로 합치면 서로 상쇄돼
     * 최적화가 아무 방향도 못 잡는다.</p>
     *
     * <p>폴백을 두는 이유: 타임프레임별 표본이 최소치에 못 미치는 동안에는 종전처럼 레짐 레벨
     * 가중치를 쓰는 것이 낫다. 표본이 쌓이면 자동으로 세분화된 값이 이긴다.</p>
     */
    public static double get(String regime, String timeframe, String strategyName, double defaultWeight) {
        if (timeframe != null) {
            Map<String, Double> tfWeights = store.get(key(regime, timeframe));
            if (tfWeights != null) return tfWeights.getOrDefault(strategyName, defaultWeight);
        }
        Map<String, Double> regimeWeights = store.get(key(regime, null));
        if (regimeWeights == null) return defaultWeight;
        return regimeWeights.getOrDefault(strategyName, defaultWeight);
    }

    /** {@code regime} 또는 {@code regime@tf} — 타임프레임이 없으면 종전 키 그대로다(하위 호환). */
    private static String key(String regime, String timeframe) {
        return timeframe == null ? regime : regime + "@" + timeframe;
    }

    /** 해당 regime에 대한 오버라이드가 존재하는지 확인 */
    public static boolean hasOverrides(String regime) {
        return store.containsKey(regime);
    }

    // ── 코인 레벨 (코인별 특화 가중치 — 레짐보다 우선) ──────────────────────

    /**
     * 특정 regime × coinPair의 전략 가중치를 갱신한다.
     *
     * @param regime   "TREND" / "RANGE" / "VOLATILITY"
     * @param coinPair "KRW-BTC" / "KRW-ETH" 등
     * @param weights  strategyName → weight (합계 1.0)
     */
    public static void updateForCoin(String regime, String coinPair, Map<String, Double> weights) {
        updateForCoin(regime, coinPair, null, weights);
    }

    /** 타임프레임까지 구분해 저장한다 (2026-09-08) — {@link #get(String, String, String, double)} 참조. */
    public static void updateForCoin(String regime, String coinPair, String timeframe,
                                      Map<String, Double> weights) {
        coinStore.put(coinKey(regime, coinPair, timeframe), Map.copyOf(weights));
    }

    /**
     * 코인 특화 가중치를 반환한다.
     * 코인 레벨 → 레짐 레벨 → defaultWeight 순으로 폴백한다.
     */
    public static double getForCoin(String regime, String coinPair, String strategyName, double defaultWeight) {
        return getForCoin(regime, coinPair, null, strategyName, defaultWeight);
    }

    /**
     * 타임프레임을 포함한 4단 폴백 (2026-09-08):
     * {@code regime:coin@tf} → {@code regime:coin} → {@code regime@tf} → {@code regime} → defaultWeight.
     *
     * <p>가장 구체적인 표본이 있으면 그것을 쓰고, 없으면 한 축씩 넓혀 간다.</p>
     */
    public static double getForCoin(String regime, String coinPair, String timeframe,
                                     String strategyName, double defaultWeight) {
        if (timeframe != null) {
            Map<String, Double> tfCoin = coinStore.get(coinKey(regime, coinPair, timeframe));
            if (tfCoin != null) return tfCoin.getOrDefault(strategyName, defaultWeight);
        }
        Map<String, Double> coinWeights = coinStore.get(coinKey(regime, coinPair, null));
        if (coinWeights != null) return coinWeights.getOrDefault(strategyName, defaultWeight);
        return get(regime, timeframe, strategyName, defaultWeight);
    }

    private static String coinKey(String regime, String coinPair, String timeframe) {
        String base = regime + ":" + coinPair;
        return timeframe == null ? base : base + "@" + timeframe;
    }

    /** 코인별 오버라이드 스냅샷 반환 (REST API 등 조회용) */
    public static Map<String, Map<String, Double>> coinSnapshot() {
        return Collections.unmodifiableMap(coinStore);
    }

    // ── 공통 ──────────────────────────────────────────────────────────────────

    /** 현재 저장된 모든 레짐 가중치의 불변 스냅샷 반환 (REST API 등 조회용) */
    public static Map<String, Map<String, Double>> snapshot() {
        return Collections.unmodifiableMap(store);
    }

    /** 전체 오버라이드 초기화 — 테스트 격리 전용. */
    public static void clear() {
        store.clear();
        coinStore.clear();
    }
}
