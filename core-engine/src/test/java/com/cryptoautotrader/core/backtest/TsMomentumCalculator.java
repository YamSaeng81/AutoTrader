package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.selector.BlackSwanGuard;
import com.cryptoautotrader.core.selector.BtcMarketGuard;
import com.cryptoautotrader.strategy.Candle;

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
import java.util.function.Predicate;

/**
 * BTC·ETH 주간 시계열 모멘텀(매수 또는 현금) — 로컬 계산기 (docs/RESEARCH_PROPOSAL_TS_MOMENTUM.md v3 §4, 리뷰 35·36차).
 *
 * <p>운영 엔진과 연결하지 않는 연구용 계산기다. 규칙은 제안서 §4 를 그대로 옮긴다 — 여기서 바꾸면 제안서도 같이 고친다.</p>
 * <ul>
 *   <li>시각은 모두 UTC. 판단 시각 t = 월요일 00:00 UTC. 입력은 H1</li>
 *   <li>판단 가격 = 시작 ≤ t−1h 인 마지막 봉의 종가, 그 봉 시작 ≥ t−24h 일 때만(#10 과 같다)</li>
 *   <li>R = 판단가격(t) / 판단가격(t−28일) − 1 — <b>달력 28일</b>이고 행 수로 세지 않는다. 업비트는 체결 없는 시간에 봉을 만들지 않는다</li>
 *   <li>코인마다 독립된 몫(초기 현금의 1/2). R &gt; 0 보유, R ≤ 0 현금, 가격이 없으면 상태 유지. 보유 중 R &gt; 0 이면 그대로 둔다</li>
 *   <li>R 은 t 에 계산해 그 주의 진입 대기 동안 고정한다(리뷰 36차). 매도는 시작이 [t, t+24h) 인 첫 봉 시가.
 *       매수는 같은 창 안에서 급락 관문이 풀린 첫 봉 시가 — 끝내 막히면 그 주는 현금. 경계 시각 이후에는 진입하지 않는다</li>
 *   <li>급락 관문은 <b>완료 H1 모형</b>이다({@link #gate}). 운영 LIVE 는 진행 중인 봉까지 보고 60초마다 판정하므로
 *       분 단위 해제 시각은 재현하지 못한다 — 그 차이는 전향 때 따로 기록한다(제안서 §4-2)</li>
 *   <li>최소 주문 5,000원은 <b>체결 시점의 대금</b>으로 검사한다(#10 계산기의 알려진 한계 수정, 리뷰 21차)</li>
 *   <li>종료 E 에 같은 체결 규칙으로 전량 청산. 체결 봉이 없거나 최소 주문 미만이면 가상 청산 없이 계산 불가</li>
 * </ul>
 */
final class TsMomentumCalculator {

    static final MathContext MC = MathContext.DECIMAL128;
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final Duration HOUR = Duration.ofHours(1);
    static final Duration STALE = Duration.ofHours(24);
    static final Duration LOOKBACK = Duration.ofDays(28);
    static final Duration WEEK = Duration.ofDays(7);
    static final String BTC = "KRW-BTC";

    // 급락 관문 — 급락 판정은 운영 BlackSwanGuard.check · BtcMarketGuard.check 를 그대로 부른다(리뷰 37차: 반올림 순서까지 같게).
    // 최신성은 web-api 의 CandleFreshnessGuard.problem 을 core-engine 에서 부를 수 없어 같은 순서로 옮겼다. 상수는 98aaac4 와 같다.
    /** LIVE 조회 범위 — TradingConstants.CANDLE_LOOKBACK 봉 길이의 <b>시간</b>(H1 이면 500시간). 행 수가 아니다(리뷰 37차). */
    static final Duration FETCH_SPAN = Duration.ofHours(500);
    /** CandleFreshnessGuard.GRACE — 계산기의 판정 시각은 봉 시작 h + GRACE 다(h−1h 봉이 최신 완료 봉으로 요구되는 첫 시각). */
    static final Duration GRACE = Duration.ofMinutes(2);
    static final int MAX_MISSING = 2;

