package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.bollinger.BollingerStrategy;
import com.cryptoautotrader.strategy.ema.EmaCrossStrategy;
import com.cryptoautotrader.strategy.grid.GridStrategy;
import com.cryptoautotrader.strategy.macd.MacdStrategy;
import com.cryptoautotrader.strategy.orderbook.OrderbookImbalanceStrategy;
import com.cryptoautotrader.strategy.rsi.RsiStrategy;
import com.cryptoautotrader.strategy.supertrend.SupertrendStrategy;
import com.cryptoautotrader.strategy.volumedelta.VolumeDeltaStrategy;
import com.cryptoautotrader.strategy.vwap.VwapStrategy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 복합 전략 프리셋의 <b>단일 진실 원천</b>.
 *
 * <h3>왜 필요한가</h3>
 * <p>이전에는 같은 전략 이름의 구성이 세 곳에 따로 있었다.
 * <ul>
 *   <li>{@code CompositePresetRegistrar}(web-api) — 운영이 실행하는 구성</li>
 *   <li>{@code BacktestService.compositeBreakoutBt()/compositeEthBt()} — 백테스트 전용 구성</li>
 *   <li>{@code StrategySelector} static 폴백 — Spring 없는 환경(단위 테스트)용 구성</li>
 * </ul>
 * COMPOSITE_BREAKOUT은 세 구성이 전부 달랐다. 운영은 ATR(0.5)+VD(0.3)+MACD(0.2)에 RSI Veto를
 * 두른 형태였지만, 백테스트와 단위 테스트는 P1-1·P1-2 개선 이전의 ATR(0.4)+VD(0.3)+RSI(0.2)+EMA(0.1)를
 * 돌리고 있었다. <b>측정한 전략과 실행하는 전략이 다르면 성과 비교 자체가 근거가 되지 못한다.</b>
 *
 * <h3>계약</h3>
 * <ul>
 *   <li>모든 프리셋은 {@link Supplier} 팩토리로 정의한다. 호출할 때마다 <b>전체 전략 트리가
 *       새로 생성</b>되므로, 바깥 래퍼뿐 아니라 내부 GRID·레짐 감지기까지 상태가 격리된다.</li>
 *   <li>{@link #registerAll()}은 멱등이다. Spring 기동 경로와 테스트 경로에서 몇 번 불려도
 *       같은 구성이 등록된다.</li>
 *   <li>전부 {@code registerStateful}로 등록한다 — stateless 프리셋이라도 팩토리를 남겨야
 *       실행·세션 단위로 새 트리를 뽑을 수 있다({@code StrategyRegistry.createNew}).</li>
 * </ul>
 *
 * <p>여기 없는 단일 시장 전략은 {@link StrategyRegistry} 자체 static 블록이 등록한다.
 */
public final class CompositePresets {

    static {
        // 이 클래스를 건드리기만 해도 프리셋이 존재하도록 한다. 이름으로 전략을 찾는 지점
        // (BacktestEngine, StrategySelector)은 ensureRegistered()를 따로 부르지만, 그 둘을
        // 거치지 않고 StrategyRegistry를 직접 조회하는 경로도 있다.
        registerAll();
    }

    private CompositePresets() {}

    // ── 공통 성분 조립 ────────────────────────────────────────────────────
    // 같은 구성이 여러 프리셋에 반복 등장한다. 한 곳에서 만들어야 한쪽만 갱신되는 드리프트가 없다.

    /**
     * CB(Composite Breakout) 코어: ATR(0.5) + VD(0.3) + MACD(0.2), EMA·ADX 필터 ON.
     *
     * <p>변경 이력:
     * <ul>
     *   <li>P1-1: EMA_CROSS(0.1) → MACD(0.2) 교체. EMA_CROSS는 EMA 방향 필터(EMA20/50)와
     *       동일 지표를 이중 카운팅했다. MACD는 독립적인 모멘텀 신호원.</li>
     *   <li>P1-2: RSI(0.2) 제거 → {@link RsiVetoStrategy} 래퍼로 대체. 가중치 0.2로는
     *       수학적으로 단독 BUY 차단이 불가능했다(confidence &gt; 2.0 필요).
     *       RSI &gt; 75이면 어떤 BUY도 강제 차단하는 Veto Gate로 분리했다.</li>
     * </ul>
     */
    private static Strategy breakoutCore(String name) {
        return new RsiVetoStrategy(name,
                new CompositeStrategy(name + "_BASE", List.of(
                        new WeightedStrategy(new AtrBreakoutStrategy(), 0.5),
                        new WeightedStrategy(new VolumeDeltaStrategy(),  0.3),
                        new WeightedStrategy(new MacdStrategy(),         0.2)
                ), true, true));  // emaFilter=ON, adxFilter=ON
    }

    /** CMI_V1 코어: MACD(0.5) + VWAP(0.3) + GRID(0.2), EMA 방향 필터 ON. GRID가 stateful. */
    private static CompositeStrategy momentumCore(String name) {
        return new CompositeStrategy(name, List.of(
                new WeightedStrategy(new MacdStrategy(), 0.5),
                new WeightedStrategy(new VwapStrategy(), 0.3),
                new WeightedStrategy(new GridStrategy(), 0.2)
        ), true);
    }

    /** CMI_V2 코어: MACD(0.5) + SUPERTREND(0.3) + GRID(0.2). V1의 VWAP(역추세)를 추세추종으로 교체. */
    private static CompositeStrategy momentumV2Core(String name) {
        return new CompositeStrategy(name, List.of(
                new WeightedStrategy(new MacdStrategy(),       0.5),
                new WeightedStrategy(new SupertrendStrategy(), 0.3),
                new WeightedStrategy(new GridStrategy(),       0.2)
        ), true);
    }

    // ── 프리셋 정의 ──────────────────────────────────────────────────────

    /**
     * 이름 → 팩토리. 등록 순서를 보존해 로그·목록이 안정적으로 나오도록 LinkedHashMap을 쓴다.
     */
    public static Map<String, Supplier<Strategy>> factories() {
        Map<String, Supplier<Strategy>> m = new LinkedHashMap<>();

        // COMPOSITE: 시장 국면 기반 동적 전략 선택 — MarketRegimeDetector 상태 보유
        m.put("COMPOSITE", RegimeAdaptiveStrategy::new);

        // COMPOSITE_REGIME_ROUTER: 레짐 기반으로 최적 전략을 자동 위임하는 단일 메타 전략.
        // 90일 실전 분석(2026-06-30): CMI_V1이 전 레짐 압도.
        //   VOLATILITY → BREAKOUT / TREND·TRANSITIONAL·RANGE → CMI_V1
        m.put("COMPOSITE_REGIME_ROUTER", CompositeRegimeRouter::new);

        // COMPOSITE_MOMENTUM: 적합 BTC·ETH 등 대형 코인(VWAP 신뢰도 높음).
        // 근거: KRW-BTC H1 백테스트 — MACD +151.9%, VWAP 평균 +23.2%, MDD 낮음.
        m.put("COMPOSITE_MOMENTUM", () -> momentumCore("COMPOSITE_MOMENTUM"));

        // COMPOSITE_ETH: ATR(0.5) + ORDERBOOK(0.3) + EMA(0.2). ETH 2025 H1 결과 기반.
        //
        // ⚠️ 2026-09-18까지 BacktestService가 ATR(0.7)+OB(0.1)+EMA(0.2)라는 별도 구성을 돌렸다.
        //    "백테스트는 호가를 캔들로 근사하므로 호가 성분 비중을 줄인다"는 의도였으나, 결과적으로
        //    운영과 다른 전략의 성과를 COMPOSITE_ETH의 검증 결과로 읽게 만들었다.
        //    운영 구성으로 통일한다. 호가 근사의 불확실성은 가중치를 몰래 바꾸는 대신
        //    백테스트 결과에 "호가 근사 사용"을 남겨 드러내는 방향으로 다룬다.
        //    (LiveTradingService의 실호가 주입은 단독 ORDERBOOK_IMBALANCE 세션에만 적용되므로,
        //     COMPOSITE_ETH 내부 호가 성분은 운영에서도 캔들 근사를 쓴다 — 리뷰 §3.2)
        m.put("COMPOSITE_ETH", () -> new CompositeStrategy("COMPOSITE_ETH", List.of(
                new WeightedStrategy(new AtrBreakoutStrategy(),        0.5),
                new WeightedStrategy(new OrderbookImbalanceStrategy(), 0.3),
                new WeightedStrategy(new EmaCrossStrategy(),           0.2)
        )));

        // COMPOSITE_BREAKOUT: 적합 BTC·ETH·SOL 등 추세 뚜렷한 코인 / 부적합 XRP(MDD -30.8%), 소형 알트.
        // 필터: EMA 방향 필터 ON / ADX 횡보장 필터 ON / RSI Veto(>75) ON
        m.put("COMPOSITE_BREAKOUT", () -> breakoutCore("COMPOSITE_BREAKOUT"));

        // COMPOSITE_MOMENTUM_ICHIMOKU (V1): COMPOSITE_MOMENTUM + Ichimoku 구름 필터.
        // 구름 아래 BUY / 구름 위 SELL 억제 → 추세 역행 진입 추가 차단.
        m.put("COMPOSITE_MOMENTUM_ICHIMOKU", () ->
                new IchimokuFilteredStrategy("COMPOSITE_MOMENTUM_ICHIMOKU",
                        momentumCore("COMPOSITE_MOMENTUM_ICHIMOKU_BASE")));

        // COMPOSITE_MOMENTUM_ICHIMOKU_V2: VWAP(역추세)를 SUPERTREND(추세추종)로 교체한 개선 버전.
        // V1 문제: MACD(추세추종) + VWAP(역추세) 공존 → ADX 25~35에서 상충 → HOLD 남발.
        // 적합: XRP·ETH. V1과 동일 코인 병행 운영으로 비교.
        m.put("COMPOSITE_MOMENTUM_ICHIMOKU_V2", () ->
                new IchimokuFilteredStrategy("COMPOSITE_MOMENTUM_ICHIMOKU_V2",
                        momentumV2Core("COMPOSITE_MOMENTUM_ICHIMOKU_V2_BASE")));

        // COMPOSITE_BREAKOUT_ICHIMOKU: COMPOSITE_BREAKOUT + Ichimoku 구름 필터.
        // ⚠️ 백테스트상 COMPOSITE_BREAKOUT과 동일 결과 — ADX 필터(ADX<20 → HOLD)가 횡보장을
        //    이미 전부 차단하므로 Ichimoku가 추가로 막는 신호가 없다.
        m.put("COMPOSITE_BREAKOUT_ICHIMOKU", () ->
                new IchimokuFilteredStrategy("COMPOSITE_BREAKOUT_ICHIMOKU",
                        breakoutCore("COMPOSITE_BREAKOUT_ICHIMOKU_RSI")));

        // ── Multi-Timeframe(MTF) 확인 전략 ────────────────────────────────
        // H1 신호 + H4 추세 방향 일치 시에만 진입. HTF 확인자는 SupertrendStrategy.

        // COMPOSITE_MTF_CONFIRMED: CRR(H1) + Supertrend(H4) — 범용 (ETH·SOL 최적)
        m.put("COMPOSITE_MTF_CONFIRMED", () ->
                new MtfConfirmedStrategy("COMPOSITE_MTF_CONFIRMED",
                        new CompositeRegimeRouter(),
                        new SupertrendStrategy(),
                        4));

        // COMPOSITE_MTF_BTC: CB(H1) + Supertrend(H4) — BTC 특화 (백테스트 +106.71%).
        m.put("COMPOSITE_MTF_BTC", () ->
                new MtfConfirmedStrategy("COMPOSITE_MTF_BTC",
                        breakoutCore("COMPOSITE_MTF_BTC_CB"),
                        new SupertrendStrategy(),
                        4));

        // COMPOSITE_MTF_MOMENTUM: CMI_V2(H1) + Supertrend(H4) — DOGE·ETH 특화 (DOGE +124.77%).
        m.put("COMPOSITE_MTF_MOMENTUM", () ->
                new MtfConfirmedStrategy("COMPOSITE_MTF_MOMENTUM",
                        new IchimokuFilteredStrategy("COMPOSITE_MTF_MOMENTUM_BASE",
                                momentumV2Core("COMPOSITE_MTF_MOMENTUM_CORE")),
                        new SupertrendStrategy(),
                        4));

        // COMPOSITE_MTF_MOMENTUM_CLOSED: MTF_MOMENTUM 과 같되 **완결된 H4 봉만** 사용한다.
        // 2026-09-25 추가 — docs/SUPERTREND_VALIDATION_PREREG.md 전향 검증의 **팔 A**.
        // 🔴 역사적 22코인 검사에서 B 대비 우위가 재현되지 않아 **채택 보류** 상태다.
        //    운영 기본값이 아니며, 전향 검증 관측용으로만 가동한다.
        m.put("COMPOSITE_MTF_MOMENTUM_CLOSED", () ->
                new MtfConfirmedStrategy("COMPOSITE_MTF_MOMENTUM_CLOSED",
                        new IchimokuFilteredStrategy("COMPOSITE_MTF_MOMENTUM_CLOSED_BASE",
                                momentumV2Core("COMPOSITE_MTF_MOMENTUM_CLOSED_CORE")),
                        new SupertrendStrategy(),
                        4, false, true));   // strictHtf=false, htfClosedOnly=true

        // ⚠️ DEPRECATED (2026-08-24) — strictHtf가 구조적으로 무효라 COMPOSITE_MTF_BTC와 동일하다.
        //    HTF 확인자 SupertrendStrategy는 데이터만 있으면 절대 HOLD를 내지 않고(추세선 위=BUY /
        //    아래=SELL 이분법), getMinimumCandleCount()=max(ltf, 4×12)라 호출 시점에 HTF 캔들 12개가
        //    보장된다 → strictHtf가 갈리는 두 분기가 모두 도달 불가.
        //    운영 실측: 두 전략의 청산 43건이 코인·진입시각·손익까지 전부 동일(진입 차 30ms).
        //    증명: core-engine SupertrendStrictHtfNoOpTest.
        //    등록은 남긴다 — 과거 세션 조회와 재활성화 경로를 깨지 않기 위해서다.
        //    되살리려면 HTF 확인자를 HOLD를 낼 수 있는 전략으로 교체할 것.
        m.put("COMPOSITE_MTF_BTC_STRICT", () ->
                new MtfConfirmedStrategy("COMPOSITE_MTF_BTC_STRICT",
                        breakoutCore("COMPOSITE_MTF_BTC_STRICT_CB"),
                        new SupertrendStrategy(),
                        4, true));  // strictHtf=ON

        // COMPOSITE_PULLBACK_MTF: "강한 추세 중 눌림목 회복" — 기존 돌파/모멘텀 쏠림과 직교.
        //   진입: H4 Supertrend 상승 + H1 종가>EMA200 + RSI 40~55 + EMA20/VWAP 눌림 후 회복 + ADX≥18
        //   청산: H4 Supertrend 하락 전환 또는 H1 EMA20 이탈 (SL/TP는 LiveTradingService 처리)
        m.put("COMPOSITE_PULLBACK_MTF", CompositePullbackMtfStrategy::new);

        // COMPOSITE_MEANREV_BB: BOLLINGER(0.55) + RSI(0.30) + VWAP(0.15) — 평균회귀 계열.
        // 배경(2026-07-20): 동적 세션 6개가 전부 추세추종이라 하락·횡보장에서 동시 침묵
        // (07-09~19 11일간 매수 체결 0건). 직교 전략으로 구성의 빈틈을 메운다.
        // 필터: EMA 방향 필터 OFF(하락추세 매수가 전제) / Composite ADX 하한 필터 OFF
        //       (BOLLINGER의 ADX 상한 필터와 정반대 방향).
        // VWAP는 가중 0.15라 단독 만점으로도 동적 세션 weak 임계(0.19~0.20)에 못 미친다 —
        // 반드시 BOLLINGER/RSI와 합의해야 진입한다(VWAP 단독 BUY 남발 방지).
        m.put("COMPOSITE_MEANREV_BB", () -> new CompositeStrategy("COMPOSITE_MEANREV_BB", List.of(
                new WeightedStrategy(new BollingerStrategy(), 0.55),
                new WeightedStrategy(new RsiStrategy(),       0.30),
                new WeightedStrategy(new VwapStrategy(),      0.15)
        )));

        return m;
    }

    /** {@link #ensureRegistered()}가 이미 등록을 마쳤는지. */
    private static volatile boolean registered = false;

    /**
     * 모든 복합 프리셋을 {@link StrategyRegistry}에 등록한다. 멱등하다.
     *
     * <p>Spring 기동 시 {@code CompositePresetRegistrar}가 호출한다.
     */
    public static synchronized void registerAll() {
        factories().forEach(StrategyRegistry::registerStateful);
        registered = true;
    }

    /**
     * 아직 등록되지 않았으면 등록한다.
     *
     * <p>이름으로 전략을 찾는 쪽이 조회 직전에 부른다. 이전에는 프리셋 등록이
     * {@link StrategySelector} static 블록에만 있어서, <b>그 클래스를 건드리지 않는 실행 경로에서는
     * 프리셋이 아예 없었다</b>. Spring이 뜨는 운영에서는 드러나지 않지만 Spring 없는 경로
     * (단위 테스트, 배치 러너)에서는 "알 수 없는 전략"으로 죽거나, 더 나쁘게는 어떤 클래스가
     * 먼저 로드됐는지에 따라 동작이 갈렸다. 등록을 클래스 로딩 순서에 맡기지 않는다.
     */
    public static void ensureRegistered() {
        if (!registered) {
            registerAll();
        }
    }
}
