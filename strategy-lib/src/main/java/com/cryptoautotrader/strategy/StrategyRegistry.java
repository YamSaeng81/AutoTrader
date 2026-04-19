package com.cryptoautotrader.strategy;

import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.fvg.FairValueGapStrategy;
import com.cryptoautotrader.strategy.bollinger.BollingerStrategy;
import com.cryptoautotrader.strategy.ema.EmaCrossStrategy;
import com.cryptoautotrader.strategy.grid.GridStrategy;
import com.cryptoautotrader.strategy.macd.MacdStrategy;
import com.cryptoautotrader.strategy.orderbook.OrderbookImbalanceStrategy;
import com.cryptoautotrader.strategy.rsi.RsiStrategy;
import com.cryptoautotrader.strategy.volumedelta.VolumeDeltaStrategy;
import com.cryptoautotrader.strategy.macdstochbb.MacdStochBbStrategy;
import com.cryptoautotrader.strategy.stochasticrsi.StochasticRsiStrategy;
import com.cryptoautotrader.strategy.supertrend.SupertrendStrategy;
import com.cryptoautotrader.strategy.testtraded.TestTimedStrategy;
import com.cryptoautotrader.strategy.vwap.VwapStrategy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class StrategyRegistry {

    private static final Map<String, Strategy>           STRATEGIES = new ConcurrentHashMap<>();
    /** StatefulStrategy 구현체의 인스턴스 팩토리 — 세션별 신규 인스턴스 생성에 사용 */
    private static final Map<String, Supplier<Strategy>> FACTORIES  = new ConcurrentHashMap<>();

    static {
        // Phase 1 전략
        register(new VwapStrategy());
        register(new EmaCrossStrategy());
        register(new BollingerStrategy());
        registerStateful("GRID", GridStrategy::new);
        // Phase 3 전략 (로직 구현 완료)
        register(new RsiStrategy());
        register(new MacdStrategy());
        register(new SupertrendStrategy());
        register(new AtrBreakoutStrategy());
        register(new OrderbookImbalanceStrategy());
        register(new VolumeDeltaStrategy());
        // Phase 3 전략 6번째 (로직 구현 완료)
        register(new StochasticRsiStrategy());
        // FVG (Fair Value Gap) 전략 — A단계 모멘텀 방식
        register(new FairValueGapStrategy());
        // MACD + StochRSI + 볼린저밴드 복합 추세 전략 (StatefulStrategy: 쿨다운 상태 보유)
        registerStateful("MACD_STOCH_BB", MacdStochBbStrategy::new);
        // 실전매매 동작 검증용 테스트 전략
        register(new TestTimedStrategy());
    }

    private StrategyRegistry() {}

    public static void register(Strategy strategy) {
        STRATEGIES.put(strategy.getName(), strategy);
    }

    /** StatefulStrategy 등록: 공유 인스턴스 저장 + 세션별 생성 팩토리 등록 */
    public static void registerStateful(String name, Supplier<Strategy> factory) {
        STRATEGIES.put(name, factory.get());
        FACTORIES.put(name, factory);
    }

    public static Strategy get(String name) {
        Strategy strategy = STRATEGIES.get(name);
        if (strategy == null) {
            throw new IllegalArgumentException("알 수 없는 전략: " + name);
        }
        return strategy;
    }

    /** 세션별 전략 인스턴스가 필요한지 여부 (StatefulStrategy 구현체인 경우 true) */
    public static boolean isStateful(String name) {
        return FACTORIES.containsKey(name);
    }

    /** StatefulStrategy의 새 인스턴스를 반환 (세션별 상태 격리용) */
    public static Strategy createNew(String name) {
        Supplier<Strategy> factory = FACTORIES.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("StatefulStrategy가 아닌 전략: " + name);
        }
        return factory.get();
    }

    public static Map<String, Strategy> getAll() {
        return Map.copyOf(STRATEGIES);
    }
}
