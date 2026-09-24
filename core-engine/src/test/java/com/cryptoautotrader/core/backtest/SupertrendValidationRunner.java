package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.core.selector.CandleDownsampler;
import com.cryptoautotrader.core.selector.CompositePresets;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백테스트 미사용 22코인 일반화 검사 — {@code docs/SUPERTREND_VALIDATION_PREREG.md} v3
 *
 * <p>실행
 * <pre>
 *   -Dstval.dir=d:/tmp/stval                 22코인 H1 CSV
 *   -Dstval.out=d:/tmp/stval_out             산출물 디렉터리
 *   -Dstval.verify=ong                       🔴 회계 검증만 (한 코인, §6.5)
 * </pre>
 *
 * <h3>무엇을 내보내는가</h3>
 * <ul>
 *   <li>{@code equity_daily.csv} — coin,arm,cost,date,equity
 *       <b>일별 평가 자산</b> = 현금 + 보유수량 × 그날 종가 (미청산 포지션 포함).
 *       거래 손익을 청산일에 몰아넣지 않는다(§3)</li>
 *   <li>{@code trades.csv} — coin,arm,cost,entryTime,exitTime,entryAmount,netPnl
 *       왕복 거래 단위. <b>거래당 순수익률 = netPnl / entryAmount</b> (§4, 가격 기준)</li>
 *   <li>{@code summary.csv} — coin,arm,cost,totalReturnPct,trades,mddPct,expectancyPct,finalEquity,calls</li>
 * </ul>
 *
 * <h3>🔴 자산곡선은 엔진을 고치지 않고 재구성한다</h3>
 * {@link TradeRecord} (side·price·quantity·fee·executedAt) 와 캔들 종가로 현금·보유수량을
 * 되짚는다. 검증(§6.5): {@code E[마지막] == finalEquity} 이고
 * {@code Σ(E[d]−E[d−1]) == finalEquity − 초기자본}.
 *
 * <h3>🔴 비용 스트레스는 엔진 재실행이다</h3>
 * 비용은 체결가·청산 판단·포지션 크기에 영향을 준다. 사후 차감하지 않는다.
 */
class SupertrendValidationRunner {

    private static final int HTF_FACTOR = 4;
    private static final long H1_SECONDS = 3600L;
    private static final BigDecimal INITIAL = new BigDecimal("10000000");
    private static final String BASE_PRESET = "COMPOSITE_MOMENTUM_ICHIMOKU_V2";

    /** (라벨, 수수료%, 슬리피지%) — 주 비용과 비용 스트레스. */
    private record Cost(String label, String fee, String slip) {}

    private static final List<Cost> COSTS = List.of(
            new Cost("MAIN", "0.05", "0.1"),
            new Cost("STRESS", "0.05", "0.2"));

