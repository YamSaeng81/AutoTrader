package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CompositeStrategy — Weighted Voting 신호 조합")
class CompositeStrategyTest {

    // ── 스텁 전략 ─────────────────────────────────────────────────────────

    /** 항상 지정된 Action/strength를 반환하는 스텁 */
    private static Strategy stub(String name, StrategySignal.Action action, int strength) {
        return new Strategy() {
            @Override public String getName()              { return name; }
            @Override public int    getMinimumCandleCount(){ return 1; }
            @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
                return switch (action) {
                    case BUY  -> StrategySignal.buy(BigDecimal.valueOf(strength), name + "-BUY");
                    case SELL -> StrategySignal.sell(BigDecimal.valueOf(strength), name + "-SELL");
                    case HOLD -> StrategySignal.hold(name + "-HOLD");
                };
            }
        };
    }

    private static List<Candle> oneCandle() {
        return List.of(Candle.builder()
                .time(Instant.EPOCH)
                .open(BigDecimal.valueOf(100)).high(BigDecimal.valueOf(105))
                .low(BigDecimal.valueOf(95)).close(BigDecimal.valueOf(102))
                .volume(BigDecimal.TEN)
                .build());
    }

    // ── BUY ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("모든 전략 BUY(100) + 가중치 합 > 0.6 → BUY 반환")
    void allBuy_strongSignal() {
        // weight 0.5 * conf 1.0 + weight 0.3 * conf 1.0 = 0.8 > 0.6
        CompositeStrategy cs = new CompositeStrategy(List.of(
                new WeightedStrategy(stub("A", StrategySignal.Action.BUY, 100), 0.5),
                new WeightedStrategy(stub("B", StrategySignal.Action.BUY, 100), 0.3)
        ));

        StrategySignal result = cs.evaluate(oneCandle(), Map.of());
        assertThat(result.getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(result.getStrength().doubleValue()).isGreaterThan(60.0);
    }

    @Test
    @DisplayName("BUY score 0.4~0.6 구간 → BUY (weak) 반환")
    void weakBuy() {
        // 0.4 * 1.0 + 0.1 * 0.5 = 0.45 → > 0.4 → BUY
        CompositeStrategy cs = new CompositeStrategy(List.of(
                new WeightedStrategy(stub("A", StrategySignal.Action.BUY, 100), 0.4),
                new WeightedStrategy(stub("B", StrategySignal.Action.BUY, 50),  0.1)
        ));

        StrategySignal result = cs.evaluate(oneCandle(), Map.of());
        assertThat(result.getAction()).isEqualTo(StrategySignal.Action.BUY);
    }

    // ── SELL ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("모든 전략 SELL(100) + 가중치 합 > 0.6 → SELL 반환")
    void allSell_strongSignal() {
        CompositeStrategy cs = new CompositeStrategy(List.of(
                new WeightedStrategy(stub("A", StrategySignal.Action.SELL, 100), 0.5),
                new WeightedStrategy(stub("B", StrategySignal.Action.SELL, 100), 0.3)
        ));

        StrategySignal result = cs.evaluate(oneCandle(), Map.of());
        assertThat(result.getAction()).isEqualTo(StrategySignal.Action.SELL);
    }

    // ── HOLD ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BUY + SELL 상충(양쪽 > 0.4) → HOLD 반환")
    void conflicting_buyAndSell_hold() {
        // buyScore = 0.5*1.0 = 0.5 > 0.4, sellScore = 0.5*1.0 = 0.5 > 0.4
        CompositeStrategy cs = new CompositeStrategy(List.of(
                new WeightedStrategy(stub("A", StrategySignal.Action.BUY,  100), 0.5),
                new WeightedStrategy(stub("B", StrategySignal.Action.SELL, 100), 0.5)
        ));

        StrategySignal result = cs.evaluate(oneCandle(), Map.of());
        assertThat(result.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(result.getReason()).contains("상충");
    }

    @Test
    @DisplayName("모든 전략 HOLD → HOLD 반환")
    void allHold() {
        CompositeStrategy cs = new CompositeStrategy(List.of(
                new WeightedStrategy(stub("A", StrategySignal.Action.HOLD, 0), 0.5),
                new WeightedStrategy(stub("B", StrategySignal.Action.HOLD, 0), 0.5)
        ));

        StrategySignal result = cs.evaluate(oneCandle(), Map.of());
        assertThat(result.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    @DisplayName("BUY score < 0.4 → HOLD (점수 미달)")
    void lowBuyScore_hold() {
        // 0.1 * 1.0 = 0.1 < 0.4
        CompositeStrategy cs = new CompositeStrategy(List.of(
                new WeightedStrategy(stub("A", StrategySignal.Action.BUY, 100), 0.1)
        ));

        StrategySignal result = cs.evaluate(oneCandle(), Map.of());
        assertThat(result.getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }

    // ── getMinimumCandleCount ─────────────────────────────────────────────

    @Test
    @DisplayName("getMinimumCandleCount — 전략 중 최대값 반환")
    void minimumCandleCount() {
        Strategy s1 = new Strategy() {
            @Override public String getName() { return "S1"; }
            @Override public int getMinimumCandleCount() { return 20; }
            @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) { return StrategySignal.hold(""); }
        };
        Strategy s2 = new Strategy() {
            @Override public String getName() { return "S2"; }
            @Override public int getMinimumCandleCount() { return 50; }
            @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) { return StrategySignal.hold(""); }
        };

        CompositeStrategy cs = new CompositeStrategy(List.of(
                new WeightedStrategy(s1, 0.5),
                new WeightedStrategy(s2, 0.5)
        ));
        assertThat(cs.getMinimumCandleCount()).isEqualTo(50);
    }

    // ── StrategySignal.getConfidence ──────────────────────────────────────

    @Test
    @DisplayName("getConfidence: strength 100 → 1.0, strength 50 → 0.5, HOLD → 0.0")
    void signalConfidence() {
        StrategySignal buy  = StrategySignal.buy(BigDecimal.valueOf(100), "test");
        StrategySignal mid  = StrategySignal.buy(BigDecimal.valueOf(50), "test");
        StrategySignal hold = StrategySignal.hold("test");

        assertThat(buy.getConfidence().doubleValue()).isEqualTo(1.0);
        assertThat(mid.getConfidence().doubleValue()).isEqualTo(0.5);
        assertThat(hold.getConfidence().doubleValue()).isEqualTo(0.0);
    }
}