    record Bar(Instant start, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {}

    /** 비용 — 편도 %. */
    record Costs(String name, BigDecimal feePct, BigDecimal slipPct) {
        BigDecimal fee() { return feePct.divide(HUNDRED, MC); }
        BigDecimal slip() { return slipPct.divide(HUNDRED, MC); }

        static final Costs MAIN = new Costs("main", new BigDecimal("0.05"), new BigDecimal("0.1"));
        static final Costs STRESS = new Costs("stress", new BigDecimal("0.05"), new BigDecimal("0.2"));

        /**
         * 실측 비용 재판정 팔(제안서 §6-3): 편도 슬리피지 = max((③ − 0.10) / 2, 주 비용 팔 0.10), 수수료 0.05.
         * 실측이 낮아도 주 비용 팔보다 유리하게 바꾸지 않는다. 평균 비용을 고정한 모형상 재판정이다.
         */
        static Costs measured(BigDecimal roundTripPct) {
            BigDecimal slip = roundTripPct.subtract(new BigDecimal("0.10")).divide(BigDecimal.valueOf(2), MC);
            return new Costs("measured", new BigDecimal("0.05"), slip.max(MAIN.slipPct()));
        }
    }

    record Params(BigDecimal initialCash, BigDecimal minOrder) {
        static Params fixed() { return new Params(BigDecimal.valueOf(10_000_000), BigDecimal.valueOf(5_000)); }
    }

    /** 규칙 ⑮ 진단의 척도 — STAGE_1 고정 투입 자본과 포지션 배정액(LIVE_ENTRY_PREREG.md §6). */
    record Stage(BigDecimal capital, BigDecimal position) {
        static Stage fixed() { return new Stage(BigDecimal.valueOf(100_000), BigDecimal.valueOf(20_000)); }
    }

    enum Gate { CLEAR, COIN_UNKNOWN, COIN_DROP, COIN_VOLUME, BTC_UNKNOWN, BTC_DROP }

    record Trade(String coin, boolean sell, Instant at, BigDecimal open, BigDecimal gross, BigDecimal fee, boolean natural) {}

    /** 코인 한 개의 한 주 — R(없으면 null), 한 일, 매수 대기 중 관문에 막힌 봉 수. */
    record CoinWeek(BigDecimal r, String action, int gateWaits) {}

    record Week(Instant t, Map<String, CoinWeek> coins, BigDecimal valueBefore) {}

    /** 규칙 ⑮ 진단 — 조건이 거짓에서 참이 된 시각. 판정에 쓰지 않는다. */
    record Hit(String kind, String coin, Instant at) {}

    /**
     * 공식 점검 예외(리뷰 39차) — 운영 V93 {@code CandleFreshnessGuard.maintenanceExemption} 의 두 조건을 옮긴 것.
     * (가) 봉 구간 [slot, slot+1h) 전체가 판정 시각 now 이전에 <b>끝난</b> 점검 구간 안에 있다(경계에 걸친 봉은 빼지 않는다)
     * (나) 거래소 재조회에서도 그 코인의 그 칸이 없었다. 구간·재조회 결과는 입력 파일로 고정한다
     * (scripts/tsmom/maintenance_windows_tsmom.csv · maintenance_recheck_h1.py). ① 최신 완료 봉 조건에는 쓰지 않는다.
     */
    record Maintenance(List<Window> windows, Map<String, Set<Instant>> absentConfirmed) {
        record Window(String id, Instant startsAt, Instant endsAt) {
            boolean contains(Instant slot) { return !slot.isBefore(startsAt) && !slot.plus(HOUR).isAfter(endsAt); }
        }

        static final Maintenance NONE = new Maintenance(List.of(), Map.of());

        boolean exempt(String coin, Instant slot, Instant now) {
            return absentConfirmed.getOrDefault(coin, Set.of()).contains(slot)
                    && windows.stream().anyMatch(w -> !w.endsAt().isAfter(now) && w.contains(slot));
        }
    }

    record Result(Costs costs, Instant start, Instant end, List<Week> weeks, List<Trade> trades,
                  BigDecimal finalValue, BigDecimal cumReturn, BigDecimal mdd, List<Hit> hits, String uncomputable) {
        boolean computable() { return uncomputable == null; }

        /** 자연 청산 왕복 — 주간 판단 R ≤ 0 으로 판 것만. 종료 E 의 일괄 청산은 세지 않는다. */
        int naturalRoundTrips(String coin) {
            return (int) trades.stream().filter(x -> x.sell() && x.natural() && x.coin().equals(coin)).count();
        }
    }

    private final Map<String, NavigableMap<Instant, Bar>> bars = new TreeMap<>();
    /** 관문 입력용 — 같은 봉을 운영 Candle 로 한 번만 바꿔 둔다. */
    private final Map<String, NavigableMap<Instant, Candle>> candles = new TreeMap<>();
    private final Maintenance maintenance;

    TsMomentumCalculator(Map<String, List<Bar>> data) {
        this(data, Maintenance.NONE);
    }

    TsMomentumCalculator(Map<String, List<Bar>> data, Maintenance maintenance) {
        this.maintenance = maintenance;
        if (!data.containsKey(BTC)) throw new IllegalArgumentException("BTC 급락 관문에 " + BTC + " 자료가 필요하다");
        data.forEach((coin, list) -> {
            NavigableMap<Instant, Bar> m = new TreeMap<>();
            NavigableMap<Instant, Candle> cm = new TreeMap<>();
            for (Bar b : list) {
                m.put(b.start(), b);
                cm.put(b.start(), Candle.builder().time(b.start()).open(b.open()).high(b.high()).low(b.low())
                        .close(b.close()).volume(b.volume()).build());
            }
            bars.put(coin, m);
            candles.put(coin, cm);
        });
    }

    List<String> coins() { return List.copyOf(bars.keySet()); }

    // ── 가격 규칙 ───────────────────────────────────────────────────────

    BigDecimal decisionPrice(String coin, Instant t) {
        Map.Entry<Instant, Bar> e = bars.get(coin).floorEntry(t.minus(HOUR));
        if (e == null || e.getKey().isBefore(t.minus(STALE))) return null;
        return e.getValue().close();
    }

    Bar fillBar(String coin, Instant t) {
        Map.Entry<Instant, Bar> e = bars.get(coin).ceilingEntry(t);
        if (e == null || !e.getKey().isBefore(t.plus(STALE))) return null;
        return e.getValue();
    }

    BigDecimal markPrice(String coin, Instant h) {
        Map.Entry<Instant, Bar> e = bars.get(coin).floorEntry(h.minus(HOUR));
        return e == null ? null : e.getValue().close();
    }

    BigDecimal lookbackReturn(String coin, Instant t) {
        BigDecimal now = decisionPrice(coin, t);
        BigDecimal then = decisionPrice(coin, t.minus(LOOKBACK));
        if (now == null || then == null || then.signum() <= 0) return null;
        return now.divide(then, MC).subtract(BigDecimal.ONE);
    }

    // ── 급락 관문 (완료 H1 모형) ──────────────────────────────────────────

    /**
     * 시작 h 인 봉의 시가로 매수하려 할 때의 관문. 판정 시각 now = h + {@link #GRACE}.
     * <ul>
     *   <li>입력 캔들: 시작이 [now − {@link #FETCH_SPAN}, now] 인 봉 중 완료된 것(시작 ≤ h−1h) — LIVE {@code loadRecentCandles} 의 시각 범위다.
     *       전략 판단용 28일 이력({@link #lookbackReturn})과는 따로 고른다</li>
     *   <li>최신성({@link #problem}, CandleFreshnessGuard ①②): 코인이 어기면 운영은 그 회차를 평가하지 않으므로 진입하지 않는다.
     *       BTC 가 어기면 판정 불가로 막는다(LIVE {@code blocksEntry})</li>
     *   <li>코인 급락: {@link BlackSwanGuard#check}. BTC 급락: {@link BtcMarketGuard#check}. 입력은 위의 완료 봉 목록</li>
     * </ul>
     * 공식 점검 공백은 {@link Maintenance} 가 주어지면 운영과 같은 두 조건으로 결측에서 뺀다(리뷰 39차, 기본은 예외 없음).
     * 모형의 한계: 운영은 진행 중인 봉과 60초 주기를 쓴다. 여기서는 둘 다 재현하지 않는다.
     */
    Gate gate(String coin, Instant h) {
        Instant now = h.plus(GRACE);
        List<Candle> own = fetched(coin, now);
        if (problem(own, now, s -> maintenance.exempt(coin, s, now)) != null) return Gate.COIN_UNKNOWN;
        BlackSwanGuard.Result swan = BlackSwanGuard.check(own);
        // 운영 Result 에는 종류가 없어 사유 문구로 나눈다(98aaac4: "1시간 내 급락 …" / "거래량 급증 …"). 판정에는 쓰지 않고 기록용이다
        if (swan.triggered()) return swan.reason().startsWith("거래량") ? Gate.COIN_VOLUME : Gate.COIN_DROP;
        List<Candle> btc = fetched(BTC, now);
        if (problem(btc, now, s -> maintenance.exempt(BTC, s, now)) != null) return Gate.BTC_UNKNOWN;
        return BtcMarketGuard.check(btc).triggered() ? Gate.BTC_DROP : Gate.CLEAR;
    }

    /** LIVE 가 now 에 조회하는 시각 범위 [now − 500h, now] 의 완료 봉(시각 오름차순). */
    List<Candle> fetched(String coin, Instant now) {
        return List.copyOf(candles.get(coin).subMap(now.minus(FETCH_SPAN), true, now.minus(HOUR), true).values());
    }

    /**
     * CandleFreshnessGuard.problem(candles, "H1", now) 를 옮긴 것(공식 점검 예외 없음) — 쓸 수 있으면 {@code null}.
     * ① 최신 완료 봉 시작 ≥ floor(now − GRACE) − 1h ② 첫 완료 봉부터 마지막 완료 봉까지 빈 칸 ≤ {@value #MAX_MISSING}.
     * 범위 앞쪽의 빈 칸은 첫 봉 앞이므로 세지 않는다(운영과 같다).
     */
    static String problem(List<Candle> candles, Instant now) {
        return problem(candles, now, null);
    }

    /** 위와 같고, {@code exemptSlot} 이 참인 빈 칸은 ② 에서 뺀다(운영 problem(…, exemptSlot) 과 같은 순서). {@code null} 이면 모두 센다. */
    static String problem(List<Candle> candles, Instant now, Predicate<Instant> exemptSlot) {
        if (candles.isEmpty()) return "캔들 없음";
        long tfSec = HOUR.getSeconds();
        long ref = now.minus(GRACE).getEpochSecond();
        Instant latestClosedStart = Instant.ofEpochSecond(ref - Math.floorMod(ref, tfSec) - tfSec);
        int closed = 0;
        Instant first = null, last = null;
        for (Candle c : candles) {
            if (c.getTime().plusSeconds(tfSec).isAfter(now)) continue;
            if (first == null) first = c.getTime();
            last = c.getTime();
            closed++;
        }
        if (last == null) return "완료 봉 없음";
        if (last.isBefore(latestClosedStart)) return "최신 완료 봉 없음";
        long missing = Duration.between(first, last).getSeconds() / tfSec + 1 - closed;
        long exempt = 0;
        if (missing > 0 && exemptSlot != null) {
            Set<Long> have = new java.util.HashSet<>();
            for (Candle c : candles) have.add(c.getTime().getEpochSecond());
            for (long t = first.getEpochSecond(); t <= last.getEpochSecond(); t += tfSec) {
                if (!have.contains(t) && exemptSlot.test(Instant.ofEpochSecond(t))) exempt++;
            }
        }
        return missing - exempt > MAX_MISSING ? "결측 " + (missing - exempt) + "칸" : null;
    }

    // ── 구간 ─────────────────────────────────────────────────────────

    static Instant nextMonday(Instant from) {
        var d = from.atZone(ZoneOffset.UTC).toLocalDate();
        var t = d.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC).toInstant();
        return t.isBefore(from) ? t.plus(WEEK) : t;
    }

