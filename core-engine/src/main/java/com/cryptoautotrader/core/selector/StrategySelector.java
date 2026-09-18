package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.strategy.Strategy;
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
        // Spring 컨텍스트가 없는 환경(단위 테스트 등)에서도 프리셋이 있어야 select()가 동작한다.
        //
        // 이전에는 여기서 COMPOSITE_BREAKOUT을 ATR(0.4)+VD(0.3)+RSI(0.2)+EMA(0.1)로 직접 등록했다.
        // 운영이 P1-1·P1-2로 ATR(0.5)+VD(0.3)+MACD(0.2)+RsiVeto로 바뀐 뒤에도 이 폴백은 갱신되지
        // 않아, core-engine 단위 테스트 전체가 운영과 다른 구성을 검증하고 있었다.
        // 이제 CompositePresets 하나만 보므로 경로에 따라 구성이 갈릴 수 없다.
        CompositePresets.ensureRegistered();
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
        return select(current, previous, coinPair, null);
    }

    /**
     * 코인 × <b>타임프레임</b> 특화 가중치 + TRANSITIONAL 처리 (2026-09-08).
     *
     * <p>같은 전략·코인·레짐이라도 H1 과 M15 의 성적은 부호가 반대인 경우가 실제로 있다
     * ({@code COMPOSITE_MTF_BTC} H1 −1.428% / M15 +0.046%). 타임프레임이 키에 없으면
     * 두 성적이 한 가중치로 합쳐져 서로 상쇄된다.</p>
     *
     * <p>{@code timeframe} 이 null 이면 종전 동작과 완전히 같다 —
     * {@code WeightOverrideStore} 가 타임프레임 무관 키로 폴백한다.</p>
     */
    public static List<WeightedStrategy> select(MarketRegime current, MarketRegime previous,
                                                 String coinPair, String timeframe) {
        return switch (current) {
            case TREND       -> trend(coinPair, timeframe);
            case RANGE       -> range(coinPair, timeframe);
            case VOLATILITY  -> volatility(coinPair, timeframe);
            case TRANSITIONAL -> {
                MarketRegime base = (previous == MarketRegime.TRANSITIONAL) ? MarketRegime.RANGE : previous;
                yield select(base, base, coinPair, timeframe).stream()
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

    private static List<WeightedStrategy> trend(String coinPair, String timeframe) {
        final String r = "TREND";
        return List.of(
                ws(r, coinPair, timeframe, "COMPOSITE_BREAKOUT",  0.65),
                ws(r, coinPair, timeframe, "COMPOSITE_MOMENTUM",  0.35)
        );
    }

    private static List<WeightedStrategy> range(String coinPair, String timeframe) {
        final String r = "RANGE";
        return List.of(
                ws(r, coinPair, timeframe, "COMPOSITE_MOMENTUM",  0.60),
                ws(r, coinPair, timeframe, "COMPOSITE_BREAKOUT",  0.40)
        );
    }

    private static List<WeightedStrategy> volatility(String coinPair, String timeframe) {
        final String r = "VOLATILITY";
        return List.of(
                ws(r, coinPair, timeframe, "COMPOSITE_BREAKOUT",  0.70),
                ws(r, coinPair, timeframe, "COMPOSITE_MOMENTUM",  0.30)
        );
    }

    /**
     * 코인 × 타임프레임 특화 가중치(있으면) → 코인 → 레짐 → 기본값 순으로 WeightedStrategy 를 만든다.
     * 폴백 순서는 {@code WeightOverrideStore.getForCoin} 이 담당한다.
     */
    private static WeightedStrategy ws(String regime, String coinPair, String timeframe,
                                        String name, double defaultWeight) {
        double weight = (coinPair != null)
                ? WeightOverrideStore.getForCoin(regime, coinPair, timeframe, name, defaultWeight)
                : WeightOverrideStore.get(regime, timeframe, name, defaultWeight);
        // 공유 인스턴스를 물면 바깥 세션 전략만 새로 만들어도 내부 GRID·레짐 감지기 상태가
        // 세션·백테스트 실행 사이에 공유된다. 매번 새 트리를 뽑아 격리한다.
        return new WeightedStrategy(newInstance(name), weight);
    }

    /**
     * 팩토리가 등록돼 있으면 새 인스턴스를, 아니면 공유 인스턴스를 반환한다.
     *
     * <p>복합 프리셋은 전부 {@link CompositePresets}가 팩토리로 등록하므로 새 트리가 나온다.
     * 팩토리가 없는 전략은 stateless 단일 전략이라 공유해도 안전하다.
     */
    private static Strategy newInstance(String name) {
        return StrategyRegistry.hasFactory(name)
                ? StrategyRegistry.createNew(name)
                : StrategyRegistry.get(name);
    }
}
