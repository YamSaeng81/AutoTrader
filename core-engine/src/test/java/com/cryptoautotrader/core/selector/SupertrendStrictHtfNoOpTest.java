package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.strategy.supertrend.SupertrendStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code strictHtf} 는 HTF 확인자가 {@link SupertrendStrategy} 인 한 <b>구조적으로 무효</b>임을 고정한다.
 *
 * <h3>운영 증거 (2026-08-24)</h3>
 * paper_trading 청산 이력에서 {@code COMPOSITE_MTF_BTC} 와 {@code COMPOSITE_MTF_BTC_STRICT} 의
 * 43건이 <b>코인·진입시각·실현손익까지 전부 동일</b>했다(진입 시각 차이 30ms = 같은 tick).
 * 두 프리셋의 유일한 차이가 {@code strictHtf} 인데 결과가 같다는 것은 이 플래그가 아무것도
 * 막지 못했다는 뜻이다.
 *
 * <h3>왜 무효인가</h3>
 * {@link MtfConfirmedStrategy} 에서 {@code strictHtf} 가 동작을 바꾸는 분기는 딱 둘이다:
 * <ol>
 *   <li>HTF 캔들 부족 → strict면 HOLD</li>
 *   <li>HTF 신호가 HOLD(중립) → strict면 HOLD</li>
 * </ol>
 * 그런데 Supertrend 는 <b>데이터가 충분하면 절대 HOLD 를 반환하지 않는다</b> — 가격이
 * 추세선 위면 BUY, 아래면 SELL 로 이분된다(SupertrendStrategy 의 마지막 if/else).
 * 그리고 {@code getMinimumCandleCount()} 가 {@code max(ltf.min, htfFactor × 12)} 이므로
 * 전략이 호출되는 시점에 HTF 캔들은 이미 12개 이상이 보장된다. 두 분기 모두 도달 불가다.
 *
 * <p>따라서 {@code COMPOSITE_MTF_BTC_STRICT} 는 {@code COMPOSITE_MTF_BTC} 의 중복이며,
 * 별도 세션을 돌리는 것은 표본만 둘로 쪼갠다. strict 를 의미 있게 만들려면 HTF 확인자를
 * <b>HOLD 를 낼 수 있는 전략</b>(예: ADX 임계 미달 시 HOLD)으로 바꿔야 한다.</p>
 */
@DisplayName("strictHtf 무효 증명 — HTF 확인자가 Supertrend 인 경우")
class SupertrendStrictHtfNoOpTest {

    /** 무작위 워크 캔들 — 상승/하락/횡보가 섞이도록 시드로 재현 가능하게 만든다. */
    private static List<Candle> randomWalk(int n, long seed) {
        Random rnd = new Random(seed);
        List<Candle> list = new ArrayList<>(n);
        double px = 100_000_000d;
        for (int i = 0; i < n; i++) {
            double drift = (rnd.nextDouble() - 0.5) * 0.04;   // ±2%
            double open = px;
            px = Math.max(1_000d, px * (1 + drift));
            double high = Math.max(open, px) * (1 + rnd.nextDouble() * 0.005);
            double low = Math.min(open, px) * (1 - rnd.nextDouble() * 0.005);
            list.add(Candle.builder()
                    .time(Instant.ofEpochSecond(i * 3600L))
                    .open(BigDecimal.valueOf(open))
                    .high(BigDecimal.valueOf(high))
                    .low(BigDecimal.valueOf(low))
                    .close(BigDecimal.valueOf(px))
                    .volume(BigDecimal.valueOf(100 + rnd.nextInt(900)))
                    .build());
        }
        return list;
    }

    /** 고정 신호 스텁 — LTF 자리에 넣어 HTF 분기만 관찰한다. */
    private static Strategy stub(StrategySignal signal) {
        return new Strategy() {
            @Override public String getName() { return "ltf-stub"; }
            @Override public int getMinimumCandleCount() { return 1; }
            @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) { return signal; }
        };
    }

    @Test
    @DisplayName("Supertrend 는 데이터가 충분하면 HOLD 를 내지 않는다 — strict 가 막을 대상이 없다")
    void supertrendNeverHoldsWithEnoughData() {
        SupertrendStrategy st = new SupertrendStrategy();
        int min = st.getMinimumCandleCount();

        int holds = 0;
        for (long seed = 0; seed < 300; seed++) {
            StrategySignal s = st.evaluate(randomWalk(min + 40, seed), Map.of());
            if (s.getAction() == StrategySignal.Action.HOLD) holds++;
        }
        assertThat(holds)
                .as("300개 무작위 시나리오 중 Supertrend 가 HOLD 를 낸 횟수")
                .isZero();
    }

    @Test
    @DisplayName("strictHtf=true 와 false 가 완전히 같은 신호를 낸다 (300 시나리오 × BUY/SELL)")
    void strictAndLenientAreIndistinguishable() {
        StrategySignal ltfBuy = StrategySignal.buy(BigDecimal.valueOf(70), "LTF매수");
        StrategySignal ltfSell = StrategySignal.sell(BigDecimal.valueOf(70), "LTF매도");

        for (StrategySignal ltf : List.of(ltfBuy, ltfSell)) {
            Strategy lenient = new MtfConfirmedStrategy("A", stub(ltf), new SupertrendStrategy(), 4, false);
            Strategy strict  = new MtfConfirmedStrategy("B", stub(ltf), new SupertrendStrategy(), 4, true);

            // 전략이 실제로 호출되는 조건 = 최소 캔들 수 이상. 그 지점부터 관찰한다.
            int min = Math.max(lenient.getMinimumCandleCount(), strict.getMinimumCandleCount());

            for (long seed = 0; seed < 300; seed++) {
                List<Candle> candles = randomWalk(min + (int) (seed % 50), seed);
                StrategySignal a = lenient.evaluate(candles, Map.of());
                StrategySignal b = strict.evaluate(candles, Map.of());

                assertThat(b.getAction())
                        .as("seed=%d, LTF=%s — strict 와 lenient 의 신호가 갈리는 케이스", seed, ltf.getAction())
                        .isEqualTo(a.getAction());
            }
        }
    }

    @Test
    @DisplayName("최소 캔들 수 계약상 HTF 데이터 부족 분기도 도달 불가")
    void htfDataShortageBranchIsUnreachable() {
        MtfConfirmedStrategy mtf =
                new MtfConfirmedStrategy("T", stub(StrategySignal.buy(BigDecimal.TEN, "x")),
                        new SupertrendStrategy(), 4, true);

        // getMinimumCandleCount() = max(1, 4 × 12) = 48 → downsample(48, 4) = 12 = Supertrend 최소치
        int min = mtf.getMinimumCandleCount();
        assertThat(min).isEqualTo(4 * new SupertrendStrategy().getMinimumCandleCount());
        assertThat(CandleDownsampler.downsample(randomWalk(min, 1L), 4))
                .as("최소 캔들 수만큼 줬을 때 HTF 캔들 수")
                .hasSizeGreaterThanOrEqualTo(new SupertrendStrategy().getMinimumCandleCount());
    }
}
