package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * 같은 입력·같은 시각의 LIVE 고정 코인 ↔ PAPER 비교 실행기 (2026-11-10, 총괄 v1.143 판단 1·2 후속 1~2).
 *
 * <p>운영 소스(b5cd1d0 + 후보 상한)의 <b>실제 호출부</b>를 그대로 탄다 — LIVE 는 {@code evaluateAndExecuteSession} → 회차 접수 →
 * {@link EntryPriorityRounds#processDue} 승인 → {@code approveQueuedEntry} → 예약·의도({@code reserveAndPrepareIntent}) → 실행기,
 * 실시간 손절은 {@code onRealtimePriceEvent}. PAPER 는 운영 스케줄러가 세션마다 부르는 {@code runSessionStrategy}.
 * 전략 함수를 복사한 새 모의 엔진은 없다. 거래소 주문 클라이언트·텔레그램·시세 동기화는 목이고 <b>외부 주문 호출은 0</b>이다
 * (목 주문 클라이언트는 접수(wait)만 답하고 체결하지 않는다 — 체결 뒤 잔액 대조는 이 실행기 범위 밖이다).</p>
 *
 * <p>세션 조건은 SID 203 과 같은 숫자다 — 초기 20,200 · 투자 비율 0.995 · 1회 상한 20,000 · 손절 하한 5% · 시간 청산 0(끔).
 * PAPER 는 같은 숫자에 {@code paperMaxInvestment=20000}.</p>
 *
 * <p>각 시나리오의 기대(같음/다름)는 소스 읽기로 <b>실행 전에</b> 적었다. 관측이 기대와 다르면 시험이 실패한다 — 기대값을 고쳐
 * 통과시키지 않는다. 결과 표는 {@code build/sma20-same-input/report.tsv} 에 쓴다.</p>
 */
@DisplayName("SMA20 같은 입력 LIVE↔PAPER 비교 — 진입·관문·회차·가격·장중 SL/TP·시세 무효·위험/한도·청산")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=Sma20LivePaperSameInputTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class Sma20LivePaperSameInputTest extends IntegrationTestBase {

    private static final String BTC = "KRW-BTC";
    private static final String ETH = "KRW-ETH";
    private static final BigDecimal CAP = new BigDecimal("20000");

    @Autowired private EntryPriorityRounds rounds;
    @Autowired private EntryClock entryClock;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired private MarkPriceBook markPriceBook;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private StrategyLogRepository strategyLogRepository;
    @Autowired private PaperTradingService paperTradingService;
    @Autowired private VirtualBalanceRepository balanceRepo;
    @Autowired private PaperPositionRepository paperPositionRepo;
    @Autowired private PaperOrderRepository paperOrderRepo;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository cacheRepository;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("orderExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor orderExecutor;

    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private PortfolioSyncService portfolioSyncService;
    @SpyBean private RiskManagementService riskManagementService;

    /** 결과 표 — 시나리오 · 항목 · LIVE · PAPER · 기대 · 관측. */
    private static final List<String[]> ROWS = Collections.synchronizedList(new ArrayList<>());

    private String savedStage;
    private BigDecimal savedTotal;
    private BigDecimal savedPer;
    private Integer savedSlots;
    private final List<Long> liveIds = new ArrayList<>();
    private final List<Long> paperIds = new ArrayList<>();
    private Instant lastOpen;

    private static final java.util.concurrent.atomic.AtomicReference<Instant> LAST_ROUND =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.MAX);

    @BeforeEach
    void setUp() {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        limits.setStage("SAME_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        limits.setPerPositionCapKrw(CAP);
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        poolLossGuard.setEnabledSetting(true);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRisk();
        rounds.setForcedForTest(true);
        when(upbitOrderClient.createOrder(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> new com.cryptoautotrader.exchange.upbit.UpbitOrderClient.ExchangeResult(
                        accepted(inv.getArgument(0), inv.getArgument(1), inv.getArgument(4)), "{}"));
        deleteCache();
        Instant base = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        lastOpen = LAST_ROUND.updateAndGet(prev -> base.isBefore(prev) ? base : prev.minusSeconds(1));
        useCloseHour(lastOpen);
        clearRealtimeThrottle();
    }

    @AfterEach
    void tearDown() {
        awaitDrained();
        entryClock.setForTest(null);
        rounds.setForcedForTest(false);
        StrategyRegistry.register(new DailySmaTrendStrategy());
        for (Long id : liveIds) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(strategyLogRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
        TransactionTemplate tx = new TransactionTemplate(txManager);
        for (Long id : paperIds) {
            tx.executeWithoutResult(st -> paperOrderRepo.deleteBySessionId(id));
            paperPositionRepo.findBySessionId(id).forEach(paperPositionRepo::delete);
            strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("PAPER", id).forEach(strategyLogRepository::delete);
            balanceRepo.findById(id).ifPresent(balanceRepo::delete);
        }
        liveIds.clear();
        paperIds.clear();
        deleteCache();
        jdbc.update("DELETE FROM capital_stage_baseline WHERE stage = ?", limits.getStage());
        limits.setStage(savedStage);
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
    }

    @AfterAll
    static void writeReport() throws IOException {
        List<String[]> rows = new ArrayList<>(ROWS);
        rows.sort((a, b) -> (a[0] + a[1]).compareTo(b[0] + b[1]));
        StringBuilder sb = new StringBuilder("scenario\titem\tLIVE\tPAPER\tdeclared\tobserved\n");
        for (String[] r : rows) sb.append(String.join("\t", r)).append('\n');
        Path out = Path.of("build", "sma20-same-input", "report.tsv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[같은 입력 비교] " + out.toAbsolutePath() + "\n" + sb);
    }

    // ── 대역 ────────────────────────────────────────────────────────

    private static com.cryptoautotrader.exchange.upbit.dto.OrderResponse accepted(String market, String side, String ordType) {
        com.cryptoautotrader.exchange.upbit.dto.OrderResponse r = new com.cryptoautotrader.exchange.upbit.dto.OrderResponse();
        r.setUuid("same-input-" + UUID.randomUUID());
        r.setSide(side);
        r.setOrdType(ordType);
        r.setState("wait");
        r.setMarket(market);
        return r;
    }

    private void awaitDrained() {
        var pool = orderExecutor.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0
                    && pool.getTaskCount() == pool.getCompletedTaskCount()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("orderExecutor 가 비워지지 않았다");
    }

    private void deleteCache() {
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair IN (?, ?) AND timeframe = 'H1'", BTC, ETH);
    }

    private static void useCloseHour(Instant closeBarOpen) {
        StrategyRegistry.register(new DailySmaTrendStrategy(closeBarOpen.atZone(ZoneOffset.UTC).getHour()));
    }

    /** 실시간 손절 처리기의 코인별 5초 간격·급변 기록 — 시나리오끼리 섞이지 않게 비운다(운영 동작은 그대로). */
    @SuppressWarnings("unchecked")
    private void clearRealtimeThrottle() {
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        ((Map<String, ?>) ReflectionTestUtils.getField(target, "rtStopLossLastCheckMs")).clear();
        ((Map<String, ?>) ReflectionTestUtils.getField(target, "priceHistory")).clear();
    }

    // ── 자료 ────────────────────────────────────────────────────────

    /** 닫힌 H1 500봉, 마지막 봉 시작 = lastBar. */
    private static List<Candle> series(Instant lastBar, IntFunction<Long> price) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            BigDecimal c = BigDecimal.valueOf(price.apply(i));
            BigDecimal o = BigDecimal.valueOf(price.apply(Math.max(0, i - 1)));
            out.add(Candle.builder().time(lastBar.minus(499 - i, ChronoUnit.HOURS))
                    .open(o).high(c.max(o).add(BigDecimal.valueOf(1000))).low(c.min(o).subtract(BigDecimal.valueOf(1000)))
                    .close(c).volume(new BigDecimal("100")).build());
        }
        return out;
    }

    private static List<Candle> uptrend(Instant lastBar) {
        return series(lastBar, i -> 10_000_000L + 5_000L * i);
    }

    private static List<Candle> downtrend(Instant lastBar) {
        return series(lastBar, i -> 20_000_000L - 5_000L * i);
    }

    private void insertCache(String coin, List<Candle> cs) {
        cacheRepository.saveAll(cs.stream().map(c -> com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                .time(c.getTime()).coinPair(coin).timeframe("H1")
                .open(c.getOpen()).high(c.getHigh()).low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                .build()).toList());
    }

    /** 진행 중 봉(마지막 닫힌 봉 다음) — 시작은 지금보다 앞, 끝은 지금보다 뒤. */
    private void insertInProgress(String coin, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        insertCache(coin, List.of(Candle.builder().time(lastOpen.plus(1, ChronoUnit.HOURS))
                .open(open).high(high).low(low).close(close).volume(new BigDecimal("10")).build()));
    }

    private static BigDecimal lastClose(List<Candle> cs) {
        return cs.get(cs.size() - 1).getClose();
    }

    // ── 세션 ────────────────────────────────────────────────────────

    private LiveTradingSessionEntity live(String coin) {
        LiveTradingSessionEntity s = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType(DailySmaTrendStrategy.NAME).coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("20200")).availableKrw(new BigDecimal("20200")).totalAssetKrw(new BigDecimal("20200"))
                .investRatio(new BigDecimal("0.995")).maxInvestment(CAP)
                .stopLossPct(new BigDecimal("5.00")).maxHoldHours(0)
                .status("RUNNING").startedAt(Instant.now().minus(30, ChronoUnit.DAYS)).build());
        liveIds.add(s.getId());
        return s;
    }

    private VirtualBalanceEntity paper(String coin) {
        PaperTradingStartRequest req = new PaperTradingStartRequest();
        req.setStrategyType(DailySmaTrendStrategy.NAME);
        req.setCoinPair(coin);
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("20200"));
        req.setInvestRatio(new BigDecimal("0.995"));
        req.setStopLossPct(new BigDecimal("5.00"));
        req.setMaxHoldHours(0);
        Map<String, Object> p = new HashMap<>();
        p.put(PaperMaxInvestment.KEY, 20000);
        req.setStrategyParams(p);
        VirtualBalanceEntity s = paperTradingService.start(req);
        paperIds.add(s.getId());
        return s;
    }

    /** 이미 보유 중인 같은 포지션 — LIVE·PAPER 같은 진입가·SL·TP·보유 시작. */
    private PositionEntity livePos(LiveTradingSessionEntity s, BigDecimal entry, BigDecimal sl, BigDecimal tp, Instant openedAt) {
        BigDecimal size = CAP.divide(entry, 8, RoundingMode.DOWN);
        liveRepo.findById(s.getId()).ifPresent(x -> {
            x.setAvailableKrw(new BigDecimal("200"));
            liveRepo.save(x);
        });
        return positionRepository.save(PositionEntity.builder()
                .coinPair(s.getCoinPair()).side("BUY").entryPrice(entry).avgPrice(entry).size(size)
                .investedKrw(CAP).entryAllocationKrw(CAP).stopLossPrice(sl).takeProfitPrice(tp)
                .status("OPEN").sessionId(s.getId()).sessionKind("LIVE").openedAt(openedAt).build());
    }

    private PaperPositionEntity paperPos(VirtualBalanceEntity s, BigDecimal entry, BigDecimal sl, BigDecimal tp, Instant openedAt) {
        BigDecimal size = CAP.divide(entry, 8, RoundingMode.DOWN);
        VirtualBalanceEntity b = balanceRepo.findById(s.getId()).orElseThrow();
        b.setAvailableKrw(new BigDecimal("200"));
        balanceRepo.save(b);
        return paperPositionRepo.save(PaperPositionEntity.builder()
                .sessionKind("PAPER").sessionId(s.getId()).coinPair(s.getCoinPair()).side("BUY")
                .entryPrice(entry).avgPrice(entry).size(size).positionFee(BigDecimal.ZERO).investedKrw(CAP)
                .stopLossPrice(sl).takeProfitPrice(tp).status("OPEN").openedAt(openedAt).build());
    }

    // ── 엔진 호출(운영 호출부) ───────────────────────────────────────

    private void evaluateLive(LiveTradingSessionEntity s) {
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        ReflectionTestUtils.invokeMethod(target, "evaluateAndExecuteSession", liveRepo.findById(s.getId()).orElseThrow());
    }

    private List<EntryPriorityRounds.Outcome> approveRound() {
        EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        return rounds.processDue(EntryPriorityRounds.deadline(round));
    }

    private void runPaper(VirtualBalanceEntity s) {
        Object target = AopTestUtils.getUltimateTargetObject(paperTradingService);
        ReflectionTestUtils.invokeMethod(target, "runSessionStrategy", balanceRepo.findById(s.getId()).orElseThrow());
    }

    private void realtimeTick(String coin, BigDecimal price) {
        clearRealtimeThrottle();
        // 운영 진입점은 @Async(market-data 실행기)다 — 프록시를 거치면 처리 전에 읽게 된다(1회차 S06 이 그 경합으로 실패, 손절 로그는 남음).
        // 같은 메서드 본문을 프록시 없이 이 스레드에서 부른다. 처리 규칙은 같고, 대기·sleep 은 없다.
        LiveTradingService target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        target.onRealtimePriceEvent(new RealtimePriceEvent(coin, price, Instant.now(), RealtimePriceEvent.WS_TRADE));
    }

    // ── 관측 ────────────────────────────────────────────────────────

    private List<PositionEntity> livePositions(LiveTradingSessionEntity s) {
        return positionRepository.findBySessionKindAndSessionId("LIVE", s.getId());
    }

    private List<PaperPositionEntity> paperPositions(VirtualBalanceEntity s) {
        return paperPositionRepo.findBySessionId(s.getId());
    }

    private List<OrderEntity> liveOrders(LiveTradingSessionEntity s, String side) {
        awaitDrained();
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", s.getId()).stream()
                .filter(o -> side.equals(o.getSide())).toList();
    }

    private String lastLog(String kind, Long id) {
        List<StrategyLogEntity> logs = strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc(kind, id);
        if (logs.isEmpty()) return "로그 없음";
        StrategyLogEntity l = logs.get(0);
        return l.getSignal() + " / " + l.getReason() + (l.getBlockedReason() != null ? " / 차단=" + l.getBlockedReason() : "");
    }

    private static String entryState(int n, String status) {
        return n == 0 ? "포지션 없음" : n + "건 " + status;
    }

    private static BigDecimal pctBelow(BigDecimal entry, BigDecimal level) {
        return entry.subtract(level).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal pctAbove(BigDecimal entry, BigDecimal level) {
        return level.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    private static String s(Object o) {
        if (o instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        return String.valueOf(o).replace('\t', ' ').replace('\n', ' ');
    }

    /** 표에 남기고, 관측이 실행 전 기대와 같은지 돌려준다. */
    private static boolean row(String scenario, String item, Object live, Object paper, boolean expectSame) {
        boolean same = Objects.equals(s(live), s(paper));
        ROWS.add(new String[]{scenario, item, s(live), s(paper), expectSame ? "같음" : "다름", same ? "같음" : "다름"});
        return same == expectSame;
    }

    private static void expect(boolean ok, String what) {
        assertThat(ok).as(what + " — 실행 전 기대와 관측이 다르다(기대값을 고치지 말고 보고)").isTrue();
    }

    // ── 시나리오 ────────────────────────────────────────────────────

    @Test
    @DisplayName("S01 정상 진입 — 결정·매수금 같음, 시점(PAPER 평가 즉시 / LIVE 회차 승인 뒤)과 기준가(PAPER 슬리피지 0.1%) 다름")
    void s01NormalEntry() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);

        evaluateLive(l);
        runPaper(p);
        int liveAtEval = livePositions(l).size();
        int paperAtEval = paperPositions(p).size();
        expect(row("S01 정상 진입", "1 평가 직후 포지션", entryState(liveAtEval, "OPEN"), entryState(paperAtEval, "OPEN"), false), "평가 직후");

        approveRound();
        List<PositionEntity> lp = livePositions(l);
        List<PaperPositionEntity> pp = paperPositions(p);
        assertThat(lp).hasSize(1);
        assertThat(pp).hasSize(1);
        expect(row("S01 정상 진입", "2 승인 뒤 진입 결정", "진입", "진입", true), "진입 결정");
        expect(row("S01 정상 진입", "3 매수금", lp.get(0).getInvestedKrw(), pp.get(0).getInvestedKrw(), true), "매수금");
        expect(row("S01 정상 진입", "4 기준 진입가", lp.get(0).getEntryPrice(), pp.get(0).getEntryPrice(), false), "기준가");
        assertThat(pp.get(0).getEntryPrice()).isEqualByComparingTo(
                lastClose(cs).multiply(new BigDecimal("1.001")).setScale(8, RoundingMode.HALF_UP));
        assertThat(lp.get(0).getEntryPrice()).isEqualByComparingTo(lastClose(cs));
        row("S01 정상 진입", "5 손절 거리 %", pctBelow(lp.get(0).getEntryPrice(), lp.get(0).getStopLossPrice()),
                pctBelow(pp.get(0).getEntryPrice(), pp.get(0).getStopLossPrice()), true);
        row("S01 정상 진입", "6 익절 거리 %", pctAbove(lp.get(0).getEntryPrice(), lp.get(0).getTakeProfitPrice()),
                pctAbove(pp.get(0).getEntryPrice(), pp.get(0).getTakeProfitPrice()), true);
        expect(row("S01 정상 진입", "7 거래소 주문", liveOrders(l, "BUY").size() + "건 SUBMITTED", "0건(내부 FILLED)", false), "주문");
        row("S01 정상 진입", "8 신호 로그", lastLog("LIVE", l.getId()), lastLog("PAPER", p.getId()), false);
    }

    @Test
    @DisplayName("S02 낡은 입력(마지막 완료 봉 3시간 전) — LIVE 판정 불가로 진입 없음, PAPER 는 낡은 봉으로 진입")
    void s02StaleInput() {
        Instant stale = lastOpen.minus(3, ChronoUnit.HOURS);
        useCloseHour(stale);
        insertCache(BTC, uptrend(stale));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);

        evaluateLive(l);
        runPaper(p);
        approveRound();
        expect(row("S02 낡은 입력", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), false), "낡은 입력 진입");
        row("S02 낡은 입력", "2 신호 로그", lastLog("LIVE", l.getId()), lastLog("PAPER", p.getId()), false);
        assertThat(livePositions(l)).isEmpty();
        assertThat(paperPositions(p)).hasSize(1);
    }

    @Test
    @DisplayName("S03 BTC 판정 불가(대조 코인 KRW-ETH, BTC 캔들 없음) — LIVE 차단, PAPER 진입. KRW-BTC 세션은 자기 캔들을 써서 S02 로 귀결(비적용)")
    void s03BtcUnknown() {
        insertCache(ETH, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(ETH);
        VirtualBalanceEntity p = paper(ETH);

        evaluateLive(l);
        runPaper(p);
        approveRound();
        expect(row("S03 BTC 판정 불가(ETH)", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), false), "BTC 판정 불가 진입");
        String liveLog = lastLog("LIVE", l.getId());
        row("S03 BTC 판정 불가(ETH)", "2 신호 로그", liveLog, lastLog("PAPER", p.getId()), false);
        assertThat(liveLog).contains("BTC_MARKET_GUARD 판정 불가");
        ROWS.add(new String[]{"S03 BTC 판정 불가(ETH)", "3 KRW-BTC 세션", "비적용 — btcMarketGate 가 자기 캔들(최신성 통과분)을 쓴다. 낡으면 S02",
                "비적용 — 자기 캔들로 check", "비적용", "비적용"});
    }

    @Test
    @DisplayName("S04a 승인 실행 만료 E 직전(E−1ms) — LIVE 진입, PAPER 는 이미 평가 시각에 진입")
    void s04aBeforeExpiry() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        EntryPriorityRounds.RoundKey k = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        entryClock.setForTest(Clock.fixed(EntryPriorityRounds.expiry(k).minusMillis(1), ZoneOffset.UTC));
        List<EntryPriorityRounds.Outcome> out = approveRound();
        entryClock.setForTest(null);
        String reason = out.isEmpty() ? "후보 없음" : String.valueOf(out.get(0).reason());
        expect(row("S04a E 직전", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), true), "E 직전 진입");
        ROWS.add(new String[]{"S04a E 직전", "2 승인 결과", s(reason), "회차 없음(평가 즉시 체결)", "다름", "다름"});
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).isNull();
    }

    @Test
    @DisplayName("S04b 승인 실행 만료 E 경계(E 정각) — LIVE 만료로 진입 없음, PAPER 진입")
    void s04bAtExpiry() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        EntryPriorityRounds.RoundKey k = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        entryClock.setForTest(Clock.fixed(EntryPriorityRounds.expiry(k), ZoneOffset.UTC));
        List<EntryPriorityRounds.Outcome> out = approveRound();
        entryClock.setForTest(null);
        expect(row("S04b E 정각", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), false), "E 정각 진입");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).contains("승인 실행 만료");
        ROWS.add(new String[]{"S04b E 정각", "2 승인 결과", s(out.get(0).reason()).replaceAll("\\(.*\\)", "(…)"), "회차 없음(평가 즉시 체결)", "다름", "다름"});
        assertThat(liveOrders(l, "BUY")).isEmpty();
    }

    @Test
    @DisplayName("S05 신호 뒤 가격 변화(평가와 승인 사이 +2%) — 매수금 같음, LIVE 기준가는 승인 시각 가격, PAPER 는 평가 시각 가격")
    void s05PriceMoveAfterSignal() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        BigDecimal c0 = lastClose(cs);
        BigDecimal c1 = c0.multiply(new BigDecimal("1.02")).setScale(0, RoundingMode.HALF_UP);
        insertInProgress(BTC, c0, c1, c0, c1);
        approveRound();
        List<PositionEntity> lp = livePositions(l);
        List<PaperPositionEntity> pp = paperPositions(p);
        assertThat(lp).hasSize(1);
        assertThat(pp).hasSize(1);
        expect(row("S05 신호 뒤 +2%", "1 매수금", lp.get(0).getInvestedKrw(), pp.get(0).getInvestedKrw(), true), "매수금");
        expect(row("S05 신호 뒤 +2%", "2 기준 진입가", lp.get(0).getEntryPrice(), pp.get(0).getEntryPrice(), false), "기준가");
        assertThat(lp.get(0).getEntryPrice()).isEqualByComparingTo(c1);
        assertThat(pp.get(0).getEntryPrice()).isEqualByComparingTo(c0.multiply(new BigDecimal("1.001")).setScale(8, RoundingMode.HALF_UP));
        row("S05 신호 뒤 +2%", "3 손절가", lp.get(0).getStopLossPrice(), pp.get(0).getStopLossPrice(), false);
    }

    @Test
    @DisplayName("S06 장중 SL 도달(진행 중 봉 저가 −6%, 종가 −1%) — 평가 회차는 둘 다 유지, 실시간 시세로 LIVE 만 손절")
    void s06IntrabarStopLoss() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        BigDecimal entry = lastClose(cs);
        BigDecimal sl = entry.multiply(new BigDecimal("0.95"));
        BigDecimal tp = entry.multiply(new BigDecimal("1.10"));
        Instant opened = Instant.now().minus(20, ChronoUnit.HOURS);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        PositionEntity lpos = livePos(l, entry, sl, tp, opened);
        PaperPositionEntity ppos = paperPos(p, entry, sl, tp, opened);
        BigDecimal low = entry.multiply(new BigDecimal("0.94")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal close = entry.multiply(new BigDecimal("0.99")).setScale(0, RoundingMode.HALF_UP);
        insertInProgress(BTC, entry, entry, low, close);

        evaluateLive(l);
        runPaper(p);
        String liveAfterEval = positionRepository.findById(lpos.getId()).orElseThrow().getStatus();
        String paperAfterEval = paperPositionRepo.findById(ppos.getId()).orElseThrow().getStatus();
        expect(row("S06 장중 SL", "1 평가 회차 뒤", liveAfterEval, paperAfterEval, true), "평가 회차");

        realtimeTick(BTC, low);
        PositionEntity lAfter = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity pAfter = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        expect(row("S06 장중 SL", "2 저가 시세 뒤", lAfter.getStatus() + " " + lAfter.getExitReason(), pAfter.getStatus() + " " + pAfter.getExitReason(), false), "실시간 손절");
        assertThat(lAfter.getStatus()).isEqualTo("CLOSING");
        assertThat(lAfter.getExitReason()).isEqualTo(ExitReason.STOP_LOSS);
        assertThat(pAfter.getStatus()).isEqualTo("OPEN");
        ROWS.add(new String[]{"S06 장중 SL", "3 감시 입력", "실시간 체결가(1~5초, 처리기 간격 5초)", "1분 주기·캐시 마지막 봉 종가", "다름", "다름"});
    }

    @Test
    @DisplayName("S07 장중 TP 근처(진행 중 봉 고가 +4%, TP +3%, 종가 +1%) — 둘 다 청산 없음, LIVE 만 실시간 고가로 TP 를 끌어올림")
    void s07IntrabarTakeProfitTrailing() {
        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        BigDecimal entry = lastClose(cs);
        BigDecimal sl = entry.multiply(new BigDecimal("0.95"));
        BigDecimal tp = entry.multiply(new BigDecimal("1.03"));
        Instant opened = Instant.now().minus(20, ChronoUnit.HOURS);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        PositionEntity lpos = livePos(l, entry, sl, tp, opened);
        PaperPositionEntity ppos = paperPos(p, entry, sl, tp, opened);
        BigDecimal high = entry.multiply(new BigDecimal("1.04")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal close = entry.multiply(new BigDecimal("1.01")).setScale(0, RoundingMode.HALF_UP);
        insertInProgress(BTC, entry, high, entry, close);

        realtimeTick(BTC, high);
        evaluateLive(l);
        runPaper(p);
        PositionEntity lAfter = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity pAfter = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        expect(row("S07 장중 TP", "1 청산", lAfter.getStatus(), pAfter.getStatus(), true), "TP 청산 여부");
        expect(row("S07 장중 TP", "2 익절가", lAfter.getTakeProfitPrice(), pAfter.getTakeProfitPrice(), false), "TP 트레일링");
        assertThat(lAfter.getTakeProfitPrice()).isGreaterThan(tp);
        assertThat(pAfter.getTakeProfitPrice()).isEqualByComparingTo(tp);
    }

    @Test
    @DisplayName("S08 시세 무효·회복 — 낡은 캔들(종가 SL 아래)로 PAPER 는 손절, LIVE 는 유효 현재가 없으면 판정 안 함 → 실시간 현재가 회복 뒤 손절")
    void s08StaleQuoteThenRecovery() {
        Instant stale = lastOpen.minus(3, ChronoUnit.HOURS);
        List<Candle> cs = series(stale, i -> 10_000_000L + 5_000L * i);
        insertCache(BTC, cs);
        BigDecimal staleClose = lastClose(cs);
        BigDecimal entry = staleClose.divide(new BigDecimal("0.94"), 0, RoundingMode.HALF_UP);
        BigDecimal sl = entry.multiply(new BigDecimal("0.95"));
        BigDecimal tp = entry.multiply(new BigDecimal("1.10"));
        Instant opened = Instant.now().minus(20, ChronoUnit.HOURS);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        PositionEntity lpos = livePos(l, entry, sl, tp, opened);
        PaperPositionEntity ppos = paperPos(p, entry, sl, tp, opened);

        BigDecimal markBefore = markPriceBook.freshPrice(BTC, Instant.now());
        evaluateLive(l);
        runPaper(p);
        PositionEntity l1 = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity p1 = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        ROWS.add(new String[]{"S08 시세 무효·회복", "0 시작 전 LIVE 유효 현재가", s(markBefore), "-", "-", "-"});
        expect(row("S08 시세 무효·회복", "1 낡은 캔들 회차", l1.getStatus() + " " + l1.getExitReason(), p1.getStatus() + " " + p1.getExitReason(), false), "낡은 캔들 회차");
        assertThat(l1.getStatus()).isEqualTo("OPEN");
        assertThat(p1.getStatus()).isEqualTo("CLOSED");
        assertThat(p1.getExitReason()).isEqualTo(ExitReason.STOP_LOSS);

        // 회복 — 실시간 현재가(체결가 관측)가 다시 들어온다. 캔들은 여전히 낡았다.
        markPriceBook.observe(BTC, staleClose, Instant.now(), Instant.now(), RealtimePriceEvent.WS_TRADE);
        evaluateLive(l);
        PositionEntity l2 = positionRepository.findById(lpos.getId()).orElseThrow();
        expect(row("S08 시세 무효·회복", "2 현재가 회복 뒤", l2.getStatus() + " " + l2.getExitReason(), "(이미 CLOSED — 3시간 전 종가 기준)", false), "회복 뒤");
        assertThat(l2.getStatus()).isEqualTo("CLOSING");
        assertThat(l2.getExitReason()).isEqualTo(ExitReason.STOP_LOSS);
    }

    @Test
    @DisplayName("S09a 전역 위험 한도 거절 — LIVE 승인 시각 checkRisk 거절로 진입 없음, PAPER 진입")
    void s09aRiskRejected() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.reject("시험 — 일 손실 한도")).when(riskManagementService).checkRisk();
        List<EntryPriorityRounds.Outcome> out = approveRound();
        expect(row("S09a 위험 한도", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), false), "위험 한도");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).contains("리스크 한도");
        ROWS.add(new String[]{"S09a 위험 한도", "2 승인 결과", s(out.get(0).reason()), "위험 관문 없음", "다름", "다름"});
    }

    @Test
    @DisplayName("S09b 공유 풀 총 한도 부족 — LIVE 예약 거절, PAPER 진입(독립 가상 잔액)")
    void s09bPoolCapRejected() {
        insertCache(BTC, uptrend(lastOpen));
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        evaluateLive(l);
        runPaper(p);
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("10000")));
        List<EntryPriorityRounds.Outcome> out = approveRound();
        expect(row("S09b 풀 한도", "1 진입", entryState(livePositions(l).size(), "OPEN"), entryState(paperPositions(p).size(), "OPEN"), false), "풀 한도");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).isNotNull();
        ROWS.add(new String[]{"S09b 풀 한도", "2 승인 결과", s(out.get(0).reason()), "풀·슬롯·손실 한도 없음", "다름", "다름"});
    }

    @Test
    @DisplayName("S10 정상 청산(일봉 마감 종가 < SMA20, 보유 20h, +1%) — 둘 다 전략 신호 청산, LIVE 는 매도 주문 접수·PAPER 는 즉시 체결")
    void s10StrategyExit() {
        List<Candle> cs = downtrend(lastOpen);
        insertCache(BTC, cs);
        BigDecimal close = lastClose(cs);
        BigDecimal entry = close.multiply(new BigDecimal("0.99")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal sl = entry.multiply(new BigDecimal("0.95"));
        BigDecimal tp = entry.multiply(new BigDecimal("1.10"));
        Instant opened = Instant.now().minus(20, ChronoUnit.HOURS);
        LiveTradingSessionEntity l = live(BTC);
        VirtualBalanceEntity p = paper(BTC);
        PositionEntity lpos = livePos(l, entry, sl, tp, opened);
        PaperPositionEntity ppos = paperPos(p, entry, sl, tp, opened);

        evaluateLive(l);
        runPaper(p);
        PositionEntity lAfter = positionRepository.findById(lpos.getId()).orElseThrow();
        PaperPositionEntity pAfter = paperPositionRepo.findById(ppos.getId()).orElseThrow();
        expect(row("S10 정상 청산", "1 청산 사유", lAfter.getExitReason(), pAfter.getExitReason(), true), "청산 사유");
        expect(row("S10 정상 청산", "2 상태", lAfter.getStatus(), pAfter.getStatus(), false), "상태");
        assertThat(lAfter.getStatus()).isEqualTo("CLOSING");
        assertThat(pAfter.getStatus()).isEqualTo("CLOSED");
        expect(row("S10 정상 청산", "3 매도 주문", liveOrders(l, "SELL").size() + "건 SUBMITTED", "0건(내부 FILLED)", false), "매도 주문");
    }
}
