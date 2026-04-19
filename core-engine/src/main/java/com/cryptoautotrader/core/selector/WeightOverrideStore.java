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
 * <p>구조: regime → (strategyName → weight)
 * 각 regime 그룹 내 가중치 합계는 optimizer가 1.0으로 정규화해서 저장한다.
 */
public final class WeightOverrideStore {

    private WeightOverrideStore() {}

    /** regime → (strategyName → weight) */
    private static final Map<String, Map<String, Double>> store = new ConcurrentHashMap<>();

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

    /** 현재 저장된 모든 가중치의 불변 스냅샷 반환 (REST API 등 조회용) */
    public static Map<String, Map<String, Double>> snapshot() {
        return Collections.unmodifiableMap(store);
    }

    /** 전체 오버라이드 초기화 — 테스트 격리 전용. */
    public static void clear() {
        store.clear();
    }
}
