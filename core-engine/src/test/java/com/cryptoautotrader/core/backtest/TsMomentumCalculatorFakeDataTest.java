package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.backtest.TsMomentumCalculator.ArmVerdict;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Bar;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Costs;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Gate;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Hit;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.OosWindow;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Overall;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Params;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Result;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Stage;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Trade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 주간 시계열 모멘텀 계산기 가짜 자료 시험 (제안서 v3 §4, 리뷰 35·36차).
 *
 * <p>실제 자료 1회 실행 전에 계산 규칙이 제안서대로 구현됐는지만 본다 — 가짜 자료의 성과 값은 보지 않는다.</p>
 */
class TsMomentumCalculatorFakeDataTest {

    /** 2024-01-01 00:00 UTC — 월요일. 자료는 이틀 앞에서 시작해 week(4) 부터 R 을 계산할 수 있다. */
    static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    static final Instant DATA_FROM = T0.minus(Duration.ofDays(2));
    static final Duration H = Duration.ofHours(1);
    static final String BTC = "KRW-BTC", ETH = "KRW-ETH";
    static final Costs ZERO = new Costs("zero", BigDecimal.ZERO, BigDecimal.ZERO);
    static final Params P = Params.fixed();
    static final Stage S = Stage.fixed();

    /** [from, to) 시간봉. 봉 h 의 시가 = f(h), 종가 = f(h+1h) — 그래서 t 의 판단 가격과 체결 시가가 모두 f(t) 다. */
    static List<Bar> bars(Instant from, Instant to, Function<Instant, Double> f) {
        List<Bar> out = new ArrayList<>();
        for (Instant h = from; h.isBefore(to); h = h.plus(H)) out.add(bar(h, f.apply(h), f.apply(h.plus(H))));
        return out;
    }

    static Bar bar(Instant h, double open, double close) {
        return new Bar(h, bd(open), bd(Math.max(open, close)), bd(Math.min(open, close)), bd(close), BigDecimal.ONE);
    }

