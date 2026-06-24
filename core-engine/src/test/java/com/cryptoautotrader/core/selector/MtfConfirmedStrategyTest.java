package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.strategy.StrategySignal.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MtfConfirmedStrategy — HTF 확인 래퍼 (SL/TP 보존 · strictHtf)")
class MtfConfirmedStrategyTest {

    /** flat 캔들 n개 (downsample만 필요, 지표 계산 없음 — stub 전략 사용). */
    private static List<Candle> flat(int n) {
        List<Candle> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(Candle.builder()
                    .time(Instant.ofEpochSecond(i * 3600L))
                    .open(BigDecimal.TEN).high(BigDecimal.TEN)
                    .low(BigDecimal.TEN).close(BigDecimal.TEN)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return list;
    }

    /** 고정 신호를 반환하는 스텁 전략. */
    private static Strategy stub(String name, StrategySignal signal, int minCount) {
        return new Strategy() {
            @Override public String getName() { return name; }
            @Override public int getMinimumCandleCount() { return minCount; }
            @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) { return signal; }
        };
    }

    private static final BigDecimal SL = BigDecimal.valueOf(9000);
    private static final BigDecimal TP = BigDecimal.valueOf(11000);

    @Test
    @DisplayName("방향 일치 통과 시 하위(LTF) 전략의 SL/TP를 보존한다")
    void preservesSuggestedSlTpOnPass() {
        StrategySignal ltfBuy = StrategySignal.buy(BigDecimal.valueOf(70), "LTF매수", SL, TP);
        StrategySignal htfBuy = StrategySignal.buy(BigDecimal.valueOf(50), "HTF상승");

        Strategy mtf = new MtfConfirmedStrategy("T", stub("ltf", ltfBuy, 1), stub("htf", htfBuy, 2), 4);
        StrategySignal s = mtf.evaluate(flat(8), Map.of());  // 8 → downsample×4 = 2 캔들 (>=2 충족)

        assertThat(s.getAction()).isEqualTo(Action.BUY);
        assertThat(s.getSuggestedStopLoss()).isEqualByComparingTo(SL);
        assertThat(s.getSuggestedTakeProfit()).isEqualByComparingTo(TP);
        assertThat(s.getReason()).contains("[H4:BUY]");
    }

    @Test
    @DisplayName("기본(lenient): HTF HOLD면 LTF 신호를 통과시킨다")
    void lenientPassesOnHtfHold() {
        StrategySignal ltfBuy = StrategySignal.buy(BigDecimal.valueOf(70), "LTF매수", SL, TP);
        StrategySignal htfHold = StrategySignal.hold("HTF중립");

        Strategy mtf = new MtfConfirmedStrategy("T", stub("ltf", ltfBuy, 1), stub("htf", htfHold, 2), 4);
        StrategySignal s = mtf.evaluate(flat(8), Map.of());

        assertThat(s.getAction()).isEqualTo(Action.BUY);
        assertThat(s.getReason()).contains("[H4:중립]");
        assertThat(s.getSuggestedStopLoss()).isEqualByComparingTo(SL);
    }

    @Test
    @DisplayName("strictHtf=true: HTF HOLD면 진입을 차단한다")
    void strictBlocksOnHtfHold() {
        StrategySignal ltfBuy = StrategySignal.buy(BigDecimal.valueOf(70), "LTF매수", SL, TP);
        StrategySignal htfHold = StrategySignal.hold("HTF중립");

        Strategy mtf = new MtfConfirmedStrategy("T", stub("ltf", ltfBuy, 1), stub("htf", htfHold, 2), 4, true);
        StrategySignal s = mtf.evaluate(flat(8), Map.of());

        assertThat(s.getAction()).isEqualTo(Action.HOLD);
        assertThat(s.getReason()).contains("strict");
    }

    @Test
    @DisplayName("strictHtf=true: HTF 데이터 부족이면 진입을 차단한다")
    void strictBlocksOnHtfDataShortage() {
        StrategySignal ltfBuy = StrategySignal.buy(BigDecimal.valueOf(70), "LTF매수", SL, TP);
        StrategySignal htfBuy = StrategySignal.buy(BigDecimal.valueOf(50), "HTF상승");

        // htf minCount=5인데 8캔들 ÷4 = 2캔들뿐 → 데이터 부족 경로
        Strategy mtf = new MtfConfirmedStrategy("T", stub("ltf", ltfBuy, 1), stub("htf", htfBuy, 5), 4, true);
        StrategySignal s = mtf.evaluate(flat(8), Map.of());

        assertThat(s.getAction()).isEqualTo(Action.HOLD);
        assertThat(s.getReason()).contains("데이터부족").contains("strict");
    }

    @Test
    @DisplayName("방향 불일치(H4=SELL vs H1=BUY)는 strict 여부와 무관하게 차단한다")
    void directionalMismatchBlocks() {
        StrategySignal ltfBuy = StrategySignal.buy(BigDecimal.valueOf(70), "LTF매수", SL, TP);
        StrategySignal htfSell = StrategySignal.sell(BigDecimal.valueOf(50), "HTF하락");

        Strategy mtf = new MtfConfirmedStrategy("T", stub("ltf", ltfBuy, 1), stub("htf", htfSell, 2), 4);
        StrategySignal s = mtf.evaluate(flat(8), Map.of());

        assertThat(s.getAction()).isEqualTo(Action.HOLD);
        assertThat(s.getReason()).contains("MTF불일치");
    }
}
