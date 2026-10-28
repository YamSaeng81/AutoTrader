package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.core.selector.CompositePresets;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * #9 D3 — 운영과 백테스트의 <b>전략 호출 경로 차이</b>가 신호를 바꾸는지 가짜 자료로 확인한다 (리뷰 11차).
 *
 * <h3>확인한 두 호출 경로 (코드)</h3>
 * <ul>
 *   <li><b>운영</b>(LIVE·PAPER·DYNAMIC): 60초 틱마다 돌지만 전략 평가는 <b>새 완료 봉에서 한 번</b>
 *       ({@code lastEvaluatedClosedCandle}). 창은 최근 500봉({@code CANDLE_LOOKBACK}).
 *       팩토리 등록 전략은 세션(DYNAMIC 은 세션·코인)별 인스턴스를 메모리에 두고 계속 쓴다 —
 *       서버 재시작·세션 재시작 때 새로 만든다. 팩토리가 없는 전략은 레지스트리 공유 인스턴스.</li>
 *   <li><b>백테스트</b>: 봉마다 한 번 평가. 실행(WF 구간)마다 새 인스턴스. 창은
 *       {@code [max(구간 시작, i−499), i]} — 구간 첫 499봉 동안은 운영보다 <b>짧은 창</b>이다.</li>
 * </ul>
 *
 * <h3>비교 시나리오 (모두 같은 가짜 캔들, 같은 시각의 신호 동작 BUY/SELL/HOLD 비교)</h3>
 * <ul>
 *   <li>REF — 운영식: P0 에 만든 인스턴스, 매 봉 500봉 창.</li>
 *   <li>BT — 백테스트식: 구간 시작 S 에 새 인스턴스, 구간 안에서 자라는 창. 짧은 창 구간과 창이 같아진 뒤를 나눠 센다.</li>
 *   <li>NEW — 인스턴스 시작 시점만 다름: 재시작 지점 10곳에서 새 인스턴스, 500봉 창, 각 300봉 비교
 *       (서버·세션 재시작과 같은 상황).</li>
 *   <li>SKIP — 운영에서 완료 봉 평가를 2회씩 놓침: 한 인스턴스가 세 지점에서 놓친다 (서버 중단·자료 지연).</li>
 *   <li>SHARED — 팩토리 없는 전략만: 공유 인스턴스를 두 코인이 번갈아 부를 때.</li>
 * </ul>
 * <p>대조 2개: 호출 횟수를 세는 전략({@code CONTROL_CALLCOUNT})과 히스테리시스 레짐 감지기 출력을 그대로
 * 신호로 내는 전략({@code CONTROL_HYSTERESIS}). 이 둘에서 차이가 나와야 시험이 상태 차이를 잡을 수 있다는 뜻이다.
 * 자료 시드 2개로 돌린다.</p>
 * <p>성과는 재지 않는다. 신호 일치 여부만 본다. 실제 시세 자료는 쓰지 않는다.
 * 운영 입력(동적 가중치 {@code WeightOverrideStore}, 실호가)은 이 시험으로 재현할 수 없어 코드 확인 표에 따로 적는다.</p>
 *
 * <pre>
 * ./gradlew :core-engine:test --tests '*StrategyCallPathDivergenceRunner' -Dcallpath.out=d:/tmp/callpath/callpath.csv
 * </pre>
 */
class StrategyCallPathDivergenceRunner {

    /** NEXT_CANDIDATE_PLAN.md §2 잠정 후보 풀 24개. */
    static final List<String> POOL = List.of(
            "ATR_BREAKOUT", "BOLLINGER", "EMA_CROSS", "FAIR_VALUE_GAP", "GRID", "HEIKIN_ASHI_STOCH", "MACD",
            "MACD_STOCH_BB", "RSI", "STOCHASTIC_RSI", "SUPERTREND", "VOLUME_DELTA", "VWAP",
            "COMPOSITE", "COMPOSITE_REGIME_ROUTER", "COMPOSITE_MOMENTUM", "COMPOSITE_ETH", "COMPOSITE_BREAKOUT",
            "COMPOSITE_MOMENTUM_ICHIMOKU", "COMPOSITE_BREAKOUT_ICHIMOKU", "COMPOSITE_MTF_CONFIRMED",
            "COMPOSITE_MTF_BTC", "COMPOSITE_PULLBACK_MTF", "COMPOSITE_MEANREV_BB");

