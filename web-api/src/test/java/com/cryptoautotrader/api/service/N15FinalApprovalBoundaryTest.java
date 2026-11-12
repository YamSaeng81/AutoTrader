package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.api.util.TimeframeUtils;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * N15 최종 승인 경계 (2026-11-05, 총괄 v1.109 §77 — 근거 n15-n16-review-4445f36).
 *
 * <p>6f6a522 는 계좌 잠금 <b>직후</b> 한 번만 만료를 봤다. 그 뒤 대기 가능한 한도 조회·세션 행 잠금·의도 승인 조회 중 E 에 닿으면
 * 여전히 차감·의도를 만들었다(총괄 국소 재현). 여기서는 실제 예약·의도 경로에서 세 경우를 확인한다.</p>
 * <ul>
 *   <li>(a) LIVE·DYNAMIC — 잠금 직후 E−1ms, 등록 손실 한도 검사({@code PoolLossGuard.checkForEntry}) 중 E 도달</li>
 *   <li>(b) DYNAMIC — 다른 트랜잭션이 세션 행을 잠근 동안 예약의 {@code em.refresh(PESSIMISTIC_WRITE)} 가 기다리고, 그 사이 E 도달(실제 잠금 대기)</li>
 *   <li>(c) LIVE·DYNAMIC — 예약(차감) 뒤 의도 승인 조회({@code approveNewEntry} 의 위험 설정 조회) 중 E 도달</li>
 * </ul>
 * <p>기대: 원래 가용액 유지·열린 슬롯 0·매수 의도(모든 상태) 0·거래소 0·원래 신호에 {@code ENTRY_EXPIRED}. 대조군 ⑩ 은 같은 지점에
 * 갈고리를 걸되 시계를 움직이지 않아(E−1ms) 정상 진입한다. 대기는 같은 가짜 시계로 만들고 긴 sleep 은 없다.</p>
 *
 * <p>🔴 H2 의 행 잠금으로 PostgreSQL 잠금 동작 전체를 검증한 것은 아니다. (b) 는 "그 refresh 가 기다리는 동안 E 를 지나면 차감 전에
 * 걸린다" 는 순서만 확인한다.</p>
 *
 * <p>🔴 2026-11-12 (총괄 v1.156 §124): 종전 "실제 현재 − 61분 − 초 오프셋 + 613ms" 비정시 봉과 초 오프셋 회차 격리를 걷었다.
 * LIVE 입력은 정시 격자 봉·회차 키에 접수 시각 두 위치(C+1s·D−1ms)를 시험 시계로 고정하고, 독립 입력마다 새 회차 상태를 쓴다
 * ({@link N15GridRounds}). DYNAMIC 입력은 제품이 실제 시각을 보므로 실제 시각 기준 자료에 새 회차 상태만 쓴다. 단언은 그대로다.</p>
 */
@DisplayName("N15 최종 승인 경계 — 한도 조회·세션 잠금·의도 승인 중 E 도달 시 차감·의도 없음")
class N15FinalApprovalBoundaryTest extends IntegrationTestBase {

    private static final String STRATEGY = "N15_FINAL_ALWAYS_BUY";
    private static final String CODE = "ENTRY_EXPIRED";

    @Autowired private ApplicationContext ctx;
    @Autowired private EntryPriorityRounds rounds;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository candleDataRepository;

    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private PortfolioSyncService portfolioSyncService;
    @SpyBean private RiskManagementService riskManagementService;
    @SpyBean private PoolLossGuard poolLossGuard;

    private String savedStage;
    private BigDecimal savedTotal;
    private BigDecimal savedPer;
    private Integer savedSlots;
    private final List<Long> liveIds = new ArrayList<>();
    private final List<Long> dynIds = new ArrayList<>();
    private final List<String> coins = List.of("KRW-INJ", "KRW-CRO", "KRW-IOTA", "KRW-ONG", "KRW-BTC");
    private final MutableClock fake = new MutableClock(Instant.now());
    private final List<N15GridRounds.Isolated> isolated = new ArrayList<>();

