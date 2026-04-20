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
     * 전략의 동적 가중치를 반환한다.
     * 해당 regime/strategy 데이터가 없으면 defaultWeight를 반환한다.
     */
    public static double get(String regime, String strategyName, double defaultWeight) {
        Map<String, Double> regimeWeights = store.get(regime);
        if (regimeWeights == null) return defaultWeight;
        return regimeWeights.getOrDefault(strategyName, defaultWeight);
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
        coinStore.put(regime + ":" + coinPair, Map.copyOf(weights));
    }

    /**
     * 코인 특화 가중치를 반환한다.
     * 코인 레벨 → 레짐 레벨 → defaultWeight 순으로 폴백한다.
     */
    public static double getForCoin(String regime, String coinPair, String strategyName, double defaultWeight) {
        Map<String, Double> coinWeights = coinStore.get(regime + ":" + coinPair);
        if (coinWeights != null) return coinWeights.getOrDefault(strategyName, defaultWeight);
        return get(regime, strategyName, defaultWeight);
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
