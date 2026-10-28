package com.cryptoautotrader.core.backtest;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * 코인 간 상대 강도 순환 보유 — 로컬 포트폴리오 계산기 (docs/RESEARCH_PROPOSAL_XS_MOMENTUM.md v2 §5, 리뷰 18차).
 *
 * <p>운영 엔진과 연결하지 않는 연구용 계산기다. 규칙은 제안서 §5 를 그대로 옮긴다 — 여기서 바꾸면 제안서도 같이 고친다.</p>
 * <ul>
 *   <li>시각은 모두 UTC. 재조정 시각 t = 월요일 00:00 UTC(= 09:00 KST)</li>
 *   <li>판정 가격 = t 이전에 <b>완료된</b> H1 봉(시작 ≤ t−1h) 중 마지막 봉의 종가, 그 봉 시작이 t−24h 이상일 때만</li>
 *   <li>4주 = 달력 28일: R = 판정가격(t) / 판정가격(t−28일) − 1 — 행 수(672)로 세지 않는다</li>
 *   <li>체결 = 시작이 [t, t+24h) 인 첫 봉의 시가 ± 슬리피지, 대금에 수수료. 그런 봉이 없으면 그 주 그 코인은 거래하지 않는다</li>
 *   <li>목표 비중: TOP_N 은 선택 코인마다 1/N(남는 자리는 현금), BASKET 은 적격 코인 전체 동일 비중</li>
 *   <li>매주 목표 비중으로 다시 맞춘다(유지 종목 비중 조정 포함). 대금 5,000원 미만 주문은 매수·매도 모두 하지 않는다(전량 매도도, 리뷰 20차)</li>
 *   <li>주문 크기는 t 에 완료된 봉의 종가로 정하고, 체결은 봉 시각 순서로 처리한다 — 매도 전 대금을 쓰지 않고,
 *       매수 전 보유량을 평가에 넣지 않는다(리뷰 19차)</li>
 *   <li>종료 시각 E 에 전부 같은 체결 규칙으로 청산한다(청산 비용 포함)</li>
 *   <li>체결 봉을 확인하지 못한 종료 청산, 최소 주문 미만이라 청산하지 못한 잔량, 자료가 끝난 보유 코인
 *       → 가상 청산 없이 <b>계산 불가</b>(리뷰 19·20차).
 *       마지막 종가 매도 가정은 쓰지 않는다</li>
 * </ul>
 */
final class XsMomentumCalculator {

    static final MathContext MC = MathContext.DECIMAL128;
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final Duration HOUR = Duration.ofHours(1);
    static final Duration STALE = Duration.ofHours(24);
    static final Duration LOOKBACK = Duration.ofDays(28);
    static final Duration WEEK = Duration.ofDays(7);

    enum Mode { TOP_N, BASKET }

    record Bar(Instant start, BigDecimal open, BigDecimal close) {}

    /** 비용 — 편도 %. */
    record Costs(String name, BigDecimal feePct, BigDecimal slipPct) {
        BigDecimal fee() { return feePct.divide(HUNDRED, MC); }
        BigDecimal slip() { return slipPct.divide(HUNDRED, MC); }
    }

    record Params(int topN, BigDecimal initialCash, BigDecimal minOrder) {
        static Params fixed() {
            return new Params(5, BigDecimal.valueOf(10_000_000), BigDecimal.valueOf(5_000));
        }
    }

    /** 한 주 기록 — 재조정 직전 평가액, 그 주 매수·매도 대금, 비용, 보유 결과. */
    record Week(Instant t, int eligible, List<String> selected, BigDecimal valueBefore,
                BigDecimal bought, BigDecimal sold, BigDecimal fee, BigDecimal slippage,
                Map<String, BigDecimal> qtyAfter, BigDecimal cashAfter, List<String> unfilled) {}

    record Result(Mode mode, Costs costs, Instant start, Instant end, List<Week> weeks,
                  BigDecimal finalValue, BigDecimal cumReturn, BigDecimal mdd,
                  BigDecimal totalBought, BigDecimal totalSold, BigDecimal totalFee, BigDecimal totalSlippage,
                  String uncomputable) {
        boolean computable() { return uncomputable == null; }
    }

