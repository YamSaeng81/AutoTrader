package com.cryptoautotrader.core.backtest;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supertrend 확인 필터 게이트 측정 — {@code docs/SUPERTREND_FILTER_PREREG.md} v2 §4.5
 *
 * <p>실행: {@code -Dstfilter.dir=d:/tmp/stfilter} — {@code st_<coin>_h1.csv}
 * (utc,open,high,low,close,volume · 헤더 없음) 를 코인별로 읽는다.
 *
 * <h3>무엇을 세는가</h3>
 * 전략 3종 × 변형 A·B 에 대해 평가 봉마다:
 * <ul>
 *   <li>기본 BUY 수 · 기본 SELL 수 (= 차단률의 <b>분모</b>)</li>
 *   <li><b>BUY 차단 수</b> · <b>SELL 차단 수</b> — 래퍼는 {@code !=} 조건이라 <b>SELL 도 막는다</b></li>
 *   <li>HTF 데이터부족 · HTF 중립 횟수</li>
 *   <li>차단이 없을 때 ON/OFF 의 action·confidence·손절/익절 제안값이 <b>같은지</b></li>
 * </ul>
 *
 * <h3>변형</h3>
 * <ul>
 *   <li><b>B (주)</b> — {@link CandleDownsampler} 그대로. 말미의 <b>형성 중</b> H4 봉을 포함한다.
 *       <b>운영에 배포된 거동</b>이며, 판단 시점까지 종료된 H1 봉만 집계하므로 미래 참조가 아니다</li>
 *   <li>A (보조) — 말미의 미완결 H4 봉을 버린다. <b>별도 변경안</b></li>
 * </ul>
 *
 * <h3>🔴 교차 검증</h3>
 * 여기서 재구성한 (기본 + HTF) 판정이 실제 프리셋({@code COMPOSITE_MTF_*}) 의 출력과
 * 매 평가 시점에 일치하는지 확인한다. 어긋나면 기본 전략 재구성이 틀린 것이므로
 * 카운트 전체를 믿을 수 없다 — <b>불일치 수를 반드시 함께 보고한다.</b>
 *
 * <p>⚠️ SELL 차단 건수는 <b>실제로 청산을 막은 건수와 다르다.</b> 보유 포지션 유무와
 * 엔진의 청산 조건(최소보유시간·본전청산차단·SL/TP 우선)까지 반영한 영향은 Q2 에서 본다.
 */
class SupertrendFilterGateRunner {

    private static final int WINDOW = 500;      // BacktestEngine.MAX_LOOKBACK / CANDLE_LOOKBACK
    private static final int HTF_FACTOR = 4;    // H1 -> H4
    private static final long H1_SECONDS = 3600L;

    private record Spec(String label, Supplier<Strategy> base, String wrappedPreset) {}

    private static Supplier<Strategy> preset(String name) {
        return () -> CompositePresets.factories().get(name).get();
    }

    private static final List<Spec> SPECS = List.of(
            new Spec("MTF_CONFIRMED", CompositeRegimeRouter::new, "COMPOSITE_MTF_CONFIRMED"),
            new Spec("MTF_BTC", preset("COMPOSITE_BREAKOUT"), "COMPOSITE_MTF_BTC"),
            new Spec("MTF_MOMENTUM", preset("COMPOSITE_MOMENTUM_ICHIMOKU_V2"), "COMPOSITE_MTF_MOMENTUM"));

    /**
     * Q1 용 신호 덤프 — {@code -Dstfilter.csv=<경로>} 가 있을 때만 쓴다.
     * 한 줄 = 기본 신호 하나: coin,strategy,variant,time,action,blocked
     * 🔴 이 덤프는 **관측 전용**이다. Q2 러너는 자기 인스턴스만 쓰고 이 경로를 타지 않는다.
     */
    private final List<String> dump = new ArrayList<>();
    private String csvOut;

    /** 변형별 누적 카운터. */
    private static final class Counter {
        long bars, baseBuy, baseSell, blockBuy, blockSell, htfShort, htfNeutral;
        long mismatch, passDiff;      // 교차검증 불일치 · 비차단인데 ON/OFF 출력이 다른 수
    }