    static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }

    static Function<Instant, Double> growth(double weekly) {
        return h -> 100.0 * Math.pow(1 + weekly, Duration.between(T0, h).toHours() / 168.0);
    }

    static Function<Instant, Double> flat() { return h -> 100.0; }

    /** until 까지 주 rise 로 오르고, 그 뒤 주 fall 로 내린다. */
    static Function<Instant, Double> upThenDown(Instant until, double rise, double fall) {
        Function<Instant, Double> up = growth(rise);
        return h -> h.isBefore(until) ? up.apply(h)
                : up.apply(until) * Math.pow(1 + fall, Duration.between(until, h).toHours() / 168.0);
    }

    static Instant week(int n) { return T0.plus(Duration.ofDays(7L * n)); }

    static TsMomentumCalculator calc(List<Bar> btc, List<Bar> eth) {
        Map<String, List<Bar>> m = new TreeMap<>();
        m.put(BTC, btc);
        m.put(ETH, eth);
        return new TsMomentumCalculator(m);
    }

    static void replace(List<Bar> list, Instant h, double open, double close) {
        list.replaceAll(b -> b.start().equals(h) ? bar(h, open, close) : b);
    }

    @Test
    @DisplayName("28일은 달력 28일 — 중간 결측이 있어도 672행 앞이 아니라 t−28일 가격을 쓴다")
    void lookbackIsCalendar() {
        Function<Instant, Double> f = growth(0.01);
        List<Bar> eth = new ArrayList<>(bars(DATA_FROM, week(10), f));
        Instant gap = week(5).plus(Duration.ofHours(10));
        eth.removeIf(b -> !b.start().isBefore(gap) && b.start().isBefore(gap.plus(Duration.ofHours(100))));
        var c = calc(bars(DATA_FROM, week(10), flat()), eth);
        Instant t = week(8);
        double expect = f.apply(t) / f.apply(t.minus(Duration.ofDays(28))) - 1;
        assertThat(c.lookbackReturn(ETH, t).doubleValue()).isCloseTo(expect, within(1e-12));
        double byRows = f.apply(t) / f.apply(t.minus(Duration.ofHours(672 + 100))) - 1;
        assertThat(Math.abs(byRows - expect)).isGreaterThan(1e-4);
    }

    @Test
    @DisplayName("판단은 t 이전에 끝난 봉만 쓰고, 매수 체결은 시작이 t 인 봉의 시가다")
    void decisionUsesCompletedBarsOnly() {
        List<Bar> eth = new ArrayList<>(bars(DATA_FROM, week(8), growth(0.02)));
        var before = calc(bars(DATA_FROM, week(8), flat()), eth);
        Instant s = before.firstStart();
        BigDecimal r = before.lookbackReturn(ETH, s);
        replace(eth, s, 90.0, 300.0);   // t 에 시작하는 봉을 크게 바꾼다 — R 은 그대로, 체결가만 바뀐다
        var after = calc(bars(DATA_FROM, week(8), flat()), eth);
        assertThat(after.lookbackReturn(ETH, s)).isEqualByComparingTo(r);
        Result res = after.run(ZERO, P, S, s, after.lastEnd());
        Trade buy = res.trades().get(0);
        assertThat(buy.coin()).isEqualTo(ETH);
        assertThat(buy.at()).isEqualTo(s);
        assertThat(buy.open()).isEqualByComparingTo(bd(90.0));
    }

    @Test
    @DisplayName("R > 0 매수 · 보유 중 R > 0 은 그대로(거래 없음) · R ≤ 0 매도 · R = 0 은 현금 · 종료 청산은 자연 왕복이 아니다")
    void holdAndExitOnlyOnWeeklyDecision() {
        List<Bar> eth = bars(DATA_FROM, week(20), upThenDown(week(9), 0.03, -0.03));
        var c = calc(bars(DATA_FROM, week(20), flat()), eth);
        Result r = c.run(ZERO, P, S, c.firstStart(), c.lastEnd());
        assertThat(r.computable()).isTrue();
        // BTC 는 R = 0 이라 한 번도 사지 않는다
        assertThat(r.trades()).noneMatch(x -> x.coin().equals(BTC));
        List<Trade> e = r.trades().stream().filter(x -> x.coin().equals(ETH)).toList();
        assertThat(e).hasSize(2);
        assertThat(e.get(0).sell()).isFalse();
        assertThat(e.get(1).sell()).isTrue();
        assertThat(e.get(1).natural()).isTrue();
        // 매도 주: 28일 수익률이 처음으로 0 이하가 되는 월요일
        Instant firstNonPositive = null;
        for (Instant t = week(9); ; t = t.plus(Duration.ofDays(7))) {
            if (c.lookbackReturn(ETH, t).signum() <= 0) { firstNonPositive = t; break; }
        }
        assertThat(e.get(1).at()).isEqualTo(firstNonPositive);
        assertThat(r.naturalRoundTrips(ETH)).isEqualTo(1);
        assertThat(r.weeks().stream().filter(w -> w.coins().get(ETH).action().equals("HOLD")).count()).isGreaterThan(0);
    }

    @Test
    @DisplayName("보유 중에 판단 가격이 없으면 상태를 유지한다 — 팔지도 사지도 않는다")
    void noPriceKeepsState() {
        List<Bar> eth = new ArrayList<>(bars(DATA_FROM, week(14), upThenDown(week(9), 0.03, -0.03)));
        var probe = calc(bars(DATA_FROM, week(14), flat()), eth);
        Instant sellWeek = null;
        for (Instant t = week(9); ; t = t.plus(Duration.ofDays(7))) {
            if (probe.lookbackReturn(ETH, t).signum() <= 0) { sellWeek = t; break; }
        }
        final Instant sw = sellWeek;
        eth.removeIf(b -> !b.start().isBefore(sw.minus(Duration.ofHours(30))) && b.start().isBefore(sw));
        var c = calc(bars(DATA_FROM, week(14), flat()), eth);
        Result r = c.run(ZERO, P, S, c.firstStart(), c.lastEnd());
        var w = r.weeks().stream().filter(x -> x.t().equals(sw)).findFirst().orElseThrow();
        assertThat(w.coins().get(ETH).action()).isEqualTo("NO_PRICE");
        assertThat(r.trades()).noneMatch(x -> x.at().equals(sw));
        assertThat(r.trades().stream().filter(x -> x.coin().equals(ETH) && x.sell() && x.natural()).findFirst().orElseThrow().at())
                .isEqualTo(sw.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("관문: BTC 가 t−1h 봉에서 −2% 면 ETH 매수는 막히고, 완료 봉 기준으로 풀린 첫 봉 시가에 체결한다")
    void gateDelaysEntryWithinWindow() {
        List<Bar> eth = bars(DATA_FROM, week(10), growth(0.03));
        var probe = calc(bars(DATA_FROM, week(10), flat()), eth);
        Instant s = probe.firstStart();
        Function<Instant, Double> btcF = h -> h.isBefore(s) ? 100.0 : 98.0;
        List<Bar> btc = bars(DATA_FROM, week(10), btcF);   // t−1h 봉: 시가 100 → 종가 98
        var c = calc(btc, eth);
        assertThat(c.gate(ETH, s)).isEqualTo(Gate.BTC_DROP);
        assertThat(c.gate(ETH, s.plus(H))).isEqualTo(Gate.BTC_DROP);       // 창에 t−1h 봉 고가 100 이 남아 있다
        assertThat(c.gate(ETH, s.plus(Duration.ofHours(2)))).isEqualTo(Gate.CLEAR);
        Result r = c.run(ZERO, P, S, s, c.lastEnd());
        Trade buy = r.trades().stream().filter(x -> x.coin().equals(ETH)).findFirst().orElseThrow();
        assertThat(buy.at()).isEqualTo(s.plus(Duration.ofHours(2)));
        assertThat(buy.open().doubleValue()).isCloseTo(growth(0.03).apply(s.plus(Duration.ofHours(2))), within(1e-9));
        assertThat(r.weeks().get(0).coins().get(ETH).gateWaits()).isEqualTo(2);
    }

    @Test
    @DisplayName("관문: [t, t+24h) 내내 막히면 그 주는 현금 — BTC 결측(판정 불가)은 500봉 창에서 밀려날 때까지 계속 막는다")
    void gateBlocksWholeWindowThenCash() {
        List<Bar> eth = bars(DATA_FROM, week(12), growth(0.03));
        var probe = calc(bars(DATA_FROM, week(12), flat()), eth);
        Instant s = probe.firstStart();
        List<Bar> btc = new ArrayList<>(bars(DATA_FROM, week(12), flat()));
        btc.removeIf(b -> !b.start().isBefore(s.minus(H)) && b.start().isBefore(s.plus(Duration.ofHours(24))));
        var c = calc(btc, eth);
        assertThat(c.gate(ETH, s)).isEqualTo(Gate.BTC_UNKNOWN);
        Result r = c.run(ZERO, P, S, s, c.lastEnd());
        assertThat(r.weeks().get(0).coins().get(ETH).action()).isEqualTo("BUY_BLOCKED");
        assertThat(r.weeks().get(0).coins().get(ETH).gateWaits()).isEqualTo(24);
        // 빈 칸 25개가 최근 500봉 안에 남아 있는 동안(운영 CandleFreshnessGuard ② 와 같다) 다음 주도 막힌다
        assertThat(r.weeks().get(1).coins().get(ETH).action()).isEqualTo("BUY_BLOCKED");
        Instant entry = r.trades().stream().filter(x -> x.coin().equals(ETH)).findFirst().orElseThrow().at();
        assertThat(entry).isAfterOrEqualTo(s.plus(Duration.ofHours(24 + 500 - 2)));
    }

    @Test
    @DisplayName("관문은 매도에 적용하지 않는다")
    void gateDoesNotBlockSell() {
        List<Bar> eth = bars(DATA_FROM, week(20), upThenDown(week(9), 0.03, -0.03));
        var probe = calc(bars(DATA_FROM, week(20), flat()), eth);
        Instant sellWeek = probe.run(ZERO, P, S, probe.firstStart(), probe.lastEnd()).trades().stream()
                .filter(x -> x.coin().equals(ETH) && x.sell()).findFirst().orElseThrow().at();
        Function<Instant, Double> btcF = h -> h.isBefore(sellWeek) ? 100.0 : 98.0;
        var c = calc(bars(DATA_FROM, week(20), btcF), eth);
        assertThat(c.gate(ETH, sellWeek)).isEqualTo(Gate.BTC_DROP);
        Result r = c.run(ZERO, P, S, c.firstStart(), c.lastEnd());
        assertThat(r.trades().stream().filter(x -> x.coin().equals(ETH) && x.sell()).findFirst().orElseThrow().at())
                .isEqualTo(sellWeek);
    }

    @Test
    @DisplayName("코인 관문: 1시간 창 고가 대비 −5% 면 막고, −2% 이면서 거래량이 직전 20봉 평균의 5배면 막는다")
    void coinGate() {
        List<Bar> eth = new ArrayList<>(bars(DATA_FROM, week(8), flat()));
        Instant h = week(6);
        replace(eth, h.minus(H), 100.0, 94.9);
        var c = calc(bars(DATA_FROM, week(8), flat()), eth);
        assertThat(c.gate(ETH, h)).isEqualTo(Gate.COIN_DROP);
        List<Bar> eth2 = new ArrayList<>(bars(DATA_FROM, week(8), flat()));
        eth2.replaceAll(b -> b.start().equals(h.minus(H))
                ? new Bar(b.start(), bd(100), bd(100), bd(97), bd(97.9), BigDecimal.valueOf(5)) : b);
        assertThat(calc(bars(DATA_FROM, week(8), flat()), eth2).gate(ETH, h)).isEqualTo(Gate.COIN_VOLUME);
        eth2.replaceAll(b -> b.start().equals(h.minus(H))
                ? new Bar(b.start(), bd(100), bd(100), bd(97), bd(97.9), new BigDecimal("4.99")) : b);
        assertThat(calc(bars(DATA_FROM, week(8), flat()), eth2).gate(ETH, h)).isEqualTo(Gate.CLEAR);
    }

    @Test
    @DisplayName("최소 주문은 체결 시점 대금으로 검사한다(#10 계산기 한계 수정) — 미달 매도는 하지 않고 보유를 유지한다")
    void minOrderCheckedAtFill() {
        List<Bar> eth = new ArrayList<>(bars(DATA_FROM, week(20), upThenDown(week(9), 0.03, -0.03)));
        var probe = calc(bars(DATA_FROM, week(20), flat()), eth);
        Instant sw = probe.run(ZERO, P, S, probe.firstStart(), probe.lastEnd()).trades().stream()
                .filter(x -> x.coin().equals(ETH) && x.sell()).findFirst().orElseThrow().at();
        double px = upThenDown(week(9), 0.03, -0.03).apply(sw);
        replace(eth, sw, px / 100_000, px);   // 체결 봉 시가만 무너뜨린다 — 판단 가격(직전 종가)은 그대로
        var c = calc(bars(DATA_FROM, week(20), flat()), eth);
        Result r = c.run(ZERO, P, S, c.firstStart(), c.lastEnd());
        var w = r.weeks().stream().filter(x -> x.t().equals(sw)).findFirst().orElseThrow();
        assertThat(w.coins().get(ETH).action()).contains("최소 주문 미만");
        assertThat(r.trades()).noneMatch(x -> x.at().equals(sw));
    }

    @Test
    @DisplayName("종료 청산 체결 봉이 없으면 가상 청산 없이 계산 불가")
    void endWithoutFillIsUncomputable() {
        List<Bar> eth = new ArrayList<>(bars(DATA_FROM, week(10).plus(Duration.ofDays(3)), growth(0.03)));
        var probe = calc(bars(DATA_FROM, week(10).plus(Duration.ofDays(3)), flat()), eth);
        Instant e = probe.lastEnd();
        eth.removeIf(b -> !b.start().isBefore(e) && b.start().isBefore(e.plus(Duration.ofHours(24))));
        var c = calc(bars(DATA_FROM, week(10).plus(Duration.ofDays(3)), flat()), eth);
        Result r = c.run(ZERO, P, S, c.firstStart(), e);
        assertThat(r.computable()).isFalse();
        assertThat(r.uncomputable()).contains("종료 청산");
    }

    @Test
    @DisplayName("비용: 매수가 = 시가 × (1+슬리피지), 매도가 = 시가 × (1−슬리피지), 대금에 수수료 — 왕복 1건을 손으로 맞춘다")
    void costsOnOneRoundTrip() {
        Function<Instant, Double> f = upThenDown(week(9), 0.03, -0.03);
        var c = calc(bars(DATA_FROM, week(20), flat()), bars(DATA_FROM, week(20), f));
        Result r = c.run(Costs.MAIN, P, S, c.firstStart(), c.lastEnd());
        List<Trade> e = r.trades().stream().filter(x -> x.coin().equals(ETH)).toList();
        double share = 5_000_000, fee = 0.0005, slip = 0.001;
        double grossBuy = share / (1 + fee);
        double qty = grossBuy / (f.apply(e.get(0).at()) * (1 + slip));
        double grossSell = qty * f.apply(e.get(1).at()) * (1 - slip);
        double expect = share + grossSell * (1 - fee);
        assertThat(r.finalValue().doubleValue()).isCloseTo(expect, within(1e-4));
    }

    @Test
    @DisplayName("비교 기준은 S 에 사서 E 에 판다 — 중간 거래 없음, 관문 미적용")
    void benchmarkBuyAndHold() {
        Function<Instant, Double> btcF = h -> h.isBefore(week(5)) ? 100.0 : 98.0;
        var c = calc(bars(DATA_FROM, week(12), btcF), bars(DATA_FROM, week(12), growth(0.02)));
        Instant s = c.firstStart();
        Result b = c.benchmark(Costs.MAIN, P, s, c.lastEnd());
        assertThat(b.trades()).hasSize(4);
        assertThat(b.trades().stream().filter(x -> !x.sell())).allMatch(x -> x.at().equals(s));
        assertThat(b.trades().stream().filter(Trade::sell)).allMatch(x -> x.at().equals(c.lastEnd()) && !x.natural());
    }

    @Test
    @DisplayName("판정: 세 조건 · 두 비용 팔 · 우선순위(계산된 팔 위반 → 탈락, 아니고 계산 불가 → 판정 불가)")
    void verdictAndPriority() {
        Result ok = result(Costs.MAIN, "0.10", "0.20");
        Result bench = result(Costs.MAIN, "0.05", "0.30");
        assertThat(TsMomentumCalculator.verdict(ok, bench).pass()).isTrue();
        assertThat(TsMomentumCalculator.verdict(result(Costs.MAIN, "0.04", "0.20"), bench).relative()).isFalse();
        assertThat(TsMomentumCalculator.verdict(result(Costs.MAIN, "0.10", "0.30"), bench).drawdown()).isFalse();
        assertThat(TsMomentumCalculator.verdict(result(Costs.MAIN, "-0.01", "0.20"), result(Costs.MAIN, "-0.05", "0.30")).absolute()).isFalse();
        Result none = new Result(Costs.STRESS, null, null, List.of(), List.of(), null, null, null, List.of(), "x");
        ArmVerdict pass = TsMomentumCalculator.verdict(ok, bench);
        ArmVerdict undetermined = TsMomentumCalculator.verdict(none, bench);
        ArmVerdict fail = TsMomentumCalculator.verdict(result(Costs.STRESS, "-0.01", "0.20"), bench);
        assertThat(TsMomentumCalculator.overall(List.of(pass, pass))).isEqualTo(Overall.PASS);
        assertThat(TsMomentumCalculator.overall(List.of(pass, undetermined))).isEqualTo(Overall.UNDETERMINED);
        assertThat(TsMomentumCalculator.overall(List.of(fail, undetermined))).isEqualTo(Overall.FAIL);
    }

    static Result result(Costs c, String cum, String mdd) {
        return new Result(c, null, null, List.of(), List.of(), BigDecimal.ONE, new BigDecimal(cum), new BigDecimal(mdd), List.of(), null);
    }

    @Test
    @DisplayName("실측 비용 재판정 팔: 편도 슬리피지 = max((③ − 0.10)/2, 0.10) — 낮은 실측으로 유리하게 바꾸지 않는다")
    void measuredCostArm() {
        assertThat(Costs.measured(new BigDecimal("0.25")).slipPct()).isEqualByComparingTo("0.10");
        assertThat(Costs.measured(new BigDecimal("0.40")).slipPct()).isEqualByComparingTo("0.15");
        assertThat(Costs.measured(new BigDecimal("0.40")).feePct()).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("⑮ 진단: 포지션 배정액 −15%·포지션 고점 −20%·고정 자본 누적 −15%·24시간 −3% 를 각 분모로 기록한다(판정 무관)")
    void emergencyStopDiagnostics() {
        // ETH 를 산 뒤 한 시간에 16% 떨어지고 그대로 머문다: 20,000 배정 → −3,200 → CAPITAL_LOSS·24시간 −3% 해당, 고점 −20% 는 아니다
        Instant crash = week(6).plus(Duration.ofHours(30));
        Function<Instant, Double> up = growth(0.05);
        Function<Instant, Double> f = h -> h.isBefore(crash) ? up.apply(h) : up.apply(crash) * 0.84;
        var c = calc(bars(DATA_FROM, week(9), flat()), bars(DATA_FROM, week(9), f));
        Result r = c.run(ZERO, P, S, c.firstStart(), c.lastEnd());
        List<String> kinds = r.hits().stream().map(Hit::kind).toList();
        assertThat(kinds).contains("POOL_24H");
        assertThat(kinds).doesNotContain("STAGE_CUMULATIVE", "MAX_DRAWDOWN");
        // 진입가 대비로는 상승분이 있어 −15% 에 못 미칠 수 있다 — 진입 배정액 분모를 그대로 확인한다
        Trade buy = r.trades().get(0);
        double entryPx = buy.open().doubleValue();
        boolean capExpected = up.apply(crash) * 0.84 <= entryPx * 0.85;
        assertThat(kinds.contains("CAPITAL_LOSS")).isEqualTo(capExpected);
        // 진단은 거래를 바꾸지 않는다
        assertThat(r.trades()).isEqualTo(c.run(ZERO, P, new Stage(BigDecimal.ONE, BigDecimal.ONE), c.firstStart(), c.lastEnd()).trades());
    }

    @Test
    @DisplayName("워크포워드 구조적 상한(가격 미열람 가상 달력): 이력을 주면 OOS 판단 14·14·15회·자연 왕복 상한 7+7+7=21, 예열 없으면 10·10·11회")
    void walkForwardCalendarBound() {
        // 개발 구간 2023-12-31 15:00 UTC ~ 2026-09-22 00:00 UTC(끝 제외) = 23,889 시간
        Instant first = Instant.parse("2023-12-31T15:00:00Z");
        assertThat(Duration.between(first, Instant.parse("2026-09-22T00:00:00Z")).toHours()).isEqualTo(23_889);
        List<OosWindow> w = TsMomentumCalculator.rollingOosCalendar(first, 23_889, 0.7, 3);
        assertThat(w).extracting(OosWindow::withHistory).containsExactly(14, 14, 15);
        assertThat(w).extracting(OosWindow::noHistory).containsExactly(10, 10, 11);
        assertThat(w.stream().mapToInt(OosWindow::maxNaturalRoundTrips).sum()).isEqualTo(21);
        assertThat(w.get(0).oosStart()).isEqualTo(Instant.parse("2024-08-19T21:00:00Z"));
        assertThat(w.get(2).oosEnd()).isEqualTo(Instant.parse("2026-09-22T00:00:00Z"));
    }
}
