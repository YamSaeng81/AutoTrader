package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.core.selector.Ema200RegimeGate;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백테스트 EMA200 게이트 면제 — LIVE·PAPER 와 같은 조건인지 (2026-10-28 리뷰 12차).
 *
 * <p>LIVE({@code LiveTradingService})·PAPER({@code PaperTradingService}) 는
 * {@code isExempt(전략) || allowsBuy(창)} 로 판정한다. 백테스트는 면제 확인 없이 {@code allowsBuy} 만
 * 불러 {@code COMPOSITE_MEANREV_BB} 가 백테스트에서만 EMA200 아래 매수를 막혔다.
 *
 * <p>같은 시험용 전략 객체를 넘기고 설정의 전략 이름만 바꾼다 — 게이트는 이름으로 면제를 판정한다.
 */
class Ema200ExemptionParityTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final int N = 400;
    private static final int BUY_FROM = 300;   // 창 300봉 이상에서만 매수 신호 — EMA200 게이트가 켜진 구간
    private static final String EXEMPT = "COMPOSITE_MEANREV_BB";
    private static final String NOT_EXEMPT = "EMA200_PARITY_PROBE";

    /** 창이 {@value #BUY_FROM} 봉 이상이면 늘 매수, 매도는 하지 않는다. */
    private static class AlwaysBuyProbe implements Strategy {
        @Override public String getName() { return "EMA200_PARITY_PROBE"; }
        @Override public int getMinimumCandleCount() { return 20; }
        @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) {
            return c.size() >= BUY_FROM
                    ? StrategySignal.buy(new BigDecimal("80"), "탐침 매수")
                    : StrategySignal.hold("탐침 대기");
        }
    }

    /** 첫 200봉은 1000 근처 수평, 이후 봉당 {@code drift} 로 움직인다. */
    private static List<Candle> candles(double drift) {
        List<Candle> out = new ArrayList<>();
        double price = 1000.0;
        for (int i = 0; i < N; i++) {
            double open = price;
            double close = i < 200 ? open * (1 + 0.0005 * Math.sin(i * 0.5)) : open * (1 + drift);
            out.add(Candle.builder()
                    .time(T0.plus(Duration.ofHours(i)))
                    .open(bd(open)).close(bd(close))
                    .high(bd(Math.max(open, close) * 1.0005))
                    .low(bd(Math.min(open, close) * 0.9995))
                    .volume(new BigDecimal("1000"))
                    .build());
            price = close;
        }
        return out;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP);
    }

    private static BacktestConfig config(String strategyName, List<Candle> c) {
        return BacktestConfig.builder()
                .strategyName(strategyName)
                .coinPair("KRW-TEST")
                .timeframe("H1")
                .startDate(c.get(0).getTime())
                .endDate(c.get(c.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .strategyParams(Map.of())
                .build();
    }

    private static long buys(BacktestResult r) {
        return r.getTrades().stream().filter(t -> t.getSide() == OrderSide.BUY).count();
    }

    @Test
    @DisplayName("EMA200 아래 — 면제 전략(COMPOSITE_MEANREV_BB)은 매수, 비면제 전략은 차단")
    void EMA200_아래_면제와_비면제() {
        List<Candle> c = candles(-0.002);
        // 전제: 매수 신호 구간 전체가 EMA200 아래다
        for (int i = BUY_FROM - 1; i < N; i++) {
            assertThat(Ema200RegimeGate.allowsBuy(c.subList(Math.max(0, i + 1 - BacktestEngine.MAX_LOOKBACK), i + 1), null))
                    .as("봉 %d 는 EMA200 아래여야 한다", i).isFalse();
        }
        assertThat(Ema200RegimeGate.isExempt(EXEMPT)).isTrue();
        assertThat(Ema200RegimeGate.isExempt(NOT_EXEMPT)).isFalse();

        BacktestEngine engine = new BacktestEngine();
        BacktestResult exempt = engine.run(config(EXEMPT, c), c, new AlwaysBuyProbe());
        BacktestResult blocked = engine.run(config(NOT_EXEMPT, c), c, new AlwaysBuyProbe());

        assertThat(buys(exempt)).as("면제 전략은 EMA200 아래에서도 매수한다 (LIVE·PAPER 와 같음)").isPositive();
        TradeRecord first = exempt.getTrades().get(0);
        assertThat(first.getExecutedAt()).isEqualTo(c.get(BUY_FROM).getTime());
        assertThat(buys(blocked)).as("비면제 전략은 EMA200 아래 매수가 막힌다").isZero();
    }

    @Test
    @DisplayName("대조: EMA200 위 — 면제·비면제 모두 매수 (차이는 게이트에서만 난다)")
    void EMA200_위_둘다_매수() {
        List<Candle> c = candles(0.002);
        BacktestEngine engine = new BacktestEngine();
        BacktestResult exempt = engine.run(config(EXEMPT, c), c, new AlwaysBuyProbe());
        BacktestResult notExempt = engine.run(config(NOT_EXEMPT, c), c, new AlwaysBuyProbe());

        assertThat(buys(exempt)).isPositive();
        assertThat(buys(notExempt)).isEqualTo(buys(exempt));
        assertThat(notExempt.getTrades().get(0).getExecutedAt()).isEqualTo(c.get(BUY_FROM).getTime());
    }
}
