package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.WeightOptimizerSnapshotRepository;
import com.cryptoautotrader.core.selector.WeightOverrideStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StrategyWeightOptimizer v3 회귀 테스트 (20260415_analy.md §6: 4h 적중률 → 실현 수익률).
 *
 * <p>v3 계약:
 * <ul>
 *   <li>{@code optimize()}는 {@link PositionRepository#findClosedPositionsForWeighting}로
 *       <b>개별 청산 포지션 행</b> {@code [strategy, regime, pnl, invested, closedAt]}을 받아
 *       지수가중 net return = Σ(pnl·w)/Σ(invested·w) 을 계산한다 (행 1개 = 1거래).</li>
 *   <li>레짐별 ≥ MIN_REGIME_SAMPLE(20)건이면 갱신, 미만이면 4h 적중률 폴백 →
 *       그것도 부족하면 기본값 유지(override 미등록).</li>
 *   <li>코인 레벨은 {@link PositionRepository#findClosedPositionsForWeightingByCoin}
 *       (기본 stub 빈 리스트)로 분리.</li>
 * </ul>
 *
 * <p>DEFAULTS(StrategySelector 동기화): TREND 0.65/0.35, RANGE MOMENTUM 0.60/BREAKOUT 0.40,
 * VOLATILITY 0.70/0.30 — 2전략(COMPOSITE_BREAKOUT + COMPOSITE_MOMENTUM).
 */
class StrategyWeightOptimizerTest {

    private PositionRepository positionRepository;
    private StrategyLogRepository strategyLogRepository;
    private WeightOptimizerSnapshotRepository snapshotRepository;
    private StrategyWeightOptimizer optimizer;

    @BeforeEach
    void setUp() {
        positionRepository    = mock(PositionRepository.class);
        strategyLogRepository = mock(StrategyLogRepository.class);
        snapshotRepository    = mock(WeightOptimizerSnapshotRepository.class);
        // 폴백 경로(4h 적중률)·코인 레벨은 기본적으로 비활성 (빈 데이터)
        when(strategyLogRepository.findEvaluatedSignalsBySessionType(any(), any())).thenReturn(List.of());
        when(positionRepository.findClosedPositionsForWeightingByCoin(any())).thenReturn(List.of());
        optimizer = new StrategyWeightOptimizer(
                strategyLogRepository, positionRepository, snapshotRepository);
        WeightOverrideStore.clear();
    }

    @Test
    @DisplayName("실현 수익률 높은 전략이 가중치 상위를 차지")
    void 실현수익률_높은_전략이_가중치_상위를_차지() {
        // TREND 레짐 — BREAKOUT +15%(15건), MOMENTUM -5%(10건). 합 25 ≥ MIN_REGIME_SAMPLE(20)
        List<Object[]> rows = new ArrayList<>();
        rows.addAll(positions("COMPOSITE_BREAKOUT", "TREND", "1500000", "10000000", 15));
        rows.addAll(positions("COMPOSITE_MOMENTUM", "TREND", "-500000", "10000000", 10));
        when(positionRepository.findClosedPositionsForWeighting(any())).thenReturn(rows);

        optimizer.optimize();

        Map<String, Map<String, Double>> snapshot = WeightOverrideStore.snapshot();
        assertThat(snapshot).containsKey("TREND");
        Map<String, Double> trend = snapshot.get("TREND");

        assertThat(trend.get("COMPOSITE_BREAKOUT"))
                .as("실현 수익률 +15% 전략이 손실 전략보다 가중치 높아야 한다")
                .isGreaterThan(trend.get("COMPOSITE_MOMENTUM"));
        assertThat(trend.get("COMPOSITE_MOMENTUM"))
                .as("손실 전략도 MIN_WEIGHT(0.05) 이상 유지")
                .isGreaterThanOrEqualTo(0.05);
        double sum = trend.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0, offset(0.01));
    }

    @Test
    @DisplayName("모든 전략 손실이면 DEFAULTS로 폴백")
    void 모든_전략_손실이면_기본값으로_폴백() {
        // VOLATILITY — 모두 음수 수익률. 합 22 ≥ 20 이지만 점수 전부 0 → 기본값 폴백
        List<Object[]> rows = new ArrayList<>();
        rows.addAll(positions("COMPOSITE_BREAKOUT", "VOLATILITY", "-100000", "10000000", 12));
        rows.addAll(positions("COMPOSITE_MOMENTUM", "VOLATILITY", "-50000",  "10000000", 10));
        when(positionRepository.findClosedPositionsForWeighting(any())).thenReturn(rows);

        optimizer.optimize();

        Map<String, Double> vol = WeightOverrideStore.snapshot().get("VOLATILITY");
        assertThat(vol).isNotNull();
        // 모두 클램프 0 → 기본값 폴백 = VOLATILITY DEFAULTS
        assertThat(vol.get("COMPOSITE_BREAKOUT")).isEqualTo(0.70);
        assertThat(vol.get("COMPOSITE_MOMENTUM")).isEqualTo(0.30);
    }

    @Test
    @DisplayName("실현 샘플 부족·신호 없으면 override 등록 안 함")
    void 실현샘플_부족시_기본값_유지_그리고_신호폴백_안쓰이면_건너뜀() {
        when(positionRepository.findClosedPositionsForWeighting(any())).thenReturn(List.of());
        when(strategyLogRepository.findEvaluatedSignalsBySessionType(any(), any())).thenReturn(List.of());

        optimizer.optimize();

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
        assertThat(trendStrategies).extracting(m -> (String) m.get("name"))
                .doesNotContain("COMPOSITE_MOMENTUM_ICHIMOKU_V2");
    }

    @Test
    @DisplayName("RANGE 레짐 — 가중치 합계 1.0, 2전략만 포함")
    void range_가중치_합계_검증() {
        // RANGE — MOMENTUM +8%(12건), BREAKOUT +2%(8건). 합 20 ≥ 20
        List<Object[]> rows = new ArrayList<>();
        rows.addAll(positions("COMPOSITE_MOMENTUM", "RANGE", "800000", "10000000", 12));
        rows.addAll(positions("COMPOSITE_BREAKOUT", "RANGE", "200000", "10000000",  8));
        when(positionRepository.findClosedPositionsForWeighting(any())).thenReturn(rows);

        optimizer.optimize();

        Map<String, Double> range = WeightOverrideStore.snapshot().get("RANGE");
        assertThat(range).isNotNull();
        assertThat(range).containsOnlyKeys("COMPOSITE_MOMENTUM", "COMPOSITE_BREAKOUT");

        double sum = range.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0, offset(0.01));

        assertThat(range.get("COMPOSITE_MOMENTUM"))
                .as("수익률 높은 MOMENTUM(+8%)이 BREAKOUT(+2%)보다 가중치 높아야 한다")
                .isGreaterThan(range.get("COMPOSITE_BREAKOUT"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * findClosedPositionsForWeighting 의 개별 청산 포지션 행 N개를 생성한다.
     * 컬럼: [strategy, regime, pnl, invested, closedAt]. closedAt=now → 지수가중치 ≈ 1.
     */
    private List<Object[]> positions(String strategy, String regime,
                                     String pnl, String invested, int count) {
        List<Object[]> rows = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[]{
                    strategy, regime, new BigDecimal(pnl), new BigDecimal(invested), now
            });
        }
        return rows;
    }
}
