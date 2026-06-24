package com.cryptoautotrader.api.backtest;

import com.cryptoautotrader.core.backtest.BacktestConfig;
import com.cryptoautotrader.core.backtest.BacktestEngine;
import com.cryptoautotrader.core.backtest.BacktestResult;
import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.core.selector.CompositePullbackMtfStrategy;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategyRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * COMPOSITE_PULLBACK_MTF 실거래 캔들 백테스트 러너 (수동 실행).
 *
 * <p>Upbit 공개 캔들 API에서 H1 데이터를 직접 받아 {@link BacktestEngine}으로 돌린다. DB 불필요.
 * 네트워크에 의존하므로 CI 자동 실행에서 제외하려면 {@code @Disabled}를 켠다.
 *
 * <pre>{@code
 * ./gradlew :web-api:test --tests "*PullbackMtfBacktestRunner" -i
 * }</pre>
 */
class PullbackMtfBacktestRunner {

    private static final String[] COINS = {"KRW-BTC", "KRW-ETH", "KRW-SOL"};
    private static final String TIMEFRAME = "H1";
    private static final int LOOKBACK_DAYS = 730;

    @Test
    @Disabled("수동 백테스트 러너 — Upbit 네트워크 의존. 실행: 이 줄 주석 처리 후 ./gradlew :web-api:test --tests \"*PullbackMtfBacktestRunner\" -i")
    void runBacktest() {
        StrategyRegistry.register(new CompositePullbackMtfStrategy());

        UpbitCandleCollector collector = new UpbitCandleCollector(new UpbitRestClient());
        Instant to = Instant.now();
        Instant from = to.minus(LOOKBACK_DAYS, ChronoUnit.DAYS);

        System.out.println("\n========== COMPOSITE_PULLBACK_MTF 백테스트 (H1, 최근 " + LOOKBACK_DAYS + "일) ==========");
        System.out.printf("%-9s %8s %7s %7s %6s %8s %7s %7s%n",
                "coin", "수익률%", "거래수", "승률%", "PF", "MDD%", "샤프", "승/패비");
        System.out.println("-".repeat(70));

        for (String coin : COINS) {
            try {
                List<Candle> candles = collector.fetchCandles(coin, TIMEFRAME, from, to);
                if (candles.size() < 250) {
                    System.out.printf("%-9s  캔들 부족(%d) — 스킵%n", coin, candles.size());
                    continue;
                }

                BacktestConfig config = BacktestConfig.builder()
                        .strategyName("COMPOSITE_PULLBACK_MTF")
                        .coinPair(coin)
                        .timeframe(TIMEFRAME)
                        .startDate(from)
                        .endDate(to)
                        .initialCapital(new BigDecimal("1000000"))
                        .strategyParams(new java.util.HashMap<>())
                        .build();

                BacktestResult result = new BacktestEngine().run(config, candles);
                PerformanceReport m = result.getMetrics();
                BigDecimal pf = profitFactor(result.getTrades());

                System.out.printf("%-9s %8s %7d %7s %6s %8s %7s %7s%n",
                        coin,
                        fmt(m.getTotalReturnPct()),
                        m.getTotalTrades(),
                        fmt(m.getWinRatePct()),
                        pf == null ? "  n/a" : pf.toPlainString(),
                        fmt(m.getMddPct()),
                        fmt(m.getSharpeRatio()),
                        fmt(m.getWinLossRatio()));
            } catch (Exception e) {
                System.out.printf("%-9s  실패: %s%n", coin, e.getMessage());
            }
        }
        System.out.println("-".repeat(70));
        System.out.println("운영 판정 기준: 거래수≥20 · PF≥1.2 · MDD≤10% (문서 기준)\n");
    }

    /** Profit Factor = Σ(+pnl) / |Σ(-pnl)|. 거래/손실 없으면 null. */
    private static BigDecimal profitFactor(List<TradeRecord> trades) {
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        for (TradeRecord t : trades) {
            BigDecimal pnl = t.getPnl();
            if (pnl == null) continue;
            if (pnl.signum() > 0) grossProfit = grossProfit.add(pnl);
            else if (pnl.signum() < 0) grossLoss = grossLoss.add(pnl.abs());
        }
        if (grossLoss.signum() == 0) return null;
        return grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP);
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "n/a" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
