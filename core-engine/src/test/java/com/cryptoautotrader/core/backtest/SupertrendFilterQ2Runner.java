package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.selector.CandleDownsampler;
import com.cryptoautotrader.core.selector.CompositePresets;
import com.cryptoautotrader.core.selector.CompositeRegimeRouter;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.strategy.supertrend.SupertrendStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q2 — Supertrend 확인 필터 ON/OFF 운용 비교.
 * {@code docs/SUPERTREND_FILTER_PREREG.md} v2 §4
 *
 * <p>실행: {@code -Dstfilter.q2.dir=d:/tmp/stfilter}
 *
 * <h3>비교 대상</h3>
 * 같은 기간·자본·청산·비용·포지션 제한에서 <b>확인 필터만</b> 바꾼다.
 * <ul>
 *   <li>OFF — 기본 전략 (래퍼 없음)</li>
 *   <li>ON/B (주) — 운영과 동일. {@link CandleDownsampler} 그대로(형성 중 H4 포함)</li>
 *   <li>ON/A (보조) — 말미의 미완결 H4 봉을 버린다</li>
 * </ul>
 *
 * <h3>🔴 상태를 가진 전략에 대한 실행 조건</h3>
 * {@code MarketRegimeDetector}(히스테리시스) 와 {@code GridStrategy}(lastHighest/lastLowest) 는
 * <b>상태를 들고 있어 평가 호출 이력에 따라 출력이 달라진다.</b> 그래서:
 * <ol>
 *   <li><b>실행마다 새 전략 인스턴스</b> — (코인 × ON/OFF × 변형) 조합마다 {@code Supplier} 로 새로 만든다</li>
 *   <li><b>같은 워밍업·같은 평가 시점</b> — 동일 캔들 배열을 같은 엔진에 넘긴다</li>
 *   <li>🔴 <b>엔진은 포지션 상태에 따라 전략 호출을 건너뛴다</b> —
 *       {@code BacktestEngine} 의 SL/TP 청산(222행)과 time stop(272행) 뒤의 {@code continue} 가
 *       그 봉의 {@code strategy.evaluate} 를 건너뛴다. ON/OFF 는 포지션 이력이 다르므로
 *       <b>건너뛰는 봉도 달라진다.</b> {@link CountingStrategy} 로 실제 호출 수를 세어 기록한다</li>
 *   <li><b>Q1 의 관측용 호출과 분리</b> — 이 러너는 Q2 전용 인스턴스만 쓰고,
 *       관측 목적의 추가 {@code evaluate} 를 하지 않는다</li>
 * </ol>
 */
class SupertrendFilterQ2Runner {

    private static final int HTF_FACTOR = 4;
    private static final long H1_SECONDS = 3600L;

    private final BacktestEngine engine = new BacktestEngine();

    private record Spec(String label, Supplier<Strategy> base) {}

    private static Supplier<Strategy> preset(String name) {
        return () -> CompositePresets.factories().get(name).get();
    }

    private static final List<Spec> SPECS = List.of(
            new Spec("MTF_CONFIRMED", CompositeRegimeRouter::new),
            new Spec("MTF_BTC", preset("COMPOSITE_BREAKOUT")),
            new Spec("MTF_MOMENTUM", preset("COMPOSITE_MOMENTUM_ICHIMOKU_V2")));

    /** evaluate 호출 수를 세는 데코레이터 — 엔진이 건너뛴 봉을 드러낸다. */
    private static final class CountingStrategy implements Strategy {
        private final Strategy inner;
        long calls;

        CountingStrategy(Strategy inner) {
            this.inner = inner;
        }

        @Override
        public String getName() {
            return inner.getName();
        }

        @Override
        public int getMinimumCandleCount() {
            return inner.getMinimumCandleCount();
        }

