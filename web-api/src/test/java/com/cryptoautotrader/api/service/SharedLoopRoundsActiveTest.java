package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회차가 켜진 공유 루프 — 기존 76 + 새 9(LIVE_ALIGNED) + REAL 후보·REAL 열린 포지션 (2026-11-11, 총괄 v1.145 §3-3).
 *
 * <p>v1.144 F({@link SharedPaperLoopIsolationTest})는 회차 비활성·A/B 종전 PAPER·REAL 표 0행이었다. 여기서는 회차를 켜고, 새 9를 모두 확정 모드
 * (LIVE_ALIGNED)로 두고, 같은 H1 회차에 실자금 SMA20 후보와 실자금 열린 포지션을 함께 둔다. 같은 입력으로 대조(새 9 없음) → 처리(새 9 있음)
 * → 대조 재실행을 돌려 기존 76의 결정·REAL 후보 결과와 순위·REAL 주문이 같은지, PAPER 후보가 REAL 뒤에 처리되는지, 틱·승인·실시간
 * 처리 시간을 본다. 회차 객체의 열린/닫힌 회차는 실행 <b>사이에만</b> 비운다(같은 회차 키로 비교하기 위해). 실행 안에서는 건드리지 않는다.</p>
 *
 * <p>한계: H2·합성 캔들·시험 컨텍스트이고, 실행마다 표본 1개다. 시간은 상대 비교용이며 운영 부하 근거가 아니다. 사전 등록 세션의 세부 설정은
 * 운영에서 읽지 않았다(메타데이터만). 실시간 처리기는 프록시 없이 동기로 불러 처리 시간만 잰다 — 실행기 대기열 효과는 별도 시험이 본다.</p>
 */