    @Test
    @EnabledIfSystemProperty(named = "stfilter.dir", matches = ".+")
    void measureGate() throws Exception {
        Path dir = Path.of(System.getProperty("stfilter.dir"));
        List<Path> csvs;
        try (Stream<Path> s = Files.list(dir)) {
            csvs = s.filter(p -> p.getFileName().toString().matches("st_\\w+_h1\\.csv"))
                    .sorted().toList();
        }
        assertThat(csvs).isNotEmpty();
        csvOut = System.getProperty("stfilter.csv");

        Map<String, Map<String, Counter>> acc = new LinkedHashMap<>();
        for (Spec sp : SPECS) {
            Map<String, Counter> m = new LinkedHashMap<>();
            m.put("B", new Counter());
            m.put("A", new Counter());
            acc.put(sp.label(), m);
        }

        // 🔴 파일명 → 코인: replace("st_","") 는 bla[st_]h1.csv 처럼 내부의 "st_" 까지 지운다
        //    (BLAST 가 "BLAH1.CSV" 로 잘못 붙었다). 접두사 3글자만 잘라낸다.
        for (Path csv : csvs) {
            String coin = csv.getFileName().toString()
                    .substring(3).replace("_h1.csv", "").toUpperCase();
            List<Candle> candles = loadCsv(csv.toString());
            System.out.printf("%n-- KRW-%s  candles %,d  (%s ~ %s)%n", coin, candles.size(),
                    candles.get(0).getTime(), candles.get(candles.size() - 1).getTime());

            for (Spec sp : SPECS) {
                Strategy base = sp.base().get();
                Strategy htf = new SupertrendStrategy();
                Strategy wrapped = CompositePresets.factories().get(sp.wrappedPreset()).get();

                for (int end = WINDOW; end <= candles.size(); end++) {
                    List<Candle> win = candles.subList(end - WINDOW, end);
                    StrategySignal ltf = base.evaluate(win, Map.of());
                    // 🔴 래퍼는 **매 봉** 호출해야 한다. CompositeRegimeRouter 안의
                    //    MarketRegimeDetector 는 히스테리시스 상태(previousRegime·holdCount)를
                    //    들고 있어, 조건부로만 호출하면 내부 상태 이력이 달라져 교차검증이 깨진다.
                    //    (실제로 그렇게 했을 때 MTF_CONFIRMED 에서 불일치 20건이 나왔다.)
                    StrategySignal wrappedSig = wrapped.evaluate(win, Map.of());

                    for (String variant : List.of("B", "A")) {
                        Counter c = acc.get(sp.label()).get(variant);
                        c.bars++;
                        if (ltf.getAction() == StrategySignal.Action.HOLD) {
                            continue;
                        }
                        if (ltf.getAction() == StrategySignal.Action.BUY) {
                            c.baseBuy++;
                        } else {
                            c.baseSell++;
                        }

                        List<Candle> htfCandles = CandleDownsampler.downsample(win, HTF_FACTOR);
                        if ("A".equals(variant)) {
                            htfCandles = dropForming(htfCandles, win);
                        }

                        if (htfCandles.size() < htf.getMinimumCandleCount()) {
                            c.htfShort++;
                            continue;                       // strictHtf=false -> 통과
                        }
                        StrategySignal hs = htf.evaluate(htfCandles, Map.of());
                        if (hs.getAction() == StrategySignal.Action.HOLD) {
                            c.htfNeutral++;
                            continue;                       // 통과
                        }
                        boolean blocked = hs.getAction() != ltf.getAction();
                        if (csvOut != null) {
                            dump.add(coin + "," + sp.label() + "," + variant + ","
                                    + win.get(win.size() - 1).getTime() + ","
                                    + ltf.getAction() + "," + blocked);
                        }
                        if (blocked) {
                            if (ltf.getAction() == StrategySignal.Action.BUY) {
                                c.blockBuy++;
                            } else {
                                c.blockSell++;
                            }
                        }

                        if ("B".equals(variant)) {
                            StrategySignal w = wrappedSig;
                            StrategySignal.Action expect = blocked
                                    ? StrategySignal.Action.HOLD : ltf.getAction();
                            if (w.getAction() != expect) {
                                c.mismatch++;
                            }
                            if (!blocked && !sameProposal(w, ltf)) {
                                c.passDiff++;
                            }
                        }
                    }
                }
            }
        }
        report(acc);
        if (csvOut != null) {
            List<String> lines = new ArrayList<>();
            lines.add("coin,strategy,variant,time,action,blocked");
            lines.addAll(dump);
            Files.write(Path.of(csvOut), lines, java.nio.charset.StandardCharsets.UTF_8);
            System.out.printf("%nQ1 signal dump: %,d rows -> %s%n", dump.size(), csvOut);
        }
    }

