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
 *   <li>매주 목표 비중으로 다시 맞춘다(유지 종목 비중 조정 포함). 대금 5,000원 미만 주문은 하지 않는다</li>
 *   <li>보유 코인의 자료가 끝나면(그 뒤 봉이 없음) 마지막 종가에 비용을 빼고 판 것으로 본다(거래지원 종료)</li>
 *   <li>종료 시각 E 에 전부 같은 체결 규칙으로 청산한다(청산 비용 포함)</li>
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
                Map<String, BigDecimal> qtyAfter, BigDecimal cashAfter) {}

    record Result(Mode mode, Costs costs, Instant start, Instant end, List<Week> weeks,
                  BigDecimal finalValue, BigDecimal cumReturn, BigDecimal mdd,
                  BigDecimal totalBought, BigDecimal totalSold, BigDecimal totalFee, BigDecimal totalSlippage,
                  int delistSales) {}

    private final Map<String, NavigableMap<Instant, Bar>> bars = new TreeMap<>();

    XsMomentumCalculator(Map<String, List<Bar>> data) {
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

    /** 이 시각 이후 봉이 하나도 없으면 자료가 끝난 것(거래지원 종료로 본다). */
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

    Result run(Mode mode, Costs costs, Params p, Instant start, Instant end) {
        BigDecimal fee = costs.fee(), slip = costs.slip();
        BigDecimal cash = p.initialCash();
        Map<String, BigDecimal> qty = new TreeMap<>();
        List<Week> weeks = new ArrayList<>();
        List<BigDecimal> curve = new ArrayList<>();
        BigDecimal tb = BigDecimal.ZERO, ts = BigDecimal.ZERO, tf = BigDecimal.ZERO, tsl = BigDecimal.ZERO;
        int delist = 0;

        for (Instant t = start; ; t = t.plus(WEEK)) {
            // 지난주 보유분의 시간별 평가(낙폭 계산용) — 직전 재조정 뒤부터 t 직전까지
            if (!weeks.isEmpty()) {
                for (Instant h = t.minus(WEEK).plus(HOUR); h.isBefore(t); h = h.plus(HOUR)) {
                    curve.add(markValue(cash, qty, h));
                }
            }
            // 자료가 끝난 보유 코인 → 마지막 종가에 비용을 빼고 매도
            for (String c : List.copyOf(qty.keySet())) {
                if (ended(c, t)) {
                    BigDecimal px = bars.get(c).lastEntry().getValue().close();
                    BigDecimal gross = qty.get(c).multiply(px.multiply(BigDecimal.ONE.subtract(slip), MC), MC);
                    BigDecimal f = gross.multiply(fee, MC);
                    cash = cash.add(gross.subtract(f));
                    ts = ts.add(gross); tf = tf.add(f); tsl = tsl.add(qty.get(c).multiply(px, MC).multiply(slip, MC));
                    qty.remove(c);
                    delist++;
                }
            }
            boolean last = !t.isBefore(end);
            List<String> rank = last ? List.of() : ranked(t);
            List<String> sel = mode == Mode.TOP_N ? rank.subList(0, Math.min(p.topN(), rank.size())) : rank;
            BigDecimal w = sel.isEmpty() ? BigDecimal.ZERO
                    : BigDecimal.ONE.divide(BigDecimal.valueOf(mode == Mode.TOP_N ? p.topN() : sel.size()), MC);

            // 재조정 직전 평가액: 체결 봉이 있으면 그 시가, 없으면 평가용 종가
            Map<String, BigDecimal> px = new TreeMap<>();
            BigDecimal value = cash;
            for (var e : qty.entrySet()) {
                Bar fb = fillBar(e.getKey(), t);
                BigDecimal v = fb != null ? fb.open() : markPrice(e.getKey(), t);
                if (fb != null) px.put(e.getKey(), fb.open());
                value = value.add(e.getValue().multiply(v, MC));
            }
            for (String c : sel) {
                Bar fb = fillBar(c, t);
                if (fb != null) px.putIfAbsent(c, fb.open());
            }
            curve.add(value);

            BigDecimal wb = BigDecimal.ZERO, ws = BigDecimal.ZERO, wf = BigDecimal.ZERO, wsl = BigDecimal.ZERO;
            // 매도 먼저(목표보다 많은 만큼), 그 뒤 매수
            for (String c : List.copyOf(qty.keySet())) {
                BigDecimal o = px.get(c);
                if (o == null) continue;   // 체결 봉 없음 → 이번 주 거래 안 함
                BigDecimal target = sel.contains(c) ? value.multiply(w, MC) : BigDecimal.ZERO;
                BigDecimal cur = qty.get(c).multiply(o, MC);
                BigDecimal excess = cur.subtract(target);
                if (excess.signum() <= 0 || excess.compareTo(p.minOrder()) < 0) continue;
                BigDecimal q = target.signum() == 0 ? qty.get(c) : excess.divide(o, MC);
                BigDecimal gross = q.multiply(o.multiply(BigDecimal.ONE.subtract(slip), MC), MC);
                BigDecimal f = gross.multiply(fee, MC);
                cash = cash.add(gross.subtract(f));
                ws = ws.add(gross); wf = wf.add(f); wsl = wsl.add(q.multiply(o, MC).multiply(slip, MC));
                BigDecimal left = qty.get(c).subtract(q);
                if (left.signum() <= 0) qty.remove(c); else qty.put(c, left);
            }
            Map<String, BigDecimal> need = new LinkedHashMap<>();
            BigDecimal needSum = BigDecimal.ZERO;
            for (String c : sel) {
                BigDecimal o = px.get(c);
                if (o == null) continue;
                BigDecimal cur = qty.getOrDefault(c, BigDecimal.ZERO).multiply(o, MC);
                BigDecimal n = value.multiply(w, MC).subtract(cur);
                if (n.compareTo(p.minOrder()) < 0) continue;
                need.put(c, n);
                needSum = needSum.add(n.multiply(BigDecimal.ONE.add(fee), MC));
            }
            // 현금이 모자라면 매수 대금을 같은 비율로 줄인다
            BigDecimal scale = needSum.compareTo(cash) > 0 && needSum.signum() > 0 ? cash.divide(needSum, MC) : BigDecimal.ONE;
            for (var e : need.entrySet()) {
                BigDecimal gross = e.getValue().multiply(scale, MC);
                if (gross.compareTo(p.minOrder()) < 0) continue;
                BigDecimal o = px.get(e.getKey());
                BigDecimal exec = o.multiply(BigDecimal.ONE.add(slip), MC);
                BigDecimal q = gross.divide(exec, MC);
                BigDecimal f = gross.multiply(fee, MC);
                cash = cash.subtract(gross).subtract(f);
                qty.merge(e.getKey(), q, BigDecimal::add);
                wb = wb.add(gross); wf = wf.add(f); wsl = wsl.add(q.multiply(o, MC).multiply(slip, MC));
            }
            tb = tb.add(wb); ts = ts.add(ws); tf = tf.add(wf); tsl = tsl.add(wsl);
            weeks.add(new Week(t, rank.size(), List.copyOf(sel), value, wb, ws, wf, wsl, Map.copyOf(qty), cash));
            if (last) {
                if (!qty.isEmpty()) {
                    // 체결 봉이 없어 못 판 코인 → 평가용 종가에 비용을 빼고 청산
                    for (var e : qty.entrySet()) {
                        BigDecimal o = markPrice(e.getKey(), t);
                        BigDecimal gross = e.getValue().multiply(o.multiply(BigDecimal.ONE.subtract(slip), MC), MC);
                        BigDecimal f = gross.multiply(fee, MC);
                        cash = cash.add(gross.subtract(f));
                        ts = ts.add(gross); tf = tf.add(f); tsl = tsl.add(e.getValue().multiply(o, MC).multiply(slip, MC));
                    }
                    qty.clear();
                }
                curve.add(cash);
                BigDecimal cum = cash.divide(p.initialCash(), MC).subtract(BigDecimal.ONE);
                return new Result(mode, costs, start, end, weeks, cash, cum, maxDrawdown(curve), tb, ts, tf, tsl, delist);
            }
        }
    }

    private BigDecimal markValue(BigDecimal cash, Map<String, BigDecimal> qty, Instant h) {
        BigDecimal v = cash;
        for (var e : qty.entrySet()) {
            BigDecimal px = markPrice(e.getKey(), h);
            if (px != null) v = v.add(e.getValue().multiply(px, MC));
        }
        return v;
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

    record ArmVerdict(Costs costs, boolean absolute, boolean relative, boolean drawdown) {
        boolean pass() { return absolute && relative && drawdown; }
    }

    /** 한 비용 팔의 판정: 절대 순수익 > 0, 바구니 대비 누적 순수익률 차 > 0, 낙폭 차 < 10%p. */
    static ArmVerdict verdict(Result strat, Result basket) {
        boolean abs = strat.cumReturn().signum() > 0;
        boolean rel = strat.cumReturn().subtract(basket.cumReturn()).signum() > 0;
        boolean dd = strat.mdd().subtract(basket.mdd()).compareTo(new BigDecimal("0.10")) < 0;
        return new ArmVerdict(strat.costs(), abs, rel, dd);
    }
}
