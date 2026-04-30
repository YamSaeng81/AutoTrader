package com.cryptoautotrader.api.config;

import com.cryptoautotrader.core.selector.CompositeRegimeRouter;
import com.cryptoautotrader.core.selector.CompositeStrategy;
import com.cryptoautotrader.core.selector.IchimokuFilteredStrategy;
import com.cryptoautotrader.core.selector.MtfConfirmedStrategy;
import com.cryptoautotrader.core.selector.RegimeAdaptiveStrategy;
import com.cryptoautotrader.core.selector.RsiVetoStrategy;
import com.cryptoautotrader.core.selector.WeightedStrategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
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

        // COMPOSITE_REGIME_ROUTER: 기존 복합 전략 3종(BREAKOUT/V1/V2)을 레짐 기반으로 위임하는 메타 전략.
        // 레짐 전환(Hysteresis 3회 연속)에 따라 자동으로 최적 전략으로 교체.
        //   VOLATILITY  → COMPOSITE_BREAKOUT  (ATR spike — 돌파 유리)
        //   TREND       → CMI_V2              (강한 추세 — MACD+SUPERTREND)
        //   TRANSITIONAL→ CMI_V1              (전환 구간 — 보수적 모멘텀)
        //   RANGE       → HOLD                (횡보 — 진입 금지)
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
    }
}