    static final int LOOKBACK = 500;   // 운영 CANDLE_LOOKBACK = 백테스트 MAX_LOOKBACK
    static final int N = 2600;
    static final int P0 = 500;         // 운영 세션 시작 (창이 이미 500봉)
    static final int S = 1100;         // WF 구간 시작
    static final int[] RESTARTS = {600, 773, 946, 1119, 1292, 1465, 1638, 1811, 1984, 2157}; // 재시작 지점
    static final int RESTART_SPAN = 300;
    static final int[] SKIPS = {1000, 1500, 2000};   // 평가 누락 지점 (M, M+1)
    static final long[] SEEDS = {20261028L, 20261035L};
    static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    /** 대조: 호출 횟수에 따라 신호를 낸다 — 호출 순서·인스턴스 수명 차이를 반드시 드러내야 한다. */
    static class CallCountControl implements Strategy {
        private int calls = 0;
        @Override public String getName() { return "CONTROL_CALLCOUNT"; }
        @Override public int getMinimumCandleCount() { return 20; }
        @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) {
            return (calls++ % 7 == 0) ? StrategySignal.buy(BigDecimal.ONE, "대조") : StrategySignal.hold("대조");
        }
    }

    /** 대조: 히스테리시스 레짐 감지기(상태 보유)의 출력을 신호로 — TREND=BUY, VOLATILITY=SELL. */
    static class HysteresisControl implements Strategy {
        private final MarketRegimeDetector detector = new MarketRegimeDetector();
        @Override public String getName() { return "CONTROL_HYSTERESIS"; }
        @Override public int getMinimumCandleCount() { return MarketRegimeDetector.MIN_CANDLE_COUNT; }
        @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) {
            MarketRegime r = detector.detect(c);
            if (r == MarketRegime.TREND) return StrategySignal.buy(BigDecimal.ONE, "대조");
            if (r == MarketRegime.VOLATILITY) return StrategySignal.sell(BigDecimal.ONE, "대조");
            return StrategySignal.hold("대조");
        }
    }

    /** 추세 상승·횡보·급변동·추세 하락 구간을 번갈아 둔 가짜 H1 캔들 (고정 시드). */
    static List<Candle> synthetic(long seed) {
        Random rnd = new Random(seed);
        double[][] regimes = { {0.0010, 0.006}, {0.0, 0.003}, {0.0, 0.02}, {-0.0010, 0.006} }; // {추세, 변동성}
        List<Candle> out = new ArrayList<>();
        double price = 1000.0;
        int i = 0, block = 0;
        while (i < N) {
            double[] r = regimes[block++ % regimes.length];
            int len = 120 + rnd.nextInt(200);
            for (int k = 0; k < len && i < N; k++, i++) {
                double open = price;
                double close = open * Math.exp(r[0] + r[1] * rnd.nextGaussian());
                double hi = Math.max(open, close) * (1 + Math.abs(rnd.nextGaussian()) * r[1] * 0.5);
                double lo = Math.min(open, close) * (1 - Math.abs(rnd.nextGaussian()) * r[1] * 0.5);
                double vol = 1000 * Math.exp(0.5 * rnd.nextGaussian());
                out.add(Candle.builder().time(T0.plus(Duration.ofHours(i)))
                        .open(bd(open)).high(bd(hi)).low(bd(lo)).close(bd(close)).volume(bd(vol)).build());
                price = close;
            }
        }
        return out;
    }

    static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP);
    }

    static Strategy instance(String name) {
        if (name.equals("CONTROL_CALLCOUNT")) return new CallCountControl();
        if (name.equals("CONTROL_HYSTERESIS")) return new HysteresisControl();
        return StrategyRegistry.hasFactory(name) ? StrategyRegistry.createNew(name) : StrategyRegistry.get(name);
    }

    static Map<String, Object> params(String coin) {
        Map<String, Object> p = new HashMap<>();
        p.put("coinPair", coin);
        p.put("timeframe", "H1");
        return p;
    }

    static StrategySignal.Action eval(Strategy s, List<Candle> c, int from, int t, String coin) {
        return s.evaluate(c.subList(from, t + 1), params(coin)).getAction();
    }

    /** 비교 결과: 비교 봉 수 · 불일치 수 · BUY 관련 불일치 수 · 마지막 불일치의 기준점 대비 오프셋(-1 = 없음). */
    record Diff(int compared, int diff, int buyDiff, int lastOffset) {
        static Diff of(StrategySignal.Action[] ref, StrategySignal.Action[] x, int from, int to, int origin) {
            int cmp = 0, d = 0, bd = 0, last = -1;
            for (int t = from; t <= to; t++) {
                if (ref[t] == null || x[t] == null) continue;
                cmp++;
                if (ref[t] != x[t]) {
                    d++;
                    if (ref[t] == StrategySignal.Action.BUY || x[t] == StrategySignal.Action.BUY) bd++;
                    last = t - origin;
                }
            }
            return new Diff(cmp, d, bd, last);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "callpath.out", matches = ".+")
    void 호출경로_신호차이() throws Exception {
        CompositePresets.ensureRegistered();
        List<String> names = new ArrayList<>(List.of("CONTROL_CALLCOUNT", "CONTROL_HYSTERESIS"));
        names.addAll(POOL);
        int last = N - 2; // 백테스트 루프는 마지막 봉을 평가하지 않는다

        List<String> rows = new ArrayList<>();
        rows.add("seed,strategy,factory,minCandles,refBuy,refSell,"
                + "btShortCmp,btShortDiff,btShortBuyDiff,btFullCmp,btFullDiff,btFullBuyDiff,btLastOffset,"
                + "newCmp,newDiff,newBuyDiff,newMaxLastOffset,skipCmp,skipDiff,skipBuyDiff,skipMaxLastOffset,sharedDiff");

        for (long seed : SEEDS) {
            List<Candle> a = synthetic(seed);
            List<Candle> b = synthetic(seed + 1);
            for (String name : names) {
                long t0 = System.currentTimeMillis();
                boolean control = name.startsWith("CONTROL_");
                boolean factory = control || StrategyRegistry.hasFactory(name);
                Strategy ref = instance(name), bt = instance(name), skip = instance(name);
                int min = ref.getMinimumCandleCount();

                StrategySignal.Action[] rA = new StrategySignal.Action[N];
                StrategySignal.Action[] btA = new StrategySignal.Action[N];
                StrategySignal.Action[] skA = new StrategySignal.Action[N];

                for (int t = P0; t <= last; t++) {
                    int full = t - LOOKBACK + 1;
                    rA[t] = eval(ref, a, full, t, "KRW-TEST");
                    boolean skipped = false;
                    for (int m : SKIPS) skipped |= (t == m || t == m + 1);
                    if (!skipped) skA[t] = eval(skip, a, full, t, "KRW-TEST");
                    if (t >= S + min) btA[t] = eval(bt, a, Math.max(S, full), t, "KRW-TEST");
                }

                // 재시작: 지점마다 새 인스턴스, 500봉 창
                int nCmp = 0, nDiff = 0, nBuy = 0, nLast = -1;
                for (int r : RESTARTS) {
                    Strategy nw = instance(name);
                    StrategySignal.Action[] nwA = new StrategySignal.Action[N];
                    int end = Math.min(r + RESTART_SPAN - 1, last);
                    for (int t = r; t <= end; t++) nwA[t] = eval(nw, a, t - LOOKBACK + 1, t, "KRW-TEST");
                    Diff d = Diff.of(rA, nwA, r, end, r);
                    nCmp += d.compared(); nDiff += d.diff(); nBuy += d.buyDiff(); nLast = Math.max(nLast, d.lastOffset());
                }
                // 평가 누락: 지점마다 다음 누락 전까지(최대 498봉) 비교
                int sCmp = 0, sDiff = 0, sBuy = 0, sLast = -1;
                for (int k = 0; k < SKIPS.length; k++) {
                    int from = SKIPS[k] + 2;
                    int to = Math.min(k + 1 < SKIPS.length ? SKIPS[k + 1] - 1 : last, from + 497);
                    Diff d = Diff.of(rA, skA, from, to, SKIPS[k]);
                    sCmp += d.compared(); sDiff += d.diff(); sBuy += d.buyDiff(); sLast = Math.max(sLast, d.lastOffset());
                }
                String shared = "NA";
                if (!factory) {
                    // 공유 인스턴스: 같은 객체를 두 코인이 번갈아 부른다 (운영 레지스트리 싱글턴과 같은 상황)
                    Strategy one = StrategyRegistry.get(name);
                    StrategySignal.Action[] shA = new StrategySignal.Action[N];
                    for (int t = P0; t <= last; t++) {
                        int full = t - LOOKBACK + 1;
                        shA[t] = eval(one, a, full, t, "KRW-TEST");
                        eval(one, b, full, t, "KRW-TEST2");
                    }
                    shared = String.valueOf(Diff.of(rA, shA, P0, last, P0).diff());
                }

                int refBuy = 0, refSell = 0;
                for (int t = P0; t <= last; t++) {
                    if (rA[t] == StrategySignal.Action.BUY) refBuy++;
                    if (rA[t] == StrategySignal.Action.SELL) refSell++;
                }
                Diff btShort = Diff.of(rA, btA, S + min, Math.min(S + LOOKBACK - 2, last), S);
                Diff btFull = Diff.of(rA, btA, S + LOOKBACK - 1, last, S);
                Diff btAll = Diff.of(rA, btA, S + min, last, S);

                String row = String.join(",", String.valueOf(seed), name, String.valueOf(factory), String.valueOf(min),
                        String.valueOf(refBuy), String.valueOf(refSell),
                        String.valueOf(btShort.compared()), String.valueOf(btShort.diff()), String.valueOf(btShort.buyDiff()),
                        String.valueOf(btFull.compared()), String.valueOf(btFull.diff()), String.valueOf(btFull.buyDiff()),
                        String.valueOf(btAll.lastOffset()),
                        String.valueOf(nCmp), String.valueOf(nDiff), String.valueOf(nBuy), String.valueOf(nLast),
                        String.valueOf(sCmp), String.valueOf(sDiff), String.valueOf(sBuy), String.valueOf(sLast),
                        shared);
                rows.add(row);
                System.out.println(row + "   (" + (System.currentTimeMillis() - t0) + "ms)");
            }
        }

        Path out = Path.of(System.getProperty("callpath.out"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.write(out, rows, StandardCharsets.UTF_8);
        System.out.println("저장: " + out);
    }
}