    static final class AlwaysBuy implements Strategy {
        @Override public String getName() { return STRATEGY; }
        @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            return StrategySignal.buy(new BigDecimal("100"), "N15 최종 경계 시험 신호");
        }
        @Override public int getMinimumCandleCount() { return 50; }
    }

    /**
     * 등록한 스레드에만 가짜 시각을 준다(다른 스레드는 실제 시각) — N15EntryExpiryTest 와 같은 방식. 🔴 2026-11-12 점검: 시험 프로필은
     * 스케줄러가 꺼져 있다({@link N15GridRounds#assertNoBackgroundScheduler}). 승인은 시험 스레드나 등록한 대기 스레드에서만 돈다.
     */
    static final class MutableClock extends Clock {
        private volatile Instant now;
        private final java.util.Set<Thread> faked = java.util.concurrent.ConcurrentHashMap.newKeySet();
        MutableClock(Instant start) { this.now = start; }
        void set(Instant t) { this.now = t; }
        void fakeFor(Thread t) { faked.add(t); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return faked.contains(Thread.currentThread()) ? now : Instant.now(); }
    }

    @BeforeEach
    void setUp() throws Exception {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        StrategyRegistry.register(new AlwaysBuy());
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        limits.setStage("N15F_" + UUID.randomUUID().toString().substring(0, 8));
        poolLossGuard.setEnabledSetting(true);
        rounds.setForcedForTest(true);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRisk();
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 8);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        fake.fakeFor(Thread.currentThread());
        wireClock(fake);
        N15GridRounds.assertNoBackgroundScheduler(ctx);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = isolated.size() - 1; i >= 0; i--) isolated.get(i).close();
        isolated.clear();
        wireClock(null);
        rounds.setForcedForTest(false);
        for (Long id : liveIds) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
        for (Long id : dynIds) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.findById(id).ifPresent(dynamicRepo::delete);
        }
        for (String c : coins) jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", c);
        jdbc.update("DELETE FROM capital_stage_baseline WHERE stage = ?", limits.getStage());
        limits.setStage(savedStage);
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
    }

    private void wireClock(Clock c) throws Exception {
        Class<?> k = Class.forName("com.cryptoautotrader.api.service.EntryClock");
        k.getMethod("setForTest", Clock.class).invoke(ctx.getBean(k), c);
    }

    static Instant expiryOf(EntryPriorityRounds.RoundKey r) {
        Duration t = Duration.ofMinutes(TimeframeUtils.toMinutes(r.timeframe()));
        Duration w = t.dividedBy(2).compareTo(Duration.ofMinutes(5)) < 0 ? t.dividedBy(2) : Duration.ofMinutes(5);
        return EntryPriorityRounds.deadline(r).plus(w);
    }

    private static List<Candle> candles(Instant lastOpen) {
        List<Candle> out = new ArrayList<>();
        for (int i = 299; i >= 0; i--) {
            BigDecimal p = new BigDecimal(1000 + (299 - i));
            out.add(Candle.builder().time(lastOpen.minus(i, ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.ONE)).low(p.subtract(BigDecimal.ONE)).close(p)
                    .volume(new BigDecimal("1000")).build());
        }
        return out;
    }

    private void insertCandleData(String coin, List<Candle> cs) {
        candleDataRepository.saveAll(cs.stream().map(c -> com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                .time(c.getTime()).coinPair(coin).timeframe("H1")
                .open(c.getOpen()).high(c.getHigh()).low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                .build()).toList());
    }

    /** 독립 입력 하나의 새 회차 상태 — 정리에서 원래 빈으로 되돌린다({@link N15GridRounds.Isolated}). */
    private EntryPriorityRounds isolate() {
        if (!isolated.isEmpty()) isolated.get(isolated.size() - 1).close();   // 앞 입력의 회차 상태를 먼저 원래 빈으로 되돌린다
        N15GridRounds.Isolated i = new N15GridRounds.Isolated(ctx);
        isolated.add(i);
        return i.rounds;
    }

    /** LIVE 정시 격자 — 거래 코인 L 까지, BTC 는 진행 중 봉 C 까지(DB). 조회 대역은 같은 정시 자료. */
    private Instant liveGrid() {
        Instant last = N15GridRounds.gridLastOpen();
        insertCandleData("KRW-BTC", candles(N15GridRounds.btcLastOpen(last)));
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(candles(last));
        return last;
    }

    /** DYNAMIC 실제 시각 기준 자료(비정시 — {@link N15GridRounds#dynamicLastOpen}). DYNAMIC 은 캔들을 조회 대역으로만 읽는다(BTC 포함). */
    private Instant dynamicData() {
        Instant last = N15GridRounds.dynamicLastOpen();
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(candles(last));
        return last;
    }

    private Instant market(String coin, Instant lastOpen) {
        List<Candle> cs = candles(lastOpen);
        insertCandleData(coin, cs);
        when(marketDataSyncService.fetchWithCache(eq(coin), anyString(), anyInt())).thenReturn(cs);
        return lastOpen;
    }

    private LiveTradingSessionEntity live(String coin) {
        LiveTradingSessionEntity s = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType(STRATEGY).coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("20000")).availableKrw(new BigDecimal("20000"))
                .totalAssetKrw(new BigDecimal("20000")).status("RUNNING").startedAt(Instant.now()).build());
        liveIds.add(s.getId());
        return s;
    }

    private DynamicSessionEntity dynamic(String coin) {
        DynamicSessionEntity s = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType(STRATEGY).timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("10000")).totalAssetKrw(new BigDecimal("10000"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000")).watchlistRefreshMin(60)
                .watchlistJson("[\"" + coin + "\"]").watchlistRefreshedAt(Instant.now())
                .startedAt(Instant.now())
                .build());
        dynIds.add(s.getId());
        return s;
    }

    private void evaluateLive(LiveTradingSessionEntity s) {
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        ReflectionTestUtils.invokeMethod(target, "evaluateAndExecuteSession", liveRepo.findById(s.getId()).orElseThrow());
    }

    private void evaluateDynamic(DynamicSessionEntity d) {
        dynamicTradingService.processTick(dynamicRepo.findById(d.getId()).orElseThrow());
    }

    /** 이 세션의 매수 주문 — 상태 무관(INTENT_READY·FAILED·WITHDRAWN 모두 센다). */
    private List<OrderEntity> buys(String kind, Long id) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, id).stream()
                .filter(o -> "BUY".equals(o.getSide())).toList();
    }

    private long openPositions(String kind, Long id) {
        return positionRepository.findBySessionKindAndSessionId(kind, id).stream()
                .filter(p -> "OPEN".equals(p.getStatus()) || "CLOSING".equals(p.getStatus())).count();
    }

    private String positionsText(String kind, Long id) {
        return positionRepository.findBySessionKindAndSessionId(kind, id).stream()
                .map(p -> p.getStatus() + "/" + p.getSize()).toList().toString();
    }

    private int exchangeCalls(String coin) {
        return (int) org.mockito.Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName()) && coin.equals(i.getArgument(0))).count();
    }

    private Object[] signal(String kind, Long id, String coin) {
        List<Object[]> rows = jdbc.query("SELECT was_executed, blocked_reason FROM strategy_log WHERE session_type = ? "
                        + "AND session_id = ? AND coin_pair = ? ORDER BY id DESC",
                (rs, n) -> new Object[]{rs.getBoolean(1), rs.getString(2)}, kind, id, coin);
        return rows.isEmpty() ? new Object[]{null, "(신호 기록 없음)"} : rows.get(0);
    }

    private BigDecimal funds(String kind, Long id) {
        return "LIVE".equals(kind) ? liveRepo.findById(id).orElseThrow().getAvailableKrw()
                : dynamicRepo.findById(id).orElseThrow().getAvailableKrw();
    }

    private static String reasons(List<EntryPriorityRounds.Outcome> out) {
        return out.stream().map(o -> o.candidate().coinPair() + "=" + (o.reason() == null ? "진입" : o.reason())).toList().toString();
    }

    /** 현재 스레드 호출 스택에 그 클래스의 그 메서드가 있는가 — 갈고리를 실제 경로의 그 지점에서만 움직이게 한다. */
    private static boolean calledFrom(String classPart, String method) {
        return Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(f -> method.equals(f.getMethodName()) && f.getClassName().contains(classPart));
    }

    /**
     * 세션 하나를 그 입력의 회차 상태 {@code r} 에 접수하고 회차 E 를 돌려준다. LIVE 는 시험 시계의 접수 위치({@code intake}),
     * DYNAMIC 은 제품이 실제 시각으로 접수한다({@code intake} 무시).
     */
    private Instant enqueue(EntryPriorityRounds r, String kind, Long id, String coin, Instant lastOpen, N15GridRounds.Intake intake) {
        market(coin, lastOpen);
        EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        if ("LIVE".equals(kind)) {
            fake.set(intake.at(round));
            System.out.println("[N15F] LIVE " + coin + " 접수 " + intake + " " + intake.at(round));
            evaluateLive(liveRepo.findById(id).orElseThrow());
        } else {
            fake.set(Instant.now());
            evaluateDynamic(dynamicRepo.findById(id).orElseThrow());
        }
        assertThat(r.openRounds()).as(kind + " " + coin + " 접수").isEqualTo(1);
        return expiryOf(round);
    }

    /** 만료 거절의 공통 기대 — 원래 가용액·슬롯 0·의도 0·거래소 0·원래 신호에 ENTRY_EXPIRED. */
    private void assertExpired(EntryPriorityRounds r, String tag, String kind, Long id, String coin, List<EntryPriorityRounds.Outcome> out,
                               String initial, String stage) {
        Object[] sig = signal(kind, id, coin);
        System.out.println("[N15F " + tag + "] " + kind + " " + coin + " → " + reasons(out) + " · 매수 주문(모든 상태) "
                + buys(kind, id).size() + " · 포지션 " + positionsText(kind, id) + " · 열린 슬롯 " + openPositions(kind, id)
                + " · 가용 " + funds(kind, id) + " · 거래소 " + exchangeCalls(coin) + " · 신호 실행 " + sig[0] + " 사유 " + sig[1]);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).as(tag + " 만료 거절").contains(CODE).contains(stage);
        assertThat(buys(kind, id)).as(tag + " 매수 의도 0(모든 상태)").isEmpty();
        assertThat(openPositions(kind, id)).as(tag + " 열린 슬롯 0").isZero();
        assertThat(funds(kind, id)).as(tag + " 원래 가용액 — 순예약 0").isEqualByComparingTo(initial);
        assertThat(exchangeCalls(coin)).as(tag + " 거래소 0").isZero();
        assertThat(sig[0]).isEqualTo(false);
        assertThat((String) sig[1]).as(tag + " 원래 신호에 만료 사유").contains(CODE);
        assertThat(r.openRounds()).as("회차 닫힘").isZero();
        assertThat(r.processDue(Instant.now().plus(2, ChronoUnit.HOURS))).as("재대기 없음").isEmpty();
    }

    /** (a) 잠금 직후 E−1ms, 손실 한도 검사 중 E. 갈고리는 예약 경로 안에서만 시계를 움직인다. */
    private void lossCheckCase(String kind, String coin, String initial, N15GridRounds.Intake intake) {
        Instant base = "LIVE".equals(kind) ? liveGrid() : dynamicData();
        EntryPriorityRounds r = isolate();
        AtomicReference<Instant> target = new AtomicReference<>();
        AtomicInteger hooked = new AtomicInteger();
        doAnswer(inv -> {
            if (target.get() != null && calledFrom("CapitalReservationService", "reserveForBuy")) {
                hooked.incrementAndGet();
                fake.set(target.get());
            }
            return inv.callRealMethod();
        }).when(poolLossGuard).checkForEntry(any(Instant.class), any());
        Long id = "LIVE".equals(kind) ? live(coin).getId() : dynamic(coin).getId();
        Instant e = enqueue(r, kind, id, coin, base, intake);
        target.set(e);
        fake.set(e.minusMillis(1));
        List<EntryPriorityRounds.Outcome> out = r.processDue(e.minusMillis(1));
        target.set(null);
        System.out.println("[N15F ⑦] " + kind + " 콜백·잠금 직후 E−1ms, 손실 한도 검사 갈고리 " + hooked.get() + "회 → 시계 " + e);
        assertThat(hooked.get()).as("갈고리가 예약 경로 안에서 불렸다").isEqualTo(1);
        assertExpired(r, "⑦", kind, id, coin, out, initial, "차감 직전");
    }

    @ParameterizedTest(name = "접수 {0}")
    @EnumSource(N15GridRounds.Intake.class)
    @DisplayName("⑦a (a) LIVE 잠금 직후 E−1ms·손실 한도 검사 중 E — 차감·슬롯·의도·거래소 0, 신호에 ENTRY_EXPIRED")
    void lossCheckCrossesExpiryLive(N15GridRounds.Intake intake) {
        lossCheckCase("LIVE", "KRW-CRO", "20000", intake);
    }

    @Test
    @DisplayName("⑦b (a) DYNAMIC 잠금 직후 E−1ms·손실 한도 검사 중 E — 차감·슬롯·의도·거래소 0, 신호에 ENTRY_EXPIRED")
    void lossCheckCrossesExpiryDynamic() {
        lossCheckCase("DYNAMIC", "KRW-IOTA", "10000", null);
    }

    /** (b) DYNAMIC 예약의 세션 행 refresh 가 다른 트랜잭션의 행 잠금을 기다리는 동안 E — 실제 잠금 대기. */
    @Test
    @DisplayName("⑧ (b) DYNAMIC 세션 행 잠금(refresh) 대기 중 E — 차감·슬롯·의도·거래소 0, 신호에 ENTRY_EXPIRED")
    void dynamicRefreshLockWaitCrossesExpiry() throws Exception {
        Instant base = dynamicData();
        EntryPriorityRounds r = isolate();
        DynamicSessionEntity d = dynamic("KRW-IOTA");
        Instant e = enqueue(r, "DYNAMIC", d.getId(), "KRW-IOTA", base, null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> approver = new AtomicReference<>();
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(st -> {
                jdbc.queryForObject("SELECT status FROM dynamic_session WHERE id = ? FOR UPDATE", String.class, d.getId());
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            fake.set(e.minusMillis(1));
            Future<List<EntryPriorityRounds.Outcome>> result = pool.submit(() -> {
                fake.fakeFor(Thread.currentThread());
                approver.set(Thread.currentThread());
                return r.processDue(e.minusMillis(1));
            });
            boolean waiting = false;
            String where = "";
            long until = System.currentTimeMillis() + 5_000;
            while (!waiting && System.currentTimeMillis() < until) {
                Thread a = approver.get();
                if (a != null) {
                    StackTraceElement[] st = a.getStackTrace();
                    boolean inReserve = Arrays.stream(st).anyMatch(f -> "reserveForBuy".equals(f.getMethodName())
                            && f.getClassName().contains("CapitalReservationService"));
                    boolean inRefresh = Arrays.stream(st).anyMatch(f -> "refresh".equals(f.getMethodName()));
                    waiting = inReserve && inRefresh && a.getState() != Thread.State.RUNNABLE;
                    if (waiting) where = a.getState() + " " + Arrays.stream(st).limit(3).map(String::valueOf).toList();
                }
                if (!waiting) Thread.sleep(1);
            }
            fake.set(e);   // 세션 행 잠금을 기다리는 동안 E 에 닿았다
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            List<EntryPriorityRounds.Outcome> out = result.get(20, TimeUnit.SECONDS);
            System.out.println("[N15F ⑧] 예약 refresh 잠금 대기 관측 " + waiting + " " + where + " · 대기 중 시계 E " + e);
            assertThat(waiting).as("승인 스레드가 예약의 세션 refresh 에서 기다렸다").isTrue();
            assertExpired(r, "⑧", "DYNAMIC", d.getId(), "KRW-IOTA", out, "10000", "차감 직전");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** (c) 예약(차감) 뒤 의도 승인 조회 중 E. 차감이 정확히 되돌려지고 의도·슬롯이 남지 않는다. */
    private void intentGateCase(String kind, String coin, String initial, N15GridRounds.Intake intake) {
        Instant base = "LIVE".equals(kind) ? liveGrid() : dynamicData();
        EntryPriorityRounds r = isolate();
        AtomicReference<Instant> target = new AtomicReference<>();
        AtomicInteger hooked = new AtomicInteger();
        doAnswer(inv -> {
            if (target.get() != null && calledFrom("OrderExecutionEngine", "approveNewEntry")) {
                hooked.incrementAndGet();
                fake.set(target.get());
            }
            return inv.callRealMethod();
        }).when(riskManagementService).hasRiskConfig();
        Long id = "LIVE".equals(kind) ? live(coin).getId() : dynamic(coin).getId();
        Instant e = enqueue(r, kind, id, coin, base, intake);
        target.set(e);
        fake.set(e.minusMillis(1));
        List<EntryPriorityRounds.Outcome> out = r.processDue(e.minusMillis(1));
        target.set(null);
        System.out.println("[N15F ⑨] " + kind + " 예약 통과(E−1ms) 뒤 의도 승인 조회 갈고리 " + hooked.get() + "회 → 시계 " + e);
        assertThat(hooked.get()).as("갈고리가 의도 승인 안에서 불렸다").isEqualTo(1);
        assertExpired(r, "⑨", kind, id, coin, out, initial, "의도 직전");
    }

    @ParameterizedTest(name = "접수 {0}")
    @EnumSource(N15GridRounds.Intake.class)
    @DisplayName("⑨a (c) LIVE 차감 뒤 의도 승인 조회 중 E — 가용 원복·슬롯·의도·거래소 0, 신호에 ENTRY_EXPIRED")
    void intentGateCrossesExpiryLive(N15GridRounds.Intake intake) {
        intentGateCase("LIVE", "KRW-CRO", "20000", intake);
    }

    @Test
    @DisplayName("⑨b (c) DYNAMIC 차감 뒤 의도 승인 조회 중 E — 가용 원복·열린 슬롯·의도·거래소 0, 신호에 ENTRY_EXPIRED")
    void intentGateCrossesExpiryDynamic() {
        intentGateCase("DYNAMIC", "KRW-IOTA", "10000", null);
    }

    /** ⑩ 대조군 — 같은 두 지점에 갈고리가 불리지만 시계는 E−1ms 그대로: 정상 진입(차감·슬롯·의도 1). */
    @ParameterizedTest(name = "LIVE 접수 {0}")
    @EnumSource(N15GridRounds.Intake.class)
    @DisplayName("⑩ 대조군: 갈고리 지점을 지나도 시계가 E−1ms 면 LIVE·DYNAMIC 정상 진입 — 차감·슬롯·매수 의도 1")
    void controlBeforeExpiryEnters(N15GridRounds.Intake intake) {
        AtomicInteger lossHook = new AtomicInteger();
        AtomicInteger intentHook = new AtomicInteger();
        doAnswer(inv -> {
            if (calledFrom("CapitalReservationService", "reserveForBuy")) lossHook.incrementAndGet();
            return inv.callRealMethod();
        }).when(poolLossGuard).checkForEntry(any(Instant.class), any());
        doAnswer(inv -> {
            if (calledFrom("OrderExecutionEngine", "approveNewEntry")) intentHook.incrementAndGet();
            return inv.callRealMethod();
        }).when(riskManagementService).hasRiskConfig();

        String[][] cases = {{"LIVE", "KRW-CRO", "15000"}, {"DYNAMIC", "KRW-IOTA", "2000"}};
        for (int i = 0; i < cases.length; i++) {
            String kind = cases[i][0], coin = cases[i][1];
            Instant base = "LIVE".equals(kind) ? liveGrid() : dynamicData();
            EntryPriorityRounds r = isolate();
            Long id = "LIVE".equals(kind) ? live(coin).getId() : dynamic(coin).getId();
            Instant e = enqueue(r, kind, id, coin, base, intake);
            int l0 = lossHook.get(), i0 = intentHook.get();
            fake.set(e.minusMillis(1));
            List<EntryPriorityRounds.Outcome> out = r.processDue(e.minusMillis(1));
            List<OrderEntity> b = buys(kind, id);
            System.out.println("[N15F ⑩] " + kind + " " + coin + " E−1ms 유지 · 손실 갈고리 " + (lossHook.get() - l0) + " · 의도 갈고리 "
                    + (intentHook.get() - i0) + " → " + reasons(out) + " · 매수 의도 " + b.size()
                    + (b.isEmpty() ? "" : " (예약액 " + b.get(0).getReservedKrw() + ")") + " · 포지션 " + positionsText(kind, id)
                    + " · 가용 " + funds(kind, id));
            assertThat(lossHook.get() - l0).isEqualTo(1);
            assertThat(intentHook.get() - i0).isEqualTo(1);
            assertThat(out.get(0).reason()).as(kind + " 정상 진입").isNull();
            assertThat(b).hasSize(1);
            assertThat(openPositions(kind, id)).isEqualTo(1);
            assertThat(funds(kind, id)).isEqualByComparingTo(cases[i][2]);
        }
    }
}
