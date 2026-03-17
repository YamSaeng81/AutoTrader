package com.cryptoautotrader.strategy;

import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.bollinger.BollingerStrategy;
import com.cryptoautotrader.strategy.ema.EmaCrossStrategy;
import com.cryptoautotrader.strategy.grid.GridStrategy;
import com.cryptoautotrader.strategy.macd.MacdStrategy;
import com.cryptoautotrader.strategy.orderbook.OrderbookImbalanceStrategy;
import com.cryptoautotrader.strategy.rsi.RsiStrategy;
import com.cryptoautotrader.strategy.stochasticrsi.StochasticRsiStrategy;
import com.cryptoautotrader.strategy.supertrend.SupertrendStrategy;
import com.cryptoautotrader.strategy.testtraded.TestTimedStrategy;
import com.cryptoautotrader.strategy.vwap.VwapStrategy;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StrategyRegistry {

    private static final Map<String, Strategy> STRATEGIES = new LinkedHashMap<>();

    static {
        // Phase 1 전략
        register(new VwapStrategy());
        register(new EmaCrossStrategy());
        register(new BollingerStrategy());
        register(new GridStrategy());
        // Phase 3 전략 (로직 구현 완료)
        register(new RsiStrategy());
        register(new MacdStrategy());
        register(new SupertrendStrategy());
        register(new AtrBreakoutStrategy());
        register(new OrderbookImbalanceStrategy());
        // Phase 3 전략 6번째 (로직 구현 완료)
        register(new StochasticRsiStrategy());
        // 실전매매 동작 검증용 테스트 전략
        register(new TestTimedStrategy());
    }

    private StrategyRegistry() {}

    public static void register(Strategy strategy) {
        STRATEGIES.put(strategy.getName(), strategy);
    }

    public static Strategy get(String name) {
        Strategy strategy = STRATEGIES.get(name);
        if (strategy == null) {
            throw new IllegalArgumentException("알 수 없는 전략: " + name);
        }
        return strategy;
    }

    public static Map<String, Strategy> getAll() {
        return Map.copyOf(STRATEGIES);
    }
}
