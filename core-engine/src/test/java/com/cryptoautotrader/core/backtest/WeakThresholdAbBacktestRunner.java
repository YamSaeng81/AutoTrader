package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.selector.CompositeStrategy;
import com.cryptoautotrader.core.selector.IchimokuFilteredStrategy;
import com.cryptoautotrader.core.selector.RsiVetoStrategy;
import com.cryptoautotrader.core.selector.WeightedStrategy;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.grid.GridStrategy;
import com.cryptoautotrader.strategy.macd.MacdStrategy;
import com.cryptoautotrader.strategy.volumedelta.VolumeDeltaStrategy;
import com.cryptoautotrader.strategy.vwap.VwapStrategy;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-07-02 S-1 검증 — {@code CompositeStrategy.WEAK_THRESHOLD}가 0.4→0.3으로 이미 실전 반영된
 * 채 백테스트 검증 이력이 없던 것을 사후 확인한다 (2026-06-01 감사 §6 P1-A 기록: "사용자 확인상
 * 매매빈도 부족으로 의도된 조정").
 *
 * <p>A = 0.3(현 실전값) vs B = 0.4(구 기본값). CMI_V1(MACD+VWAP+GRID+Ichimoku, CRR의 TREND/
 * TRANSITIONAL/RANGE 위임 대상 — 90일 분석에서 전 레짐 채택된 주력 delegate)와 COMPOSITE_BREAKOUT
 * (VOLATILITY 위임 대상)으로 검증한다.</p>
 *
 * <p>실행: {@code -Dreview.backtest.dir=d:/tmp} (BTC/ETH/SOL/XRP H1 CSV 필요)</p>
 */
class WeakThresholdAbBacktestRunner {

    private final BacktestEngine engine = new BacktestEngine();

    @Test
    @EnabledIfSystemProperty(named = "review.backtest.dir", matches = ".+")
    void runWeakThresholdAbBacktests() throws Exception {
        Path dir = Path.of(System.getProperty("review.backtest.dir"));
        List<Path> csvs;
        try (Stream<Path> s = Files.list(dir)) {
            csvs = s.filter(p -> p.getFileName().toString().matches("ha_backtest_\\w+_h1\\.csv"))
                    .sorted()
                    .toList();
        }
        assertThat(csvs).isNotEmpty();

        for (Path csv : csvs) {
            String name = csv.getFileName().toString();
            String coinPair = "KRW-" + name.replace("ha_backtest_", "")
                    .replace("_h1.csv", "").toUpperCase();
            List<Candle> candles = loadCsv(csv.toString());
            System.out.printf("%n████ %s — 캔들 %d개 (%s ~ %s) ████%n",
                    coinPair, candles.size(),
                    candles.get(0).getTime(), candles.get(candles.size() - 1).getTime());

            // CMI_V1 delegate (MACD 0.5 + VWAP 0.3 + GRID 0.2, EMA+Ichimoku 필터) — CRR 주력
            Supplier<Strategy> v1 = () -> new IchimokuFilteredStrategy("COMPOSITE_MOMENTUM_ICHIMOKU",
                    new CompositeStrategy("COMPOSITE_MOMENTUM_ICHIMOKU_BASE", List.of(
                            new WeightedStrategy(new MacdStrategy(), 0.5),
                            new WeightedStrategy(new VwapStrategy(), 0.3),
                            new WeightedStrategy(new GridStrategy(), 0.2)
                    ), true));
            run("V1 │ A: WEAK=0.3 (현 실전값)", coinPair, "COMPOSITE_MOMENTUM_ICHIMOKU", v1.get(), candles,
                    Map.of("weakThreshold", 0.3));
            run("V1 │ B: WEAK=0.4 (구 기본값)", coinPair, "COMPOSITE_MOMENTUM_ICHIMOKU", v1.get(), candles,
                    Map.of("weakThreshold", 0.4));

            // COMPOSITE_BREAKOUT (ATR 0.5 + VD 0.3 + MACD 0.2, EMA+ADX 필터 + RSI Veto) — CRR VOLATILITY 위임
            Supplier<Strategy> cb = () -> new RsiVetoStrategy("COMPOSITE_BREAKOUT",
                    new CompositeStrategy("COMPOSITE_BREAKOUT_BASE", List.of(
                            new WeightedStrategy(new AtrBreakoutStrategy(), 0.5),
                            new WeightedStrategy(new VolumeDeltaStrategy(), 0.3),
                            new WeightedStrategy(new MacdStrategy(), 0.2)
                    ), true, true));
            run("CB │ A: WEAK=0.3 (현 실전값)", coinPair, "COMPOSITE_BREAKOUT", cb.get(), candles,
                    Map.of("weakThreshold", 0.3));
            run("CB │ B: WEAK=0.4 (구 기본값)", coinPair, "COMPOSITE_BREAKOUT", cb.get(), candles,
                    Map.of("weakThreshold", 0.4));
        }
    }

    private void run(String label, String coinPair, String strategyName,
                     Strategy strategy, List<Candle> candles, Map<String, Object> params) {
        BacktestConfig config = BacktestConfig.builder()
                .strategyName(strategyName)
                .coinPair(coinPair)
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.05"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(new HashMap<>(params))
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
