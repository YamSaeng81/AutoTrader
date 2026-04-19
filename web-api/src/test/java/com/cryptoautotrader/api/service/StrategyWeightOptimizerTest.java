package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.core.selector.WeightOverrideStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 20260415_analy.md Tier 1 §6 회귀 방지.
 * - 실현 수익률 높은 전략이 가중치 상위를 차지한다
 * - 샘플 부족 전략은 중립(기본값 근방)으로 처리된다
 * - CLOSED 포지션 전무 시 4h 적중률 폴백이 동작한다
 */
class StrategyWeightOptimizerTest {

    private PositionRepository positionRepository;
    private StrategyLogRepository strategyLogRepository;
    private StrategyWeightOptimizer optimizer;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        strategyLogRepository = mock(StrategyLogRepository.class);
        when(strategyLogRepository.findEvaluatedSignals(any())).thenReturn(List.of());
        optimizer = new StrategyWeightOptimizer(strategyLogRepository, positionRepository);
        WeightOverrideStore.clear();
    }

    @Test
    void 실현수익률_높은_전략이_가중치_상위를_차지() {
        // TREND 레짐 — BREAKOUT 이 압도적 수익, MOMENTUM 은 손실, V2 는 평범
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("COMPOSITE_BREAKOUT",             "TREND", "1500000", "10000000", 15L));
        rows.add(row("COMPOSITE_MOMENTUM_ICHIMOKU_V2", "TREND", "300000",  "10000000",  8L));
        rows.add(row("COMPOSITE_MOMENTUM",             "TREND", "-500000", "10000000", 10L));
        when(positionRepository.aggregateRealizedReturnsByStrategyAndRegime(any())).thenReturn(rows);

        optimizer.optimize();

        Map<String, Map<String, Double>> snapshot = WeightOverrideStore.snapshot();
        assertThat(snapshot).containsKey("TREND");
        Map<String, Double> trend = snapshot.get("TREND");

        assertThat(trend.get("COMPOSITE_BREAKOUT"))
                .as("실현 수익률 15% 전략이 최상위")
                .isGreaterThan(trend.get("COMPOSITE_MOMENTUM_ICHIMOKU_V2"));
        assertThat(trend.get("COMPOSITE_MOMENTUM_ICHIMOKU_V2"))
                .as("V2 (3%) > MOMENTUM (손실)")
                .isGreaterThan(trend.get("COMPOSITE_MOMENTUM"));
        // 손실 전략도 MIN_WEIGHT(0.05) 이상 유지
        assertThat(trend.get("COMPOSITE_MOMENTUM")).isGreaterThanOrEqualTo(0.05);
        // 합계 1.0 (정규화)
        double sum = trend.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void 모든_전략_손실이면_기본값으로_폴백() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("COMPOSITE_BREAKOUT",             "VOLATILITY", "-100000", "10000000", 10L));
        rows.add(row("COMPOSITE_MOMENTUM_ICHIMOKU_V2", "VOLATILITY", "-200000", "10000000",  8L));
        rows.add(row("COMPOSITE_MOMENTUM",             "VOLATILITY", "-50000",  "10000000",  7L));
        when(positionRepository.aggregateRealizedReturnsByStrategyAndRegime(any())).thenReturn(rows);

        optimizer.optimize();

        Map<String, Map<String, Double>> snapshot = WeightOverrideStore.snapshot();
        assertThat(snapshot).containsKey("VOLATILITY");
        Map<String, Double> vol = snapshot.get("VOLATILITY");
        // 모두 클램프 0 → 기본값 폴백 = BREAKOUT 최상위
        assertThat(vol.get("COMPOSITE_BREAKOUT")).isEqualTo(0.50);
    }

    @Test
    void 실현샘플_부족시_기본값_유지_그리고_신호폴백_안쓰이면_건너뜀() {
        // CLOSED 포지션 전무, strategy_log 도 빈 상태
        when(positionRepository.aggregateRealizedReturnsByStrategyAndRegime(any()))
                .thenReturn(List.of());
        when(strategyLogRepository.findEvaluatedSignals(any())).thenReturn(List.of());

        optimizer.optimize();

        // 어떤 레짐도 override 등록되지 않아야 함
        Map<String, Map<String, Double>> snapshot = WeightOverrideStore.snapshot();
        assertThat(snapshot).isEmpty();
    }

    @Test
    void DEFAULTS_는_V2_포함() {
        // getCurrentWeights 는 DEFAULTS 전략들을 그대로 노출
        Map<String, Object> current = optimizer.getCurrentWeights();
        assertThat(current).containsKeys("TREND", "RANGE", "VOLATILITY");
        @SuppressWarnings("unchecked")
        Map<String, Object> trend = (Map<String, Object>) current.get("TREND");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> strategies = (List<Map<String, Object>>) trend.get("strategies");
        assertThat(strategies).extracting(m -> (String) m.get("name"))
                .contains("COMPOSITE_BREAKOUT", "COMPOSITE_MOMENTUM", "COMPOSITE_MOMENTUM_ICHIMOKU_V2");
    }

    private Object[] row(String strategy, String regime, String sumPnl, String sumInvested, long count) {
        return new Object[] {
                strategy,
                regime,
                new BigDecimal(sumPnl),
                new BigDecimal(sumInvested),
                count
        };
    }
}
