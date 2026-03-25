package com.cryptoautotrader.api.config;

import com.cryptoautotrader.core.selector.CompositeStrategy;
import com.cryptoautotrader.core.selector.RegimeAdaptiveStrategy;
import com.cryptoautotrader.core.selector.WeightedStrategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.ema.EmaCrossStrategy;
import com.cryptoautotrader.strategy.grid.GridStrategy;
import com.cryptoautotrader.strategy.macd.MacdStrategy;
import com.cryptoautotrader.strategy.vwap.VwapStrategy;
import com.cryptoautotrader.strategy.orderbook.OrderbookImbalanceStrategy;
import com.cryptoautotrader.strategy.volumedelta.VolumeDeltaStrategy;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 백테스트 결과 기반 코인별 복합 전략 프리셋을 애플리케이션 시작 시 StrategyRegistry에 등록한다.
 *
 * <ul>
 *   <li>COMPOSITE_BTC — MACD(0.5) + VWAP(0.3) + GRID(0.2) : BTC H1 백테스트 기반 V2.
 *       GridStrategy는 세션별 상태를 가지므로 stateful로 등록하여 세션마다 새 인스턴스 사용.</li>
 *   <li>COMPOSITE_ETH — ATR_BREAKOUT(0.5) + ORDERBOOK_IMBALANCE(0.3) + EMA_CROSS(0.2) : ETH 2025 H1 결과 기반.
 *       구성 전략이 모두 stateless이므로 일반 등록.</li>
 * </ul>
 */
@Component
public class CompositePresetRegistrar {

    @PostConstruct
    public void registerPresets() {
        // COMPOSITE: 시장 국면(regime) 기반 동적 전략 선택 — MarketRegimeDetector 상태 보유
        StrategyRegistry.registerStateful("COMPOSITE", RegimeAdaptiveStrategy::new);

        // COMPOSITE_BTC V2: GRID는 stateful(그리드 레벨 상태 보유) → 세션마다 새 인스턴스
        // EMA 방향 필터 활성화: 추세 역행 신호 억제
        // 구성: MACD(최적화, BTC fast=14/slow=22)×0.5 + VWAP×0.3 + GRID×0.2
        // 근거: KRW-BTC H1 백테스트 — MACD +151.9%, VWAP 평균 +23.2% (MDD 낮음), GRID 안정성 보완
        StrategyRegistry.registerStateful("COMPOSITE_BTC", () ->
                new CompositeStrategy("COMPOSITE_BTC", List.of(
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

        // COMPOSITE_ETH_VD: Volume Delta 편입 후보 — 백테스트 비교용
        // Live: ATR(0.4) + OB(0.3) + VD(0.2) + EMA(0.1)
        StrategyRegistry.register(new CompositeStrategy("COMPOSITE_ETH_VD", List.of(
                new WeightedStrategy(new AtrBreakoutStrategy(),        0.4),
                new WeightedStrategy(new OrderbookImbalanceStrategy(), 0.3),
                new WeightedStrategy(new VolumeDeltaStrategy(),        0.2),
                new WeightedStrategy(new EmaCrossStrategy(),           0.1)
        )));
    }
}
