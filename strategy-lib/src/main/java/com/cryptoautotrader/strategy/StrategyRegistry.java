package com.cryptoautotrader.strategy;

import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.fvg.FairValueGapStrategy;
import com.cryptoautotrader.strategy.bollinger.BollingerStrategy;
import com.cryptoautotrader.strategy.ema.EmaCrossStrategy;
import com.cryptoautotrader.strategy.grid.GridStrategy;
import com.cryptoautotrader.strategy.heikinashi.HeikinAshiStochStrategy;
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
        // Heikin-Ashi + 200 EMA + Stochastic RSI 추세추종 전략 (고정 손익비 1:2)
        register(new HeikinAshiStochStrategy());
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

    /**
     * 이 이름으로 <b>새 인스턴스를 뽑을 수 있는지</b> 여부.
     *
     * <p>이전 이름은 {@code isStateful} 이었다. 팩토리 등록 여부와 "전략이 상태를 가지는가"는
     * 같은 질문이 아니다 — 겉보기 stateless 인 복합 전략도 내부에 GRID 나 레짐 감지기를 품으면
     * 상태를 가지며, 성분을 하나 갈아끼우는 것만으로 그 사실이 조용히 바뀐다.
     * 그래서 복합 프리셋은 상태 유무와 무관하게 전부 팩토리로 등록하고, 호출자는
     * "상태가 있는가"가 아니라 "새로 만들 수 있는가"만 묻는다.
     */
    public static boolean hasFactory(String name) {
        return FACTORIES.containsKey(name);
    }

    /** 새 인스턴스를 반환 (실행·세션 단위 상태 격리용). 전체 전략 트리가 새로 만들어진다. */
    public static Strategy createNew(String name) {
        Supplier<Strategy> factory = FACTORIES.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("팩토리가 등록되지 않은 전략: " + name);
        }
        return factory.get();
    }

    public static Map<String, Strategy> getAll() {
        return Map.copyOf(STRATEGIES);
    }
}
