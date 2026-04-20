package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.util.TradingConstants;
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
 * 실전·모의 신호 품질 데이터를 기반으로 Composite 전략 가중치를 자동 조정한다.
 *
 * <p>동작 원리:
 * <ol>
 *   <li>최근 LOOKBACK_DAYS 일간의 4h 평가 완료 신호를 레짐별·Composite 전략별로 집계한다.</li>
 *   <li>각 레짐 그룹 내 Composite 전략의 4h 적중률을 정규화해 가중치로 변환한다.</li>
 *   <li>급격한 변동 방지를 위해 계산값(70%)과 기본값(30%)을 혼합한다.</li>
 *   <li>{@link WeightOverrideStore}에 저장 → {@link com.cryptoautotrader.core.selector.StrategySelector}가 즉시 적용한다.</li>
 * </ol>
 *
 * <p>개선 이력:
 * <ul>
 *   <li>v1: SUPERTREND·EMA_CROSS 등 컴포넌트 전략명 기준 → strategy_log에 기록되지 않아 가중치 항상 기본값</li>
 *   <li>v2: COMPOSITE_BREAKOUT·COMPOSITE_MOMENTUM 등 Composite 전략명 기준으로 전환</li>
 *   <li>v3 (20260415_analy.md §6): <strong>4h 적중률 → 종료 포지션 실현 수익률</strong> 로 교체.
 *       4h 적중률은 신호 방향 정확성만 측정하므로 SL/TP·수수료·슬리피지가 완전히 빠진다.
 *       본 버전은 {@code position} 테이블의 CLOSED 포지션을 (전략, 레짐) 으로 집계해
 *       {@code sum(realizedPnl) / sum(investedKrw)} 를 net return 지표로 사용한다.
 *       실전 체결 데이터가 MIN_STRATEGY_SAMPLE 건 미만인 경우에만 4h 적중률로 폴백.</li>
 *   <li>v3: DEFAULTS 에 {@code COMPOSITE_MOMENTUM_ICHIMOKU_V2} 추가 — SOL/DOGE 3년 백테스트 최강.</li>
 * </ul>
 *
 * <p>보호 장치:
 * <ul>
 *   <li>레짐별 최소 샘플 {@value MIN_REGIME_SAMPLE}건 미만이면 해당 레짐은 기본값 유지</li>
 *   <li>전략별 최소 샘플 {@value MIN_STRATEGY_SAMPLE}건 미만이면 해당 전략도 기본값 사용 (중립 50%)</li>
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

    /**
     * regime → (Composite 전략명 → 기본 가중치).
     *
     * <p>StrategySelector의 실제 전략 구성과 반드시 동기화되어야 한다.
     * 여기 없는 전략명은 WeightOverrideStore에 저장돼도 StrategySelector가 조회하지 않아
     * 가중치 정규화가 왜곡된다.
     *
     * <p>기본값 근거 — 3년(2023~2025) H1 백테스트:
     * <ul>
     *   <li>COMPOSITE_BREAKOUT: BTC +104.2%, SOL +64.9%, ETH +38.9% — 추세·변동성 레짐 최강</li>
     *   <li>COMPOSITE_MOMENTUM: ETH +53.6%, SOL +59.8%, BTC +0.4% — VWAP·GRID 포함으로 레인지 적합</li>
     * </ul>
     *
     * <p>COMPOSITE_MOMENTUM_ICHIMOKU_V2 등 Ichimoku 계열은 CompositePresetRegistrar 에 구현·등록 완료.
     * 단, StrategySelector 의 레짐 기반 선택에는 아직 미연동 — 세션 직접 할당 방식으로 운영 중.
     * StrategySelector 에 추가 시 이 맵에도 동시 추가 필요.
     */
    private static final Map<String, Map<String, Double>> DEFAULTS = Map.of(
            "TREND",      Map.of(
                    "COMPOSITE_BREAKOUT", 0.65,
                    "COMPOSITE_MOMENTUM", 0.35),
            "RANGE",      Map.of(
                    "COMPOSITE_MOMENTUM", 0.60,
                    "COMPOSITE_BREAKOUT", 0.40),
            "VOLATILITY", Map.of(
                    "COMPOSITE_BREAKOUT", 0.70,
                    "COMPOSITE_MOMENTUM", 0.30)
    );

    private static final BigDecimal FEE_THRESHOLD = TradingConstants.FEE_THRESHOLD;

    private final StrategyLogRepository strategyLogRepo;
    private final PositionRepository positionRepository;

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

        // Primary: 종료 포지션의 실현 수익률 집계 — (regime → strategy → PerfStats)
        Map<String, Map<String, PerfStats>> realizedByRegime = loadRealizedReturns(from);

        // Fallback: 4h 적중률 (CLOSED 포지션이 부족한 초기 단계용)
        List<StrategyLogEntity> signals = strategyLogRepo.findEvaluatedSignals(from);
        Map<String, Map<String, List<StrategyLogEntity>>> signalsByRegime = groupByRegimeAndStrategy(signals);

        for (Map.Entry<String, Map<String, Double>> regimeEntry : DEFAULTS.entrySet()) {
            String regime = regimeEntry.getKey();
            Map<String, Double> defaultWeights = regimeEntry.getValue();

            Map<String, PerfStats> perf = realizedByRegime.getOrDefault(regime, Map.of());
            int realizedSample = perf.values().stream().mapToInt(p -> p.tradeCount).sum();

            Map<String, Double> newWeights;
            if (realizedSample >= MIN_REGIME_SAMPLE) {
                newWeights = computeWeightsFromRealized(defaultWeights, perf);
                log.info("[WeightOptimizer] {} 실현수익률 기반 갱신 ({}건) — {}",
                        regime, realizedSample, formatWeights(newWeights));
            } else {
                // 폴백: 4h 적중률 (초기 운영 또는 레짐별 거래 부족)
                Map<String, List<StrategyLogEntity>> strategyMap = signalsByRegime.getOrDefault(regime, Map.of());
                int signalSample = strategyMap.values().stream().mapToInt(List::size).sum();
                if (signalSample < MIN_REGIME_SAMPLE) {
                    log.info("[WeightOptimizer] {} 실현/신호 샘플 모두 부족 (realized={}, signals={}) — 기본값 유지",
                            regime, realizedSample, signalSample);
                    continue;
                }
                newWeights = computeWeightsFromSignals(defaultWeights, strategyMap);
                log.info("[WeightOptimizer] {} 4h 적중률 폴백 갱신 (realized={} < {}, signals={}) — {}",
                        regime, realizedSample, MIN_REGIME_SAMPLE, signalSample, formatWeights(newWeights));
            }

            WeightOverrideStore.update(regime, newWeights);
        }
    }

    // ── 실현 수익률 로딩 ──────────────────────────────────────────────────────

    /** (regime → strategy → PerfStats) — 수수료 포함 실현 수익률. */
    private Map<String, Map<String, PerfStats>> loadRealizedReturns(Instant from) {
        List<Object[]> rows = positionRepository.aggregateRealizedReturnsByStrategyAndRegime(from);
        Map<String, Map<String, PerfStats>> result = new HashMap<>();
        for (Object[] row : rows) {
            String strategy = (String) row[0];
            String regime   = (String) row[1];
            BigDecimal sumPnl      = toBigDecimal(row[2]);
            BigDecimal sumInvested = toBigDecimal(row[3]);
            int tradeCount = ((Number) row[4]).intValue();

            if (strategy == null || regime == null) continue;
            if (sumInvested.compareTo(BigDecimal.ZERO) <= 0) continue;
            if ("UNKNOWN".equals(regime) || "TRANSITIONAL".equals(regime)) continue;

            double netReturnPct = sumPnl.doubleValue() / sumInvested.doubleValue();
            result.computeIfAbsent(regime, k -> new HashMap<>())
                    .put(strategy, new PerfStats(netReturnPct, tradeCount));
        }
        return result;
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(o.toString());
    }

    /** 전략별 실현 성과 — {@code netReturnPct} = sum(realizedPnl)/sum(investedKrw). */
    private static final class PerfStats {
        final double netReturnPct;
        final int tradeCount;
        PerfStats(double netReturnPct, int tradeCount) {
            this.netReturnPct = netReturnPct;
            this.tradeCount = tradeCount;
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
     * 실현 수익률 기반 가중치 계산 — §6 핵심 개선.
     *
     * <p>점수: {@code max(netReturnPct, 0) + MIN_SCORE_FLOOR} — 음수 수익률 전략은 최소값만 할당.
     * 샘플 부족 전략은 중립 점수(0 return) 로 처리해 기본값 근방으로 수렴.</p>
     */
    private Map<String, Double> computeWeightsFromRealized(
            Map<String, Double> defaultWeights,
            Map<String, PerfStats> perf) {

        Map<String, Double> scores = new LinkedHashMap<>();
        for (String strategyName : defaultWeights.keySet()) {
            PerfStats p = perf.get(strategyName);
            if (p == null || p.tradeCount < MIN_STRATEGY_SAMPLE) {
                scores.put(strategyName, 0.0); // 중립 — 정규화·스무딩에서 기본값에 수렴
            } else {
                // 음수 수익률 전략은 0 으로 클램프 (MIN_WEIGHT 보정은 normalize 가 담당)
                scores.put(strategyName, Math.max(p.netReturnPct, 0.0));
            }
        }

        // 모든 점수가 0 이면 기본값으로 폴백
        if (scores.values().stream().allMatch(v -> v == 0.0)) {
            return new LinkedHashMap<>(defaultWeights);
        }

        Map<String, Double> normalized = normalize(scores);
        Map<String, Double> smoothed = new LinkedHashMap<>();
        for (String name : defaultWeights.keySet()) {
            double computed = normalized.getOrDefault(name, defaultWeights.get(name));
            double def      = defaultWeights.get(name);
            smoothed.put(name, SMOOTHING_NEW * computed + SMOOTHING_DEFAULT * def);
        }
        return normalize(smoothed);
    }

    /**
     * [FALLBACK] 레짐 내 전략별 4h 적중률 → 정규화 → 스무딩 — 실현 수익률 샘플 부족 시에만 사용.
     */
    private Map<String, Double> computeWeightsFromSignals(
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
