package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.backtest.XsMomentumCalculator.ArmVerdict;
import com.cryptoautotrader.core.backtest.XsMomentumCalculator.Bar;
import com.cryptoautotrader.core.backtest.XsMomentumCalculator.Costs;
import com.cryptoautotrader.core.backtest.XsMomentumCalculator.Mode;
import com.cryptoautotrader.core.backtest.XsMomentumCalculator.Params;
import com.cryptoautotrader.core.backtest.XsMomentumCalculator.Result;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 상대 강도 순환 보유 — 개발 자료 1회 실행 러너 (제안서 v2 §5·§6, 리뷰 18차).
 *
 * <p>🔴 실제 자료 실행은 <b>1회</b>다. 제안서 v2 의 규칙 고정·리뷰 확인 뒤에만 돌린다.</p>
 * <pre>
 * ./gradlew :core-engine:test --tests '*XsMomentumRunner' -i -Dxsm.dir=d:/tmp/stval -Dxsm.out=&lt;출력 폴더&gt;
 * </pre>
 * 출력: {@code summary.csv}(2 비용 × 전략·바구니), {@code weekly.csv}(주별 기록), {@code verdict.txt}(팔별 판정).
 */
class XsMomentumRunner {

    static final List<Costs> ARMS = List.of(
            new Costs("main", new BigDecimal("0.05"), new BigDecimal("0.1")),
            new Costs("stress", new BigDecimal("0.05"), new BigDecimal("0.2")));

    /**
     * Gradle 명령 → 시험 JVM 전달 확인(리뷰 14차 교훈). 실제 자료를 읽지 않는다.
     * <pre>./gradlew :core-engine:test --tests '*XsMomentumRunner.optionsReachTestJvm' -Dxsm.dir=none -Dxsm.out=wired</pre>
     */
    @Test
    @EnabledIfSystemProperty(named = "xsm.dir", matches = "none")
    void optionsReachTestJvm() {
        org.assertj.core.api.Assertions.assertThat(System.getProperty("xsm.out")).isEqualTo("wired");
    }

    @Test
    @EnabledIfSystemProperty(named = "xsm.dir", matches = "(?!none$).+")
    void run() throws Exception {
        String out = System.getProperty("xsm.out");
        if (out == null || out.isBlank()) throw new IllegalStateException("-Dxsm.out 이 없다");
        System.out.println(String.join("\n", runAll(Path.of(System.getProperty("xsm.dir")), Path.of(out))));
    }

    static Map<String, List<Bar>> load(Path dir) throws Exception {
        Map<String, List<Bar>> data = new LinkedHashMap<>();
        for (Path csv : BWalkForwardRunner.selectCoinCsvs(dir)) {
            List<Bar> bars = new ArrayList<>();
            for (Candle c : BWalkForwardRunner.loadCsv(csv.toString())) bars.add(new Bar(c.getTime(), c.getOpen(), c.getClose()));
            data.put(BWalkForwardRunner.coinOf(csv), bars);
        }
        return data;
    }

    /** 21코인 폴더 → 두 비용 팔 × (전략, 바구니) 계산 → 파일 3개. 판정 줄을 돌려준다. */
    static List<String> runAll(Path dir, Path out) throws Exception {
        var calc = new XsMomentumCalculator(load(dir));
        Params p = Params.fixed();
        Instant s = calc.firstStart(p.topN()), e = calc.lastEnd();
        Files.createDirectories(out);
        List<String> summary = new ArrayList<>(List.of(
                "arm,mode,start,end,weeks,final_value,cum_return_pct,mdd_pct,bought,sold,fee,slippage,delist_sales"));
        List<String> weekly = new ArrayList<>(List.of(
                "arm,mode,t,eligible,selected,value_before,bought,sold,fee,slippage,cash_after"));
        List<String> verdict = new ArrayList<>(List.of("구간(UTC) " + s + " ~ " + e));
        boolean all = true;
        for (Costs c : ARMS) {
            Result st = calc.run(Mode.TOP_N, c, p, s, e);
            Result bk = calc.run(Mode.BASKET, c, p, s, e);
            for (Result r : List.of(st, bk)) {
                summary.add(String.join(",", c.name(), r.mode().name(), s.toString(), e.toString(),
                        String.valueOf(r.weeks().size()), n(r.finalValue(), 0), pct(r.cumReturn()), pct(r.mdd()),
                        n(r.totalBought(), 0), n(r.totalSold(), 0), n(r.totalFee(), 0), n(r.totalSlippage(), 0),
                        String.valueOf(r.delistSales())));
                for (var w : r.weeks()) {
                    weekly.add(String.join(",", c.name(), r.mode().name(), w.t().toString(), String.valueOf(w.eligible()),
                            String.join("|", w.selected()), n(w.valueBefore(), 0), n(w.bought(), 0), n(w.sold(), 0),
                            n(w.fee(), 0), n(w.slippage(), 0), n(w.cashAfter(), 0)));
                }
            }
            ArmVerdict v = XsMomentumCalculator.verdict(st, bk);
            all &= v.pass();
            verdict.add(c.name() + ": 절대 " + ok(v.absolute()) + " (전략 " + pct(st.cumReturn()) + "%)"
                    + " · 상대 " + ok(v.relative()) + " (차 " + pct(st.cumReturn().subtract(bk.cumReturn())) + "%p)"
                    + " · 낙폭 " + ok(v.drawdown()) + " (차 " + pct(st.mdd().subtract(bk.mdd())) + "%p)"
                    + " → " + (v.pass() ? "통과" : "탈락"));
        }
        verdict.add("개발 판정: " + (all ? "통과(두 팔 모두 세 조건 충족)" : "탈락 — 제안 종료(§5-⑥)"));
        Files.write(out.resolve("summary.csv"), summary, StandardCharsets.UTF_8);
        Files.write(out.resolve("weekly.csv"), weekly, StandardCharsets.UTF_8);
        Files.write(out.resolve("verdict.txt"), verdict, StandardCharsets.UTF_8);
        return verdict;
    }

    static String ok(boolean b) { return b ? "충족" : "미충족"; }

    static String n(BigDecimal v, int scale) { return v.setScale(scale, RoundingMode.HALF_UP).toPlainString(); }

    static String pct(BigDecimal v) { return v.multiply(XsMomentumCalculator.HUNDRED).setScale(2, RoundingMode.HALF_UP).toPlainString(); }
}
