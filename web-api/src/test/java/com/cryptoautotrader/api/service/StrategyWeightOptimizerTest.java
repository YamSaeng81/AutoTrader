package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.core.selector.WeightOverrideStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 20260415_analy.md Tier 1 §6 회귀 방지.
 * - 실현 수익률 높은 전략이 가중치 상위를 차지한다
 * - 샘플 부족 전략은 중립(기본값 근방)으로 처리된다
 * - CLOSED 포지션 전무 시 4h 적중률 폴백이 동작한다
 *
 * <p>DEFAULTS 는 StrategySelector 와 동기화된 2전략 구조
 * (COMPOSITE_BREAKOUT + COMPOSITE_MOMENTUM). ICHIMOKU_V2 는 미구현 상태로 제외.
 */
class StrategyWeightOptimizerTest {

    private PositionRepository positionRepository;
    private StrategyLogRepository strategyLogRepository;
    private StrategyWeightOptimizer optimizer;

    @BeforeEach
    void setUp() {
        positionRepository    = mock(PositionRepository.class);
        strategyLogRepository = mock(StrategyLogRepository.class);
        when(strategyLogRepository.findEvaluatedSignalsBySessionType(any(), any())).thenReturn(List.of());
        optimizer = new StrategyWeightOptimizer(strategyLogRepository, positionRepository);
        WeightOverrideStore.clear();
    }

    @Test
    @DisplayName("실현 수익률 높은 전략이 가중치 상위를 차지")
    void 실현수익률_높은_전략이_가중치_상위를_차지() {
        // TREND 레짐 — BREAKOUT 압도적 수익(15%), MOMENTUM 손실(-5%)
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("COMPOSITE_BREAKOUT", "TREND", "1500000", "10000000", 15L));
        rows.add(row("COMPOSITE_MOMENTUM", "TREND", "-500000", "10000000", 10L));
        when(positionRepository.aggregateRealizedReturnsByStrategyAndRegime(any())).thenReturn(rows);

        optimizer.optimize();

        Map<String, Map<String, Double>> snapshot = WeightOverrideStore.snapshot();
        assertThat(snapshot).containsKey("TREND");
        Map<String, Double> trend = snapshot.get("TREND");

        // BREAKOUT 수익률 우위 → 더 높은 가중치
        assertThat(trend.get("COMPOSITE_BREAKOUT"))
                .as("실현 수익률 15% 전략이 손실 전략보다 가중치 높아야 한다")
                .isGreaterThan(trend.get("COMPOSITE_MOMENTUM"));
        // 손실 전략도 MIN_WEIGHT(0.05) 이상 유지
        assertThat(trend.get("COMPOSITE_MOMENTUM"))
                .isGreaterThanOrEqualTo(0.05);
        // 합계 1.0 (정규화)
        double sum = trend.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0, offset(0.01));
    }

    @Test
    @DisplayName("모든 전략 손실이면 DEFAULTS로 폴백")
    void 모든_전략_손실이면_기본값으로_폴백() {
        // MIN_REGIME_SAMPLE=20 충족: 12 + 10 = 22건
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("COMPOSITE_BREAKOUT", "VOLATILITY", "-100000", "10000000", 12L));
        rows.add(row("COMPOSITE_MOMENTUM", "VOLATILITY", "-50000",  "10000000", 10L));
        when(positionRepository.aggregateRealizedReturnsByStrategyAndRegime(any())).thenReturn(rows);

        optimizer.optimize();

        Map<String, Map<String, Double>> snapshot = WeightOverrideStore.snapshot();
        assertThat(snapshot).containsKey("VOLATILITY");
        Map<String, Double> vol = snapshot.get("VOLATILITY");

        // 모두 클램프 0 → 기본값 폴백 = BREAKOUT 0.70
        assertThat(vol.get("COMPOSITE_BREAKOUT")).isEqualTo(0.70);
        assertThat(vol.get("COMPOSITE_MOMENTUM")).isEqualTo(0.30);
    }

    @Test
    @DisplayName("실현 샘플 부족·신호 없으면 override 등록 안 함")
    void 실현샘플_부족시_기본값_유지_그리고_신호폴백_안쓰이면_건너뜀() {
        when(positionRepository.aggregateRealizedReturnsByStrategyAndRegime(any()))
                .thenReturn(List.of());
        when(strategyLogRepository.findEvaluatedSignalsBySessionType(any(), any())).thenReturn(List.of());

        optimizer.optimize();

        // 어떤 레짐도 override 등록되지 않아야 함
        assertThat(WeightOverrideStore.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("getCurrentWeights — DEFAULTS 2전략 구조 노출")
    void getCurrentWeights_2전략_구조_노출() {
        Map<String, Object> current = optimizer.getCurrentWeights();
        assertThat(current).containsKeys("TREND", "RANGE", "VOLATILITY");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trendStrategies =
                (List<Map<String, Object>>) ((Map<String, Object>) current.get("TREND")).get("strategies");

        assertThat(trendStrategies).extracting(m -> (String) m.get("name"))
                .containsExactlyInAnyOrder("COMPOSITE_BREAKOUT", "COMPOSITE_MOMENTUM");

        // ICHIMOKU_V2 는 미구현 상태 — DEFAULTS 에 없어야 함
        assertThat(trendStrategies).extracting(m -> (String) m.get("name"))
                .doesNotContain("COMPOSITE_MOMENTUM_ICHIMOKU_V2");
    }

    @Test
    @DisplayName("RANGE 레짐 — 가중치 합계 1.0, 2전략만 포함")
    void range_가중치_합계_검증() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("COMPOSITE_MOMENTUM", "RANGE", "800000", "10000000", 12L));
        rows.add(row("COMPOSITE_BREAKOUT", "RANGE", "200000", "10000000",  8L));
        when(positionRepository.aggregateRealizedReturnsByStrategyAndRegime(any())).thenReturn(rows);

        optimizer.optimize();

        Map<String, Double> range = WeightOverrideStore.snapshot().get("RANGE");
        assertThat(range).containsOnlyKeys("COMPOSITE_MOMENTUM", "COMPOSITE_BREAKOUT");

        double sum = range.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0, offset(0.01));

        // 수익률 높은 MOMENTUM > BREAKOUT
        assertThat(range.get("COMPOSITE_MOMENTUM"))
                .isGreaterThan(range.get("COMPOSITE_BREAKOUT"));
    }

    private Object[] row(String strategy, String regime, String sumPnl, String sumInvested, long count) {
        return new Object[]{
                strategy, regime,
                new BigDecimal(sumPnl), new BigDecimal(sumInvested), count
        };
    }
}