    /** S = 모든 코인의 R 을 계산할 수 있는 첫 월요일. */
    Instant firstStart() {
        Instant earliest = bars.values().stream().map(NavigableMap::firstKey).min(Comparator.naturalOrder()).orElseThrow();
        for (Instant t = nextMonday(earliest); !t.isAfter(lastEnd()); t = t.plus(WEEK)) {
            final Instant tt = t;
            if (bars.keySet().stream().allMatch(c -> lookbackReturn(c, tt) != null)) return t;
        }
        throw new IllegalStateException("모든 코인의 R 을 계산할 수 있는 주가 없다");
    }

    /** E = 코인별 마지막 봉 시작 중 가장 이른 시각 이하의 마지막 월요일. */
    Instant lastEnd() {
        Instant minLast = bars.values().stream().map(NavigableMap::lastKey).min(Comparator.naturalOrder()).orElseThrow();
        Instant m = nextMonday(minLast);
        return m.equals(minLast) ? m : m.minus(WEEK);
    }

    // ── 실행 ─────────────────────────────────────────────────────────

    /** 코인 한 개의 몫 — 현금·보유량과 ⑮ 진단용 진입 원가·고점. */
    private static final class Sleeve {
        BigDecimal cash, qty = BigDecimal.ZERO, basis = BigDecimal.ZERO, peak = BigDecimal.ZERO;
        boolean capHit, mddHit;
        Sleeve(BigDecimal cash) { this.cash = cash; }
        boolean held() { return qty.signum() > 0; }
    }

