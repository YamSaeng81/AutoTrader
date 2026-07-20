package com.cryptoautotrader.api.config;

import com.cryptoautotrader.core.selector.CompositePullbackMtfStrategy;
import com.cryptoautotrader.core.selector.CompositeRegimeRouter;
import com.cryptoautotrader.core.selector.CompositeStrategy;
import com.cryptoautotrader.core.selector.IchimokuFilteredStrategy;
import com.cryptoautotrader.core.selector.MtfConfirmedStrategy;
import com.cryptoautotrader.core.selector.RegimeAdaptiveStrategy;
import com.cryptoautotrader.core.selector.RsiVetoStrategy;
import com.cryptoautotrader.core.selector.WeightedStrategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.bollinger.BollingerStrategy;
import com.cryptoautotrader.strategy.ema.EmaCrossStrategy;
import com.cryptoautotrader.strategy.grid.GridStrategy;
import com.cryptoautotrader.strategy.macd.MacdStrategy;
import com.cryptoautotrader.strategy.vwap.VwapStrategy;
import com.cryptoautotrader.strategy.supertrend.SupertrendStrategy;
import com.cryptoautotrader.strategy.orderbook.OrderbookImbalanceStrategy;
import com.cryptoautotrader.strategy.rsi.RsiStrategy;
import com.cryptoautotrader.strategy.volumedelta.VolumeDeltaStrategy;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 백테스트 결과 기반 코인별 복합 전략 프리셋을 애플리케이션 시작 시 StrategyRegistry에 등록한다.
 *
 * <ul>
 *   <li>COMPOSITE_MOMENTUM — MACD(0.5) + VWAP(0.3) + GRID(0.2) : BTC H1 백테스트 기반.
 *       BTC·ETH 등 거래량 많은 대형 코인 최적화. GridStrategy는 세션별 상태를 가지므로 stateful 등록.</li>
 *   <li>COMPOSITE_ETH — ATR_BREAKOUT(0.5) + ORDERBOOK_IMBALANCE(0.3) + EMA_CROSS(0.2) : ETH 2025 H1 결과 기반.
 *       구성 전략이 모두 stateless이므로 일반 등록.</li>
 *   <li>COMPOSITE_BREAKOUT — ATR(0.4) + VD(0.3) + RSI(0.2) + EMA(0.1) : ETH·SOL·XRP 등 중대형 알트 최적화.
 *       ADX 횡보장 차단·EMA 추세 역행 억제 필터 활성화.</li>
 * </ul>
 */
@Component
public class CompositePresetRegistrar {

