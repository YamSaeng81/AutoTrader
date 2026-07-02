package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.selector.CompositeStrategy;
import com.cryptoautotrader.core.selector.IchimokuFilteredStrategy;
import com.cryptoautotrader.core.selector.RsiVetoStrategy;
import com.cryptoautotrader.core.selector.WeightedStrategy;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
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
 * 2026-07-02 실전 4대 전략 검토 항목의 A/B 백테스트 러너.
 *
 * <p>실행: {@code -Dreview.backtest.dir=d:/tmp} — 디렉터리에서 {@code ha_backtest_*_h1.csv}
 * (utc,open,high,low,close,volume 헤더 없음) 를 찾아 코인별로 실행한다.
 *
 * <p>A/B 항목 (A=현 기본값, B=구 기본값/대안 — 2026-07-02 검토에서 기본값 반영 완료):
 * <ol>
 *   <li>COMPOSITE_BREAKOUT — RSI Veto 과매도 SELL 차단: 기본 OFF vs 구 기본 25 (4코인 성과 동일 확인)</li>
 *   <li>HEIKIN_ASHI_STOCH — minStrengthPct: 기본 0 vs 구 기본 70 (해제가 4코인 전부 우수 확인)</li>
 *   <li>CMI_V1 — GRID 레벨 dedup ON(기본) vs OFF (성과 동일 확인 → 기본 유지)</li>
 *   <li>CMI_V1 — Ichimoku 구름 현재시점(기본 단순화) vs 표준 26봉 선행이동 (성과 동일 확인 → 기본 유지)</li>
 * </ol>
 */
class StrategyReviewAbBacktestRunner {

    private final BacktestEngine engine = new BacktestEngine();

    @Test
    @EnabledIfSystemProperty(named = "review.backtest.dir", matches = ".+")
    void runReviewAbBacktests() throws Exception {
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

            // ── 1. COMPOSITE_BREAKOUT: RSI Veto 과매도 SELL 차단 A/B ──
            Supplier<Strategy> cb = () -> new RsiVetoStrategy("COMPOSITE_BREAKOUT",
                    new CompositeStrategy("COMPOSITE_BREAKOUT_BASE", List.of(
                            new WeightedStrategy(new AtrBreakoutStrategy(), 0.5),
                            new WeightedStrategy(new VolumeDeltaStrategy(), 0.3),
                            new WeightedStrategy(new MacdStrategy(),        0.2)
                    ), true, true));
            run("CB │ A: 기본값 (과매도 SELL 차단 OFF)", coinPair,
                    "COMPOSITE_BREAKOUT", cb.get(), candles, Map.of());
            run("CB │ B: 과매도 SELL 차단 ON (구 기본 vetoOversold=25)", coinPair,
                    "COMPOSITE_BREAKOUT", cb.get(), candles, Map.of("vetoOversold", 25.0));

            // ── 2. HEIKIN_ASHI_STOCH: minStrengthPct A/B ──
            run("HAS │ A: 기본값 (minStrengthPct=0)", coinPair,
                    "HEIKIN_ASHI_STOCH", StrategyRegistry.get("HEIKIN_ASHI_STOCH"), candles, Map.of());
            run("HAS │ B: minStrengthPct=70 (구 기본값)", coinPair,
                    "HEIKIN_ASHI_STOCH", StrategyRegistry.get("HEIKIN_ASHI_STOCH"), candles,
                    Map.of("minStrengthPct", 70.0));

            // ── 3·4. CMI_V1: GRID dedup / Ichimoku 선행이동 A/B (GRID stateful → 매 실행 새 인스턴스) ──
            Supplier<Strategy> v1 = () -> new IchimokuFilteredStrategy("COMPOSITE_MOMENTUM_ICHIMOKU",
                    new CompositeStrategy("COMPOSITE_MOMENTUM_ICHIMOKU_BASE", List.of(
                            new WeightedStrategy(new MacdStrategy(), 0.5),
                            new WeightedStrategy(new VwapStrategy(), 0.3),
                            new WeightedStrategy(new GridStrategy(), 0.2)
                    ), true));
            run("V1 │ A: 현행 (dedup ON / 구름 현재시점)", coinPair,
                    "COMPOSITE_MOMENTUM_ICHIMOKU", v1.get(), candles, Map.of());
            run("V1 │ B: GRID dedup OFF", coinPair,
                    "COMPOSITE_MOMENTUM_ICHIMOKU", v1.get(), candles,
                    Map.of("levelDedupEnabled", false));
            run("V1 │ C: Ichimoku 표준 선행이동", coinPair,
                    "COMPOSITE_MOMENTUM_ICHIMOKU", v1.get(), candles,
                    Map.of("ichimokuDisplaced", true));
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