    private record Exec(String coin, boolean sell, Bar bar) {}

    Result run(Costs c, Params p, Stage s, Instant start, Instant end) { return simulate(c, p, s, start, end, false); }

    /** 비교 기준 — 같은 두 몫으로 S 에 사서 E 에 판다. 관문은 적용하지 않는다(전략의 진입 안전장치이므로). */
    Result benchmark(Costs c, Params p, Instant start, Instant end) { return simulate(c, p, Stage.fixed(), start, end, true); }

    private Result simulate(Costs c, Params p, Stage stage, Instant start, Instant end, boolean buyAndHold) {
        Map<String, Sleeve> sl = new LinkedHashMap<>();
        BigDecimal share = p.initialCash().divide(BigDecimal.valueOf(bars.size()), MC);
        for (String coin : bars.keySet()) sl.put(coin, new Sleeve(share));
        List<Week> weeks = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        List<BigDecimal> curve = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        Diag diag = new Diag(stage);

        for (Instant t = start; ; t = t.plus(WEEK)) {
            boolean last = !t.isBefore(end);
            BigDecimal value = value(sl, t);
            curve.add(value);
            diag.observe(sl, t, hits);
            Map<String, CoinWeek> cw = new LinkedHashMap<>();
            List<Exec> queue = new ArrayList<>();
            for (var e : sl.entrySet()) {
                String coin = e.getKey();
                Sleeve x = e.getValue();
                if (last) {
                    if (!x.held()) { cw.put(coin, new CoinWeek(null, "CASH", 0)); continue; }
                    Bar b = fillBar(coin, t);
                    if (b == null) return fail(c, start, end, weeks, trades, hits, coin + " 종료 청산 [" + t + ", +24h) 체결 봉 없음");
                    queue.add(new Exec(coin, true, b));
                    cw.put(coin, new CoinWeek(null, "SELL_END", 0));
                    continue;
                }
                if (buyAndHold) {
                    if (t.equals(start)) {
                        Bar b = fillBar(coin, t);
                        if (b == null) return fail(c, start, end, weeks, trades, hits, coin + " 비교 기준 시작 체결 봉 없음");
                        queue.add(new Exec(coin, false, b));
                        cw.put(coin, new CoinWeek(null, "BUY", 0));
                    } else cw.put(coin, new CoinWeek(null, "HOLD", 0));
                    continue;
                }
                BigDecimal r = lookbackReturn(coin, t);
                if (r == null) { cw.put(coin, new CoinWeek(null, "NO_PRICE", 0)); continue; }
                boolean want = r.signum() > 0;
                if (want == x.held()) { cw.put(coin, new CoinWeek(r, want ? "HOLD" : "CASH", 0)); continue; }
                if (!want) {
                    Bar b = fillBar(coin, t);   // 매도에는 관문을 적용하지 않는다
                    if (b == null) { cw.put(coin, new CoinWeek(r, "SELL_NO_BAR", 0)); continue; }
                    queue.add(new Exec(coin, true, b));
                    cw.put(coin, new CoinWeek(r, "SELL", 0));
                    continue;
                }
                int waits = 0;
                Bar entry = null;
                for (Bar b : bars.get(coin).subMap(t, true, t.plus(STALE), false).values()) {
                    if (gate(coin, b.start()) == Gate.CLEAR) { entry = b; break; }
                    waits++;
                }
                if (entry == null) { cw.put(coin, new CoinWeek(r, waits > 0 ? "BUY_BLOCKED" : "BUY_NO_BAR", waits)); continue; }
                queue.add(new Exec(coin, false, entry));
                cw.put(coin, new CoinWeek(r, "BUY", waits));
            }
            queue.sort(Comparator.comparing((Exec q) -> q.bar().start()));
            int i = 0;
            Instant until = last ? t.plus(STALE) : t.plus(WEEK).minus(HOUR);
            for (Instant h = t.plus(HOUR); !h.isAfter(until); h = h.plus(HOUR)) {
                for (; i < queue.size() && queue.get(i).bar().start().isBefore(h); i++) {
                    Exec q = queue.get(i);
                    String err = execute(sl.get(q.coin()), q, c, p, !last, trades, diag);
                    if (err != null) {
                        if (last) return fail(c, start, end, weeks, trades, hits, err);
                        cw.put(q.coin(), new CoinWeek(cw.get(q.coin()).r(), err, cw.get(q.coin()).gateWaits()));
                    }
                }
                curve.add(value(sl, h));
                diag.observe(sl, h, hits);
            }
            weeks.add(new Week(t, Map.copyOf(cw), value));
            if (last) {
                BigDecimal cash = sl.values().stream().map(x -> x.cash).reduce(BigDecimal.ZERO, BigDecimal::add);
                return new Result(c, start, end, weeks, trades, cash, cash.divide(p.initialCash(), MC).subtract(BigDecimal.ONE),
                        maxDrawdown(curve), hits, null);
            }
        }
    }

