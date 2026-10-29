package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.backtest.TsMomentumCalculator.ArmVerdict;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Bar;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Costs;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Gate;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Maintenance;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Params;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Result;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BTC·ETH 주간 시계열 모멘텀 — 개발 자료 1회 실행 러너 (제안서 v3.4 §4·§7 3단계, 리뷰 40차 승인).
 *
 * <p>🔴 실제 자료 실행은 <b>1회</b>다. 결과를 보고 기간·문턱·관문·룩백을 바꾸지 않는다. 버그를 고쳐 다시 돌리면 같은 규칙으로만,
 * 입력 해시와 사유를 기록한다(제안서 §4). 실행 전에 다음을 확인하고 하나라도 어긋나면 실행하지 않는다.</p>
 * <ul>
 *   <li>작업 트리에 커밋되지 않은 추적 파일 변경이 없다 — 실행 코드 SHA 를 결과에 묶기 위해서다</li>
 *   <li>고정 입력({@code scripts/tsmom/tsmom_devrun_inputs.sha256})과 근거 원본 389개(MANIFEST)의 SHA-256 이 바이트 그대로 같다.
 *       줄바꿈 변환으로 달라진 해시도 받아들이지 않는다(리뷰 40차 ②)</li>
 *   <li>고정 점검 예외가 계산기에 실제로 전달됐다 — 기본 생성자의 "예외 없음" 경로를 쓰지 않는다(리뷰 40차). 코인마다 예외 칸 40</li>
 *   <li>출력 폴더가 비어 있다 — 같은 폴더에 두 번 쓰지 않는다</li>
 * </ul>
 * <pre>
 * ./gradlew :core-engine:cleanTest :core-engine:test --tests '*TsMomentumDevRunner.run' -i -Dtsm.mode=run -Dtsm.out=&lt;출력 폴더&gt;
 * </pre>
 * 출력: {@code inputs.txt}(코드 SHA·입력 해시), {@code summary.csv}, {@code weekly.csv}, {@code trades.csv}, {@code hits.csv},
 * {@code diagnostics.txt}(관문 차단 주·실제 배치 상한, 판정에 쓰지 않음), {@code verdict.txt}.
 */
class TsMomentumDevRunner {

    static final Instant S0 = Instant.parse("2023-12-31T15:00:00Z"), E0 = Instant.parse("2026-09-22T00:00:00Z");
    static final Map<String, Path> PRICES = new TreeMap<>(Map.of(
            "KRW-BTC", Path.of("d:/tmp/stval_btc/btc_h1.csv"),
            "KRW-ETH", Path.of("d:/tmp/stval_eth/eth_h1.csv")));
    static final Path EVIDENCE = Path.of("d:/tmp/stval_maint");
    static final String FROZEN = "scripts/tsmom/tsmom_devrun_inputs.sha256";
    static final String WINDOWS = "scripts/tsmom/maintenance_windows_tsmom.csv";
    static final String RECHECK = "scripts/tsmom/maintenance_recheck_summary.json";
    static final String MANIFEST = "scripts/tsmom/maintenance_evidence_manifest.txt";
    /** 제안서 §6-2 ⑩·⑪ 에서 시각 열만으로 고정한 값 — 실행 전 대조용이다. */
    static final int ROWS = 23_848, EXEMPT_SLOTS = 40;
    static final List<Costs> ARMS = List.of(Costs.MAIN, Costs.STRESS);

    /**
     * Gradle 명령 → 시험 JVM 전달 확인(리뷰 14차 교훈). 실제 자료를 읽지 않는다.
     * <pre>./gradlew :core-engine:test --tests '*TsMomentumDevRunner.optionsReachTestJvm' -Dtsm.mode=wire -Dtsm.out=wired</pre>
     */
    @Test
    @EnabledIfSystemProperty(named = "tsm.mode", matches = "wire")
    void optionsReachTestJvm() {
        assertThat(System.getProperty("tsm.out")).isEqualTo("wired");
    }

    @Test
    @EnabledIfSystemProperty(named = "tsm.mode", matches = "run")
    void run() throws Exception {
        String out = System.getProperty("tsm.out");
        if (out == null || out.isBlank()) throw new IllegalStateException("-Dtsm.out 이 없다");
        Path root = Path.of(git(Path.of("."), "rev-parse", "--show-toplevel"));
        System.out.println(String.join("\n", runAll(root, Path.of(out))));
    }

