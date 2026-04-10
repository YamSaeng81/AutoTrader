package com.cryptoautotrader.api.config;

import com.cryptoautotrader.core.selector.CompositeStrategy;
import com.cryptoautotrader.core.selector.IchimokuFilteredStrategy;
import com.cryptoautotrader.core.selector.RegimeAdaptiveStrategy;
import com.cryptoautotrader.core.selector.WeightedStrategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.ema.EmaCrossStrategy;
import com.cryptoautotrader.strategy.grid.GridStrategy;
import com.cryptoautotrader.strategy.macd.MacdStrategy;
import com.cryptoautotrader.strategy.vwap.VwapStrategy;
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

        // COMPOSITE_BREAKOUT: ATR(0.4) + VD(0.3) + RSI(0.2) + EMA(0.1)
        // 적합: ETH·SOL·XRP 등 중대형 알트 (추세 뚜렷, 변동성 중간)
        // 부적합: BTC (변동성 낮아 ATR 돌파 신호 희소), 소형 알트 (변동성 과다로 기준선 왜곡)
        // - RSI 브레이크: 과매수(>70) 구간에서 ATR BUY와 상충 → HOLD (가짜 돌파 방지)
        // - EMA 방향 필터: 추세 역행 신호(하락추세 BUY / 상승추세 SELL) 억제
        // - ADX 횡보장 필터: ADX(14) < 20이면 하위 전략 평가 없이 즉시 HOLD
        StrategyRegistry.register(new CompositeStrategy("COMPOSITE_BREAKOUT", List.of(
                new WeightedStrategy(new AtrBreakoutStrategy(), 0.4),
                new WeightedStrategy(new VolumeDeltaStrategy(),  0.3),
                new WeightedStrategy(new RsiStrategy(),          0.2),
                new WeightedStrategy(new EmaCrossStrategy(),     0.1)
        ), true, true));

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

        // COMPOSITE_BREAKOUT_ICHIMOKU: COMPOSITE_BREAKOUT + Ichimoku 구름 필터
        // 기존 EMA·ADX 필터 위에 Ichimoku 추가 → 횡보장 및 추세 역행 이중 차단
        // 모든 구성 전략이 stateless → 공유 인스턴스 재사용
        StrategyRegistry.register(new IchimokuFilteredStrategy("COMPOSITE_BREAKOUT_ICHIMOKU",
                new CompositeStrategy("COMPOSITE_BREAKOUT_ICHIMOKU_BASE", List.of(
                        new WeightedStrategy(new AtrBreakoutStrategy(), 0.4),
                        new WeightedStrategy(new VolumeDeltaStrategy(),  0.3),
                        new WeightedStrategy(new RsiStrategy(),          0.2),
                        new WeightedStrategy(new EmaCrossStrategy(),     0.1)
                ), true, true)));  // EMA + ADX 필터 유지
    }
}