    /** 체결 — 시가 ± 슬리피지, 대금에 수수료. 최소 주문은 체결 대금으로 검사한다. 실패 사유를 돌려준다. */
    private String execute(Sleeve x, Exec q, Costs c, Params p, boolean natural, List<Trade> trades, Diag diag) {
        BigDecimal open = q.bar().open();
        if (q.sell()) {
            BigDecimal gross = x.qty.multiply(open.multiply(BigDecimal.ONE.subtract(c.slip()), MC), MC);
            if (gross.compareTo(p.minOrder()) < 0) return q.coin() + " 매도 대금 최소 주문 미만(" + gross.toBigInteger() + "원)";
            BigDecimal f = gross.multiply(c.fee(), MC);
            x.cash = x.cash.add(gross).subtract(f);
            diag.realize(x, gross.subtract(f));
            x.qty = BigDecimal.ZERO;
            trades.add(new Trade(q.coin(), true, q.bar().start(), open, gross, f, natural));
            return null;
        }
        BigDecimal gross = x.cash.divide(BigDecimal.ONE.add(c.fee()), MC);
        if (gross.compareTo(p.minOrder()) < 0) return "BUY_MIN_ORDER";
        BigDecimal f = gross.multiply(c.fee(), MC);
        x.qty = gross.divide(open.multiply(BigDecimal.ONE.add(c.slip()), MC), MC);
        x.cash = x.cash.subtract(gross).subtract(f);
        x.basis = gross.add(f);
        x.peak = x.basis;
        x.capHit = x.mddHit = false;
        trades.add(new Trade(q.coin(), false, q.bar().start(), open, gross, f, natural));
        return null;
    }

