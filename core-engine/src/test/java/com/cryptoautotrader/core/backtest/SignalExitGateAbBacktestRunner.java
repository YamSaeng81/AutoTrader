package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.risk.ExitRuleConfig;
import com.cryptoautotrader.core.selector.CompositeRegimeRouter;
import com.cryptoautotrader.core.selector.CompositeStrategy;
import com.cryptoautotrader.core.selector.RsiVetoStrategy;
import com.cryptoautotrader.core.selector.WeightedStrategy;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.macd.MacdStrategy;
import com.cryptoautotrader.strategy.volumedelta.VolumeDeltaStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-07-02 L-2 검증 — BacktestEngine에 신규 추가된 전략 SELL 게이트(최소보유 180분 +
 * 본전청산차단, {@link com.cryptoautotrader.core.risk.ExitRuleChecker#allowsSignalExit})가
 * 기존 "검증됨" 백테스트 수치(BTC/SOL 등)에 실제로 얼마나 영향을 주는지 A/B로 측정한다.
 *
 * <p>A = 게이트 적용(신규 기본값, 실전과 정합) vs B = 게이트 미적용(기존 동작, 즉시 체결).
 * 차이가 크면 PROGRESS.md의 기존 Tier1/Tier2 배포 권고 수치가 전부 재검증 대상이 된다.</p>
 *
 * <p>실행: {@code -Dreview.backtest.dir=d:/tmp} (BTC/ETH/SOL/XRP H1 CSV 필요)</p>
 */
class SignalExitGateAbBacktestRunner {

    private final BacktestEngine engine = new BacktestEngine();

    @Test
    @EnabledIfSystemProperty(named = "review.backtest.dir", matches = ".+")
    void runSignalExitGateAbBacktests() throws Exception {
        Path dir = Path.of(System.getProperty("review.backtest.dir"));
        List<Path> csvs;
        try (Stream<Path> s = Files.list(dir)) {
            csvs = s.filter(p -> p.getFileName().toString().matches("ha_backtest_\\w+_h1\\.csv"))
                    .sorted()
                    .toList();
        }
        assertThat(csvs).isNotEmpty();

        ExitRuleConfig gateOn = ExitRuleConfig.defaults(); // 신규 기본값 (minHold=180, breakeven guard)
        ExitRuleConfig gateOff = ExitRuleConfig.builder()
                .minHoldMinutesForSignalExit(0)
                .minPnlPctForSignalExit(new BigDecimal("-999"))
                .lossEscapeThresholdPct(new BigDecimal("-999"))
                .build(); // 게이트 사실상 무력화 = L-2 수정 전 동작 재현

        for (Path csv : csvs) {
            String name = csv.getFileName().toString();
            String coinPair = "KRW-" + name.replace("ha_backtest_", "")
                    .replace("_h1.csv", "").toUpperCase();
            List<Candle> candles = loadCsv(csv.toString());
            System.out.printf("%n████ %s — 캔들 %d개 (%s ~ %s) ████%n",
                    coinPair, candles.size(),
                    candles.get(0).getTime(), candles.get(candles.size() - 1).getTime());

            // COMPOSITE_BREAKOUT — Tier1 (BTC +106.71%, ADA +86.98% 근거 전략)
            Supplier<Strategy> cb = () -> new RsiVetoStrategy("COMPOSITE_BREAKOUT",
                    new CompositeStrategy("COMPOSITE_BREAKOUT_BASE", List.of(
                            new WeightedStrategy(new AtrBreakoutStrategy(), 0.5),
                            new WeightedStrategy(new VolumeDeltaStrategy(), 0.3),
                            new WeightedStrategy(new MacdStrategy(),        0.2)
                    ), true, true));
            run("CB │ A: 게이트 ON (신규 기본값=실전 정합)", coinPair, "COMPOSITE_BREAKOUT", cb.get(), candles, gateOn);
            run("CB │ B: 게이트 OFF (기존 백테스트 동작)", coinPair, "COMPOSITE_BREAKOUT", cb.get(), candles, gateOff);

            // COMPOSITE_REGIME_ROUTER — Tier1 (SOL +65.38% 근거 전략, 현재 실전 가동 중)
            Supplier<Strategy> crr = CompositeRegimeRouter::new;
            run("CRR │ A: 게이트 ON", coinPair, "COMPOSITE_REGIME_ROUTER", crr.get(), candles, gateOn);
            run("CRR │ B: 게이트 OFF", coinPair, "COMPOSITE_REGIME_ROUTER", crr.get(), candles, gateOff);
        }
    }

    private void run(String label, String coinPair, String strategyName,
                     Strategy strategy, List<Candle> candles, ExitRuleConfig exitRuleConfig) {
        BacktestConfig config = BacktestConfig.builder()
                .strategyName(strategyName)
                .coinPair(coinPair)
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.05"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(new java.util.HashMap<>())
                .exitRuleConfig(exitRuleConfig)
                .build();

        BacktestResult result = engine.run(config, candles, strategy);
        PerformanceReport m = result.getMetrics();
        assertThat(result).isNotNull();

        System.out.printf("── %s%n", label);
        System.out.printf("   수익률 %8s%% │ 거래 %3d회 (승 %d/패 %d, 승률 %s%%) │ MDD %s%% │ Sharpe %s%n",
                m.getTotalReturnPct(), m.getTotalTrades(), m.getWinningTrades(),
                m.getLosingTrades(), m.getWinRatePct(), m.getMddPct(), m.getSharpeRatio());
    }

    private static List<Candle> loadCsv(String path) throws Exception {
        List<Candle> candles = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(",");
                if (p.length < 6) continue;
                LocalDateTime ldt = LocalDateTime.parse(p[0].replace(" ", "T"));
                Instant time = ldt.toInstant(ZoneOffset.UTC);
                candles.add(Candle.builder()
                        .time(time)
                        .open(new BigDecimal(p[1]))
                        .high(new BigDecimal(p[2]))
                        .low(new BigDecimal(p[3]))
                        .close(new BigDecimal(p[4]))
                        .volume(new BigDecimal(p[5]))
                        .build());
            }
        }
        candles.sort(Comparator.comparing(Candle::getTime));
        return candles;
    }
}