        @Override
        public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            calls++;
            return inner.evaluate(candles, params);
        }
    }

    /**
     * 확인 필터 래퍼. {@code MtfConfirmedStrategy} 와 같은 규칙이되,
     * 변형 A 에서는 말미의 형성 중 H4 봉을 버린다.
     */
    private static final class ConfirmWrapper implements Strategy {
        private final Strategy ltf;
        private final Strategy htf = new SupertrendStrategy();
        private final boolean dropForming;

        ConfirmWrapper(Strategy ltf, boolean dropForming) {
            this.ltf = ltf;
            this.dropForming = dropForming;
        }

        @Override
        public String getName() {
            return ltf.getName() + (dropForming ? "_ONA" : "_ONB");
        }

        @Override
        public int getMinimumCandleCount() {
            return Math.max(ltf.getMinimumCandleCount(), HTF_FACTOR * htf.getMinimumCandleCount());
        }

        @Override
        public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            StrategySignal s = ltf.evaluate(candles, params);
            if (s.getAction() == StrategySignal.Action.HOLD) {
                return s;
            }
            List<Candle> h4 = CandleDownsampler.downsample(candles, HTF_FACTOR);
            if (dropForming) {
                h4 = dropForming(h4, candles);
            }
            if (h4.size() < htf.getMinimumCandleCount()) {
                return s;                       // strictHtf=false → 통과
            }
            StrategySignal hs = htf.evaluate(h4, params);
            if (hs.getAction() == StrategySignal.Action.HOLD) {
                return s;
            }
            if (hs.getAction() != s.getAction()) {
                return StrategySignal.hold("MTF불일치 [" + s.getReason() + "]");
            }
            return s;
        }
    }

    private static List<Candle> dropForming(List<Candle> h4, List<Candle> ltfWindow) {
        if (h4.isEmpty()) {
            return h4;
        }
        long lastLtf = ltfWindow.get(ltfWindow.size() - 1).getTime().getEpochSecond();
        long bucket = H1_SECONDS * HTF_FACTOR;
        long boundary = Math.floorDiv(lastLtf, bucket) * bucket;
        boolean complete = (lastLtf - boundary) == H1_SECONDS * (HTF_FACTOR - 1);
        return complete ? h4 : h4.subList(0, h4.size() - 1);
    }

    private record Row(String coin, String strategy, String arm,
                       BigDecimal ret, int trades, BigDecimal mdd,
                       BigDecimal perTrade, long calls, int bars) {}

    @Test
    @EnabledIfSystemProperty(named = "stfilter.q2.dir", matches = ".+")
    void runQ2() throws Exception {
        Path dir = Path.of(System.getProperty("stfilter.q2.dir"));
        List<Path> csvs;
        try (Stream<Path> s = Files.list(dir)) {
            csvs = s.filter(p -> p.getFileName().toString().matches("st_\\w+_h1\\.csv"))
                    .sorted().toList();
        }
        assertThat(csvs).isNotEmpty();

        List<Row> rows = new ArrayList<>();
        // 🔴 파일명 → 코인: replace("st_","") 는 bla[st_]h1.csv 처럼 내부의 "st_" 까지 지운다
        //    (BLAST 가 "BLAH1.CSV" 로 잘못 붙었다). 접두사 3글자만 잘라낸다.
        for (Path csv : csvs) {
            String coin = "KRW-" + csv.getFileName().toString()
                    .substring(3).replace("_h1.csv", "").toUpperCase();
            List<Candle> candles = loadCsv(csv.toString());
            System.out.printf("%n-- %s  candles %,d%n", coin, candles.size());

            for (Spec sp : SPECS) {
                // 🔴 실행마다 새 인스턴스 — 상태가 실행 간에 새지 않도록
                rows.add(run(coin, sp.label(), "OFF", new CountingStrategy(sp.base().get()), candles));
                rows.add(run(coin, sp.label(), "ON_B",
                        new CountingStrategy(new ConfirmWrapper(sp.base().get(), false)), candles));
                rows.add(run(coin, sp.label(), "ON_A",
                        new CountingStrategy(new ConfirmWrapper(sp.base().get(), true)), candles));
            }
        }
        report(rows);
    }

    private Row run(String coin, String label, String arm, CountingStrategy strat, List<Candle> candles) {
        BacktestConfig config = BacktestConfig.builder()
                .strategyName(label + "_" + arm)
                .coinPair(coin)
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .strategyParams(new HashMap<>())
                .build();
        BacktestResult r = engine.run(config, candles, strat);
        PerformanceReport m = r.getMetrics();
        int trades = m.getTotalTrades();
        BigDecimal ret = m.getTotalReturnPct();
        // 거래당 기대값은 엔진이 계산한 expectancyPct 를 그대로 쓴다 — 직접 나누면
        // 정의가 갈릴 수 있다(자본 기준·순손익 기준).
        BigDecimal per = m.getExpectancyPct() != null ? m.getExpectancyPct() : BigDecimal.ZERO;
        System.out.printf("   %-14s %-5s  ret %+9.3f%%  trades %4d  calls %,8d%n",
                label, arm, ret, trades, strat.calls);
        return new Row(coin, label, arm, ret, trades, m.getMddPct(), per,
                strat.calls, candles.size());
    }

    private static void report(List<Row> rows) {
        System.out.printf("%n%n%s%n", "=".repeat(120));
        System.out.println("Q2 -- Supertrend confirm filter ON/OFF (PREREG v2 section 4)");
        System.out.printf("%s%n%n", "=".repeat(120));

        System.out.printf("%-12s %-14s %-5s %10s %8s %10s %12s %10s%n",
                "coin", "strategy", "arm", "return%", "trades", "mdd%", "perTrade%", "evalCalls");
        System.out.println("-".repeat(120));
        for (Row r : rows) {
            System.out.printf("%-12s %-14s %-5s %10.3f %8d %10.3f %12.4f %,10d%n",
                    r.coin(), r.strategy(), r.arm(), r.ret(), r.trades(), r.mdd(),
                    r.perTrade(), r.calls());
        }

        System.out.printf("%n%s%n", "=".repeat(120));
        System.out.println("coin-equal-weight summary (13 coins)");
        System.out.printf("%s%n", "=".repeat(120));
        System.out.printf("%-14s %-5s %12s %10s %12s %12s%n",
                "strategy", "arm", "meanRet%", "trades", "meanMDD%", "perTrade%");
        System.out.println("-".repeat(120));
        Map<String, List<Row>> byKey = new LinkedHashMap<>();
        for (Row r : rows) {
            byKey.computeIfAbsent(r.strategy() + "|" + r.arm(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Row>> e : byKey.entrySet()) {
            String[] k = e.getKey().split("\\|");
            List<Row> v = e.getValue();
            double mr = v.stream().mapToDouble(x -> x.ret().doubleValue()).average().orElse(0);
            int tr = v.stream().mapToInt(Row::trades).sum();
            double md = v.stream().mapToDouble(x -> x.mdd().doubleValue()).average().orElse(0);
            double pt = v.stream().mapToDouble(x -> x.perTrade().doubleValue()).average().orElse(0);
            System.out.printf("%-14s %-5s %12.3f %10d %12.3f %12.4f%n", k[0], k[1], mr, tr, md, pt);
        }

        System.out.printf("%n%s%n", "=".repeat(120));
        System.out.println("evaluate-call gap: engine skips strategy.evaluate after SL/TP and time-stop exits");
        System.out.println("  -> ON and OFF have different position histories, so the SKIPPED BARS DIFFER.");
        System.out.println("     For stateful strategies (MarketRegimeDetector hysteresis, GridStrategy)");
        System.out.println("     this means the two arms feed their strategies different call histories.");
        System.out.printf("%s%n", "=".repeat(120));
        System.out.printf("%-14s %12s %12s %12s %10s%n",
                "strategy", "calls OFF", "calls ON_B", "calls ON_A", "bars");
        System.out.println("-".repeat(120));
        for (Spec sp : SPECS) {
            long off = sum(rows, sp.label(), "OFF");
            long onb = sum(rows, sp.label(), "ON_B");
            long ona = sum(rows, sp.label(), "ON_A");
            int bars = rows.stream().filter(r -> r.strategy().equals(sp.label())
                    && "OFF".equals(r.arm())).mapToInt(Row::bars).sum();
            System.out.printf("%-14s %,12d %,12d %,12d %,10d%n", sp.label(), off, onb, ona, bars);
        }
    }

    private static long sum(List<Row> rows, String strategy, String arm) {
        return rows.stream().filter(r -> r.strategy().equals(strategy) && r.arm().equals(arm))
                .mapToLong(Row::calls).sum();
    }

    private static List<Candle> loadCsv(String path) throws Exception {
        List<Candle> candles = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] p = line.split(",");
                if (p.length < 6) {
                    continue;
                }
                LocalDateTime ldt = LocalDateTime.parse(p[0].replace(" ", "T"));
                candles.add(Candle.builder()
                        .time(ldt.toInstant(ZoneOffset.UTC))
                        .open(new BigDecimal(p[1])).high(new BigDecimal(p[2]))
                        .low(new BigDecimal(p[3])).close(new BigDecimal(p[4]))
                        .volume(new BigDecimal(p[5])).build());
            }
        }
        return candles;
    }
}
