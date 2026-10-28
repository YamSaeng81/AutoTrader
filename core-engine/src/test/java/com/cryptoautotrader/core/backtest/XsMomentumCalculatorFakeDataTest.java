package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.backtest.XsMomentumCalculator.Bar;
import com.cryptoautotrader.core.backtest.XsMomentumCalculator.Costs;
import com.cryptoautotrader.core.backtest.XsMomentumCalculator.Mode;
import com.cryptoautotrader.core.backtest.XsMomentumCalculator.Params;
import com.cryptoautotrader.core.backtest.XsMomentumCalculator.Result;
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
 * 상대 강도 순환 보유 계산기 가짜 자료 시험 (제안서 v2 §5, 리뷰 18차).
 *
 * <p>실제 자료 1회 실행 전에 계산 규칙이 제안서대로 구현됐는지만 본다 — 가짜 자료의 성과 값은 보지 않는다.</p>
 */
class XsMomentumCalculatorFakeDataTest {

    /** 2024-01-01 00:00 UTC — 월요일. */
    static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    static final Duration H = Duration.ofHours(1);
    static final Costs ZERO = new Costs("zero", BigDecimal.ZERO, BigDecimal.ZERO);
    static final Costs MAIN = new Costs("main", new BigDecimal("0.05"), new BigDecimal("0.1"));
    static final Params P = Params.fixed();

    /** [from, to) 시간봉. 봉 h 의 시가 = f(h), 종가 = f(h+1h) — 그래서 t 의 판정 가격과 체결 시가가 모두 f(t) 다. */
    static List<Bar> bars(Instant from, Instant to, Function<Instant, Double> f) {
        List<Bar> out = new ArrayList<>();
        for (Instant h = from; h.isBefore(to); h = h.plus(H)) {
            out.add(new Bar(h, bd(f.apply(h)), bd(f.apply(h.plus(H)))));
        }
        return out;
    }