    private final Map<String, NavigableMap<Instant, Bar>> bars = new TreeMap<>();
    private final Set<String> confirmedDelisted;

    XsMomentumCalculator(Map<String, List<Bar>> data) { this(data, Set.of()); }

    /** confirmedDelisted: 거래소 공지로 확인된 거래지원 종료 코인 — 자료 끝과 구분해 이유에 적는다. */
    XsMomentumCalculator(Map<String, List<Bar>> data, Set<String> confirmedDelisted) {
        this.confirmedDelisted = Set.copyOf(confirmedDelisted);
        data.forEach((coin, list) -> {
            NavigableMap<Instant, Bar> m = new TreeMap<>();
            for (Bar b : list) m.put(b.start(), b);
            bars.put(coin, m);
        });
    }

    // ── 가격 규칙 ───────────────────────────────────────────────────────

    /** t 시점 판정 가격: 시작 ≤ t−1h 인 마지막 봉의 종가, 그 봉 시작 ≥ t−24h. 없으면 null. */
    BigDecimal decisionPrice(String coin, Instant t) {
        Map.Entry<Instant, Bar> e = bars.get(coin).floorEntry(t.minus(HOUR));
        if (e == null || e.getKey().isBefore(t.minus(STALE))) return null;
        return e.getValue().close();
    }

    /** t 시점 체결 봉: 시작이 [t, t+24h) 인 첫 봉. 없으면 null. */
    Bar fillBar(String coin, Instant t) {
        Map.Entry<Instant, Bar> e = bars.get(coin).ceilingEntry(t);
        if (e == null || !e.getKey().isBefore(t.plus(STALE))) return null;
        return e.getValue();
    }

    /** 평가용 가격: 시작 ≤ h−1h 인 마지막 봉의 종가(오래돼도 쓴다). */
    BigDecimal markPrice(String coin, Instant h) {
        Map.Entry<Instant, Bar> e = bars.get(coin).floorEntry(h.minus(HOUR));
        return e == null ? null : e.getValue().close();
    }

    /** 이 시각 이후 봉이 하나도 없으면 자료가 끝난 것 — 거래지원 종료로 단정하지 않는다. */
    boolean ended(String coin, Instant t) {
        return bars.get(coin).ceilingKey(t) == null;
    }

    /** 28일 수익률. 두 판정 가격 중 하나라도 없으면 적격 아님(null) — 상장 전·자료 부족·장기 결측. */
    BigDecimal lookbackReturn(String coin, Instant t) {
        BigDecimal now = decisionPrice(coin, t);
        BigDecimal then = decisionPrice(coin, t.minus(LOOKBACK));
        if (now == null || then == null || then.signum() <= 0) return null;
        return now.divide(then, MC).subtract(BigDecimal.ONE);
    }

    /** 적격 코인을 수익률 내림차순, 같으면 코인 이름 오름차순으로. */
    List<String> ranked(Instant t) {
        Map<String, BigDecimal> r = new LinkedHashMap<>();
        for (String c : bars.keySet()) {
            BigDecimal v = lookbackReturn(c, t);
            if (v != null) r.put(c, v);
        }
        List<String> out = new ArrayList<>(r.keySet());
        out.sort(Comparator.comparing((String c) -> r.get(c)).reversed().thenComparing(Comparator.naturalOrder()));
        return out;
    }

    // ── 구간 ─────────────────────────────────────────────────────────

