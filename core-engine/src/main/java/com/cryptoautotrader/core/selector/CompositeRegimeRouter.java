package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.ema.EmaCrossStrategy;
import com.cryptoautotrader.strategy.grid.GridStrategy;
import com.cryptoautotrader.strategy.macd.MacdStrategy;
import com.cryptoautotrader.strategy.volumedelta.VolumeDeltaStrategy;
import com.cryptoautotrader.strategy.vwap.VwapStrategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시장 레짐(ADX/ATR)에 따라 복합 전략을 동적으로 위임하는 메타 전략.
 *
 * <h3>위임 규칙</h3>
 * <pre>
 * VOLATILITY   (ATR > SMA×1.5, ADX < 25) → COMPOSITE_BREAKOUT  — ATR 돌파 유리
 * TREND        (ADX > 25)                 → CMI_V1              — MACD+VWAP+Ichimoku
 * TRANSITIONAL (ADX 20~25)               → CMI_V1              — ADX 임계 완화 (BTC/SOL)
 * RANGE        (ADX < 20)                → CMI_V1              — VWAP 역추세 횡보 친화
 * </pre>
 *
 * <h3>설계 배경 (2026-06-30 90일 실전 분석 기반 개편)</h3>
 * <ul>
 *   <li>CMI_V1(MACD+VWAP+GRID+Ichimoku)이 전 레짐에서 CMI_V2(MACD+SUPERTREND+GRID)를 압도.
 *       TREND 레짐 V2 → V1 교체, RANGE HOLD → V1 위임으로 변경.</li>
 *   <li>VWAP(역추세)이 RANGE에서 자연스럽게 횡보 친화 신호를 생성 → ADX 게이트 불필요.</li>
 *   <li>단일 세션에서 레짐 전환 시 자동 위임 교체 — 운영자 개입 불필요.</li>
 *   <li>MarketRegimeDetector Hysteresis(3회 연속): 레짐 진동(flickering) 방지.</li>
 * </ul>
 *
 * <h3>내부 전략 구성</h3>
 * <ul>
 *   <li>BREAKOUT delegate: ATR(0.5) + VD(0.3) + MACD(0.2), EMA·ADX 필터·RSI Veto ON</li>
 *   <li>V1 delegate: MACD(0.5) + VWAP(0.3) + GRID(0.2), EMA 필터·Ichimoku 구름 ON</li>
 * </ul>
 *
 * <p>GRID는 stateful(세션 레벨 상태 보유) → StrategyRegistry에 stateful로 등록 필수.</p>
 */
public class CompositeRegimeRouter implements Strategy {

    private static final String NAME = "COMPOSITE_REGIME_ROUTER";

    /**
     * TRANSITIONAL(ADX 20~25) 위임 시 하위 MACD의 ADX 필터 임계(기본 25)를 레짐 상단 이하로 낮춰 주입한다.
     * 그렇지 않으면 CMI_V1의 주력 MACD가 이 구간에서 100% 차단되어 사실상 진입 불가가 된다
     * (2026-05-31 죽은지표 조사 P1-A: 레짐↔임계 불일치). V1 delegate의 CompositeStrategy는
     * adxFilter=false 이므로 이 키의 유일한 소비자는 MACD 하위전략뿐 → 부작용 없음.
     *
     * <p><b>코인 선택적 적용</b> (2026-06-01 백테스트 검증 결과): 임계 완화는 진입 빈도를
     * 늘리지만, 추세성이 강한 코인에서만 수익이 개선된다. 3년 H1 검증:
     * <ul>
     *   <li>BTC +13.7%→+20.0%, SOL +70.5%→+91.3%(MDD까지 개선) — 명확한 개선 → 완화 적용</li>
     *   <li>XRP +0.6%→<b>-14.4%</b> — 추세 모호 코인에서 오탐 급증 → 완화 미적용(25 유지)</li>
     *   <li>ETH/DOGE — 수익↑이나 MDD 악화 또는 소폭 손실 → 보수적으로 미적용(25 유지)</li>
     * </ul>
     * 따라서 검증으로 개선이 확인된 코인만 화이트리스트로 완화한다.
     */
    private static final double TRANSITIONAL_MACD_ADX_THRESHOLD = 20.0;

    /** TRANSITIONAL 임계 완화(20)를 적용할 코인 — 3년 H1 백테스트에서 수익·MDD 개선 확인된 코인만. */
    private static final List<String> ADX_RELAX_COINS = List.of("BTC", "SOL");

