package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.core.selector.WeightOverrideStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 실전·모의 신호 품질 데이터를 기반으로 전략 가중치를 자동 조정한다.
 *
 * <p>동작 원리:
 * <ol>
 *   <li>최근 LOOKBACK_DAYS 일간의 4h 평가 완료 신호를 레짐별·전략별로 집계한다.</li>
 *   <li>각 레짐 그룹 내 전략의 4h 적중률을 정규화해 가중치로 변환한다.</li>
 *   <li>급격한 변동 방지를 위해 계산값(70%)과 기본값(30%)을 혼합한다.</li>
 *   <li>{@link WeightOverrideStore}에 저장 → {@link com.cryptoautotrader.core.selector.StrategySelector}가 즉시 적용한다.</li>
 * </ol>
 *
 * <p>보호 장치:
 * <ul>
 *   <li>레짐별 최소 샘플 {@value MIN_REGIME_SAMPLE}건 미만이면 해당 레짐은 기본값 유지</li>
 *   <li>전략별 최소 샘플 {@value MIN_STRATEGY_SAMPLE}건 미만이면 해당 전략도 기본값 사용</li>
 *   <li>전략 최소 가중치 {@value MIN_WEIGHT} — 어떤 전략도 완전히 배제되지 않음</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyWeightOptimizer {

    private static final int    LOOKBACK_DAYS        = 30;
    private static final int    MIN_REGIME_SAMPLE    = 20;   // 레짐별 전체 최소 신호 수
    private static final int    MIN_STRATEGY_SAMPLE  = 5;    // 전략별 최소 신호 수
    private static final double MIN_WEIGHT           = 0.05; // 전략 최소 가중치
    private static final double SMOOTHING_NEW        = 0.70; // 새 가중치 비율
    private static final double SMOOTHING_DEFAULT    = 0.30; // 기본값 유지 비율

    /** regime → (strategyName → defaultWeight) */
    private static final Map<String, Map<String, Double>> DEFAULTS = Map.of(
            "TREND",      Map.of("SUPERTREND", 0.5, "EMA_CROSS", 0.3, "ATR_BREAKOUT", 0.2),
            "RANGE",      Map.of("BOLLINGER",  0.4, "VWAP",      0.4, "GRID",         0.2),
            "VOLATILITY", Map.of("ATR_BREAKOUT", 0.6, "VOLUME_DELTA", 0.4)
    );

    /** 수수료 공제 후 실질 승리 기준 (0.10% = 업비트 왕복 수수료) */
    private static final BigDecimal FEE_THRESHOLD = new BigDecimal("0.10");

    private final StrategyLogRepository strategyLogRepo;

    // ── 스케줄링 ──────────────────────────────────────────────────────────────

    /** 서버 시작 시 한 번 즉시 실행 */
    @EventListener(ApplicationReadyEvent.class)
    public void optimizeOnStartup() {
        log.info("[WeightOptimizer] 시작 최적화 실행");
        optimize();
    }

    /** 매일 06:00 KST 실행 (cron: UTC 21:00 = KST 06:00) */
    @Scheduled(cron = "0 0 21 * * *")
    public void optimizeScheduled() {
        log.info("[WeightOptimizer] 정기 최적화 실행");
        optimize();
    }

    // ── 핵심 로직 ─────────────────────────────────────────────────────────────

    public void optimize() {
        Instant from = Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS);
        List<StrategyLogEntity> signals = strategyLogRepo.findEvaluatedSignals(from);

        if (signals.isEmpty()) {
            log.info("[WeightOptimizer] 평가 신호 없음 — 기본값 유지");
            return;
        }

        // regime → (strategyName → 신호 목록)
        Map<String, Map<String, List<StrategyLogEntity>>> grouped = groupByRegimeAndStrategy(signals);

        for (Map.Entry<String, Map<String, Double>> regimeEntry : DEFAULTS.entrySet()) {
            String regime = regimeEntry.getKey();
            Map<String, Double> defaultWeights = regimeEntry.getValue();
            Map<String, List<StrategyLogEntity>> strategyMap = grouped.getOrDefault(regime, Map.of());

            int regimeTotalSample = strategyMap.values().stream().mapToInt(List::size).sum();
            if (regimeTotalSample < MIN_REGIME_SAMPLE) {
                log.info("[WeightOptimizer] {} 샘플 부족 ({}건 < {}) — 기본값 유지",
                        regime, regimeTotalSample, MIN_REGIME_SAMPLE);
                continue;
            }

            Map<String, Double> newWeights = computeWeights(regime, defaultWeights, strategyMap);
            WeightOverrideStore.update(regime, newWeights);

            log.info("[WeightOptimizer] {} 가중치 갱신 — {}",
                    regime, formatWeights(newWeights));
        }
    }

    // ── 집계 헬퍼 ─────────────────────────────────────────────────────────────

    private Map<String, Map<String, List<StrategyLogEntity>>> groupByRegimeAndStrategy(
            List<StrategyLogEntity> signals) {

        Map<String, Map<String, List<StrategyLogEntity>>> result = new HashMap<>();
        for (StrategyLogEntity s : signals) {
            String regime   = s.getMarketRegime() != null ? s.getMarketRegime() : "UNKNOWN";
            String strategy = s.getStrategyName() != null ? s.getStrategyName() : "UNKNOWN";
            if ("UNKNOWN".equals(regime) || "TRANSITIONAL".equals(regime)) continue;

            result.computeIfAbsent(regime, k -> new HashMap<>())
                  .computeIfAbsent(strategy, k -> new ArrayList<>())
                  .add(s);
        }
        return result;
    }

    /**
     * 레짐 내 전략별 4h 적중률 → 정규화 → 스무딩 적용 → 최종 가중치 맵 반환.
     */
    private Map<String, Double> computeWeights(
            String regime,
            Map<String, Double> defaultWeights,
            Map<String, List<StrategyLogEntity>> strategyMap) {

        // 1. 전략별 4h 적중률 계산
        Map<String, Double> winRates = new LinkedHashMap<>();
        for (String strategyName : defaultWeights.keySet()) {
            List<StrategyLogEntity> group = strategyMap.getOrDefault(strategyName, List.of());
            List<StrategyLogEntity> eval4h = group.stream()
                    .filter(l -> l.getReturn4hPct() != null).toList();

            if (eval4h.size() < MIN_STRATEGY_SAMPLE) {
                // 샘플 부족 → 기본 적중률 50%로 대체 (중립)
                winRates.put(strategyName, 0.50);
            } else {
                long wins = eval4h.stream()
                        .filter(l -> l.getReturn4hPct().compareTo(FEE_THRESHOLD) > 0)
                        .count();
                winRates.put(strategyName, (double) wins / eval4h.size());
            }
        }

        // 2. 정규화: 최소 가중치(MIN_WEIGHT) 보정 후 sum=1.0
        Map<String, Double> normalized = normalize(winRates);

        // 3. 스무딩: 70% 계산값 + 30% 기본값
        Map<String, Double> smoothed = new LinkedHashMap<>();
        for (String name : defaultWeights.keySet()) {
            double computed  = normalized.getOrDefault(name, defaultWeights.get(name));
            double def       = defaultWeights.get(name);
            smoothed.put(name, SMOOTHING_NEW * computed + SMOOTHING_DEFAULT * def);
        }

        // 4. 스무딩 후 재정규화
        return normalize(smoothed);
    }

    /**
     * 값들을 합계 1.0으로 정규화한다. 최소값 MIN_WEIGHT를 보장한다.
     */
    private Map<String, Double> normalize(Map<String, Double> raw) {
        // 최소값 적용
        Map<String, Double> clamped = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            clamped.put(e.getKey(), Math.max(e.getValue(), MIN_WEIGHT));
        }
        // 합계 계산 후 비율로 변환
        double sum = clamped.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : clamped.entrySet()) {
            result.put(e.getKey(), Math.round(e.getValue() / sum * 1000.0) / 1000.0);
        }
        return result;
    }

    /** 현재 WeightOverrideStore 스냅샷 반환 (REST API용) */
    public Map<String, Object> getCurrentWeights() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Map<String, Double>> snapshot = WeightOverrideStore.snapshot();

        for (Map.Entry<String, Map<String, Double>> regimeEntry : DEFAULTS.entrySet()) {
            String regime = regimeEntry.getKey();
            Map<String, Double> defaults = regimeEntry.getValue();
            Map<String, Double> current  = snapshot.getOrDefault(regime, defaults);

            List<Map<String, Object>> strategies = new ArrayList<>();
            for (String name : defaults.keySet()) {
                strategies.add(Map.of(
                        "name",          name,
                        "weight",        current.getOrDefault(name, defaults.get(name)),
                        "defaultWeight", defaults.get(name),
                        "overridden",    snapshot.containsKey(regime)
                ));
            }
            result.put(regime, Map.of(
                    "strategies", strategies,
                    "hasOverride", snapshot.containsKey(regime)
            ));
        }
        return result;
    }

    private String formatWeights(Map<String, Double> weights) {
        return weights.entrySet().stream()
                .map(e -> e.getKey() + "=" + String.format("%.3f", e.getValue()))
                .collect(Collectors.joining(", "));
    }
}
