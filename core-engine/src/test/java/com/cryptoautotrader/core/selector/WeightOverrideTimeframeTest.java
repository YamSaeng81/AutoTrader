package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 가중치의 <b>타임프레임 축</b> — 2026-09-08 신설 (V79).
 *
 * <h3>왜 필요한가</h3>
 * <p>{@code StrategyWeightOptimizer} 는 청산 포지션을 (레짐) 또는 (레짐:코인) 으로 묶어
 * 가중치를 냈다 — <b>타임프레임이 키에 없었다.</b> 그래서 같은 전략·코인·레짐이면 H1 과 M15
 * 성적이 한 가중치로 합쳐졌는데, 두 타임프레임은 <b>부호가 반대인 경우가 실제로 있다</b>
 * (2026-08-01~ BUY 신호 사후 4h, {@code COMPOSITE_MTF_BTC} H1 −1.428% / M15 +0.046%).
 * 합치면 서로 상쇄돼 최적화가 아무 방향도 잡지 못한다.</p>
 *
 * <p>같은 축 누락이 세 번째다 — 08-24 WF 게이트, 09-08 {@code strategy_log}(V76),
 * 그리고 여기(V79).</p>
 *
 * <h3>폴백을 함께 검증한다</h3>
 * <p>세분화 표본이 최소치에 못 미치는 동안에는 종전 동작이 유지돼야 한다. 폴백이 깨지면
 * 이 변경이 <b>가중치를 개선하는 대신 지워 버리는</b> 결과가 된다.</p>
 */
class WeightOverrideTimeframeTest {

    private static final String STRAT = "COMPOSITE_BREAKOUT";

    @BeforeEach
    @AfterEach
    void reset() {
        WeightOverrideStore.clear();
    }

    @Test
    @DisplayName("같은 레짐이라도 타임프레임이 다르면 다른 가중치다 (핵심 회귀)")
    void 타임프레임별로_가중치가_갈린다() {
        WeightOverrideStore.update("TREND", "H1",  Map.of(STRAT, 0.80));
        WeightOverrideStore.update("TREND", "M15", Map.of(STRAT, 0.20));

        assertThat(WeightOverrideStore.get("TREND", "H1", STRAT, 0.5)).isEqualTo(0.80);
        assertThat(WeightOverrideStore.get("TREND", "M15", STRAT, 0.5))
                .as("H1 −1.428%% / M15 +0.046%% 처럼 방향이 반대인 경우가 있다 — 합치면 상쇄된다")
                .isEqualTo(0.20);
    }

    @Test
    @DisplayName("타임프레임 표본이 없으면 무관 가중치로 폴백한다 — 종전 동작 유지")
    void 표본이_없으면_무관_가중치로_폴백한다() {
        WeightOverrideStore.update("TREND", null, Map.of(STRAT, 0.65));

        assertThat(WeightOverrideStore.get("TREND", "M15", STRAT, 0.5))
                .as("세분화 표본이 쌓이기 전에는 종전처럼 레짐 레벨 값을 써야 한다 — "
                        + "폴백이 없으면 이 변경이 가중치를 개선하는 대신 지워 버린다")
                .isEqualTo(0.65);
    }

    @Test
    @DisplayName("코인 레벨 4단 폴백: 코인@TF → 코인 → 레짐@TF → 레짐")
    void 코인레벨_폴백_순서() {
        WeightOverrideStore.update("TREND", null, Map.of(STRAT, 0.10));
        WeightOverrideStore.update("TREND", "H1", Map.of(STRAT, 0.20));
        WeightOverrideStore.updateForCoin("TREND", "KRW-BTC", null, Map.of(STRAT, 0.30));
        WeightOverrideStore.updateForCoin("TREND", "KRW-BTC", "H1", Map.of(STRAT, 0.40));

        assertThat(WeightOverrideStore.getForCoin("TREND", "KRW-BTC", "H1", STRAT, 0.99))
                .as("가장 구체적인 표본을 써야 한다").isEqualTo(0.40);
        assertThat(WeightOverrideStore.getForCoin("TREND", "KRW-BTC", "M15", STRAT, 0.99))
                .as("BTC@M15 표본이 없으면 코인 레벨로").isEqualTo(0.30);
        assertThat(WeightOverrideStore.getForCoin("TREND", "KRW-ETH", "H1", STRAT, 0.99))
                .as("ETH 표본이 없으면 레짐@H1 로").isEqualTo(0.20);
        assertThat(WeightOverrideStore.getForCoin("TREND", "KRW-ETH", "M15", STRAT, 0.99))
                .as("둘 다 없으면 레짐 레벨로").isEqualTo(0.10);
        assertThat(WeightOverrideStore.getForCoin("RANGE", "KRW-ETH", "M15", STRAT, 0.99))
                .as("아무것도 없으면 코드 기본값").isEqualTo(0.99);
    }

    @Test
    @DisplayName("기존 2인자 API 는 동작이 바뀌지 않는다 — 하위 호환")
    void 기존_api는_동작이_같다() {
        WeightOverrideStore.update("TREND", Map.of(STRAT, 0.65));

        assertThat(WeightOverrideStore.get("TREND", STRAT, 0.5)).isEqualTo(0.65);
        assertThat(WeightOverrideStore.getForCoin("TREND", "KRW-BTC", STRAT, 0.5)).isEqualTo(0.65);
    }

    @Test
    @DisplayName("StrategySelector 가 타임프레임을 가중치 조회에 실제로 쓴다")
    void 셀렉터가_타임프레임을_전달한다() {
        WeightOverrideStore.updateForCoin("TREND", "KRW-BTC", "M15",
                Map.of("COMPOSITE_BREAKOUT", 0.90, "COMPOSITE_MOMENTUM", 0.10));

        List<WeightedStrategy> m15 = StrategySelector.select(
                MarketRegime.TREND, MarketRegime.TREND, "KRW-BTC", "M15");
        List<WeightedStrategy> h1 = StrategySelector.select(
                MarketRegime.TREND, MarketRegime.TREND, "KRW-BTC", "H1");

        assertThat(weightOf(m15, "COMPOSITE_BREAKOUT"))
                .as("M15 오버라이드가 반영되지 않는다 — 셀렉터가 타임프레임을 버리고 있다")
                .isEqualTo(0.90);
        assertThat(weightOf(h1, "COMPOSITE_BREAKOUT"))
                .as("H1 표본은 없으므로 코드 기본값(0.65)이어야 한다 — M15 값이 새면 안 된다")
                .isEqualTo(0.65);
    }

    private static double weightOf(List<WeightedStrategy> list, String name) {
        return list.stream()
                .filter(ws -> ws.getStrategy().getName().equals(name)
                        || name.equals(ws.getStrategy().getName()))
                .mapToDouble(WeightedStrategy::getWeight)
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " 가 선택 목록에 없다"));
    }
}