    static Instant nextMonday(Instant from) {
        var d = from.atZone(ZoneOffset.UTC).toLocalDate();
        var t = d.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC).toInstant();
        return t.isBefore(from) ? t.plus(WEEK) : t;
    }

    /** 시작 S = 적격 코인이 topN 개 이상인 첫 월요일. */
    Instant firstStart(int topN) {
        Instant earliest = bars.values().stream().map(NavigableMap::firstKey).min(Comparator.naturalOrder()).orElseThrow();
        for (Instant t = nextMonday(earliest); !t.isAfter(lastEnd()); t = t.plus(WEEK)) {
            if (ranked(t).size() >= topN) return t;
        }
        throw new IllegalStateException("적격 코인이 " + topN + "개 이상인 주가 없다");
    }

    /** 종료 E = 코인별 마지막 봉 시작 중 가장 이른 시각 이하의 마지막 월요일. */
    Instant lastEnd() {
        Instant minLast = bars.values().stream().map(NavigableMap::lastKey).min(Comparator.naturalOrder()).orElseThrow();
        Instant m = nextMonday(minLast);
        return m.equals(minLast) ? m : m.minus(WEEK);
    }

    // ── 실행 ─────────────────────────────────────────────────────────

    /** 한 주 주문 — 크기는 t 에 알려진 가격으로 정하고, 체결 봉 시각에 처리한다. 매도는 수량, 매수는 원화 대금. */
    private record Order(String coin, boolean sell, BigDecimal amount, Bar fill) {}

    /** 체결 순서대로 바뀌는 현금·보유량과 비용 누계. */
    private final class Book {
        BigDecimal cash;
        final Map<String, BigDecimal> qty = new TreeMap<>();
        BigDecimal bought = BigDecimal.ZERO, sold = BigDecimal.ZERO, fee = BigDecimal.ZERO, slip = BigDecimal.ZERO;

        Book(BigDecimal cash) { this.cash = cash; }

        /** h 시점 평가액 — 이미 체결된 거래만, h 에 완료된 봉 종가로. */
        BigDecimal value(Instant h) {
            BigDecimal v = cash;
            for (var e : qty.entrySet()) {
                BigDecimal px = markPrice(e.getKey(), h);
                if (px != null) v = v.add(e.getValue().multiply(px, MC));
            }
            return v;
        }

        /** 체결 봉 시가 ± 슬리피지, 대금에 수수료. 매수는 그 시각에 있는 현금만 쓴다 — 모자라 최소 주문 미만이면 false. */
        boolean execute(Order o, Costs c, BigDecimal minOrder) {
            BigDecimal open = o.fill().open();
            if (o.sell()) {
                BigDecimal q = o.amount().min(qty.get(o.coin()));
                BigDecimal gross = q.multiply(open.multiply(BigDecimal.ONE.subtract(c.slip()), MC), MC);
                BigDecimal f = gross.multiply(c.fee(), MC);
                cash = cash.add(gross.subtract(f));
                sold = sold.add(gross); fee = fee.add(f); slip = slip.add(q.multiply(open, MC).multiply(c.slip(), MC));
                BigDecimal left = qty.get(o.coin()).subtract(q);
                if (left.signum() <= 0) qty.remove(o.coin()); else qty.put(o.coin(), left);
                return true;
            }
            BigDecimal gross = o.amount().min(cash.divide(BigDecimal.ONE.add(c.fee()), MC));
            if (gross.compareTo(minOrder) < 0) return false;
            BigDecimal q = gross.divide(open.multiply(BigDecimal.ONE.add(c.slip()), MC), MC);
            BigDecimal f = gross.multiply(c.fee(), MC);
            cash = cash.subtract(gross).subtract(f);
            qty.merge(o.coin(), q, BigDecimal::add);
            bought = bought.add(gross); fee = fee.add(f); slip = slip.add(q.multiply(open, MC).multiply(c.slip(), MC));
            return true;
        }
    }

    /** 체결 가격을 확인하지 못한 까닭 — 확인된 거래지원 종료와 자료 종료·결측을 구분한다. */
    String noFillKind(String coin, Instant t) {
        if (confirmedDelisted.contains(coin)) return "확인된 거래지원 종료";
        return ended(coin, t) ? "자료 종료(거래지원 종료 미확인)" : "자료 결측";
    }

    Result run(Mode mode, Costs costs, Params p, Instant start, Instant end) {
        Book b = new Book(p.initialCash());
        List<Week> weeks = new ArrayList<>();
        List<BigDecimal> curve = new ArrayList<>();

        for (Instant t = start; ; t = t.plus(WEEK)) {
            boolean last = !t.isBefore(end);
            // 자료가 끝난 보유 코인 — 팔 수 있는 가격이 없으므로 가상 청산하지 않고 계산 불가
            for (String c : b.qty.keySet()) {
                if (ended(c, t)) return uncomputable(mode, costs, start, end, weeks, b,
                        c + " " + noFillKind(c, t) + " — " + t + " 이후 체결 봉 없음");
            }
            // 재조정 직전 평가액·주문 크기: t 에 완료된 봉의 종가만 쓴다(체결 시가는 아직 모른다)
            BigDecimal value = b.value(t);
            curve.add(value);
            List<String> rank = last ? List.of() : ranked(t);
            List<String> sel = mode == Mode.TOP_N ? rank.subList(0, Math.min(p.topN(), rank.size())) : rank;
            BigDecimal w = sel.isEmpty() ? BigDecimal.ZERO
                    : BigDecimal.ONE.divide(BigDecimal.valueOf(mode == Mode.TOP_N ? p.topN() : sel.size()), MC);

            List<Order> orders = new ArrayList<>();
            BigDecimal budget = b.cash;   // 매수 축소 기준: 현금 + 매도 예상 순대금(t 가격 기준)
            for (var e : b.qty.entrySet()) {
                String c = e.getKey();
                BigDecimal mp = markPrice(c, t);
                BigDecimal target = sel.contains(c) ? value.multiply(w, MC) : BigDecimal.ZERO;
                BigDecimal excess = e.getValue().multiply(mp, MC).subtract(target);
                // 매도도 최소 주문 이상일 때만 — 전량 매도 예외 없음(업비트 매도 최소 금액, 리뷰 20차). 미달 잔량은 보유한 채 둔다
                if (excess.compareTo(p.minOrder()) < 0) {
                    if (last) return uncomputable(mode, costs, start, end, weeks, b,
                            c + " 최소 주문 미만 잔량(" + excess.setScale(0, java.math.RoundingMode.HALF_UP) + "원) — 종료 청산 불가");
                    continue;
                }
                BigDecimal q = target.signum() == 0 ? e.getValue() : excess.divide(mp, MC);
                orders.add(new Order(c, true, q, fillBar(c, t)));
                budget = budget.add(q.multiply(mp, MC).multiply(BigDecimal.ONE.subtract(costs.slip()), MC)
                        .multiply(BigDecimal.ONE.subtract(costs.fee()), MC));
            }
            Map<String, BigDecimal> need = new LinkedHashMap<>();
            BigDecimal needSum = BigDecimal.ZERO;
            for (String c : sel) {
                BigDecimal cur = b.qty.getOrDefault(c, BigDecimal.ZERO).multiply(markPrice(c, t), MC);
                BigDecimal n = value.multiply(w, MC).subtract(cur);
                if (n.compareTo(p.minOrder()) < 0) continue;
                need.put(c, n);
                needSum = needSum.add(n.multiply(BigDecimal.ONE.add(costs.fee()), MC));
            }
            BigDecimal scale = needSum.signum() > 0 && needSum.compareTo(budget) > 0 ? budget.max(BigDecimal.ZERO).divide(needSum, MC) : BigDecimal.ONE;
            for (var e : need.entrySet()) {
                BigDecimal gross = e.getValue().multiply(scale, MC);
                if (gross.compareTo(p.minOrder()) >= 0) orders.add(new Order(e.getKey(), false, gross, fillBar(e.getKey(), t)));
            }

            List<String> unfilled = new ArrayList<>();
            for (Order o : orders) {
                if (o.fill() != null) continue;
                // 종료 청산을 못 하면 계산 불가. 중간 주의 결측은 그 주 거래만 건너뛴다(매도 못 한 코인은 계속 보유)
                if (last) return uncomputable(mode, costs, start, end, weeks, b,
                        o.coin() + " " + noFillKind(o.coin(), t) + " — 종료 청산 [" + t + ", +24h) 체결 봉 없음");
                unfilled.add(o.coin() + ":봉없음");
            }
            // 체결 시각 순서(같은 시각은 매도 먼저)로 처리하며 시간별 평가 — 체결 전 대금·보유량은 쓰지 않는다
            List<Order> queue = orders.stream().filter(o -> o.fill() != null)
                    .sorted(Comparator.comparing((Order o) -> o.fill().start()).thenComparing(o -> !o.sell())).toList();
            BigDecimal b0 = b.bought, s0 = b.sold, f0 = b.fee, sl0 = b.slip;
            int i = 0;
            Instant until = last ? t.plus(STALE) : t.plus(WEEK).minus(HOUR);
            for (Instant h = t.plus(HOUR); !h.isAfter(until); h = h.plus(HOUR)) {
                for (; i < queue.size() && queue.get(i).fill().start().isBefore(h); i++) {
                    Order o = queue.get(i);
                    if (!b.execute(o, costs, p.minOrder())) unfilled.add(o.coin() + ":현금부족");
                }
                curve.add(b.value(h));
            }
            weeks.add(new Week(t, rank.size(), List.copyOf(sel), value, b.bought.subtract(b0), b.sold.subtract(s0),
                    b.fee.subtract(f0), b.slip.subtract(sl0), Map.copyOf(b.qty), b.cash, List.copyOf(unfilled)));
            if (last) {
                BigDecimal cum = b.cash.divide(p.initialCash(), MC).subtract(BigDecimal.ONE);
                return new Result(mode, costs, start, end, weeks, b.cash, cum, maxDrawdown(curve),
                        b.bought, b.sold, b.fee, b.slip, null);
            }
        }
    }

    private static Result uncomputable(Mode mode, Costs costs, Instant start, Instant end, List<Week> weeks, Book b, String why) {
        return new Result(mode, costs, start, end, weeks, null, null, null, b.bought, b.sold, b.fee, b.slip, why);
    }

    static BigDecimal maxDrawdown(List<BigDecimal> curve) {
        BigDecimal peak = null, mdd = BigDecimal.ZERO;
        for (BigDecimal v : curve) {
            if (peak == null || v.compareTo(peak) > 0) peak = v;
            BigDecimal dd = BigDecimal.ONE.subtract(v.divide(peak, MC));
            if (dd.compareTo(mdd) > 0) mdd = dd;
        }
        return mdd;
    }

    // ── 판정 (제안서 §5-⑥) ─────────────────────────────────────────────

    record ArmVerdict(Costs costs, boolean computable, boolean absolute, boolean relative, boolean drawdown) {
        boolean pass() { return computable && absolute && relative && drawdown; }
    }

    /** 한 비용 팔의 판정: 절대 순수익 > 0, 바구니 대비 누적 순수익률 차 > 0, 낙폭 차 < 10%p. */
    static ArmVerdict verdict(Result strat, Result basket) {
        if (!strat.computable() || !basket.computable()) return new ArmVerdict(strat.costs(), false, false, false, false);
        boolean abs = strat.cumReturn().signum() > 0;
        boolean rel = strat.cumReturn().subtract(basket.cumReturn()).signum() > 0;
        boolean dd = strat.mdd().subtract(basket.mdd()).compareTo(new BigDecimal("0.10")) < 0;
        return new ArmVerdict(strat.costs(), true, abs, rel, dd);
    }

    enum Overall { PASS, FAIL, UNDETERMINED }

    /** 개발 판정 우선순위(리뷰 20차): 계산된 팔이 조건을 어기면 탈락 → 아니고 계산 불가 팔이 있으면 판정 불가 → 아니면 통과. */
    static Overall overall(List<ArmVerdict> arms) {
        if (arms.stream().anyMatch(v -> v.computable() && !v.pass())) return Overall.FAIL;
        if (arms.stream().anyMatch(v -> !v.computable())) return Overall.UNDETERMINED;
        return Overall.PASS;
    }
}
