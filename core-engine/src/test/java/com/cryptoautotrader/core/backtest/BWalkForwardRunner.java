package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.risk.ExitRuleConfig;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 새 후보 B 의 WF 게이트 로컬 실행 (2026-10-28, docs/LIVE_READINESS.md §1 #4 — 리뷰 2·3차 고정안).
 *
 * <p>서버 WF({@code BacktestService.runWalkForward})와 같은 입력으로 돌린다 — 게이트가 실제로 읽는 것은
 * 서버 실행 기록이고(#8 ③), 이 실행은 그것을 미리 맞혀 보는 용도다.</p>
 * <ul>
 *   <li>전략 {@code COMPOSITE_MTF_MOMENTUM} × 21코인(BLAST 제외) × H1, ROLLING · 학습 0.7 · 창 5</li>
 *   <li>주 비용: 수수료 0.05% · 슬리피지 0.1%, 초기자본 1,000만(서버 기본값)</li>
 *   <li>청산 규칙: 운영 {@code risk_config} 최대 ID 행의 원값을 받아 서버와 같은 NULL 기본값·단위 변환
 *       ({@code RiskManagementService.toExitRuleConfig})을 적용한다. 🔴 원값 6개를 모두 주지 않으면 실행하지 않는다
 *       (값이 NULL 이면 문자열 {@code NULL} 로 준다 — 빠뜨린 것과 구분한다)</li>
 *   <li>최대 보유 24시간, BTC 시장 가드: 같은 구간 KRW-BTC H1 을 코인마다 그 코인의 첫·끝 봉 구간으로 잘라 넣는다
 *       (서버는 같은 start~end 로 BTC 를 조회한다). 🔴 BTC 파일이 없거나 코인 구간을 덮지 못하면 실행하지 않는다</li>
 * </ul>
 * <p>판정은 두 열로 낸다. 코드 게이트(표본 하한 5건)와 사전 등록(OOS ≥ 20 · 기대값 > 0 · OVERFITTING 아님).
 * 코드가 PASS 여도 사전 등록 미충족일 수 있다. 병합 판정은 내지 않는다(코인별).</p>
 *
 * <pre>
 * ./gradlew :core-engine:test --tests '*BWalkForwardRunner' \
 *   -Dbwf.dir=d:/tmp/stval -Dbwf.btc=d:/tmp/stval_btc/btc_h1.csv -Dbwf.out=d:/tmp/bwf_out \
 *   -Dbwf.sl=… -Dbwf.tpMult=… -Dbwf.trailing=… -Dbwf.trailTpPct=… -Dbwf.trailSlPct=… -Dbwf.investPct=…
 * </pre>
 */
class BWalkForwardRunner {

    private static final String STRATEGY = "COMPOSITE_MTF_MOMENTUM";
    private static final double IN_SAMPLE_RATIO = 0.7;
    private static final int WINDOWS = 5;
    private static final int MAX_HOLD_HOURS = 24;
    private static final int PREREG_MIN_OOS_TRADES = 20;

    /** B_VALIDATION_PREREG.md §2 의 21코인 — 개수가 아니라 이 목록과 정확히 같아야 실행한다(리뷰 4차). */
    static final List<String> COINS = List.of(
            "KRW-ANKR", "KRW-BCH", "KRW-CRO", "KRW-CVC", "KRW-DKA", "KRW-ELF", "KRW-G", "KRW-GLM", "KRW-HIVE",
            "KRW-INJ", "KRW-IOTA", "KRW-JUP", "KRW-NEAR", "KRW-ONG", "KRW-POLYX", "KRW-POWR", "KRW-PUNDIX",
            "KRW-SC", "KRW-T", "KRW-WAVES", "KRW-WAXP");

    static String coinOf(Path csv) {
        return "KRW-" + csv.getFileName().toString().substring(3).replace("_h1.csv", "").toUpperCase();
    }

    /**
     * 폴더에서 21코인 CSV 를 고른다. BLAST 는 빼고, 나머지 코인 집합이 {@link #COINS} 와 정확히 같아야 한다.
     * 빠진 코인·목록 밖 코인이 하나라도 있으면 실행하지 않는다 — 개수만 보면 한 코인이 다른 코인으로 바뀌어도 통과한다.
     */
    static List<Path> selectCoinCsvs(Path dir) throws java.io.IOException {
        List<Path> csvs;
        try (Stream<Path> s = Files.list(dir)) {
            csvs = s.filter(p -> p.getFileName().toString().matches("st_\\w+_h1\\.csv"))
                    .filter(p -> !p.getFileName().toString().equals("st_blast_h1.csv"))
                    .sorted().toList();
        }
        java.util.Set<String> found = new java.util.TreeSet<>(csvs.stream().map(BWalkForwardRunner::coinOf).toList());
        java.util.Set<String> missing = new java.util.TreeSet<>(COINS);
        missing.removeAll(found);
        java.util.Set<String> extra = new java.util.TreeSet<>(found);
        extra.removeAll(COINS);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IllegalStateException("🔴 코인 집합이 사전 등록 21코인과 다르다 — 빠짐 " + missing + " · 목록 밖 " + extra);
        }
        return csvs;
    }

    /** 코인 집합 검사 — 정확한 21개는 통과, 한 코인을 다른 코인으로 바꾼 21개와 빠진 20개는 거절. */
    @Test
    void coinSetMustMatchPreregExactly(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path ok = Files.createDirectories(tmp.resolve("ok"));
        for (String c : COINS) {
            Files.writeString(ok.resolve("st_" + c.substring(4).toLowerCase() + "_h1.csv"), "");
        }
        Files.writeString(ok.resolve("st_blast_h1.csv"), "");             // BLAST 는 있어도 뺀다
        assertThat(selectCoinCsvs(ok)).hasSize(21);

        Path swapped = Files.createDirectories(tmp.resolve("swapped"));
        for (String c : COINS) {
            String name = c.equals("KRW-NEAR") ? "btc" : c.substring(4).toLowerCase();   // NEAR 대신 BTC — 개수는 21
            Files.writeString(swapped.resolve("st_" + name + "_h1.csv"), "");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> selectCoinCsvs(swapped))
                .hasMessageContaining("빠짐 [KRW-NEAR]").hasMessageContaining("목록 밖 [KRW-BTC]");

        Path short20 = Files.createDirectories(tmp.resolve("short"));
        for (String c : COINS.subList(1, 21)) {
            Files.writeString(short20.resolve("st_" + c.substring(4).toLowerCase() + "_h1.csv"), "");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> selectCoinCsvs(short20))
                .hasMessageContaining("빠짐 [KRW-ANKR]");
    }

    /** 운영 원값 → 서버와 같은 변환. "NULL" 이면 서버의 NULL 기본값. */
    static ExitRuleConfig exitRuleFromRaw(Map<String, String> raw) {
        BigDecimal pct100 = BigDecimal.valueOf(100);
        BigDecimal sl = dec(raw, "sl");
        BigDecimal tp = dec(raw, "tpMult");
        String tr = need(raw, "trailing");
        BigDecimal ttp = dec(raw, "trailTpPct");
        BigDecimal tsl = dec(raw, "trailSlPct");
        BigDecimal inv = dec(raw, "investPct");
        return ExitRuleConfig.builder()
                .stopLossPct(sl != null ? sl : new BigDecimal("5.0"))
                .takeProfitMultiplier(tp != null ? tp : new BigDecimal("2.0"))
                .trailingEnabled("NULL".equals(tr) ? Boolean.TRUE : parseBool(tr))
                .trailingTpMargin(ttp != null ? ttp.divide(pct100, 6, RoundingMode.HALF_UP) : new BigDecimal("0.005"))
                .trailingSlMargin(tsl != null ? tsl.divide(pct100, 6, RoundingMode.HALF_UP) : new BigDecimal("0.003"))
                .investRatio(inv != null ? inv.divide(pct100, 4, RoundingMode.HALF_UP) : new BigDecimal("0.25"))
                .build();
    }

    private static String need(Map<String, String> raw, String k) {
        String v = raw.get(k);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("🔴 청산 규칙 원값 없음: bwf." + k + " — 운영 조회 출력을 확인하고 넣는다(NULL 이면 'NULL')");
        }
        return v.trim();
    }

    private static BigDecimal dec(Map<String, String> raw, String k) {
        String v = need(raw, k);
        return "NULL".equals(v) ? null : new BigDecimal(v);
    }

    private static boolean parseBool(String v) {
        return switch (v) {
            case "t", "true" -> true;
            case "f", "false" -> false;
            default -> throw new IllegalStateException("trailing_enabled 값을 읽을 수 없다: " + v);
        };
    }

    /** 변환이 서버(RiskManagementService.toExitRuleConfig)와 같은지 — 원값·NULL·누락 세 경우. */
    @Test
    void exitRuleConversionMatchesServer() {
        ExitRuleConfig c = exitRuleFromRaw(Map.of("sl", "3.00", "tpMult", "1.50", "trailing", "f",
                "trailTpPct", "0.700", "trailSlPct", "0.400", "investPct", "30.00"));
        assertThat(c.getStopLossPct()).isEqualByComparingTo("3.00");
        assertThat(c.getTakeProfitMultiplier()).isEqualByComparingTo("1.50");
        assertThat(c.isTrailingEnabled()).isFalse();
        assertThat(c.getTrailingTpMargin()).isEqualByComparingTo("0.007");
        assertThat(c.getTrailingSlMargin()).isEqualByComparingTo("0.004");
        assertThat(c.getInvestRatio()).isEqualByComparingTo("0.30");

        ExitRuleConfig d = exitRuleFromRaw(Map.of("sl", "NULL", "tpMult", "NULL", "trailing", "NULL",
                "trailTpPct", "NULL", "trailSlPct", "NULL", "investPct", "NULL"));
        assertThat(d.getStopLossPct()).isEqualByComparingTo("5.0");
        assertThat(d.getTakeProfitMultiplier()).isEqualByComparingTo("2.0");
        assertThat(d.isTrailingEnabled()).isTrue();
        assertThat(d.getTrailingTpMargin()).isEqualByComparingTo("0.005");
        assertThat(d.getTrailingSlMargin()).isEqualByComparingTo("0.003");
        assertThat(d.getInvestRatio()).isEqualByComparingTo("0.25");   // ExitRuleConfig 기본 0.80 이 아니라 서버 NULL 기본 0.25

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> exitRuleFromRaw(Map.of("sl", "3.00")))
                .hasMessageContaining("원값 없음");
    }

    @Test
    @EnabledIfSystemProperty(named = "bwf.dir", matches = ".+")
    void run() throws Exception {
        Path dir = Path.of(System.getProperty("bwf.dir"));
        Path btcPath = Path.of(System.getProperty("bwf.btc", ""));
        Path out = Path.of(System.getProperty("bwf.out", "d:/tmp/bwf_out"));
        Map<String, String> raw = new java.util.HashMap<>();
        for (String k : List.of("sl", "tpMult", "trailing", "trailTpPct", "trailSlPct", "investPct")) {
            raw.put(k, System.getProperty("bwf." + k));
        }
        ExitRuleConfig exit = exitRuleFromRaw(raw);
        assertThat(Files.isRegularFile(btcPath)).as("🔴 BTC 파일 없음 — BTC 가드를 끈 채 실행하지 않는다").isTrue();
        List<Candle> btc = loadCsv(btcPath.toString());
        Files.createDirectories(out);

        List<Path> csvs = selectCoinCsvs(dir);

        List<String> rows = new ArrayList<>();
        rows.add("coin,candles,start,end,btcCandles,oosTrades,oosExpectancyPct,oosTotalReturnPct,overfittingScore,verdict,"
                + "codeGate,preregGate,preregReason");
        System.out.printf("%n청산 규칙(변환 후): SL %s%% · TP 배수 %s · 추적 %s · 추적 TP %s · 추적 SL %s · 투자 비율 %s · 최대 보유 %dh%n",
                exit.getStopLossPct(), exit.getTakeProfitMultiplier(), exit.isTrailingEnabled(),
                exit.getTrailingTpMargin(), exit.getTrailingSlMargin(), exit.getInvestRatio(), MAX_HOLD_HOURS);
        int codePass = 0, preregPass = 0;
        for (Path csv : csvs) {
            String coin = coinOf(csv);
            List<Candle> candles = loadCsv(csv.toString());
            var first = candles.get(0).getTime();
            var last = candles.get(candles.size() - 1).getTime();
            List<Candle> btcSlice = btc.stream()
                    .filter(c -> !c.getTime().isBefore(first) && !c.getTime().isAfter(last)).toList();
            // BTC 파일이 이 코인 구간 전체를 덮는가 — 구간 안의 빠진 시간은 서버 조회와 똑같이 그대로 둔다
            assertThat(btc.get(0).getTime()).as("🔴 %s: BTC 가 코인 시작 전부터 있지 않다", coin).isBeforeOrEqualTo(first);
            assertThat(btc.get(btc.size() - 1).getTime()).as("🔴 %s: BTC 가 코인 끝까지 있지 않다", coin).isAfterOrEqualTo(last);
            assertThat(btcSlice).as("🔴 %s 구간의 BTC 캔들 없음", coin).isNotEmpty();

            BacktestConfig config = BacktestConfig.builder()
                    .strategyName(STRATEGY).coinPair(coin).timeframe("H1")
                    .startDate(first).endDate(last)
                    .initialCapital(new BigDecimal("10000000"))
                    .slippagePct(new BigDecimal("0.1")).feePct(new BigDecimal("0.05"))
                    .strategyParams(Map.of())
                    .exitRuleConfig(exit)
                    .maxHoldHours(MAX_HOLD_HOURS)
                    .btcCandles(btcSlice)
                    .build();
            WalkForwardTestRunner.WalkForwardResult r =
                    new WalkForwardTestRunner().run(config, candles, IN_SAMPLE_RATIO, WINDOWS);
            PerformanceReport agg = r.getAggregatedOutSampleMetrics();
            int n = agg != null ? agg.getTotalTrades() : 0;
            BigDecimal exp = agg != null ? agg.getExpectancyPct() : null;
            boolean expPos = exp != null && exp.signum() > 0;
            String v = r.getVerdict();
            // 코드 게이트(WalkForwardValidationGate.decide) 와 같은 순서·하한
            boolean code = v != null && !"INSUFFICIENT_DATA".equals(v) && !"OVERFITTING".equals(v)
                    && !"HOLD_OUT_FAILED".equals(v) && n >= 5 && expPos;
            String why = n < PREREG_MIN_OOS_TRADES ? "OOS<20"
                    : !expPos ? "기대값≤0"
                    : "OVERFITTING".equals(v) ? "OVERFITTING"
                    : "INSUFFICIENT_DATA".equals(v) ? "과최적화 판정 불가" : "";   // 수익성이 나쁘다는 판정과 구분(리뷰 4차)
            boolean prereg = why.isEmpty();
            codePass += code ? 1 : 0;
            preregPass += prereg ? 1 : 0;
            rows.add(String.join(",", coin, String.valueOf(candles.size()), first.toString(), last.toString(),
                    String.valueOf(btcSlice.size()), String.valueOf(n), exp == null ? "" : exp.toPlainString(),
                    agg == null ? "" : agg.getTotalReturnPct().toPlainString(),
                    r.getOverfittingScore() == null ? "" : r.getOverfittingScore().toPlainString(), v,
                    code ? "PASS" : "FAIL", prereg ? "충족" : "미충족", why));
            System.out.printf("  %-11s OOS %4d  기대값 %9s%%  verdict %-17s 코드 %-4s 사전등록 %s%n", coin, n,
                    exp == null ? "-" : exp.setScale(4, RoundingMode.HALF_UP).toPlainString(), v,
                    code ? "PASS" : "FAIL", prereg ? "충족" : "미충족 — " + why);
        }
        Files.write(out.resolve("bwf_result.csv"), rows, StandardCharsets.UTF_8);
        System.out.printf("%n코드 게이트 PASS %d/21 · 사전 등록 충족 %d/21 (코인별, 병합 판정 없음)%n", codePass, preregPass);
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
