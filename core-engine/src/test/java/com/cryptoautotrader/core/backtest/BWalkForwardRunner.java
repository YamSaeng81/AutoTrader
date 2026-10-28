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
 * <p><b>전략 목록 (2026-10-28 리뷰 12차 P2, {@code NEXT_CANDIDATE_PLAN.md} §2-2·§3-1)</b>:
 * {@code bwf.strategies} 를 주지 않으면 B 와 같은 단일 전략({@value #STRATEGY})으로 돈다.
 * {@code POOL23} 이나 쉼표 목록을 주면 그 목록이 선별 풀 23개({@link #SELECTION_POOL})와 정확히 같을 때만 돈다 —
 * 빠짐·목록 밖·중복이 하나라도 있으면 실행하지 않는다(코인 집합과 같은 규칙). 결과는 전략 × 코인 행이고,
 * 전략별 요약에 사전 등록 충족 코인 수와 후보 적격(≥ {@value #ELIGIBLE_MIN_COINS}/21)을 적는다.
 * 결과 CSV 에 {@code scoreMethod}·{@code overfittingScore}·{@code rawOverfittingScore} 를 함께 남긴다.</p>
 *
 * <pre>
 * ./gradlew :core-engine:test --tests '*BWalkForwardRunner' \
 *   -Dbwf.dir=d:/tmp/stval -Dbwf.btc=d:/tmp/stval_btc/btc_h1.csv -Dbwf.out=d:/tmp/bwf_out \
 *   -Dbwf.sl=… -Dbwf.tpMult=… -Dbwf.trailing=… -Dbwf.trailTpPct=… -Dbwf.trailSlPct=… -Dbwf.investPct=… \
 *   [-Dbwf.strategies=POOL23]
 * </pre>
 */
class BWalkForwardRunner {

    private static final String STRATEGY = "COMPOSITE_MTF_MOMENTUM";
    private static final double IN_SAMPLE_RATIO = 0.7;
    private static final int WINDOWS = 5;
    private static final int MAX_HOLD_HOURS = 24;
    private static final int PREREG_MIN_OOS_TRADES = 20;
    /** 후보 적격 — 사전 등록 충족 코인 ≥ 11 / 21 (NEXT_CANDIDATE_PLAN.md §3-1, 리뷰 11차 D5). */
    static final int ELIGIBLE_MIN_COINS = 11;
    static final String POOL_KEYWORD = "POOL23";

    /** NEXT_CANDIDATE_PLAN.md §2-2 선별 풀 23개 (리뷰 12차 G1). 출력 순서도 이 순서다. */
    static final List<String> SELECTION_POOL = List.of(
            "ATR_BREAKOUT", "BOLLINGER", "EMA_CROSS", "FAIR_VALUE_GAP", "GRID", "HEIKIN_ASHI_STOCH", "MACD",
            "MACD_STOCH_BB", "RSI", "STOCHASTIC_RSI", "SUPERTREND", "VOLUME_DELTA", "VWAP",
            "COMPOSITE_REGIME_ROUTER", "COMPOSITE_MOMENTUM", "COMPOSITE_ETH", "COMPOSITE_BREAKOUT",
            "COMPOSITE_MOMENTUM_ICHIMOKU", "COMPOSITE_BREAKOUT_ICHIMOKU", "COMPOSITE_MTF_CONFIRMED",
            "COMPOSITE_MTF_BTC", "COMPOSITE_PULLBACK_MTF", "COMPOSITE_MEANREV_BB");

    /** §2-2 제외 규칙 — 등록 전략 30개 = 풀 23 + 제외 7. 성과를 보고 뺀 것이 아니다. */
    static final Map<String, String> EXCLUDED = Map.of(
            "TEST_TIMED", "시험용",
            "ORDERBOOK_IMBALANCE", "백테스트 ≠ 운영(실호가)",
            "COMPOSITE_MOMENTUM_ICHIMOKU_V2", "66세션·B 와 같은 계열",
            "COMPOSITE_MTF_MOMENTUM", "66세션·B 와 같은 계열",
            "COMPOSITE_MTF_MOMENTUM_CLOSED", "66세션·B 와 같은 계열",
            "COMPOSITE_MTF_BTC_STRICT", "폐기(DEPRECATED)",
            "COMPOSITE", "운영 입력 재현 불가(동적 가중치)");

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
        // 결과 행 순서를 사전 등록 목록 순서로 고정한다(리뷰 12차 P2). 이전에는 Path 정렬이었는데 Windows 는
        // 대소문자를 무시해 대문자로 비교하므로 st_glm 이 st_g_ 보다 앞에 와('L' < '_') 운영체제마다 순서가 달랐다.
        return csvs.stream().sorted(java.util.Comparator.comparingInt(p -> COINS.indexOf(coinOf(p)))).toList();
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
        assertThat(selectCoinCsvs(ok).stream().map(BWalkForwardRunner::coinOf).toList())
                .as("순서는 사전 등록 목록 순서 — KRW-G 가 KRW-GLM 앞").isEqualTo(COINS);

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

    /**
     * 실행할 전략 목록. 비었으면 B 단일 전략. {@value #POOL_KEYWORD} 나 쉼표 목록이면 선별 풀 23개와 정확히 같아야 하고,
     * 출력은 풀 순서로 고정한다. 개수만 보면 한 전략이 다른 전략으로 바뀌어도 통과하므로 집합을 비교하고, 중복도 거절한다.
     */
    static List<String> selectStrategies(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of(STRATEGY);
        }
        if (POOL_KEYWORD.equals(spec.trim())) {
            return SELECTION_POOL;
        }
        List<String> given = java.util.Arrays.stream(spec.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> dup = new java.util.TreeSet<>();
        for (String g : given) {
            if (!seen.add(g)) dup.add(g);
        }
        java.util.Set<String> missing = new java.util.TreeSet<>(SELECTION_POOL);
        missing.removeAll(seen);
        java.util.Set<String> extra = new java.util.TreeSet<>(seen);
        SELECTION_POOL.forEach(extra::remove);
        if (!missing.isEmpty() || !extra.isEmpty() || !dup.isEmpty()) {
            throw new IllegalStateException("🔴 전략 목록이 선별 풀 23개와 다르다 — 빠짐 " + missing + " · 목록 밖 " + extra
                    + " · 중복 " + dup);
        }
        return SELECTION_POOL;
    }

    /** 전략 목록 검사 — 비면 B, 정확한 23개(순서 무관)는 풀 순서, 바꿔치기·빠짐·중복은 거절. */
    @Test
    void strategyListMustMatchPoolExactly() {
        assertThat(selectStrategies(null)).containsExactly(STRATEGY);
        assertThat(selectStrategies(" ")).containsExactly(STRATEGY);
        assertThat(selectStrategies(POOL_KEYWORD)).isEqualTo(SELECTION_POOL).hasSize(23).doesNotHaveDuplicates();

        List<String> reversed = new ArrayList<>(SELECTION_POOL);
        java.util.Collections.reverse(reversed);
        assertThat(selectStrategies(String.join(", ", reversed))).isEqualTo(SELECTION_POOL);

        List<String> swapped = new ArrayList<>(SELECTION_POOL);
        swapped.set(swapped.indexOf("RSI"), "COMPOSITE");            // 개수는 23
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> selectStrategies(String.join(",", swapped)))
                .hasMessageContaining("빠짐 [RSI]").hasMessageContaining("목록 밖 [COMPOSITE]");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> selectStrategies(String.join(",", SELECTION_POOL.subList(1, 23))))
                .hasMessageContaining("빠짐 [ATR_BREAKOUT]");

        List<String> withDup = new ArrayList<>(SELECTION_POOL);
        withDup.add("GRID");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> selectStrategies(String.join(",", withDup)))
                .hasMessageContaining("중복 [GRID]");

        // B 단일 전략을 목록으로 주는 것도 거절한다 — B 는 목록 없이만 돈다
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> selectStrategies(STRATEGY))
                .hasMessageContaining("목록 밖 [" + STRATEGY + "]");
    }

    /**
     * Gradle 명령 → 시험 JVM → 전략 선택 연결 확인 (리뷰 14차). 실제 자료를 읽지 않는다.
     * {@code selectStrategies} 를 직접 부르는 시험은 build.gradle 이 {@code bwf.strategies} 를 시험 JVM 에 넘기지 않는
     * 누락을 잡지 못했다 — 그대로 실행하면 483 조합 대신 B 단일 전략이 돈다. 이 시험은 시험 JVM 이 실제로 받은 값으로 고른다.
     * <pre>
     * ./gradlew :core-engine:test --tests '*BWalkForwardRunner.strategySpecReachesTestJvm' \
     *   -Dbwf.dir=none -Dbwf.strategies=POOL23 -Dbwf.expectStrategyCount=23
     * </pre>
     */
    @Test
    @EnabledIfSystemProperty(named = "bwf.expectStrategyCount", matches = ".+")
    void strategySpecReachesTestJvm() {
        String spec = System.getProperty("bwf.strategies");
        int expect = Integer.parseInt(System.getProperty("bwf.expectStrategyCount"));
        List<String> selected = selectStrategies(spec);
        System.out.printf("시험 JVM 이 받은 bwf.strategies = %s → 전략 %d개%n", spec, selected.size());
        assertThat(selected).as("시험 JVM 이 받은 bwf.strategies = %s", spec).hasSize(expect);
        if (expect == SELECTION_POOL.size()) {
            assertThat(selected).isEqualTo(SELECTION_POOL);
        } else {
            assertThat(selected).containsExactly(STRATEGY);
        }
    }

    /**
     * 풀이 코드 등록 목록에서 나오는지 — 운영 코드에 등록된 전략 = 풀 23 + 제외 7, 서로 겹치지 않는다.
     * 같은 JVM 의 다른 시험이 등록한 시험용 전략(시험·러너 클래스 안의 클래스)은 뺀다.
     */
    @Test
    void poolEqualsRegisteredMinusExclusions() {
        com.cryptoautotrader.core.selector.CompositePresets.ensureRegistered();
        java.util.Set<String> registered = new java.util.TreeSet<>();
        com.cryptoautotrader.strategy.StrategyRegistry.getAll().forEach((name, st) -> {
            String top = st.getClass().getName().split("\\$")[0];
            if (!top.endsWith("Test") && !top.endsWith("Runner")) registered.add(name);
        });
        assertThat(registered).hasSize(30);
        assertThat(SELECTION_POOL).doesNotHaveDuplicates().doesNotContainAnyElementsOf(EXCLUDED.keySet());
        java.util.Set<String> expected = new java.util.TreeSet<>(SELECTION_POOL);
        expected.addAll(EXCLUDED.keySet());
        assertThat(registered).isEqualTo(expected);
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
        List<String> strategies = selectStrategies(System.getProperty("bwf.strategies"));
        System.out.printf("%n전략 목록: bwf.strategies = %s → %d개%n", System.getProperty("bwf.strategies"), strategies.size());
        Map<String, String> raw = new java.util.HashMap<>();
        for (String k : List.of("sl", "tpMult", "trailing", "trailTpPct", "trailSlPct", "investPct")) {
            raw.put(k, System.getProperty("bwf." + k));
        }
        ExitRuleConfig exit = exitRuleFromRaw(raw);
        assertThat(Files.isRegularFile(btcPath)).as("🔴 BTC 파일 없음 — BTC 가드를 끈 채 실행하지 않는다").isTrue();
        List<Candle> btc = loadCsv(btcPath.toString());
        Files.createDirectories(out);

        List<Path> csvs = selectCoinCsvs(dir);
        System.out.printf("%n청산 규칙(변환 후): SL %s%% · TP 배수 %s · 추적 %s · 추적 TP %s · 추적 SL %s · 투자 비율 %s · 최대 보유 %dh%n",
                exit.getStopLossPct(), exit.getTakeProfitMultiplier(), exit.isTrailingEnabled(),
                exit.getTrailingTpMargin(), exit.getTrailingSlMargin(), exit.getInvestRatio(), MAX_HOLD_HOURS);
        Result res = runAll(strategies, csvs, btc, exit);
        boolean single = strategies.size() == 1;
        Files.write(out.resolve(single ? "bwf_result.csv" : "selection_result.csv"), res.rows(), StandardCharsets.UTF_8);
        if (!single) {
            Files.write(out.resolve("selection_summary.csv"), res.summary(), StandardCharsets.UTF_8);
        }
    }

    /** 전략 × 코인 결과 행(머리 포함)과 전략별 요약 행(머리 포함). */
    record Result(List<String> rows, List<String> summary) { }

    /**
     * 전략 순서 × 코인(파일 이름) 순서로 WF 를 돌린다. 전략·코인 검사는 호출 전에 끝나 있어야 한다.
     * 판정·열은 B 실행과 같고, 전략 열과 {@code scoreMethod}·{@code rawOverfittingScore} 열을 더했다(리뷰 12차 P2).
     */
    static Result runAll(List<String> strategies, List<Path> csvs, List<Candle> btc, ExitRuleConfig exit) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("strategy,coin,candles,start,end,btcCandles,oosTrades,oosExpectancyPct,oosTotalReturnPct,"
                + "scoreMethod,overfittingScore,rawOverfittingScore,verdict,codeGate,preregGate,preregReason");
        List<String> summary = new ArrayList<>();
        summary.add("strategy,coins,codePass,preregPass,eligible");
        List<List<Candle>> loaded = new ArrayList<>();
        for (Path csv : csvs) {
            loaded.add(loadCsv(csv.toString()));
        }
        for (String strategy : strategies) {
            System.out.printf("%n[%s]%n", strategy);
            int codePass = 0, preregPass = 0;
            for (int k = 0; k < csvs.size(); k++) {
                String coin = coinOf(csvs.get(k));
                List<Candle> candles = loaded.get(k);
                var first = candles.get(0).getTime();
                var last = candles.get(candles.size() - 1).getTime();
                List<Candle> btcSlice = btc.stream()
                        .filter(c -> !c.getTime().isBefore(first) && !c.getTime().isAfter(last)).toList();
                // BTC 파일이 이 코인 구간 전체를 덮는가 — 구간 안의 빠진 시간은 서버 조회와 똑같이 그대로 둔다
                assertThat(btc.get(0).getTime()).as("🔴 %s: BTC 가 코인 시작 전부터 있지 않다", coin).isBeforeOrEqualTo(first);
                assertThat(btc.get(btc.size() - 1).getTime()).as("🔴 %s: BTC 가 코인 끝까지 있지 않다", coin).isAfterOrEqualTo(last);
                assertThat(btcSlice).as("🔴 %s 구간의 BTC 캔들 없음", coin).isNotEmpty();

                WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                        .run(config(strategy, coin, candles, btcSlice, exit), candles, IN_SAMPLE_RATIO, WINDOWS);
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
                rows.add(String.join(",", strategy, coin, String.valueOf(candles.size()), first.toString(), last.toString(),
                        String.valueOf(btcSlice.size()), String.valueOf(n), exp == null ? "" : exp.toPlainString(),
                        agg == null ? "" : agg.getTotalReturnPct().toPlainString(), r.getScoreMethod(),
                        r.getOverfittingScore() == null ? "" : r.getOverfittingScore().toPlainString(),
                        r.getRawOverfittingScore() == null ? "" : r.getRawOverfittingScore().toPlainString(), v,
                        code ? "PASS" : "FAIL", prereg ? "충족" : "미충족", why));
                System.out.printf("  %-11s OOS %4d  기대값 %9s%%  verdict %-17s 코드 %-4s 사전등록 %s%n", coin, n,
                        exp == null ? "-" : exp.setScale(4, RoundingMode.HALF_UP).toPlainString(), v,
                        code ? "PASS" : "FAIL", prereg ? "충족" : "미충족 — " + why);
            }
            boolean eligible = preregPass >= ELIGIBLE_MIN_COINS;
            summary.add(String.join(",", strategy, String.valueOf(csvs.size()), String.valueOf(codePass),
                    String.valueOf(preregPass), eligible ? "적격" : "미적격"));
            System.out.printf("코드 게이트 PASS %d/%d · 사전 등록 충족 %d/%d · 후보 %s (코인별, 병합 판정 없음)%n",
                    codePass, csvs.size(), preregPass, csvs.size(), eligible ? "적격" : "미적격");
        }
        return new Result(rows, summary);
    }

    static BacktestConfig config(String strategy, String coin, List<Candle> candles, List<Candle> btcSlice, ExitRuleConfig exit) {
        return BacktestConfig.builder()
                .strategyName(strategy).coinPair(coin).timeframe("H1")
                .startDate(candles.get(0).getTime()).endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.1")).feePct(new BigDecimal("0.05"))
                .strategyParams(Map.of())
                .exitRuleConfig(exit)
                .maxHoldHours(MAX_HOLD_HOURS)
                .btcCandles(btcSlice)
                .build();
    }

    static List<Candle> loadCsv(String path) throws Exception {
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