    private BigDecimal value(Map<String, Sleeve> sl, Instant h) {
        BigDecimal v = BigDecimal.ZERO;
        for (var e : sl.entrySet()) {
            v = v.add(e.getValue().cash);
            if (e.getValue().held()) v = v.add(e.getValue().qty.multiply(markPrice(e.getKey(), h), MC));
        }
        return v;
    }

    /**
     * 규칙 ⑮ — STAGE_1 계좌 비상 정지 조건(LIVE_ENTRY_PREREG.md §6)에 해당했을 시점. 포지션마다 배정액 20,000 으로 환산한다.
     * CAPITAL_LOSS: 평가액 ≤ 진입 배정액 × 0.85. MAX_DRAWDOWN: 평가액 ≤ 포지션 고점 평가액 × 0.80.
     * 누적: 풀 평가액 − 고정 투입 자본 ≤ −15%. 24시간: 풀 평가액 − 24시간 전 풀 평가액 ≤ −3%(24시간 미만이면 시작과 비교).
     * 분모는 앞의 둘이 포지션, 뒤의 둘이 고정 투입 자본이다. 서킷 브레이커·실측 비용 조건은 계산기로 볼 수 없다.
     */
    private final class Diag {
        final Stage stage;
        BigDecimal realized = BigDecimal.ZERO;
        final List<BigDecimal> pool = new ArrayList<>();
        boolean cumHit, dayHit;