    // Stateful: 세션마다 독립 인스턴스 (MarketRegimeDetector 상태 격리)
    private final MarketRegimeDetector detector = new MarketRegimeDetector();

    // Stateful: 세션마다 독립 인스턴스 (MarketRegimeDetector 상태 격리)
    private final Strategy breakoutDelegate;
    private final Strategy momentumV1Delegate;

    public CompositeRegimeRouter() {
        // VOLATILITY 레짐 → COMPOSITE_BREAKOUT (ATR돌파 + VD + MACD, EMA·ADX·RSIVeto 필터)
        this.breakoutDelegate = new RsiVetoStrategy(NAME + "_BREAKOUT",
                new CompositeStrategy(NAME + "_BREAKOUT_BASE", List.of(
                        new WeightedStrategy(new AtrBreakoutStrategy(), 0.5),
                        new WeightedStrategy(new VolumeDeltaStrategy(),  0.3),
                        new WeightedStrategy(new MacdStrategy(),         0.2)
                ), true, true));  // emaFilter=ON, adxFilter=ON

        // TREND·TRANSITIONAL·RANGE 공통 → CMI_V1 (MACD + VWAP + GRID, EMA·Ichimoku 필터)
        // 90일 실전 분석(2026-06-30): V1이 전 레짐에서 V2(SUPERTREND 기반)를 압도.
        // VWAP(역추세)이 RANGE에서 자연스럽게 횡보 친화 신호를 생성해 HOLD 불필요.
        this.momentumV1Delegate = new IchimokuFilteredStrategy(NAME + "_V1",
                new CompositeStrategy(NAME + "_V1_BASE", List.of(
                        new WeightedStrategy(new MacdStrategy(),  0.5),
                        new WeightedStrategy(new VwapStrategy(),  0.3),
                        new WeightedStrategy(new GridStrategy(),  0.2)
                ), true));  // emaFilter=ON
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getMinimumCandleCount() {
        // IchimokuFilteredStrategy(78) > MarketRegimeDetector(50) > breakout(max of subs)
        return Math.max(
                MarketRegimeDetector.MIN_CANDLE_COUNT,
                Math.max(breakoutDelegate.getMinimumCandleCount(),
                        momentumV1Delegate.getMinimumCandleCount()));
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        MarketRegime regime = detector.detect(candles);
        String tag = "[" + regime.name() + "] ";

        return switch (regime) {
            case VOLATILITY   -> tag(breakoutDelegate.evaluate(candles, params),   tag);
            case TREND        -> tag(momentumV1Delegate.evaluate(candles, params),  tag);
            case TRANSITIONAL -> tag(momentumV1Delegate.evaluate(candles, transitionalParams(params)), tag);
            case RANGE        -> tag(momentumV1Delegate.evaluate(candles, params),  tag);
        };
    }

    /**
     * TRANSITIONAL 위임용 params: 검증으로 개선이 확인된 코인(ADX_RELAX_COINS)에 한해서만
     * MACD adxThreshold를 레짐 상단 이하(20)로 낮춰 주입한다. 그 외 코인은 MACD 기본값(25)을 유지한다.
     * 호출자가 adxThreshold를 명시한 경우 그 값을 존중한다(putIfAbsent). 원본 맵은 변경하지 않는다.
     */
    private static Map<String, Object> transitionalParams(Map<String, Object> params) {
        Map<String, Object> p = (params != null) ? new HashMap<>(params) : new HashMap<>();
        if (isAdxRelaxCoin(p.get("coinPair"))) {
            p.putIfAbsent("adxThreshold", TRANSITIONAL_MACD_ADX_THRESHOLD);
        }
        return p;
    }

    /** coinPair가 임계 완화 화이트리스트에 속하는지 (예: "KRW-BTC" → BTC 매칭). */
    private static boolean isAdxRelaxCoin(Object coinPair) {
        if (!(coinPair instanceof String cp)) {
            return false;
        }
        return ADX_RELAX_COINS.stream().anyMatch(cp::contains);
    }

    /**
     * 신호에 레짐 태그를 prefix로 붙여 반환한다.
     * 하위 전략이 제안한 suggestedStopLoss/takeProfit은 toBuilder로 보존한다
     * (reason만 교체 — 향후 SL/TP 제안 전략 확장 시 손절/익절 정보 유실 방지).
     */
    private static StrategySignal tag(StrategySignal signal, String tag) {
        return signal.toBuilder()
                .reason(tag + signal.getReason())
                .build();
    }
}