    // ── 실행 전 확인 ──────────────────────────────────────────────────

    static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    /** sha256sum 형식({@code <해시> *<경로>}) 목록을 바이트 그대로 대조한다. 상대 경로는 base 기준. 어긋나면 예외. */
    static List<String> verify(Path base, Path list) throws Exception {
        List<String> ok = new ArrayList<>();
        for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String want = line.substring(0, 64), rel = line.substring(66);
            Path p = Path.of(rel).isAbsolute() ? Path.of(rel) : base.resolve(rel);
            if (!Files.exists(p)) throw new IllegalStateException("고정 입력 없음: " + rel);
            String got = sha256(p);
            if (!got.equals(want)) throw new IllegalStateException("해시 불일치: " + rel + " 고정 " + want + " · 실제 " + got);
            ok.add(want + "  " + rel);
        }
        if (ok.isEmpty()) throw new IllegalStateException("대조할 해시가 없다: " + list);
        return ok;
    }

    static String git(Path root, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", root.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) throw new IllegalStateException("git 실패: " + String.join(" ", args) + " → " + s);
        return s;
    }

    /** 고정 구간 [S0, E0) 의 봉. 빈 시간에 봉을 만들지 않는다. */
    static List<Bar> load(Path csv) throws IOException {
        List<Bar> out = new ArrayList<>();
        for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] c = line.split(",");
            Instant t = Instant.parse(c[0] + "Z");
            if (t.isBefore(S0) || !t.isBefore(E0)) continue;
            out.add(new Bar(t, new BigDecimal(c[1]), new BigDecimal(c[2]), new BigDecimal(c[3]), new BigDecimal(c[4]), new BigDecimal(c[5])));
        }
        return out;
    }

    /**
     * 고정 점검 예외를 읽어 계산기를 만든다 — 실행은 이 경로만 쓴다(리뷰 40차: 기본 생성자의 예외 없음 경로 금지).
     * 읽은 결과가 비면 {@link Maintenance#load} 가 예외를 던지고, 여기서 한 번 더 확인한다.
     */
    static TsMomentumCalculator calculator(Map<String, List<Bar>> data, Path windowsCsv, Path recheckSummary) throws IOException {
        var calc = new TsMomentumCalculator(data, Maintenance.load(windowsCsv, recheckSummary));
        if (calc.maintenance() == Maintenance.NONE || calc.maintenance().windows().isEmpty())
            throw new IllegalStateException("점검 예외가 계산기에 전달되지 않았다");
        return calc;
    }

    /** 자료 [S0, E0) 의 빈 칸 중 E0 기준으로 예외가 되는 칸 수. */
    static int exemptSlots(TsMomentumCalculator calc, String coin, List<Bar> bars) {
        var have = new java.util.HashSet<Instant>();
        bars.forEach(b -> have.add(b.start()));
        int n = 0;
        for (Instant t = S0; t.isBefore(E0); t = t.plus(TsMomentumCalculator.HOUR)) {
            if (!have.contains(t) && calc.maintenance().exempt(coin, t, E0)) n++;
        }
        return n;
    }

    // ── 실행 ─────────────────────────────────────────────────────────

    static List<String> runAll(Path root, Path out) throws Exception {
        if (Files.exists(out)) {
            try (var l = Files.list(out)) {
                if (l.findAny().isPresent()) throw new IllegalStateException("출력 폴더가 비어 있지 않다: " + out);
            }
        }
        String dirty = git(root, "status", "--porcelain", "--untracked-files=no");
        if (!dirty.isEmpty()) throw new IllegalStateException("커밋되지 않은 변경이 있다 — 코드 SHA 를 묶을 수 없다:\n" + dirty);
        String sha = git(root, "rev-parse", "HEAD");
        List<String> inputs = new ArrayList<>(List.of("실행 시작(UTC) " + Instant.now(), "코드 SHA " + sha + " (작업 트리 깨끗함)",
                "java " + System.getProperty("java.version"), "고정 입력 대조(" + FROZEN + "):"));
        inputs.addAll(verify(root, root.resolve(FROZEN)));
        int evidence = verify(EVIDENCE, root.resolve(MANIFEST)).size();
        inputs.add("근거 원본 " + EVIDENCE + " " + evidence + "개 MANIFEST 대조 일치");

        Map<String, List<Bar>> data = new LinkedHashMap<>();
        for (var e : PRICES.entrySet()) {
            List<Bar> bars = load(e.getValue());
            if (bars.size() != ROWS) throw new IllegalStateException(e.getKey() + " 구간 안 행 " + bars.size() + " ≠ " + ROWS);
            data.put(e.getKey(), bars);
        }
        var calc = calculator(data, root.resolve(WINDOWS), root.resolve(RECHECK));
        for (var e : data.entrySet()) {
            int n = exemptSlots(calc, e.getKey(), e.getValue());
            if (n != EXEMPT_SLOTS) throw new IllegalStateException(e.getKey() + " 예외 칸 " + n + " ≠ " + EXEMPT_SLOTS);
            inputs.add(e.getKey() + ": 구간 안 행 " + e.getValue().size() + " · 점검 예외 칸 " + n + " (구간 " + calc.maintenance().windows().size() + "개)");
        }

        Params p = Params.fixed();
        Stage stage = Stage.fixed();
        Instant s = calc.firstStart(), e = calc.lastEnd();
        Files.createDirectories(out);
        List<String> summary = new ArrayList<>(List.of(
                "arm,mode,start,end,weeks,final_value,cum_return_pct,mdd_pct,trades,natural_rt_btc,natural_rt_eth,fees,status"));
        List<String> weekly = new ArrayList<>(List.of("arm,t,coin,r,action,gate_waits,value_before"));
        List<String> trades = new ArrayList<>(List.of("arm,mode,coin,side,bar_start,open,gross,fee,natural"));
        List<String> hits = new ArrayList<>(List.of("arm,kind,coin,at"));
        List<String> verdict = new ArrayList<>(List.of("구간(UTC) S " + s + " ~ E " + e, "코드 SHA " + sha));
        List<ArmVerdict> arms = new ArrayList<>();
        Result mainStrategy = null;
        for (Costs c : ARMS) {
            Result st = calc.run(c, p, stage, s, e);
            Result bk = calc.benchmark(c, p, s, e);
            if (c == Costs.MAIN) mainStrategy = st;
            for (var r : Map.of("strategy", st, "benchmark", bk).entrySet().stream().sorted(Map.Entry.comparingByKey(java.util.Comparator.reverseOrder())).toList()) {
                Result x = r.getValue();
                BigDecimal fees = x.trades().stream().map(TsMomentumCalculator.Trade::fee).reduce(BigDecimal.ZERO, BigDecimal::add);
                summary.add(String.join(",", c.name(), r.getKey(), s.toString(), e.toString(), String.valueOf(x.weeks().size()),
                        n(x.finalValue(), 0), pct(x.cumReturn()), pct(x.mdd()), String.valueOf(x.trades().size()),
                        String.valueOf(x.naturalRoundTrips("KRW-BTC")), String.valueOf(x.naturalRoundTrips("KRW-ETH")), n(fees, 0),
                        x.computable() ? "ok" : "uncomputable: " + x.uncomputable().replace(',', ';')));
                for (var t : x.trades()) {
                    trades.add(String.join(",", c.name(), r.getKey(), t.coin(), t.sell() ? "SELL" : "BUY", t.at().toString(),
                            t.open().toPlainString(), n(t.gross(), 2), n(t.fee(), 2), String.valueOf(t.natural())));
                }
            }
            for (var w : st.weeks()) {
                for (var cw : new TreeMap<>(w.coins()).entrySet()) {
                    weekly.add(String.join(",", c.name(), w.t().toString(), cw.getKey(),
                            cw.getValue().r() == null ? "NA" : cw.getValue().r().setScale(6, RoundingMode.HALF_UP).toPlainString(),
                            cw.getValue().action().replace(',', ';'), String.valueOf(cw.getValue().gateWaits()), n(w.valueBefore(), 0)));
                }
            }
            for (var h : st.hits()) hits.add(String.join(",", c.name(), h.kind(), h.coin() == null ? "-" : h.coin(), h.at().toString()));
            ArmVerdict v = TsMomentumCalculator.verdict(st, bk);
            arms.add(v);
            if (!v.computable()) {
                for (Result x : List.of(st, bk)) {
                    if (!x.computable()) verdict.add(c.name() + ": 계산 불가 — " + x.uncomputable());
                }
                continue;
            }
            verdict.add(c.name() + ": (가) 절대 " + ok(v.absolute()) + " (전략 " + pct(st.cumReturn()) + "%)"
                    + " · (나) 상대 " + ok(v.relative()) + " (비교 기준 " + pct(bk.cumReturn()) + "%, 차 " + pct(st.cumReturn().subtract(bk.cumReturn())) + "%p)"
                    + " · (다) 낙폭 " + ok(v.drawdown()) + " (전략 " + pct(st.mdd()) + "% · 비교 기준 " + pct(bk.mdd()) + "%)"
                    + " → " + (v.pass() ? "통과" : "탈락"));
        }
        verdict.add("개발 판정: " + switch (TsMomentumCalculator.overall(arms)) {
            case FAIL -> "탈락 — 제안 종료(§4 ⑭)";
            case UNDETERMINED -> "판정 불가·진행 불가(계산 불가 팔 있음, 가상 청산 없음)";
            case PASS -> "통과(두 팔 모두 세 조건 충족) — 개발 결과일 뿐 검증 결과가 아니다(§1)";
        });

        List<String> diag = diagnostics(calc, data, mainStrategy);
        inputs.add("실행 끝(UTC) " + Instant.now());
        Files.write(out.resolve("inputs.txt"), inputs, StandardCharsets.UTF_8);
        Files.write(out.resolve("summary.csv"), summary, StandardCharsets.UTF_8);
        Files.write(out.resolve("weekly.csv"), weekly, StandardCharsets.UTF_8);
        Files.write(out.resolve("trades.csv"), trades, StandardCharsets.UTF_8);
        Files.write(out.resolve("hits.csv"), hits, StandardCharsets.UTF_8);
        Files.write(out.resolve("diagnostics.txt"), diag, StandardCharsets.UTF_8);
        Files.write(out.resolve("verdict.txt"), verdict, StandardCharsets.UTF_8);
        List<String> all = new ArrayList<>(inputs);
        all.addAll(verdict);
        all.addAll(diag);
        return all;
    }

    /**
     * 판정에 쓰지 않는 진단. ① 주 비용 팔 전략에서 매수 창 전체가 막힌 주와 그 사유(봉마다 관문 결과) — BTC 최신성 차단 주는
     * 따로 센다(제안서 §4-2). ② 워크포워드 OOS 의 실제 배치 상한(리뷰 40차 ③) — 급락 판정까지 넣은 전체 관문으로 매수 창이
     * 통째로 막히는 주를 표시하고 현금·보유 동적 계획으로 구한다. 실제 워크포워드 결과가 아니다(§7 5단계, 별도 승인).
     */
    static List<String> diagnostics(TsMomentumCalculator calc, Map<String, List<Bar>> data, Result main) {
        List<String> out = new ArrayList<>(List.of("[진단 — 판정에 쓰지 않음]", "① 주 비용 팔 전략: 매수 창 전체가 관문에 막힌 주"));
        int btcUnknownWeeks = 0;
        for (var w : main.weeks()) {
            for (var cw : new TreeMap<>(w.coins()).entrySet()) {
                if (!cw.getValue().action().equals("BUY_BLOCKED")) continue;
                Map<Gate, Integer> why = new TreeMap<>();
                for (Bar b : data.get(cw.getKey()).stream().filter(b -> !b.start().isBefore(w.t()) && b.start().isBefore(w.t().plus(Duration.ofDays(1)))).toList()) {
                    why.merge(calc.gate(cw.getKey(), b.start()), 1, Integer::sum);
                }
                if (why.containsKey(Gate.BTC_UNKNOWN)) btcUnknownWeeks++;
                out.add("  " + w.t().toString().substring(0, 10) + " " + cw.getKey() + " 봉별 관문 " + why);
            }
            for (var cw : w.coins().entrySet()) {
                if (cw.getValue().action().equals("BUY") && cw.getValue().gateWaits() > 0)
                    out.add("  (지연) " + w.t().toString().substring(0, 10) + " " + cw.getKey() + " 매수 " + cw.getValue().gateWaits() + "봉 대기 뒤 체결");
            }
        }
        out.add("  BTC 최신성(BTC_UNKNOWN) 차단이 걸린 막힌 주: " + btcUnknownWeeks);
        out.add("② 워크포워드 OOS 실제 배치 상한(전체 관문·점검 예외 포함, 행 분할 윈도우 3·IS 0.7)");
        for (var e : data.entrySet()) {
            List<Instant> ts = e.getValue().stream().map(Bar::start).toList();
            var oos = TsMomentumMaintenanceRealTimesCheck.oosWeeks(calc, e.getKey(), ts);
            List<Integer> b = TsMomentumMaintenanceRealTimesCheck.bounds(oos);
            out.add("  " + e.getKey() + ": 판단 " + oos.stream().map(List::size).toList() + " · 막힌 주 "
                    + TsMomentumMaintenanceRealTimesCheck.blockedWeeks(oos) + " · 상한 " + b + " = " + b.stream().mapToInt(Integer::intValue).sum());
        }
        return out;
    }

    static String ok(boolean b) { return b ? "충족" : "미충족"; }

    static String n(BigDecimal v, int scale) { return v == null ? "NA" : v.setScale(scale, RoundingMode.HALF_UP).toPlainString(); }

    static String pct(BigDecimal v) { return v == null ? "NA" : v.multiply(TsMomentumCalculator.HUNDRED).setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    // ── 연결 확인 (가짜 자료, 리뷰 40차) ─────────────────────────────────

    @Test
    @DisplayName("리뷰 40차 연결 확인: 실행기 경로(calculator)는 고정 입력 파일의 점검 예외를 계산기까지 전달한다 — 기본 생성자는 같은 자료를 막는다")
    void frozenMaintenanceReachesCalculator(@TempDir Path dir) throws Exception {
        Instant h = Instant.parse("2024-04-01T00:00:00Z");
        Map<String, List<Bar>> d = new TreeMap<>();
        d.put("KRW-BTC", TsMomentumCalculatorFakeDataTest.flatAround(h, 100, 100, 101, 102, 103));   // BTC 4칸 결측
        d.put("KRW-ETH", TsMomentumCalculatorFakeDataTest.flatAround(h, 100));
        Path csv = dir.resolve("w.csv"), json = dir.resolve("s.json");
        Files.writeString(csv, "id,gap_first_utc,starts_at_utc,ends_at_utc\nW1,x," + h.minus(Duration.ofHours(103)) + "," + h.minus(Duration.ofHours(99)) + "\n");
        StringBuilder absent = new StringBuilder();
        for (int a = 100; a <= 103; a++) absent.append(a > 100 ? "," : "").append("\"").append(h.minus(Duration.ofHours(a)).toString(), 0, 13).append("\"");
        Files.writeString(json, "[\n {\"id\": \"W1\", \"market\": \"KRW-BTC\", \"status\": 200, \"absent\": [" + absent + "]},\n"
                + " {\"id\": \"W1\", \"market\": \"KRW-ETH\", \"status\": 200, \"absent\": []}\n]\n");

        var wired = calculator(d, csv, json);
        assertThat(wired.maintenance().windows()).hasSize(1);
        assertThat(wired.maintenance().absentConfirmed().get("KRW-BTC")).hasSize(4);
        assertThat(wired.gate("KRW-ETH", h)).isEqualTo(Gate.CLEAR);
        assertThat(new TsMomentumCalculator(d).gate("KRW-ETH", h)).isEqualTo(Gate.BTC_UNKNOWN);   // 예외 없음 경로
        // 재조회 결과가 비면 조용히 예외 없음이 되지 않고 멈춘다
        Path empty = dir.resolve("empty.json");
        Files.writeString(empty, "[]\n");
        assertThatThrownBy(() -> calculator(d, csv, empty)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("리뷰 40차 입력 고정: 같은 내용이라도 줄바꿈이 CRLF 로 바뀐 파일은 해시 대조에서 실행을 거부한다")
    void crlfCopyIsRefused(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("w.csv"), list = dir.resolve("frozen.sha256");
        Files.writeString(f, "id,starts_at_utc\nG01,2023-12-31T18:00:00Z\n");
        Files.writeString(list, "# 주석\n" + sha256(f) + " *w.csv\n");
        assertThat(verify(dir, list)).hasSize(1);
        Files.writeString(f, "id,starts_at_utc\r\nG01,2023-12-31T18:00:00Z\r\n");
        assertThatThrownBy(() -> verify(dir, list)).hasMessageContaining("해시 불일치");
        Files.delete(f);
        assertThatThrownBy(() -> verify(dir, list)).hasMessageContaining("고정 입력 없음");
    }
}
