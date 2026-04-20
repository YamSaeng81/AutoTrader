package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.strategy.StrategyRegistry;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MarketRegime에 따라 활성화할 Composite 전략과 가중치를 결정한다.
 *
 * <pre>
 * TREND       : COMPOSITE_BREAKOUT(0.65) + COMPOSITE_MOMENTUM(0.35)
 *               — ATR/VD/RSI/EMA 기반 돌파 vs MACD/VWAP/GRID 기반 모멘텀
 *               — 백테스트 근거: BREAKOUT BTC +104%, SOL +65%, ETH +39%
 * RANGE       : COMPOSITE_MOMENTUM(0.60) + COMPOSITE_BREAKOUT(0.40)
 *               — VWAP·GRID(레인지 친화) vs ATR 돌파(레인지서 약화)
 * VOLATILITY  : COMPOSITE_BREAKOUT(0.70) + COMPOSITE_MOMENTUM(0.30)
 *               — ATR 기반 전략이 변동성 장에 최적화
 * TRANSITIONAL: 직전 Regime 전략 그룹 × 0.5 (포지션 축소)
 * </pre>
 *
 * <p>WeightOverrideStore에 해당 regime 오버라이드가 있으면 동적 가중치를 사용한다.
 * 오버라이드는 StrategyWeightOptimizer가 30일 신호 품질 데이터를 기반으로 주기적으로 갱신한다.
 */
public final class StrategySelector {

    static {
        // Spring 컨텍스트가 없는 환경(단위 테스트 등)을 위한 폴백 등록.
        // 실제 애플리케이션에서는 CompositePresetRegistrar(@PostConstruct)가
        // EMA·ADX 필터가 활성화된 버전으로 덮어쓴다.
        if (!StrategyRegistry.getAll().containsKey("COMPOSITE_BREAKOUT")) {
            // COMPOSITE_BREAKOUT: ATR(0.4)+VD(0.3)+RSI(0.2)+EMA(0.1) — EMA+ADX 필터 ON
            StrategyRegistry.register(new CompositeStrategy("COMPOSITE_BREAKOUT", List.of(
                    new WeightedStrategy(StrategyRegistry.get("ATR_BREAKOUT"),   0.4),
                    new WeightedStrategy(StrategyRegistry.get("VOLUME_DELTA"),   0.3),
                    new WeightedStrategy(StrategyRegistry.get("RSI"),            0.2),
                    new WeightedStrategy(StrategyRegistry.get("EMA_CROSS"),      0.1)
            ), true, true));  // emaFilter=true, adxFilter=true

            // COMPOSITE_MOMENTUM: MACD(0.5)+VWAP(0.3)+GRID(0.2) — EMA 필터 ON
            StrategyRegistry.register(new CompositeStrategy("COMPOSITE_MOMENTUM", List.of(
                    new WeightedStrategy(StrategyRegistry.get("MACD"),  0.5),
                    new WeightedStrategy(StrategyRegistry.get("VWAP"),  0.3),
                    new WeightedStrategy(StrategyRegistry.get("GRID"),  0.2)
            ), true));  // emaFilter=true
        }
    }

    private StrategySelector() {}

    /**
     * 현재 Regime에 맞는 WeightedStrategy 목록을 반환한다.
     *
     * @param current  현재 MarketRegime
     * @param previous TRANSITIONAL 시 사용할 이전 Regime (non-TRANSITIONAL이면 무시됨)
     */
    public static List<WeightedStrategy> select(MarketRegime current, MarketRegime previous) {
        return select(current, previous, null);
    }

    /**
     * 코인별 특화 가중치를 적용한 WeightedStrategy 목록을 반환한다.
     * coinPair가 null이면 레짐 레벨 기본값을 사용한다.
     *
     * @param current  현재 MarketRegime
     * @param coinPair "KRW-BTC" 등 (null 허용 → 레짐 레벨 폴백)
     */
    public static List<WeightedStrategy> select(MarketRegime current, String coinPair) {
        return select(current, current, coinPair);
    }

    /**
     * 코인별 특화 가중치 + TRANSITIONAL 처리.
     */
    public static List<WeightedStrategy> select(MarketRegime current, MarketRegime previous, String coinPair) {
        return switch (current) {
            case TREND       -> trend(coinPair);
            case RANGE       -> range(coinPair);
            case VOLATILITY  -> volatility(coinPair);
            case TRANSITIONAL -> {
                MarketRegime base = (previous == MarketRegime.TRANSITIONAL) ? MarketRegime.RANGE : previous;
                yield select(base, base, coinPair).stream()
                        .map(ws -> ws.withReducedWeight(0.5))
                        .collect(Collectors.toList());
            }
        };
    }

    /** current == previous 인 일반 호출용 오버로드 (코인 무관) */
    public static List<WeightedStrategy> select(MarketRegime current) {
        return select(current, current, null);
    }

    // ── 전략 그룹 정의 ───────────────────────────────────────────────────
    // WeightOverrideStore: 코인 레벨 → 레짐 레벨 → 하드코딩 기본값 순으로 폴백

    private static List<WeightedStrategy> trend(String coinPair) {
        final String r = "TREND";
        return List.of(
                ws(r, coinPair, "COMPOSITE_BREAKOUT",  0.65),
                ws(r, coinPair, "COMPOSITE_MOMENTUM",  0.35)
        );
    }

    private static List<WeightedStrategy> range(String coinPair) {
        final String r = "RANGE";
        return List.of(
                ws(r, coinPair, "COMPOSITE_MOMENTUM",  0.60),
                ws(r, coinPair, "COMPOSITE_BREAKOUT",  0.40)
        );
    }

    private static List<WeightedStrategy> volatility(String coinPair) {
        final String r = "VOLATILITY";
        return List.of(
                ws(r, coinPair, "COMPOSITE_BREAKOUT",  0.70),
                ws(r, coinPair, "COMPOSITE_MOMENTUM",  0.30)
        );
    }

    /**
     * 코인 특화 가중치(있으면) → 레짐 레벨 → 기본값 순으로 WeightedStrategy를 생성한다.
     */
    private static WeightedStrategy ws(String regime, String coinPair, String name, double defaultWeight) {
        double weight = (coinPair != null)
                ? WeightOverrideStore.getForCoin(regime, coinPair, name, defaultWeight)
                : WeightOverrideStore.get(regime, name, defaultWeight);
        return new WeightedStrategy(StrategyRegistry.get(name), weight);
    }
}