    /**
     * 말미의 <b>형성 중</b> H4 봉을 버린다 (변형 A).
     * H1 -> H4 에서 완결 봉은 마지막 LTF 캔들이 경계+3h 인 것이다.
     */
    private static List<Candle> dropForming(List<Candle> htfCandles, List<Candle> ltfWindow) {
        if (htfCandles.isEmpty()) {
            return htfCandles;
        }
        long lastLtf = ltfWindow.get(ltfWindow.size() - 1).getTime().getEpochSecond();
        long bucket = H1_SECONDS * HTF_FACTOR;
        long boundary = Math.floorDiv(lastLtf, bucket) * bucket;
        boolean complete = (lastLtf - boundary) == H1_SECONDS * (HTF_FACTOR - 1);
        return complete ? htfCandles : htfCandles.subList(0, htfCandles.size() - 1);
    }

    /** 차단이 없을 때 ON/OFF 의 action·confidence·손절/익절 제안값이 같은가. */
    private static boolean sameProposal(StrategySignal a, StrategySignal b) {
        return a.getAction() == b.getAction()
                && eq(a.getConfidence(), b.getConfidence())
                && eq(a.getSuggestedStopLoss(), b.getSuggestedStopLoss())
                && eq(a.getSuggestedTakeProfit(), b.getSuggestedTakeProfit());
    }

    private static boolean eq(BigDecimal x, BigDecimal y) {
        if (x == null || y == null) {
            return x == y;
        }
        return x.compareTo(y) == 0;
    }

    private static void report(Map<String, Map<String, Counter>> acc) {
        System.out.printf("%n%n%s%n", "=".repeat(118));
        System.out.println("Supertrend filter gate -- PREREG v2 section 4.5");
        System.out.printf("%s%n", "=".repeat(118));
        System.out.printf("%-15s %-4s %10s %10s %10s %10s %10s %9s %9s %8s %8s%n",
                "strategy", "var", "bars", "baseBUY", "blkBUY", "baseSELL", "blkSELL",
                "BUYblk%", "SELLblk%", "htfShort", "htfNeut");
        System.out.println("-".repeat(118));
        for (Map.Entry<String, Map<String, Counter>> e : acc.entrySet()) {
            for (Map.Entry<String, Counter> v : e.getValue().entrySet()) {
                Counter c = v.getValue();
                System.out.printf("%-15s %-4s %,10d %,10d %,10d %,10d %,10d %8.3f%% %8.3f%% %,8d %,8d%n",
                        e.getKey(), v.getKey(), c.bars, c.baseBuy, c.blockBuy,
                        c.baseSell, c.blockSell,
                        pct(c.blockBuy, c.baseBuy), pct(c.blockSell, c.baseSell),
                        c.htfShort, c.htfNeutral);
            }
        }
        System.out.printf("%ncross-check (variant B: reconstructed vs actual COMPOSITE_MTF_* preset)%n");
        for (Map.Entry<String, Map<String, Counter>> e : acc.entrySet()) {
            Counter c = e.getValue().get("B");
            System.out.printf("  %-15s mismatch %,d   pass-but-different-proposal %,d%n",
                    e.getKey(), c.mismatch, c.passDiff);
        }
        System.out.println();
        System.out.println("NOTE: SELL block count != number of exits actually prevented.");
        System.out.println("      Position state and engine exit conditions are measured in Q2.");
    }

    private static double pct(long a, long b) {
        return b == 0 ? 0.0 : a * 100.0 / b;
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