@DisplayName("회차 활성 공유 루프 — 기존 76 + 새 9(LIVE_ALIGNED) + REAL 후보·포지션: 기존 결정·REAL 불변, PAPER 는 REAL 뒤")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=SharedLoopRoundsActiveTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SharedLoopRoundsActiveTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient upbitWebSocketClient;
    @Autowired private WsSubscriptionManager wsSubscriptionManager;
    @Autowired private ApplicationEventPublisher eventPublisher;

    private static final List<String> M15_STAND_IN = SharedPaperLoopIsolationTest.M15_STAND_IN;

    record Spec(String key, String strategy, String coin, String tf, BigDecimal capital, Map<String, Object> params,
                BigDecimal ratio, BigDecimal sl, Integer maxHold, boolean isNew) {}

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "rounds-active";
    }

    private PaperTradingService paperTarget() {
        return AopTestUtils.getUltimateTargetObject(paperTradingService);
    }

    private LiveTradingService liveTarget() {
        return AopTestUtils.getUltimateTargetObject(liveTradingService);
    }

    private static Map<String, Object> aligned() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    private List<Spec> existing() {
        List<Spec> out = new ArrayList<>();
        for (String coin : SharedPaperLoopIsolationTest.PREREG_COINS) {
            for (String arm : SharedPaperLoopIsolationTest.PREREG_ARMS) {
                out.add(new Spec("PRE|" + arm + "|" + coin, arm, coin, "H1", new BigDecimal("1000000"), null, null, null, 0, false));
            }
        }
        for (var e : SharedPaperLoopIsolationTest.OTHER_M15.entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                String strat = M15_STAND_IN.get(i % 2);
                out.add(new Spec("OTH|" + strat + "|" + e.getKey() + "|" + i, strat, e.getKey(), "M15", new BigDecimal("1000000"),
                        null, null, null, 24, false));
            }
        }
        return out;
    }

    /** 새 9 — 모두 확정 모드 LIVE_ALIGNED (v1.145 결정: A/B 양 팔과 SMA20 전향 모두). */
    private List<Spec> fresh() {
        List<Spec> out = new ArrayList<>();
        out.add(new Spec("NEW|SMA20", DailySmaTrendStrategy.NAME, BTC, "H1", new BigDecimal("20200"), aligned(),
                new BigDecimal("0.995"), new BigDecimal("5.00"), 0, true));
        for (String strat : M15_STAND_IN) {
            for (String coin : List.of(BTC, ETH)) {
                for (String arm : List.of("A", "B")) {
                    out.add(new Spec("NEW|AB|" + strat + "|" + coin + "|" + arm, strat, coin, "M15", new BigDecimal("100000"), aligned(),
                            new BigDecimal("0.8"), "B".equals(arm) ? new BigDecimal("5") : null, 24, true));
                }
            }
        }
        return out;
    }

    /** {@link SharedPaperLoopIsolationTest} 와 같은 물결 캔들. */
    private void waveCandles(String coin, String tf, Instant last, long stepMin) {
        int seed = Math.abs(coin.hashCode() % 97);
        long base = 1_000_000L + seed * 10_000L;
        List<com.cryptoautotrader.api.entity.MarketDataCacheEntity> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            double wave = Math.sin((i + seed) / 9.0) * 0.03 + Math.sin((i + seed * 3) / 37.0) * 0.05 + i * 0.0004;
            double prevWave = Math.sin((i - 1 + seed) / 9.0) * 0.03 + Math.sin((i - 1 + seed * 3) / 37.0) * 0.05 + (i - 1) * 0.0004;
            BigDecimal c = BigDecimal.valueOf(Math.round(base * (1 + wave)));
            BigDecimal o = BigDecimal.valueOf(Math.round(base * (1 + prevWave)));
            BigDecimal h = c.max(o).multiply(new BigDecimal("1.004")).setScale(0, RoundingMode.HALF_UP);
            BigDecimal l = c.min(o).multiply(new BigDecimal("0.996")).setScale(0, RoundingMode.HALF_UP);
            rows.add(com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                    .time(last.minus((499 - i) * stepMin, ChronoUnit.MINUTES)).coinPair(coin).timeframe(tf)
                    .open(o).high(h).low(l).close(c).volume(new BigDecimal("1000")).build());
        }
        cacheRepository.saveAll(rows);
    }

    /**
     * 평탄한 흐름 끝에 마지막 봉만 drop 만큼 하락(거래량 3배) — COMPOSITE_MEANREV_BB 가 BUY 를 내는 모양(전략 함수 직접 탐색으로 확인, 근거 probe.xml).
     * A/B 의 M15 PAPER 팔이 실제 회차 후보가 되게 하려고 ETH M15 에만 쓴다. 세 실행에 같은 값.
     */
    private void flatDropCandles(String coin, String tf, Instant last, long stepMin, double drop) {
        double base = 3_000_000;
        List<com.cryptoautotrader.api.entity.MarketDataCacheEntity> rows = new ArrayList<>();
        double prev = base;
        for (int i = 0; i < 500; i++) {
            double c = base * (1 + 0.002 * Math.sin(i / 3.0)) * (i == 499 ? (1 - drop) : 1);
            double o = i == 0 ? c : prev;
            rows.add(com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                    .time(last.minus((499 - i) * stepMin, ChronoUnit.MINUTES)).coinPair(coin).timeframe(tf)
                    .open(BigDecimal.valueOf(Math.round(o))).high(BigDecimal.valueOf(Math.round(Math.max(o, c) * 1.001)))
                    .low(BigDecimal.valueOf(Math.round(Math.min(o, c) * 0.999))).close(BigDecimal.valueOf(Math.round(c)))
                    .volume(new BigDecimal(i == 499 ? "3000" : "1000")).build());
            prev = c;
        }
        cacheRepository.saveAll(rows);
    }

    private Map<String, Long> createAll(List<Spec> specs) {
        Map<String, Long> ids = new LinkedHashMap<>();
        Instant base = Instant.now().minus(30, ChronoUnit.DAYS);
        int n = 0;
        for (Spec s : specs) {
            Instant started = s.isNew() ? Instant.now().minus(5, ChronoUnit.MINUTES).plusMillis(n) : base.plusMillis(n);
            n++;
            VirtualBalanceEntity v = balanceRepo.save(VirtualBalanceEntity.builder()
                    .strategyName(s.strategy()).coinPair(s.coin()).timeframe(s.tf())
                    .initialCapital(s.capital()).availableKrw(s.capital()).totalKrw(s.capital())
                    .investRatio(s.ratio()).stopLossPct(s.sl()).maxHoldHours(s.maxHold())
                    .strategyParams(s.params() == null ? null : new java.util.HashMap<>(s.params()))
                    .status("RUNNING").startedAt(started).build());
            ids.put(s.key(), v.getId());
            paperIds.add(v.getId());
        }
        return ids;
    }

    private Map<String, String> snapshot(Map<String, Long> ids, List<Spec> which) {
        Map<String, String> out = new TreeMap<>();
        for (Spec s : which) {
            Long id = ids.get(s.key());
            List<StrategyLogEntity> logs = strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("PAPER", id);
            List<PaperPositionEntity> pos = paperPositionRepo.findBySessionId(id);
            VirtualBalanceEntity v = balanceRepo.findById(id).orElseThrow();
            out.put(s.key(), String.format("logs=%d last=%s pos=%d entry=%s status=%s exit=%s realized=%s avail=%s total=%s", logs.size(),
                    logs.isEmpty() ? "-" : logs.get(0).getSignal() + ":" + logs.get(0).getReason() + ":" + logs.get(0).getBlockedReason(),
                    pos.size(), pos.isEmpty() ? "-" : pos.get(0).getEntryPrice().stripTrailingZeros().toPlainString(),
                    pos.isEmpty() ? "-" : pos.get(0).getStatus(), pos.isEmpty() ? "-" : pos.get(0).getExitReason(),
                    pos.isEmpty() || pos.get(0).getRealizedPnl() == null ? "-" : pos.get(0).getRealizedPnl().stripTrailingZeros().toPlainString(),
                    v.getAvailableKrw().stripTrailingZeros().toPlainString(),
                    v.getTotalKrw() == null ? "-" : v.getTotalKrw().stripTrailingZeros().toPlainString()));
        }
        return out;
    }

    /**
     * 기존 세션 셋 중 하나에 손절가가 현재 종가 위인 열린 포지션(이 틱에 손절), 셋 중 하나에 먼 손절·익절의 열린 포지션(유지)을 넣는다
     * (시험 가지 cfef638b 와 같은 방식, 총괄 v1.147 §4 — 기존 보유의 보호 청산을 회차 활성 실행에 넣음). 세 실행에 같은 값.
     */
    private int seedPositions(Map<String, Long> ids, List<Spec> which) {
        int n = 0, i = 0;
        for (Spec s : which) {
            int k = i++ % 3;
            if (k == 2) continue;
            BigDecimal close = jdbc.queryForObject("SELECT close FROM market_data_cache WHERE coin_pair = ? AND timeframe = ? ORDER BY time DESC LIMIT 1",
                    BigDecimal.class, s.coin(), s.tf());
            BigDecimal invest = new BigDecimal("100000");
            BigDecimal size = invest.divide(close, 8, RoundingMode.DOWN);
            BigDecimal sl = close.multiply(k == 0 ? new BigDecimal("1.01") : new BigDecimal("0.80"));
            BigDecimal tp = close.multiply(new BigDecimal("1.20"));
            Long id = ids.get(s.key());
            paperPositionRepo.save(PaperPositionEntity.builder().sessionKind("PAPER").sessionId(id).coinPair(s.coin()).side("BUY")
                    .entryPrice(close).avgPrice(close).size(size).positionFee(BigDecimal.ZERO).investedKrw(invest)
                    .stopLossPrice(sl).takeProfitPrice(tp).status("OPEN").openedAt(Instant.now().minus(5, ChronoUnit.HOURS)).build());
            VirtualBalanceEntity v = balanceRepo.findById(id).orElseThrow();
            v.setAvailableKrw(v.getAvailableKrw().subtract(invest));
            balanceRepo.save(v);
            n++;
        }
        return n;
    }

    /** 실행 사이에만 — 같은 회차 키로 다시 비교하려고 회차 객체의 열린·닫힌 회차를 비운다. */
    @SuppressWarnings("unchecked")
    private void resetRoundsBetweenRuns() {
        Object target = AopTestUtils.getUltimateTargetObject(rounds);
        synchronized (target) {
            ((Map<?, ?>) ReflectionTestUtils.getField(target, "open")).clear();
            ((Map<?, ?>) ReflectionTestUtils.getField(target, "closed")).clear();
        }
    }

    record RunResult(String name, Map<String, String> existing, String realOutcome, int realIndex, int firstPaperIndex,
                     long paperCandidates, long paperH1, long paperM15, String realOrders, String realAvail, String realEthPos,
                     long evalLiveMs, long tickMs, long approveMs, long rtLiveEthUs, long rtLiveBtcUs, long rtPaperEthUs,
                     List<String> subscribed, List<String> outcomes) {}

    private RunResult run(String name, boolean withNew) {
        resetRoundsBetweenRuns();
        clearRealtimeThrottle();
        List<Spec> specs = new ArrayList<>(existing());
        if (withNew) specs.addAll(fresh());
        LiveTradingSessionEntity l = live(BTC);
        LiveTradingSessionEntity le = live(ETH);
        BigDecimal ethEntry = new BigDecimal("3000000");
        var ethPos = livePos(le, ethEntry, ethEntry.multiply(new BigDecimal("0.90")), ethEntry.multiply(new BigDecimal("1.20")),
                Instant.now().minus(10, ChronoUnit.HOURS));
        Map<String, Long> ids = createAll(specs);
        seedPositions(ids, existing());

        long t0 = System.nanoTime();
        evaluateLive(l);
        long evalLiveMs = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        paperTradingService.runStrategy();   // 운영 스케줄러 메서드 그대로 — 기존 76 평가 + 새 9 접수 + 실시간 코인 갱신
        long tickMs = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        List<EntryPriorityRounds.Outcome> out = approveRound();   // H1·M15 회차 마감이 같다(lastOpen+65분)
        long approveMs = (System.nanoTime() - t0) / 1_000_000;

        List<String> outcomes = out.stream().map(o -> o.candidate().kind() + "#" + o.candidate().round().timeframe() + "#"
                + (o.candidate().sessionId().equals(l.getId()) ? "REAL-SMA20" : String.valueOf(o.candidate().sessionId()))
                + "=" + (o.reason() == null ? "진입" : o.reason().replaceAll("\\(.*?\\)", "(…)"))).toList();
        int realIndex = -1;
        int firstPaper = -1;
        for (int i = 0; i < out.size(); i++) {
            if (!EntryPriorityRounds.KIND_PAPER.equals(out.get(i).candidate().kind()) && out.get(i).candidate().sessionId().equals(l.getId())) realIndex = i;
            if (EntryPriorityRounds.KIND_PAPER.equals(out.get(i).candidate().kind()) && firstPaper < 0) firstPaper = i;
        }
        String realOutcome = realIndex < 0 ? "후보 없음" : (out.get(realIndex).reason() == null ? "진입" : out.get(realIndex).reason());
        long paperCandidates = out.stream().filter(o -> EntryPriorityRounds.KIND_PAPER.equals(o.candidate().kind())).count();
        long paperH1 = out.stream().filter(o -> EntryPriorityRounds.KIND_PAPER.equals(o.candidate().kind()) && "H1".equals(o.candidate().round().timeframe())).count();
        long paperM15 = paperCandidates - paperH1;

        // 실시간 — REAL 열린 포지션 코인(ETH, 손절 아님)과 BTC. 동기 호출 처리 시간(µs). 실행 시작에 간격 지도를 비웠으므로 각 1회는 확인된다.
        BigDecimal ethNow = ethEntry.multiply(new BigDecimal("1.01"));
        t0 = System.nanoTime();
        liveTarget().onRealtimePriceEvent(new RealtimePriceEvent(ETH, ethNow, Instant.now(), RealtimePriceEvent.WS_TRADE));
        long rtLiveEthUs = (System.nanoTime() - t0) / 1_000;
        t0 = System.nanoTime();
        liveTarget().onRealtimePriceEvent(new RealtimePriceEvent(BTC, lastClose(uptrend(lastOpen)), Instant.now(), RealtimePriceEvent.WS_TRADE));
        long rtLiveBtcUs = (System.nanoTime() - t0) / 1_000;
        ((Map<?, ?>) ReflectionTestUtils.getField(paperTarget(), "alignedRtLastCheckMs")).remove(ETH);
        t0 = System.nanoTime();
        paperTarget().doOnAlignedRealtimePrice(new RealtimePriceEvent(ETH, ethNow, Instant.now(), RealtimePriceEvent.WS_TRADE));
        long rtPaperEthUs = (System.nanoTime() - t0) / 1_000;

        Map<String, String> snap = snapshot(ids, existing());
        List<OrderEntity> buys = liveOrders(l, "BUY");
        String realOrders = buys.size() + "건 " + buys.stream().map(o -> o.getSide() + ":" + o.getQuantity().stripTrailingZeros().toPlainString()).toList();
        String realAvail = liveRepo.findById(l.getId()).orElseThrow().getAvailableKrw().stripTrailingZeros().toPlainString()
                + "/" + liveRepo.findById(le.getId()).orElseThrow().getAvailableKrw().stripTrailingZeros().toPlainString();
        var ethAfter = positionRepository.findById(ethPos.getId()).orElseThrow();
        String realEthPos = ethAfter.getStatus() + " SL=" + ethAfter.getStopLossPrice().stripTrailingZeros().toPlainString()
                + " TP=" + ethAfter.getTakeProfitPrice().stripTrailingZeros().toPlainString();
        List<String> subscribed = new ArrayList<>(wsSubscriptionManager.getSubscribedCoins());
        java.util.Collections.sort(subscribed);

        // 다음 실행 전 정리 — 이 실행의 세션만(fixture tearDown 과 같은 방식). LIVE 주문 실행기를 먼저 비운다.
        awaitDrained();
        cleanupRun(ids, List.of(l.getId(), le.getId()));
        return new RunResult(name, snap, realOutcome, realIndex, firstPaper, paperCandidates, paperH1, paperM15, realOrders, realAvail,
                realEthPos, evalLiveMs, tickMs, approveMs, rtLiveEthUs, rtLiveBtcUs, rtPaperEthUs, subscribed, outcomes);
    }

    private void cleanupRun(Map<String, Long> ids, List<Long> lives) {
        for (Long id : lives) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(strategyLogRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
            liveIds.remove(id);
        }
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        for (Long id : ids.values()) {
            tx.executeWithoutResult(st -> paperOrderRepo.deleteBySessionId(id));
            paperPositionRepo.findBySessionId(id).forEach(paperPositionRepo::delete);
            strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("PAPER", id).forEach(strategyLogRepository::delete);
            balanceRepo.findById(id).ifPresent(balanceRepo::delete);
            paperIds.remove(id);
        }
    }

    @Test
    @DisplayName("대조(새 9 없음) → 처리(새 9) → 대조 재실행: 기존 76 결정·REAL 후보 결과·REAL 주문 같음, PAPER 후보는 모든 REAL 뒤")
    void roundsActiveExistingAndRealUnchanged() throws IOException {
        assertThat(balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING")).as("시작 전 RUNNING PAPER").isEmpty();
        Instant m15Last = lastOpen.plus(45, ChronoUnit.MINUTES);
        List<String> h1Coins = new ArrayList<>(SharedPaperLoopIsolationTest.PREREG_COINS);
        List<String> m15Coins = new ArrayList<>(SharedPaperLoopIsolationTest.OTHER_M15.keySet());
        try {
            for (String c : h1Coins) waveCandles(c, "H1", lastOpen, 60);
            insertCache(BTC, uptrend(lastOpen));   // REAL·새 SMA20 같은 BUY 입력
            for (String c : m15Coins) {
                if (ETH.equals(c)) flatDropCandles(c, "M15", m15Last, 15, 0.02);   // A/B MEANREV ETH 팔이 실제 M15 후보가 되도록
                else waveCandles(c, "M15", m15Last, 15);
            }
            assertThat(EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("M15", m15Last)))
                    .as("H1·M15 회차 마감이 같다").isEqualTo(EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("H1", lastOpen)));

            RunResult c1 = run("대조1(새 9 없음)", false);
            RunResult t = run("처리(새 9)", true);
            RunResult c2 = run("대조2(새 9 없음)", false);

            long changedT = c1.existing().keySet().stream().filter(k -> !c1.existing().get(k).equals(t.existing().get(k))).count();
            long changedC2 = c1.existing().keySet().stream().filter(k -> !c1.existing().get(k).equals(c2.existing().get(k))).count();
            long withSignal = c1.existing().values().stream().filter(v -> !v.contains("last=-")).count();
            long withPos = c1.existing().values().stream().filter(v -> !v.contains("pos=0")).count();
            long stopped = c1.existing().values().stream().filter(v -> v.contains("status=CLOSED")).count();
            long held = c1.existing().values().stream().filter(v -> v.contains("status=OPEN")).count();

            StringBuilder sb = new StringBuilder();
            sb.append("environment=H2 · 합성 캔들 · Spring 시험 컨텍스트 · 실행당 표본 1 · 시간은 상대 비교용(운영 부하 근거 아님)\n");
            sb.append("existing_sessions=").append(c1.existing().size()).append(" with_signal_log=").append(withSignal)
                    .append(" with_position=").append(withPos).append(" (CLOSED ").append(stopped).append(", OPEN ").append(held).append(")")
                    .append(" changed(treatment)=").append(changedT)
                    .append(" changed(control2)=").append(changedC2).append('\n');
            for (RunResult r : List.of(c1, t, c2)) {
                sb.append("--- ").append(r.name()).append('\n');
                sb.append("real_outcome=").append(r.realOutcome()).append(" real_index=").append(r.realIndex())
                        .append(" first_paper_index=").append(r.firstPaperIndex()).append(" paper_candidates=").append(r.paperCandidates())
                        .append(" (H1 ").append(r.paperH1()).append(", M15 ").append(r.paperM15()).append(")\n");
                sb.append("real_orders=").append(r.realOrders()).append(" real_avail(BTC/ETH)=").append(r.realAvail())
                        .append(" real_eth_pos=").append(r.realEthPos()).append('\n');
                sb.append("ms eval_live=").append(r.evalLiveMs()).append(" paper_tick=").append(r.tickMs())
                        .append(" approve_round=").append(r.approveMs()).append('\n');
                sb.append("us rt_live_eth=").append(r.rtLiveEthUs()).append(" rt_live_btc=").append(r.rtLiveBtcUs())
                        .append(" rt_paper_eth=").append(r.rtPaperEthUs()).append('\n');
                sb.append("ws_subscribed=").append(r.subscribed()).append('\n');
                sb.append("outcomes=").append(r.outcomes()).append('\n');
            }
            sb.append("--- existing (control1 | treatment | control2)\n");
            for (String k : c1.existing().keySet()) {
                sb.append(k).append('\t').append(c1.existing().get(k)).append('\t').append(t.existing().get(k)).append('\t')
                        .append(c2.existing().get(k)).append('\n');
            }
            Path outFile = Path.of("build", "rounds-active", "report.txt");
            Files.createDirectories(outFile.getParent());
            Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("[회차 활성 공유 루프]\n" + sb);

            assertThat(withPos).as("기존 보유가 실제로 들어갔다").isGreaterThan(0);
            assertThat(stopped).as("이 틱에 손절된 기존 보유가 있다(보호 청산 경로)").isGreaterThan(0);
            assertThat(changedT).as("기존 76 결정 변화(처리 대 대조1)").isZero();
            assertThat(changedC2).as("기존 76 결정 변화(대조2 대 대조1)").isZero();
            for (RunResult r : List.of(c1, t, c2)) {
                assertThat(r.realOutcome()).as(r.name() + " REAL 후보 결과").isEqualTo("진입");
                assertThat(r.realOrders()).as(r.name() + " REAL 주문").isEqualTo(c1.realOrders());
                assertThat(r.realAvail()).as(r.name() + " REAL 가용 잔액").isEqualTo(c1.realAvail());
                assertThat(r.realEthPos()).as(r.name() + " REAL 열린 포지션").isEqualTo(c1.realEthPos());
            }
            assertThat(c1.paperCandidates()).isZero();
            assertThat(c2.paperCandidates()).isZero();
            assertThat(t.paperH1()).as("새 SMA20(LIVE_ALIGNED) 이 같은 H1 회차 후보").isEqualTo(1);
            assertThat(t.paperM15()).as("A/B M15 팔이 실제 다른 마감 회차 후보(같은 processDue)").isGreaterThan(0);
            assertThat(t.firstPaperIndex()).as("PAPER 후보가 REAL 후보 뒤").isGreaterThan(t.realIndex());
            for (int i = 0; i < t.outcomes().size(); i++) {
                if (t.outcomes().get(i).startsWith("PAPER")) {
                    assertThat(t.outcomes().subList(i, t.outcomes().size())).as("PAPER 뒤에 실자금 후보가 없다")
                            .allMatch(s -> s.startsWith("PAPER"));
                    break;
                }
            }
        } finally {
            jdbc.update("DELETE FROM market_data_cache WHERE timeframe IN ('H1','M15') AND coin_pair IN ("
                    + String.join(",", java.util.stream.Stream.concat(h1Coins.stream(), m15Coins.stream())
                    .distinct().map(c -> "'" + c + "'").toList()) + ")");
        }
    }

    @Test
    @DisplayName("실시간 처리기 — 선택 모드 코인이 아니면 실행기 작업 0, 거절돼도 예외를 발행 쪽으로 올리지 않는다")
    void alignedRealtimeListenerAddsNoTaskForOtherCoinsAndContainsRejection() {
        insertCache(BTC, uptrend(lastOpen));
        VirtualBalanceEntity p = paper(BTC);
        ReflectionTestUtils.invokeMethod(paperTarget(), "refreshAlignedCoins", balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING"));
        Object original = ReflectionTestUtils.getField(paperTarget(), "alignedRtExecutor");
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger offered = new AtomicInteger();
        try {
            Executor counting = r -> { offered.incrementAndGet(); accepted.incrementAndGet(); };
            ReflectionTestUtils.setField(paperTarget(), "alignedRtExecutor", counting);
            paperTarget().onRealtimePriceEvent(new RealtimePriceEvent("KRW-ANKR", new BigDecimal("100"), Instant.now(), RealtimePriceEvent.WS_TRADE));
            int forOther = offered.get();
            paperTarget().onRealtimePriceEvent(new RealtimePriceEvent(BTC, new BigDecimal("12000000"), Instant.now(), RealtimePriceEvent.WS_TRADE));
            int forAligned = offered.get() - forOther;

            Executor rejecting = r -> { offered.incrementAndGet(); throw new RejectedExecutionException("시험 — 대기열 가득"); };
            ReflectionTestUtils.setField(paperTarget(), "alignedRtExecutor", rejecting);
            long rejectedBefore = paperTarget().alignedRtRejected.get();
            Throwable thrown = null;
            try {
                // 운영 발행 경로 그대로 — 동기 리스너(시세 장부)는 받고, PAPER 거절은 발행 쪽으로 올라오지 않는다.
                eventPublisher.publishEvent(new RealtimePriceEvent(BTC, new BigDecimal("12000001"), Instant.now(), RealtimePriceEvent.WS_TRADE));
            } catch (Throwable t) {
                thrown = t;
            }
            long rejectedNow = paperTarget().alignedRtRejected.get() - rejectedBefore;
            BigDecimal mark = markPriceBook.freshPrice(BTC, Instant.now());
            System.out.println("[실시간 처리기] 비대상 코인 작업=" + forOther + " 대상 코인 작업=" + forAligned + " 거절 시 예외=" + thrown
                    + " 거절 수=" + rejectedNow + " 시세 장부 BTC=" + mark);
            assertThat(forOther).as("선택 모드 코인이 아니면 작업을 만들지 않는다").isZero();
            assertThat(forAligned).isEqualTo(1);
            assertThat(thrown).as("PAPER 작업 거절이 발행 쪽으로 올라오면 다른 리스너의 수신이 끊길 수 있다").isNull();
            assertThat(rejectedNow).isEqualTo(1);
            assertThat(mark).as("같은 이벤트를 동기 리스너(시세 장부)가 받았다").isEqualByComparingTo("12000001");
        } finally {
            ReflectionTestUtils.setField(paperTarget(), "alignedRtExecutor", original);
        }
    }
}