    private final BacktestEngine engine = new BacktestEngine();

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
        public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) {
            calls++;
            return inner.evaluate(c, p);
        }
    }

    /** 확인 필터. dropForming=true 면 변형 A(완결 H4 만), false 면 B(운영 배포본). */
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
            return ltf.getName() + (dropForming ? "_A" : "_B");
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
                return s;
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

    private static List<Candle> dropForming(List<Candle> h4, List<Candle> win) {
        if (h4.isEmpty()) {
            return h4;
        }
        long last = win.get(win.size() - 1).getTime().getEpochSecond();
        long bucket = H1_SECONDS * HTF_FACTOR;
        long boundary = Math.floorDiv(last, bucket) * bucket;
        boolean complete = (last - boundary) == H1_SECONDS * (HTF_FACTOR - 1);
        return complete ? h4 : h4.subList(0, h4.size() - 1);
    }

    private static Strategy arm(String name) {
        Strategy base = CompositePresets.factories().get(BASE_PRESET).get();
        return switch (name) {
            case "OFF" -> base;
            case "B" -> new ConfirmWrapper(base, false);
            case "A" -> new ConfirmWrapper(base, true);
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test
    @EnabledIfSystemProperty(named = "stval.dir", matches = ".+")
    void run() throws Exception {
        Path dir = Path.of(System.getProperty("stval.dir"));
        Path out = Path.of(System.getProperty("stval.out", "d:/tmp/stval_out"));
        Files.createDirectories(out);
        String only = System.getProperty("stval.verify");     // 회계 검증 모드

        List<Path> csvs;
        try (Stream<Path> s = Files.list(dir)) {
            csvs = s.filter(p -> p.getFileName().toString().matches("st_\\w+_h1\\.csv"))
                    .filter(p -> only == null || p.getFileName().toString().contains("_" + only + "_"))
                    .sorted().toList();
        }
        assertThat(csvs).isNotEmpty();

        List<String> equityRows = new ArrayList<>();
        equityRows.add("coin,arm,cost,date,equity");
        List<String> tradeRows = new ArrayList<>();
        tradeRows.add("coin,arm,cost,entryTime,exitTime,entryAmount,netPnl");
        List<String> sumRows = new ArrayList<>();
        sumRows.add("coin,arm,cost,totalReturnPct,trades,mddPct,expectancyPct,finalEquity,calls");

        // 🔴 파일명 → 코인: replace("st_","") 는 bla[st_]h1.csv 처럼 내부의 "st_" 까지 지운다
        //    (BLAST 가 "BLAH1.CSV" 로 잘못 붙었다). 접두사 3글자만 잘라낸다.
        for (Path csv : csvs) {
            String coin = "KRW-" + csv.getFileName().toString()
                    .substring(3).replace("_h1.csv", "").toUpperCase();
            List<Candle> candles = loadCsv(csv.toString());
            System.out.printf("%n== %s  candles %,d  (%s ~ %s)%n", coin, candles.size(),
                    candles.get(0).getTime(), candles.get(candles.size() - 1).getTime());

            for (Cost cost : COSTS) {
                for (String armName : List.of("OFF", "B", "A")) {
                    CountingStrategy strat = new CountingStrategy(arm(armName));
                    BacktestConfig config = BacktestConfig.builder()
                            .strategyName(BASE_PRESET + "_" + armName)
                            .coinPair(coin).timeframe("H1")
                            .startDate(candles.get(0).getTime())
                            .endDate(candles.get(candles.size() - 1).getTime())
                            .initialCapital(INITIAL)
                            .feePct(new BigDecimal(cost.fee()))
                            .slippagePct(new BigDecimal(cost.slip()))
                            .strategyParams(new HashMap<>())
                            .build();
                    BacktestResult r = engine.run(config, candles, strat);
                    PerformanceReport m = r.getMetrics();

                    Recon rec = reconstruct(r.getTrades(), candles);
                    verify(coin, armName, cost.label(), rec, r);

                    for (Map.Entry<LocalDate, BigDecimal> e : rec.daily.entrySet()) {
                        equityRows.add(String.join(",", coin, armName, cost.label(),
                                e.getKey().toString(), e.getValue().toPlainString()));
                    }
                    for (RoundTrip t : rec.roundTrips) {
                        tradeRows.add(String.join(",", coin, armName, cost.label(),
                                t.entryTime.toString(), t.exitTime.toString(),
                                t.entryAmount.toPlainString(), t.netPnl.toPlainString()));
                    }
                    sumRows.add(String.join(",", coin, armName, cost.label(),
                            m.getTotalReturnPct().toPlainString(),
                            String.valueOf(m.getTotalTrades()),
                            m.getMddPct().toPlainString(),
                            m.getExpectancyPct() == null ? "0" : m.getExpectancyPct().toPlainString(),
                            r.getFinalEquity().toPlainString(),
                            String.valueOf(strat.calls)));
                    System.out.printf("   %-3s %-6s  ret %+9.3f%%  trades %4d  finalEquity %,15.0f  calls %,7d%n",
                            armName, cost.label(), m.getTotalReturnPct(), m.getTotalTrades(),
                            r.getFinalEquity(), strat.calls);
                }
            }
        }

        Files.write(out.resolve("equity_daily.csv"), equityRows, StandardCharsets.UTF_8);
        Files.write(out.resolve("trades.csv"), tradeRows, StandardCharsets.UTF_8);
        Files.write(out.resolve("summary.csv"), sumRows, StandardCharsets.UTF_8);
        System.out.printf("%n-> %s  (equity %,d / trades %,d / summary %,d rows)%n",
                out, equityRows.size() - 1, tradeRows.size() - 1, sumRows.size() - 1);
    }

    private record RoundTrip(Instant entryTime, Instant exitTime,
                             BigDecimal entryAmount, BigDecimal netPnl) {}

    private static final class Recon {
        final Map<LocalDate, BigDecimal> daily = new java.util.LinkedHashMap<>();
        final List<RoundTrip> roundTrips = new ArrayList<>();
        BigDecimal finalPosition = BigDecimal.ZERO;
        BigDecimal finalEquity = BigDecimal.ZERO;
    }

    /**
     * 거래 기록 + 캔들 종가로 <b>일별 평가 자산</b>을 되짚는다.
     *
     * <p>현금은 초기자본에서 시작해 BUY 시 (가격×수량 + 수수료) 만큼 줄고
     * SELL 시 (가격×수량 − 수수료) 만큼 는다. 평가자산 = 현금 + 보유수량 × 그날 종가.
     * 슬리피지는 엔진이 이미 체결가에 반영했으므로 여기서 다시 빼지 않는다.
     */
    private static Recon reconstruct(List<TradeRecord> trades, List<Candle> candles) {
        Recon rec = new Recon();
        BigDecimal cash = INITIAL;
        BigDecimal pos = BigDecimal.ZERO;

        // 왕복 거래 추적: 진입 금액(수수료 포함)과 진입 시각
        BigDecimal openAmount = BigDecimal.ZERO;
        BigDecimal openQty = BigDecimal.ZERO;
        Instant openTime = null;

        int ti = 0;
        for (Candle c : candles) {
            Instant t = c.getTime();
            while (ti < trades.size() && !trades.get(ti).getExecutedAt().isAfter(t)) {
                TradeRecord tr = trades.get(ti++);
                BigDecimal gross = tr.getPrice().multiply(tr.getQuantity());
                BigDecimal fee = tr.getFee() == null ? BigDecimal.ZERO : tr.getFee();
                if (tr.getSide() == OrderSide.BUY) {
                    cash = cash.subtract(gross).subtract(fee);
                    pos = pos.add(tr.getQuantity());
                    if (openTime == null) {
                        openTime = tr.getExecutedAt();
                        openAmount = BigDecimal.ZERO;
                        openQty = BigDecimal.ZERO;
                    }
                    openAmount = openAmount.add(gross);      // 진입 포지션 금액 (가격 기준)
                    openQty = openQty.add(tr.getQuantity());
                } else {
                    cash = cash.add(gross).subtract(fee);
                    BigDecimal sold = tr.getQuantity();
                    if (openQty.compareTo(BigDecimal.ZERO) > 0 && openTime != null) {
                        BigDecimal share = sold.min(openQty)
                                .divide(openQty, 12, RoundingMode.HALF_UP);
                        BigDecimal entryPart = openAmount.multiply(share);
                        // 순손익 = 청산금액 − 진입금액 − (양쪽 수수료). 슬리피지는 체결가에 포함됨.
                        BigDecimal net = gross.subtract(entryPart).subtract(fee);
                        rec.roundTrips.add(new RoundTrip(openTime, tr.getExecutedAt(),
                                entryPart, net));
                        openAmount = openAmount.subtract(entryPart);
                        openQty = openQty.subtract(sold.min(openQty));
                        if (openQty.compareTo(BigDecimal.ZERO) <= 0) {
                            openTime = null;
                        }
                    }
                    pos = pos.subtract(sold);
                }
            }
            BigDecimal equity = cash.add(pos.multiply(c.getClose()));
            rec.daily.put(LocalDate.ofInstant(t, ZoneOffset.UTC), equity);   // 그날 마지막 값이 남는다
        }
        rec.finalPosition = pos;
        rec.finalEquity = cash.add(pos.multiply(candles.get(candles.size() - 1).getClose()));
        return rec;
    }

    /** §6.5 회계 검증 — 어긋나면 즉시 실패시킨다. */
    private static void verify(String coin, String arm, String cost, Recon rec, BacktestResult r) {
        BigDecimal last = rec.daily.values().stream().reduce((a, b) -> b).orElse(BigDecimal.ZERO);
        BigDecimal first = rec.daily.values().iterator().next();

        // 🔴 일별 변화를 **실제로 하나씩 더한다.** last-first 로 두면 항등식이라 아무것도 검증하지 못한다.
        BigDecimal sumDelta = BigDecimal.ZERO;
        BigDecimal prev = null;
        for (BigDecimal e : rec.daily.values()) {
            if (prev != null) {
                sumDelta = sumDelta.add(e.subtract(prev));
            }
            prev = e;
        }

        BigDecimal tol = new BigDecimal("1");                // 1원 이내 (반올림)
        assertThat(rec.finalEquity.subtract(last).abs())
                .as("%s %s %s: E[마지막] 과 재구성 최종자산 불일치", coin, arm, cost)
                .isLessThanOrEqualTo(tol);
        assertThat(rec.finalEquity.subtract(r.getFinalEquity()).abs())
                .as("%s %s %s: 재구성 자산 %s vs 엔진 finalEquity %s",
                        coin, arm, cost, rec.finalEquity, r.getFinalEquity())
                .isLessThanOrEqualTo(new BigDecimal("100"));
        assertThat(sumDelta.subtract(last.subtract(first)).abs())
                .as("%s %s %s: 일별 변화 합 불일치", coin, arm, cost)
                .isLessThanOrEqualTo(tol);
        if (rec.finalPosition.abs().compareTo(new BigDecimal("0.00000001")) > 0) {
            System.out.printf("      ⚠ %s %s %s: 최종 보유수량 %s (엔진 강제청산 후 0 이어야 한다)%n",
                    coin, arm, cost, rec.finalPosition.toPlainString());
        }
    }

    private static List<Candle> loadCsv(String path) throws Exception {
        List<Candle> out = new ArrayList<>();
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
                out.add(Candle.builder().time(ldt.toInstant(ZoneOffset.UTC))
                        .open(new BigDecimal(p[1])).high(new BigDecimal(p[2]))
                        .low(new BigDecimal(p[3])).close(new BigDecimal(p[4]))
                        .volume(new BigDecimal(p[5])).build());
            }
        }
        return out;
    }
}