    static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }

    /** 주마다 일정 비율로 오르는 가격(복리, 시간 단위로 매끄럽게). */
    static Function<Instant, Double> growth(double weekly) {
        return h -> 100.0 * Math.pow(1 + weekly, Duration.between(T0, h).toHours() / 168.0);
    }

    static Instant week(int n) { return T0.plus(Duration.ofDays(7L * n)); }

    @Test
    @DisplayName("4주는 달력 28일 — 중간 결측이 있어도 672행 앞이 아니라 t−28일 가격을 쓴다")
    void lookbackIsCalendar() {
        Function<Instant, Double> f = growth(0.01);
        List<Bar> list = new ArrayList<>(bars(T0, week(10), f));
        Instant gapFrom = week(5).plus(Duration.ofHours(10));
        list.removeIf(b -> !b.start().isBefore(gapFrom) && b.start().isBefore(gapFrom.plus(Duration.ofHours(100))));
        var calc = new XsMomentumCalculator(Map.of("A", list));
        Instant t = week(8);
        double expect = f.apply(t) / f.apply(t.minus(Duration.ofDays(28))) - 1;
        assertThat(calc.lookbackReturn("A", t).doubleValue()).isCloseTo(expect, within(1e-12));
        // 672행 앞의 가격으로 셌다면 결측 100시간만큼 더 먼 과거가 되어 값이 달라진다
        double byRows = f.apply(t) / f.apply(t.minus(Duration.ofHours(672 + 100))) - 1;
        assertThat(Math.abs(byRows - expect)).isGreaterThan(1e-4);
    }

    @Test
    @DisplayName("판정은 t 이전에 끝난 봉만 쓰고, 체결은 t 에 시작하는 봉의 시가다")
    void decisionUsesCompletedBarsOnly() {
        Map<String, List<Bar>> data = new TreeMap<>();
        for (String c : List.of("A", "B", "C", "D", "E", "F")) data.put(c, bars(T0, week(8), growth(0.0)));
        Instant t = week(5);
        var before = new XsMomentumCalculator(data).ranked(t);
        // t 에 시작하는 F 봉을 크게 바꾼다 — 판정(순위)은 그대로여야 한다
        List<Bar> f = new ArrayList<>(data.get("F"));
        int i = f.indexOf(f.stream().filter(b -> b.start().equals(t)).findFirst().orElseThrow());
        f.set(i, new Bar(t, bd(999), bd(5000)));
        data.put("F", f);
        var calc = new XsMomentumCalculator(data);
        assertThat(calc.ranked(t)).isEqualTo(before);
        assertThat(calc.fillBar("F", t).open()).isEqualByComparingTo("999");
        assertThat(calc.decisionPrice("F", t)).isEqualByComparingTo(bd(100.0));
    }

    @Test
    @DisplayName("상장 전·자료 부족: 첫 봉 뒤 28일 가격이 생겨야 적격")
    void preListingIneligible() {
        Instant listed = T0.plus(Duration.ofDays(10));
        var calc = new XsMomentumCalculator(Map.of("A", bars(listed, week(10), growth(0.0))));
        // t−28일 판정 가격 = 시작이 [t−28일−24h, t−28일−1h] 인 봉 → 첫 적격 t 는 listed+28일+1h 이후
        assertThat(calc.lookbackReturn("A", listed.plus(Duration.ofDays(28)))).isNull();
        assertThat(calc.lookbackReturn("A", listed.plus(Duration.ofDays(28)).plus(H))).isNotNull();
    }

    @Test
    @DisplayName("직전 24시간에 봉이 없으면(장기 결측) 그 주는 적격이 아니다")
    void staleIneligible() {
        Instant t = week(6);
        List<Bar> list = new ArrayList<>(bars(T0, week(8), growth(0.0)));
        list.removeIf(b -> !b.start().isBefore(t.minus(Duration.ofHours(30))) && b.start().isBefore(t));
        var calc = new XsMomentumCalculator(Map.of("A", list));
        assertThat(calc.decisionPrice("A", t)).isNull();
        assertThat(calc.lookbackReturn("A", t)).isNull();
    }

    @Test
    @DisplayName("상위 5개 선택, 같은 수익률은 코인 이름 순")
    void topFiveAndTieBreak() {
        Map<String, List<Bar>> data = new TreeMap<>();
        double[] g = {0.01, 0.05, 0.03, 0.03, 0.00, -0.02, 0.04};
        String[] n = {"A", "B", "C", "D", "E", "F", "G"};
        for (int k = 0; k < n.length; k++) data.put(n[k], bars(T0, week(10), growth(g[k])));
        var calc = new XsMomentumCalculator(data);
        assertThat(calc.ranked(week(6))).containsExactly("B", "G", "C", "D", "A", "E", "F");
        Result r = calc.run(Mode.TOP_N, ZERO, P, week(6), week(7));
        assertThat(r.weeks().get(0).selected()).containsExactly("B", "G", "C", "D", "A");
    }

    @Test
    @DisplayName("가격 고정 + 비용 0 → 원금 그대로, 낙폭 0")
    void flatZeroCost() {
        Map<String, List<Bar>> data = new TreeMap<>();
        for (String c : List.of("A", "B", "C", "D", "E")) data.put(c, bars(T0, week(10), growth(0.0)));
        var calc = new XsMomentumCalculator(data);
        Result r = calc.run(Mode.TOP_N, ZERO, P, week(5), week(9));
        assertThat(r.finalValue().doubleValue()).isCloseTo(10_000_000, within(1e-6));
        assertThat(r.mdd().doubleValue()).isCloseTo(0, within(1e-12));
    }

    @Test
    @DisplayName("가격 고정 + 주 비용 → 첫 매수·마지막 청산 비용만, 실제 대금에 차감")
    void flatMainCost() {
        Map<String, List<Bar>> data = new TreeMap<>();
        for (String c : List.of("A", "B", "C", "D", "E")) data.put(c, bars(T0, week(10), growth(0.0)));
        var calc = new XsMomentumCalculator(data);
        Result r = calc.run(Mode.TOP_N, MAIN, P, week(5), week(9));
        // 매수: 현금 1천만 원 안에서 대금×(1+수수료) → 대금 = 1천만/1.0005, 수량 = 대금/(100×1.001)
        // 청산: 수량×100×0.999×(1−0.0005)
        double expect = 10_000_000 / 1.0005 / 1.001 * 0.999 * 0.9995;
        assertThat(r.finalValue().doubleValue()).isCloseTo(expect, within(1e-3));
        // 2~4주차는 비중이 그대로라 주문이 없다(차이가 최소 주문 5,000원 미만)
        for (int k = 1; k < r.weeks().size() - 1; k++) {
            assertThat(r.weeks().get(k).bought().signum()).isZero();
            assertThat(r.weeks().get(k).sold().signum()).isZero();
        }
        assertThat(r.totalFee().doubleValue())
                .isCloseTo(r.totalBought().add(r.totalSold()).doubleValue() * 0.0005, within(1e-3));
    }

    @Test
    @DisplayName("순위가 그대로여도 비중이 벌어지면 유지 종목을 다시 맞추고 그 비용을 낸다")
    void rebalanceHeldCoinsCosts() {
        Map<String, List<Bar>> data = new TreeMap<>();
        data.put("A", bars(T0, week(10), growth(0.10)));
        for (String c : List.of("B", "C", "D", "E")) data.put(c, bars(T0, week(10), growth(0.0)));
        var calc = new XsMomentumCalculator(data);
        Result r = calc.run(Mode.TOP_N, MAIN, P, week(5), week(8));
        var w2 = r.weeks().get(1);
        assertThat(w2.selected()).containsExactlyInAnyOrderElementsOf(r.weeks().get(0).selected());
        assertThat(w2.sold().signum()).isPositive();      // A 를 덜어내고
        assertThat(w2.bought().signum()).isPositive();    // 나머지를 채운다
        assertThat(w2.fee().signum()).isPositive();
        assertThat(w2.slippage().signum()).isPositive();
    }

    @Test
    @DisplayName("비교 바구니도 같은 주기로 동일 비중을 맞추고 같은 비용을 낸다")
    void basketSameCosts() {
        Map<String, List<Bar>> data = new TreeMap<>();
        for (String c : List.of("A", "B", "C", "D", "E", "F")) data.put(c, bars(T0, week(10), growth(0.0)));
        var calc = new XsMomentumCalculator(data);
        Result b = calc.run(Mode.BASKET, MAIN, P, week(5), week(9));
        assertThat(b.weeks().get(0).selected()).hasSize(6);
        double expect = 10_000_000 / 1.0005 / 1.001 * 0.999 * 0.9995;
        assertThat(b.finalValue().doubleValue()).isCloseTo(expect, within(1e-3));
        // 바구니도 비중이 벌어지면 재조정한다
        data.put("A", bars(T0, week(10), growth(0.10)));
        Result b2 = new XsMomentumCalculator(data).run(Mode.BASKET, MAIN, P, week(5), week(8));
        assertThat(b2.weeks().get(1).sold().signum()).isPositive();
    }

    /** 1개만 보유하는 매개변수 — 체결 순서 사례를 손으로 셀 수 있게. */
    static final Params ONE = new Params(1, BigDecimal.valueOf(10_000_000), BigDecimal.valueOf(5_000));

    static List<Bar> without(List<Bar> list, Instant from, Duration len) {
        List<Bar> out = new ArrayList<>(list);
        out.removeIf(b -> !b.start().isBefore(from) && b.start().isBefore(from.plus(len)));
        return out;
    }

    /** 두 시각 사이를 곧게 잇고 앞뒤는 평평한 가격. */
    static Function<Instant, Double> ramp(Instant from, Instant to, double p0, double p1) {
        return h -> h.isBefore(from) ? p0 : !h.isBefore(to) ? p1
                : p0 + (p1 - p0) * Duration.between(from, h).toMinutes() / Duration.between(from, to).toMinutes();
    }

    @Test
    @DisplayName("리뷰 19차 ①: 20:00 매도 대금으로 같은 날 00:00 매수하지 않는다 — 원금이 불어나지 않는다")
    void sellProceedsNotUsedBeforeSellFill() {
        Map<String, List<Bar>> data = new TreeMap<>();
        // A: 1~5주 100→110 뒤 평평, B: 5~6주 100→120 뒤 평평 → 5주차 A, 6주차 B 선택
        // A 는 6주차 0~20시 봉이 없어 매도가 20:00 에 체결된다. B 매수 봉은 00:00
        data.put("A", without(bars(T0, week(10), ramp(week(1), week(5), 100, 110)), week(6), Duration.ofHours(20)));
        data.put("B", bars(T0, week(10), ramp(week(5), week(6), 100, 120)));
        var calc = new XsMomentumCalculator(data);
        Result r = calc.run(Mode.TOP_N, ZERO, ONE, week(5), week(7));
        assertThat(r.computable()).isTrue();
        var w6 = r.weeks().get(1);
        assertThat(w6.selected()).containsExactly("B");
        assertThat(w6.sold().doubleValue()).isCloseTo(10_000_000, within(1e-6));   // 20:00 에 A 매도
        assertThat(w6.bought().signum()).isZero();                                 // 00:00 에는 쓸 현금이 없었다
        assertThat(w6.unfilled()).containsExactly("B:현금부족");
        for (var w : r.weeks()) assertThat(w.cashAfter().signum()).isGreaterThanOrEqualTo(0);
        assertThat(r.finalValue().doubleValue()).isCloseTo(10_000_000, within(1e-6));
    }

    @Test
    @DisplayName("리뷰 19차 ①: 매수가 20시간 늦게 체결돼도 그 전에는 현금으로 평가 — 손실 없는 경로에 낙폭이 생기지 않는다")
    void lateBuyNoPhantomDrawdown() {
        var calc = new XsMomentumCalculator(Map.of("A",
                without(bars(T0, week(10), growth(0.0)), week(5), Duration.ofHours(20))));
        Result r = calc.run(Mode.TOP_N, ZERO, ONE, week(5), week(6));
        assertThat(r.weeks().get(0).bought().doubleValue()).isCloseTo(10_000_000, within(1e-6));
        assertThat(r.mdd().doubleValue()).isCloseTo(0, within(1e-12));
        assertThat(r.finalValue().doubleValue()).isCloseTo(10_000_000, within(1e-6));
    }

    @Test
    @DisplayName("리뷰 19차 ①: 목표 금액은 체결 시가가 아니라 t 에 완료된 봉 종가로 정한다")
    void targetUsesKnownPricesOnly() {
        Map<String, List<Bar>> data = new TreeMap<>();
        for (String c : List.of("A", "B", "C", "D", "E")) data.put(c, bars(T0, week(10), growth(0.0)));
        // 6주차 A 체결 봉의 시가만 크게 바꾼다 — t 에는 알 수 없는 값
        List<Bar> a = new ArrayList<>(data.get("A"));
        a.replaceAll(b -> b.start().equals(week(6)) ? new Bar(b.start(), bd(999), b.close()) : b);
        data.put("A", a);
        Result r = new XsMomentumCalculator(data).run(Mode.TOP_N, ZERO, P, week(5), week(7));
        var w6 = r.weeks().get(1);
        assertThat(w6.valueBefore().doubleValue()).isCloseTo(10_000_000, within(1e-6));
        assertThat(w6.bought().signum()).isZero();
        assertThat(w6.sold().signum()).isZero();
    }

    @Test
    @DisplayName("리뷰 19차 ②: 종료 뒤 30시간 체결 봉이 없으면 가상 청산하지 않고 계산 불가")
    void endLiquidationWithoutFillBarUncomputable() {
        Map<String, List<Bar>> data = new TreeMap<>();
        for (String c : List.of("A", "B", "C", "D", "E")) data.put(c, bars(T0, week(10), growth(0.0)));
        data.put("A", without(data.get("A"), week(7), Duration.ofHours(30)));
        var calc = new XsMomentumCalculator(data);
        Result r = calc.run(Mode.TOP_N, ZERO, P, week(5), week(7));
        assertThat(r.computable()).isFalse();
        assertThat(r.finalValue()).isNull();
        assertThat(r.cumReturn()).isNull();
        assertThat(r.uncomputable()).startsWith("A 자료 결측");
        var v = XsMomentumCalculator.verdict(r, calc.run(Mode.BASKET, ZERO, P, week(5), week(7)));
        assertThat(v.computable()).isFalse();
        assertThat(v.pass()).isFalse();
    }

    @Test
    @DisplayName("리뷰 19차 ②: 보유 코인 자료가 끝나면 마지막 종가 매도 없이 계산 불가 — 확인된 거래지원 종료와 이유를 구분")
    void heldCoinDataEndedUncomputable() {
        Map<String, List<Bar>> data = new TreeMap<>();
        for (String c : List.of("A", "B", "C", "D", "E")) data.put(c, bars(T0, week(12), growth(0.0)));
        data.put("Z", bars(T0, week(6).plus(Duration.ofHours(30)), growth(0.05)));  // 가장 강한 코인, 6주차+30h 에 끝남
        Result r = new XsMomentumCalculator(data).run(Mode.TOP_N, MAIN, P, week(5), week(9));
        assertThat(r.weeks().get(0).selected()).contains("Z");
        assertThat(r.computable()).isFalse();
        assertThat(r.finalValue()).isNull();
        assertThat(r.uncomputable()).startsWith("Z 자료 종료(거래지원 종료 미확인)");
        Result c = new XsMomentumCalculator(data, java.util.Set.of("Z")).run(Mode.TOP_N, MAIN, P, week(5), week(9));
        assertThat(c.computable()).isFalse();
        assertThat(c.uncomputable()).startsWith("Z 확인된 거래지원 종료");
    }

    @Test
    @DisplayName("리뷰 20차 ②: 전량 매도에도 최소 주문 적용 — 800원 잔량 5건은 팔지 않고 보유, 종료 때 남으면 계산 불가")
    void dustBelowMinOrderNotSold() {
        Map<String, List<Bar>> data = new TreeMap<>();
        // A~E: 5주차까지 100, 6주차에 0.04 로 폭락(각 200만 원 → 800원). F~J: 100 평평 → 6주차에 F~J 로 바뀐다
        for (String c : List.of("A", "B", "C", "D", "E")) data.put(c, bars(T0, week(10), ramp(week(5), week(6), 100, 0.04)));
        for (String c : List.of("F", "G", "H", "I", "J")) data.put(c, bars(T0, week(10), growth(0.0)));
        Result r = new XsMomentumCalculator(data).run(Mode.TOP_N, ZERO, P, week(5), week(7));
        var w6 = r.weeks().get(1);
        assertThat(w6.selected()).containsExactly("F", "G", "H", "I", "J");
        assertThat(w6.sold().signum()).isZero();                       // 800원 매도 5건을 체결하지 않는다
        assertThat(w6.qtyAfter()).containsKeys("A", "B", "C", "D", "E"); // 잔량은 보유한 채
        assertThat(w6.cashAfter().signum()).isZero();                  // 4,000원을 현금으로 만들지 않는다
        assertThat(r.computable()).isFalse();
        assertThat(r.finalValue()).isNull();
        assertThat(r.uncomputable()).startsWith("A 최소 주문 미만 잔량(800원)");
    }

    @Test
    @DisplayName("리뷰 20차 ③: 판정 우선순위 — 계산된 팔의 조건 위반 = 탈락, 그 밖에 계산 불가 팔 = 판정 불가, 아니면 통과")
    void overallPriority() {
        var pass = new XsMomentumCalculator.ArmVerdict(MAIN, true, true, true, true);
        var fail = new XsMomentumCalculator.ArmVerdict(MAIN, true, false, true, true);
        var na = new XsMomentumCalculator.ArmVerdict(MAIN, false, false, false, false);
        assertThat(XsMomentumCalculator.overall(List.of(pass, pass))).isEqualTo(XsMomentumCalculator.Overall.PASS);
        assertThat(XsMomentumCalculator.overall(List.of(pass, na))).isEqualTo(XsMomentumCalculator.Overall.UNDETERMINED);
        assertThat(XsMomentumCalculator.overall(List.of(na, fail))).isEqualTo(XsMomentumCalculator.Overall.FAIL);
        assertThat(XsMomentumCalculator.overall(List.of(fail, pass))).isEqualTo(XsMomentumCalculator.Overall.FAIL);
    }

    @Test
    @DisplayName("구간: 시작 = 적격 5개 이상인 첫 월요일, 종료 = 가장 이른 마지막 봉 이하의 월요일")
    void startEnd() {
        Map<String, List<Bar>> data = new TreeMap<>();
        for (String c : List.of("A", "B", "C")) data.put(c, bars(T0, week(12), growth(0.0)));
        for (String c : List.of("D", "E")) data.put(c, bars(T0.plus(Duration.ofDays(20)), week(12).plus(Duration.ofDays(3)), growth(0.0)));
        data.put("F", bars(T0, week(9).plus(Duration.ofDays(2)), growth(0.0)));
        var calc = new XsMomentumCalculator(data);
        // 5주차(1월 29일 뒤 첫 적격 주)에는 A·B·C·F 4개뿐. D·E 는 1월 21일 상장 → 2월 18일 01시 이후 적격 → 2월 19일(week(7))
        assertThat(calc.firstStart(5)).isEqualTo(week(7));
        assertThat(calc.lastEnd()).isEqualTo(week(9));
    }

    @Test
    @DisplayName("판정: 절대·상대·낙폭 세 조건, 하나라도 어기면 탈락")
    void verdictRules() {
        var s = res(new BigDecimal("0.05"), new BigDecimal("0.20"));
        assertThat(XsMomentumCalculator.verdict(s, res(new BigDecimal("0.01"), new BigDecimal("0.15"))).pass()).isTrue();
        assertThat(XsMomentumCalculator.verdict(res(new BigDecimal("-0.01"), BigDecimal.ZERO),
                res(new BigDecimal("-0.30"), BigDecimal.ZERO)).absolute()).isFalse();
        assertThat(XsMomentumCalculator.verdict(s, res(new BigDecimal("0.05"), new BigDecimal("0.15"))).relative()).isFalse();
        assertThat(XsMomentumCalculator.verdict(s, res(new BigDecimal("0.01"), new BigDecimal("0.10"))).drawdown()).isFalse();
        var bad = new Result(Mode.BASKET, MAIN, T0, T0, List.of(), null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "X 자료 결측");
        assertThat(XsMomentumCalculator.verdict(s, bad).pass()).isFalse();
    }

    @Test
    @DisplayName("같은 입력을 두 번 돌리면 결과가 같다")
    void deterministic() {
        Map<String, List<Bar>> data = new TreeMap<>();
        double[] g = {0.01, 0.05, -0.03, 0.02, 0.00, 0.04, -0.01};
        for (int k = 0; k < g.length; k++) {
            double gk = g[k];
            int phase = k;
            data.put("C" + k, bars(T0, week(16), h -> growth(gk).apply(h)
                    * (1 + 0.1 * Math.sin((Duration.between(T0, h).toHours() + 37.0 * phase) / 50.0))));
        }
        var calc = new XsMomentumCalculator(data);
        Result a = calc.run(Mode.TOP_N, MAIN, P, week(5), week(15));
        Result b = new XsMomentumCalculator(data).run(Mode.TOP_N, MAIN, P, week(5), week(15));
        assertThat(a.finalValue()).isEqualByComparingTo(b.finalValue());
        assertThat(a.mdd()).isEqualByComparingTo(b.mdd());
        assertThat(a.weeks().stream().map(w -> w.selected()).toList())
                .isEqualTo(b.weeks().stream().map(w -> w.selected()).toList());
    }

    @Test
    @DisplayName("러너 배선: 가짜 21코인 CSV(BLAST 포함 폴더) → 두 비용 팔 × 전략·바구니, 파일 3개")
    void runnerWiring(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.util.List<String> coins = new ArrayList<>(BWalkForwardRunner.COINS);
        coins.add("KRW-BLAST");   // 폴더에 있어도 빠져야 한다
        for (int k = 0; k < coins.size(); k++) {
            double gk = 0.002 * (k - 10);
            int phase = k;
            List<String> lines = new ArrayList<>();
            for (Bar b : bars(T0, week(14), h -> growth(gk).apply(h)
                    * (1 + 0.05 * Math.sin((Duration.between(T0, h).toHours() + 11.0 * phase) / 40.0)))) {
                lines.add(b.start().toString().replace("Z", "") + "," + b.open() + "," + b.open() + "," + b.close()
                        + "," + b.close() + ",1");
            }
            java.nio.file.Files.write(dir.resolve("st_" + coins.get(k).substring(4).toLowerCase() + "_h1.csv"), lines);
        }
        var out = dir.resolve("out");
        List<String> v = XsMomentumRunner.runAll(dir, out);
        assertThat(v).hasSize(4);
        assertThat(v.get(1)).startsWith("main:");
        assertThat(v.get(2)).startsWith("stress:");
        List<String> summary = java.nio.file.Files.readAllLines(out.resolve("summary.csv"));
        assertThat(summary).hasSize(5);
        assertThat(summary.subList(1, 5).stream().map(l -> l.split(",")[0] + "/" + l.split(",")[1]).toList())
                .containsExactly("main/TOP_N", "main/BASKET", "stress/TOP_N", "stress/BASKET");
        assertThat(String.join("\n", java.nio.file.Files.readAllLines(out.resolve("weekly.csv")))).doesNotContain("KRW-BLAST");
        // 스트레스 팔이 주 비용 팔보다 슬리피지를 더 낸다(같은 엔진 안에서 따로 계산)
        double mainSlip = Double.parseDouble(summary.get(1).split(",")[11]);
        double stressSlip = Double.parseDouble(summary.get(3).split(",")[11]);
        assertThat(stressSlip).isGreaterThan(mainSlip);
    }

    static Result res(BigDecimal cum, BigDecimal mdd) {
        return new Result(Mode.TOP_N, MAIN, T0, T0, List.of(), BigDecimal.ZERO, cum, mdd,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
    }
}
