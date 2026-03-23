package com.cryptoautotrader.api.config;

import com.cryptoautotrader.core.selector.CompositeStrategy;
import com.cryptoautotrader.core.selector.WeightedStrategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.bollinger.BollingerStrategy;
import com.cryptoautotrader.strategy.ema.EmaCrossStrategy;
import com.cryptoautotrader.strategy.grid.GridStrategy;
import com.cryptoautotrader.strategy.orderbook.OrderbookImbalanceStrategy;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 백테스트 결과 기반 코인별 복합 전략 프리셋을 애플리케이션 시작 시 StrategyRegistry에 등록한다.
 *
 * <ul>
 *   <li>COMPOSITE_BTC — GRID(0.6) + BOLLINGER(0.4) : BTC 2025 H1 결과 기반.
 *       GridStrategy는 세션별 상태를 가지므로 stateful로 등록하여 세션마다 새 인스턴스 사용.</li>
 *   <li>COMPOSITE_ETH — ATR_BREAKOUT(0.5) + ORDERBOOK_IMBALANCE(0.3) + EMA_CROSS(0.2) : ETH 2025 H1 결과 기반.
 *       구성 전략이 모두 stateless이므로 일반 등록.</li>
 * </ul>
 */
@Component
public class CompositePresetRegistrar {

    @PostConstruct
    public void registerPresets() {
        // COMPOSITE_BTC: GRID는 stateful(그리드 레벨 상태 보유) → 세션마다 새 인스턴스
        // EMA 방향 필터 활성화: GRID+BOLLINGER 모두 역추세 전략이므로 추세 역행 신호 억제
        StrategyRegistry.registerStateful("COMPOSITE_BTC", () ->
                new CompositeStrategy("COMPOSITE_BTC", List.of(
                        new WeightedStrategy(new GridStrategy(),      0.6),
                        new WeightedStrategy(new BollingerStrategy(), 0.4)
                ), true)
        );

        // COMPOSITE_ETH: 구성 전략 모두 stateless → 공유 인스턴스 재사용
        StrategyRegistry.register(new CompositeStrategy("COMPOSITE_ETH", List.of(
                new WeightedStrategy(new AtrBreakoutStrategy(),        0.5),
                new WeightedStrategy(new OrderbookImbalanceStrategy(), 0.3),
                new WeightedStrategy(new EmaCrossStrategy(),           0.2)
        )));
    }
}