        Diag(Stage stage) { this.stage = stage; }

        BigDecimal k(Sleeve x) { return stage.position().divide(x.basis, MC); }

        void realize(Sleeve x, BigDecimal net) { realized = realized.add(net.subtract(x.basis).multiply(k(x), MC)); }

        void observe(Map<String, Sleeve> sl, Instant h, List<Hit> hits) {
            BigDecimal open = BigDecimal.ZERO;
            for (var e : sl.entrySet()) {
                Sleeve x = e.getValue();
                if (!x.held() || x.basis.signum() == 0) continue;
                BigDecimal v = x.qty.multiply(markPrice(e.getKey(), h), MC);
                if (v.compareTo(x.peak) > 0) x.peak = v;
                boolean cap = v.compareTo(x.basis.multiply(new BigDecimal("0.85"), MC)) <= 0;
                boolean mdd = v.compareTo(x.peak.multiply(new BigDecimal("0.80"), MC)) <= 0;
                if (cap && !x.capHit) hits.add(new Hit("CAPITAL_LOSS", e.getKey(), h));
                if (mdd && !x.mddHit) hits.add(new Hit("MAX_DRAWDOWN", e.getKey(), h));
                x.capHit = cap;
                x.mddHit = mdd;
                open = open.add(v.subtract(x.basis).multiply(k(x), MC));
            }
            BigDecimal now = stage.capital().add(realized).add(open);
            BigDecimal ref = pool.size() >= 24 ? pool.get(pool.size() - 24) : stage.capital();
            pool.add(now);
            boolean cum = now.subtract(stage.capital()).compareTo(stage.capital().multiply(new BigDecimal("-0.15"), MC)) <= 0;
            boolean day = now.subtract(ref).compareTo(stage.capital().multiply(new BigDecimal("-0.03"), MC)) <= 0;
            if (cum && !cumHit) hits.add(new Hit("STAGE_CUMULATIVE", null, h));
            if (day && !dayHit) hits.add(new Hit("POOL_24H", null, h));
            cumHit = cum;
            dayHit = day;
        }
    }