    @PostConstruct
    public void registerPresets() {
        // COMPOSITE: 시장 국면(regime) 기반 동적 전략 선택 — MarketRegimeDetector 상태 보유
        StrategyRegistry.registerStateful("COMPOSITE", RegimeAdaptiveStrategy::new);

        // COMPOSITE_REGIME_ROUTER: 레짐 기반으로 최적 전략을 자동 위임하는 단일 메타 전략.
        // 90일 실전 분석(2026-06-30) 기반 개편: CMI_V1(MACD+VWAP+GRID+Ichimoku)이 전 레짐 압도.
        //   VOLATILITY  → COMPOSITE_BREAKOUT  (ATR spike — 돌파 유리)
        //   TREND       → CMI_V1              (MACD+VWAP+Ichimoku, V2 대비 우수)
        //   TRANSITIONAL→ CMI_V1              (ADX 임계 완화 BTC/SOL 적용)
        //   RANGE       → CMI_V1              (VWAP 역추세 성분이 횡보 친화, HOLD 제거)
        // GRID stateful + RegimeDetector stateful → 반드시 registerStateful
        StrategyRegistry.registerStateful("COMPOSITE_REGIME_ROUTER", CompositeRegimeRouter::new);

        // COMPOSITE_MOMENTUM: GRID는 stateful(그리드 레벨 상태 보유) → 세션마다 새 인스턴스
        // 적합: BTC·ETH 등 거래량 많은 대형 코인 (VWAP 신뢰도 높음, 모멘텀 예측 가능)
        // 부적합: 소형 알트 (VWAP 신뢰도 낮음, 거래량 부족)
        // EMA 방향 필터 활성화: 추세 역행 신호 억제
        // 근거: KRW-BTC H1 백테스트 — MACD +151.9%, VWAP 평균 +23.2%, MDD 낮음
        StrategyRegistry.registerStateful("COMPOSITE_MOMENTUM", () ->
                new CompositeStrategy("COMPOSITE_MOMENTUM", List.of(
                        new WeightedStrategy(new MacdStrategy(), 0.5),
                        new WeightedStrategy(new VwapStrategy(), 0.3),
                        new WeightedStrategy(new GridStrategy(), 0.2)
                ), true)
        );

        // COMPOSITE_ETH: 구성 전략 모두 stateless → 공유 인스턴스 재사용
        StrategyRegistry.register(new CompositeStrategy("COMPOSITE_ETH", List.of(
                new WeightedStrategy(new AtrBreakoutStrategy(),        0.5),
                new WeightedStrategy(new OrderbookImbalanceStrategy(), 0.3),
                new WeightedStrategy(new EmaCrossStrategy(),           0.2)
        )));

        // COMPOSITE_BREAKOUT: ATR(0.5) + VD(0.3) + MACD(0.2)  [P1-1·P1-2 개선]
        // 변경 이력:
        //   P1-1: EMA_CROSS(0.1) → MACD(0.1→0.2) 교체
        //         EMA_CROSS는 EMA 방향 필터(EMA20/50)와 동일 지표 이중 카운팅 문제.
        //         MACD는 독립적인 모멘텀 신호원. 가중치도 0.1→0.2로 상향.
        //   P1-2: RSI(0.2) 제거 → RsiVetoStrategy 래퍼로 대체
        //         RSI 가중치 0.2로는 수학적으로 단독 BUY 차단 불가(confidence > 2.0 필요).
        //         RSI > 75이면 어떤 BUY 신호도 강제 차단하는 Veto Gate로 분리.
        // 구성: ATR(0.5, +0.1) + VD(0.3) + MACD(0.2, 신규) = 1.0
        // 적합: BTC·ETH·SOL 등 추세 뚜렷한 코인
        // 부적합: XRP(MDD -30.8%), 소형 알트
        // 필터: EMA 방향 필터 ON / ADX 횡보장 필터 ON / RSI Veto(>75) ON
        StrategyRegistry.register(new RsiVetoStrategy("COMPOSITE_BREAKOUT",
                new CompositeStrategy("COMPOSITE_BREAKOUT_BASE", List.of(
                        new WeightedStrategy(new AtrBreakoutStrategy(), 0.5),
                        new WeightedStrategy(new VolumeDeltaStrategy(),  0.3),
                        new WeightedStrategy(new MacdStrategy(),         0.2)
                ), true, true)   // emaFilter=ON, adxFilter=ON
        ));

        // ── Ichimoku 필터 추가 버전 (기존 전략 불변) ──────────────────────────────────
        // COMPOSITE_MOMENTUM_ICHIMOKU: COMPOSITE_MOMENTUM + Ichimoku 구름 필터
        // 기존 EMA 방향 필터 위에 Ichimoku(9/26/52) 를 추가 레이어로 적용.
        // 구름 아래 BUY / 구름 위 SELL 억제 → 추세 역행 진입 추가 차단
        // GRID는 stateful → 세션마다 신규 인스턴스
        StrategyRegistry.registerStateful("COMPOSITE_MOMENTUM_ICHIMOKU", () ->
                new IchimokuFilteredStrategy("COMPOSITE_MOMENTUM_ICHIMOKU",
                        new CompositeStrategy("COMPOSITE_MOMENTUM_ICHIMOKU_BASE", List.of(
                                new WeightedStrategy(new MacdStrategy(),  0.5),
                                new WeightedStrategy(new VwapStrategy(),  0.3),
                                new WeightedStrategy(new GridStrategy(),  0.2)
                        ), true))  // EMA 방향 필터 유지
        );

        // ── COMPOSITE_MOMENTUM_ICHIMOKU_V2 ───────────────────────────────────────
        // VWAP(역추세)를 SUPERTREND(추세추종)로 교체한 개선 버전.
        //
        // 기존 V1의 문제: MACD(추세추종) + VWAP(역추세) 공존 → ADX 25~35 구간에서
        //   MACD BUY + VWAP SELL 상충 → buyScore/sellScore 둘 다 0.4 초과 → HOLD 남발
        //
        // V2 구성: MACD(0.5) + SUPERTREND(0.3) + GRID(0.2)
        //   세 전략 모두 추세 방향에서 같은 의견 → 상충 없음
        //   - MACD:       골든크로스 + 제로선 + 히스토그램 확대 + ADX(25) 필터
        //   - SUPERTREND: ATR(10) 기반 동적 추세선 전환/유지 신호
        //   - GRID:       stateful (세션마다 새 인스턴스 필요)
        //   외부 필터: EMA(20/50) 방향 역행 억제 + Ichimoku 구름 내부 차단
        //
        // 적합: XRP·ETH (MOMENTUM 계열 강세 확인 코인)
        // 비교 대상: COMPOSITE_MOMENTUM_ICHIMOKU (V1) — 동일 코인 병행 운영으로 비교
        StrategyRegistry.registerStateful("COMPOSITE_MOMENTUM_ICHIMOKU_V2", () ->
                new IchimokuFilteredStrategy("COMPOSITE_MOMENTUM_ICHIMOKU_V2",
                        new CompositeStrategy("COMPOSITE_MOMENTUM_ICHIMOKU_V2_BASE", List.of(
                                new WeightedStrategy(new MacdStrategy(),       0.5),
                                new WeightedStrategy(new SupertrendStrategy(), 0.3),
                                new WeightedStrategy(new GridStrategy(),       0.2)
                        ), true))  // EMA 방향 필터 ON
        );

        // COMPOSITE_BREAKOUT_ICHIMOKU: COMPOSITE_BREAKOUT + Ichimoku 구름 필터
        // ⚠️ 백테스트 결과 의미없음 (COMPOSITE_BREAKOUT과 동일 결과):
        //    ADX 필터(ADX<20 → HOLD)가 횡보장을 이미 전부 차단하므로
        //    Ichimoku 필터가 추가로 막는 신호가 없음.
        // 구성을 COMPOSITE_BREAKOUT과 동기화 (ATR 0.5 + VD 0.3 + MACD 0.2)
        StrategyRegistry.register(new IchimokuFilteredStrategy("COMPOSITE_BREAKOUT_ICHIMOKU",
                new RsiVetoStrategy("COMPOSITE_BREAKOUT_ICHIMOKU_RSI",
                        new CompositeStrategy("COMPOSITE_BREAKOUT_ICHIMOKU_BASE", List.of(
                                new WeightedStrategy(new AtrBreakoutStrategy(), 0.5),
                                new WeightedStrategy(new VolumeDeltaStrategy(),  0.3),
                                new WeightedStrategy(new MacdStrategy(),         0.2)
                        ), true, true))));

        // ── Multi-Timeframe(MTF) 확인 전략 ──────────────────────────────────────────
        // H1 신호 + H4 추세 방향 일치 시에만 진입. CandleDownsampler로 H1→H4 다운샘플.
        // HTF 확인 전략: SupertrendStrategy (ATR 기반 동적 추세선, 명확한 BUY/SELL/HOLD).
        // 기대 효과: 역추세 진입 차단 → 승률 개선(14%→25%+). 단, 진입 빈도 감소.
        //
        // COMPOSITE_MTF_CONFIRMED: CRR(H1) + Supertrend(H4) — 범용 (ETH·SOL 최적)
        //   CRR이 ETH/SOL에서 기존 전략 1위 → H4 확인으로 역추세 노이즈 추가 차단
        //   GRID stateful + RegimeDetector stateful → registerStateful 필수
        StrategyRegistry.registerStateful("COMPOSITE_MTF_CONFIRMED", () ->
                new MtfConfirmedStrategy("COMPOSITE_MTF_CONFIRMED",
                        new CompositeRegimeRouter(),   // H1: 레짐 라우터 (BREAKOUT/V1/V2 자동 선택)
                        new SupertrendStrategy(),      // H4: 추세 방향 확인
                        4));                           // H1×4 = H4

        // COMPOSITE_MTF_BTC: CB(H1) + Supertrend(H4) — BTC 특화
        //   BTC는 CB가 압도적 (백테스트 +106.71%). H4 추세 확인으로 손절 빈도 감소 기대.
        //   CB는 stateless (GRID 없음) — 그러나 Supertrend는 stateless → 전체 stateless
        //   단, 안전하게 registerStateful 처리 (향후 내부 전략 교체 시 위험 방지)
        StrategyRegistry.registerStateful("COMPOSITE_MTF_BTC", () ->
                new MtfConfirmedStrategy("COMPOSITE_MTF_BTC",
                        new RsiVetoStrategy("COMPOSITE_MTF_BTC_CB",
                                new CompositeStrategy("COMPOSITE_MTF_BTC_BASE", List.of(
                                        new WeightedStrategy(new AtrBreakoutStrategy(), 0.5),
                                        new WeightedStrategy(new VolumeDeltaStrategy(),  0.3),
                                        new WeightedStrategy(new MacdStrategy(),         0.2)
                                ), true, true)),       // emaFilter=ON, adxFilter=ON
                        new SupertrendStrategy(),      // H4 추세 확인
                        4));

        // COMPOSITE_MTF_MOMENTUM: CMI_V2(H1) + Supertrend(H4) — DOGE·ETH 특화
        //   DOGE는 CMI_V2가 압도적 (+124.77%). H4 Supertrend로 강한 추세 구간만 진입.
        //   GRID stateful → registerStateful 필수
        StrategyRegistry.registerStateful("COMPOSITE_MTF_MOMENTUM", () ->
                new MtfConfirmedStrategy("COMPOSITE_MTF_MOMENTUM",
                        new IchimokuFilteredStrategy("COMPOSITE_MTF_MOMENTUM_BASE",
                                new CompositeStrategy("COMPOSITE_MTF_MOMENTUM_CORE", List.of(
                                        new WeightedStrategy(new MacdStrategy(),       0.5),
                                        new WeightedStrategy(new SupertrendStrategy(), 0.3),
                                        new WeightedStrategy(new GridStrategy(),       0.2)
                                ), true)),             // EMA 방향 필터 ON
                        new SupertrendStrategy(),      // H4 추세 확인 (별도 인스턴스)
                        4));

        // COMPOSITE_MTF_BTC_STRICT: COMPOSITE_MTF_BTC의 strictHtf=true A/B 변형 (EXPERIMENTAL)
        //   기존 MTF는 H4 HOLD·데이터부족 시 LTF 신호를 통과시킨다(보수적 허용). strict 변형은
        //   H4가 명시적으로 방향(BUY/SELL)을 확인할 때만 진입 → 운영 초반 과공격/추세 미확인 진입 차단.
        //   동일 코인(BTC)에 기존 COMPOSITE_MTF_BTC와 병행 운영해 H4 차단 신호의 손익을 비교한다.
        StrategyRegistry.registerStateful("COMPOSITE_MTF_BTC_STRICT", () ->
                new MtfConfirmedStrategy("COMPOSITE_MTF_BTC_STRICT",
                        new RsiVetoStrategy("COMPOSITE_MTF_BTC_STRICT_CB",
                                new CompositeStrategy("COMPOSITE_MTF_BTC_STRICT_BASE", List.of(
                                        new WeightedStrategy(new AtrBreakoutStrategy(), 0.5),
                                        new WeightedStrategy(new VolumeDeltaStrategy(),  0.3),
                                        new WeightedStrategy(new MacdStrategy(),         0.2)
                                ), true, true)),       // emaFilter=ON, adxFilter=ON
                        new SupertrendStrategy(),      // H4 추세 확인
                        4, true));                     // htfFactor=4, strictHtf=ON

        // ── COMPOSITE_PULLBACK_MTF (신규, EXPERIMENTAL) ──────────────────────────
        // 기존 라이브 전략이 돌파/모멘텀(COMPOSITE_BREAKOUT·CMI_V2)에 쏠려 있어, 성격이 직교하는
        // "강한 추세 중 눌림목 회복" 진입을 별도 검증하기 위한 전략.
        //   진입: H4 Supertrend 상승 + H1 종가>EMA200 + RSI 40~55 + EMA20/VWAP 눌림 후 회복 + ADX≥18
        //   청산: H4 Supertrend 하락 전환 또는 H1 EMA20 이탈 (SL/TP는 LiveTradingService 처리)
        // 상태 없음(stateless) → 일반 register. 충분한 실전 근거 전까지 EXPERIMENTAL(관찰 전용).
        StrategyRegistry.register(new CompositePullbackMtfStrategy());

        // ── COMPOSITE_MEANREV_BB (신규, 평균회귀 계열) ────────────────────────────
        // 배경(2026-07-20 운영 DB 분석): 동적 세션 6개가 전부 추세추종 계열이라 하락·횡보장에서
        // 동시 침묵 — 07-09~19 11일간 매수 체결 0건. 하락·횡보장에서도 신호가 나오는 직교
        // (평균회귀) 전략을 추가해 전략 구성의 빈틈을 메운다.
        //
        // 구성: BOLLINGER(0.55) + RSI(0.30) + VWAP(0.15)
        //   - BOLLINGER: %B 하단 이탈 매수 — 자체 ADX '상한' 필터(추세장 평균회귀 억제)와
        //                Squeeze HOLD(밴드 압축 시 브레이크아웃 대기)를 내장
        //   - RSI:       과매도(<30) 반등 + 피봇 강세 다이버전스
        //   - VWAP:      할인 매수(역추세 성분). 가중 0.15라 단독 만점(100)으로는 동적 세션
        //                weak 임계(0.19~0.20)에 못 미침 — 반드시 BOLLINGER/RSI와 합의해야
        //                진입한다 (추세추종 프리셋에서 관찰된 VWAP 단독 BUY 남발 방지)
        //
        // 필터: EMA 방향 필터 OFF — 하락추세에서 사는 것이 전략의 전제라 감쇠 시 무력화.
        //       Composite ADX '하한' 필터 OFF — BOLLINGER의 ADX '상한' 필터와 정반대 방향.
        // 게이트: Ema200RegimeGate.EXEMPT_STRATEGIES에 면제 등록(하락 레짐 진입이 전제).
        //         RangeRegimeGate 비차단(횡보장이 주 무대). BLACK_SWAN_GUARD·BTC_MARKET_GUARD·
        //         손실 쿨다운·SL/TP는 그대로 적용 — 급락 나이프 캐칭은 별도 경로가 방어.
        // 구성 전략 모두 stateless → 일반 register.
        StrategyRegistry.register(new CompositeStrategy("COMPOSITE_MEANREV_BB", List.of(
                new WeightedStrategy(new BollingerStrategy(), 0.55),
                new WeightedStrategy(new RsiStrategy(),       0.30),
                new WeightedStrategy(new VwapStrategy(),      0.15)
        )));
    }
}