    private Result fail(Costs c, Instant start, Instant end, List<Week> weeks, List<Trade> trades, List<Hit> hits, String why) {
        return new Result(c, start, end, weeks, trades, null, null, null, hits, why);
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

    // ── 판정 (제안서 §4 ⑬) ─────────────────────────────────────────────

    record ArmVerdict(Costs costs, boolean computable, boolean absolute, boolean relative, boolean drawdown) {
        boolean pass() { return computable && absolute && relative && drawdown; }
    }

    /** (가) 누적 순수익률 &gt; 0 (나) 비교 기준 대비 초과 수익 &gt; 0 (다) 낙폭 &lt; 비교 기준 낙폭. */
    static ArmVerdict verdict(Result strat, Result bench) {
        if (!strat.computable() || !bench.computable()) return new ArmVerdict(strat.costs(), false, false, false, false);
        return new ArmVerdict(strat.costs(), true,
                strat.cumReturn().signum() > 0,
                strat.cumReturn().subtract(bench.cumReturn()).signum() > 0,
                strat.mdd().compareTo(bench.mdd()) < 0);
    }

    enum Overall { PASS, FAIL, UNDETERMINED }

    /** 계산을 마친 팔이 조건을 어기면 탈락 → 아니고 계산 불가 팔이 있으면 판정 불가 → 아니면 통과(#10 과 같다). */
    static Overall overall(List<ArmVerdict> arms) {
        if (arms.stream().anyMatch(v -> v.computable() && !v.pass())) return Overall.FAIL;
        if (arms.stream().anyMatch(v -> !v.computable())) return Overall.UNDETERMINED;
        return Overall.PASS;
    }

    // ── 워크포워드 구조적 상한 (가격을 읽지 않는 가상 달력, 제안서 §6-2) ────────────

    /**
     * OOS 구간 [oosStart, oosEnd) 한 개 — 그 안의 월요일 판단 수.
     * withHistory: 판단 봉(t−1h)과 체결 봉(t)이 모두 구간 안. 28일 전 가격은 구간 앞 이력에서 읽는다(지표 이력만).
     * noHistory: 그에 더해 t−28일 판단 봉(t−28d−1h)도 구간 안이어야 한다 — 예열 없는 실행기.
     */
    record OosWindow(Instant oosStart, Instant oosEnd, int withHistory, int noHistory) {
        /** 왕복 1건 = 매수 판단 1회 + 매도 판단 1회. 구간 끝 강제청산은 세지 않는다. */
        int maxNaturalRoundTrips() { return withHistory / 2; }
    }

    /** 운영 실행기 ROLLING 분할(WalkForwardTestRunner.runRolling)을 빈 칸 없는 H1 달력에 적용한다. */
    static List<OosWindow> rollingOosCalendar(Instant firstBar, int totalBars, double inSampleRatio, int windowCount) {
        int windowSize = totalBars / windowCount;
        int inSampleSize = Math.max(1, (int) (windowSize * inSampleRatio));
        if (inSampleSize >= windowSize) inSampleSize = windowSize - 1;
        List<OosWindow> out = new ArrayList<>();
        for (int w = 0; w < windowCount; w++) {
            int isEnd = w * windowSize + inSampleSize;
            int osEnd = Math.min(w * windowSize + windowSize, totalBars);
            if (isEnd >= totalBars || osEnd <= isEnd) break;
            Instant a = firstBar.plus(Duration.ofHours(isEnd)), b = firstBar.plus(Duration.ofHours(osEnd));
            int with = 0, without = 0;
            for (Instant t = nextMonday(a); t.isBefore(b); t = t.plus(WEEK)) {
                if (t.minus(HOUR).isBefore(a)) continue;
                with++;
                if (!t.minus(LOOKBACK).minus(HOUR).isBefore(a)) without++;
            }
            out.add(new OosWindow(a, b, with, without));
        }
        return out;
    }
}
