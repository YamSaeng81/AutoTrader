package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.TestRedisConfig;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * <b>N15 — 실제 PostgreSQL 잠금을 기다리다 E 에 닿는 경우</b> (총괄 v1.134 §3-6, 1899b49 제품 코드 그대로).
 *
 * <p>{@link N15FinalApprovalBoundaryTest} ⑧ 은 H2 행 잠금으로 "DYNAMIC 세션 refresh 대기 중 E" 순서만 확인했다. 여기서는 격리
 * PostgreSQL(Flyway V1~V94)에서 다른 트랜잭션이 실제 행을 {@code FOR UPDATE} 로 쥐고 있는 동안 승인 스레드가 그 행 잠금을 기다리는지
 * <b>DB 상태</b>({@code pg_stat_activity.wait_event_type = 'Lock'}, {@code pg_blocking_pids} 에 쥔 쪽 backend)와 스레드 스택으로 확인한 뒤,
 * 기존 {@link EntryClock} 을 E 로 움직이고 잠금을 푼다.</p>
 * <ul>
 *   <li>① LIVE — 계좌 잠금 행 {@code account_capital_lock id=1}(예약 첫 단계 {@code lockAccount}) 대기 중 E → "잠금 뒤" 만료</li>
 *   <li>② DYNAMIC — 세션 행 {@code dynamic_session}(차감 전 {@code em.refresh(PESSIMISTIC_WRITE)}) 대기 중 E → "차감 직전" 만료</li>
 *   <li>③④ 대조군 — 같은 실제 잠금 대기를 거치되 시계를 E−1ms 로 두면 LIVE·DYNAMIC 정상 진입(대기 자체가 거절 사유가 아님)</li>
 * </ul>
 * <p>기대(①②): 원래 가용액·열린 슬롯 0·매수 의도(모든 상태) 0·거래소 0·원래 신호에 {@code ENTRY_EXPIRED}. 제품에 새 시계·트랜잭션·잠금
 * 정책을 만들지 않는다. 환경 변수 {@code N15_PG_URL} 이 있을 때만 돈다(기본 H2 시험에서는 건너뜀). 대상은 격리 DB 여야 한다.</p>
 */
@EnabledIfEnvironmentVariable(named = "N15_PG_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=${N15_PG_URL}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=${N15_PG_USER:trader}",
        "spring.datasource.password=${N15_PG_PASSWORD:}",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '20s'",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
})
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
@DisplayName("N15 실제 PostgreSQL 잠금 대기 중 E — LIVE 계좌 잠금·DYNAMIC 세션 행 잠금, 차감·의도·슬롯·거래소 0 · 대조군 정상 진입")
class N15PgLockWaitExpiryTest {

    private static final String STRATEGY = "N15_PG_ALWAYS_BUY";
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
    @Autowired(required = false) private com.cryptoautotrader.core.portfolio.PortfolioManager portfolioManager;

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
    private final List<String> coins = List.of("KRW-CRO", "KRW-IOTA", "KRW-BTC");
    private final MutableClock fake = new MutableClock(Instant.now());

    static final class AlwaysBuy implements Strategy {
        @Override public String getName() { return STRATEGY; }
        @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            return StrategySignal.buy(new BigDecimal("100"), "N15 PG 잠금 시험 신호");
        }
        @Override public int getMinimumCandleCount() { return 50; }
    }

    /** 등록한 스레드에만 가짜 시각을 준다 — {@link N15FinalApprovalBoundaryTest} 와 같은 방식. */
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
        if (portfolioManager != null) portfolioManager.syncTotalCapital(new BigDecimal("1000000000000"));   // IntegrationTestBase 와 같다
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).as("실제 PostgreSQL").startsWith("PostgreSQL");
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        StrategyRegistry.register(new AlwaysBuy());
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        limits.setStage("N15PG_" + UUID.randomUUID().toString().substring(0, 8));
        poolLossGuard.setEnabledSetting(true);
        rounds.setForcedForTest(true);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRisk();
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 8);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        fake.fakeFor(Thread.currentThread());
        wireClock(fake);
    }

    @AfterEach
    void tearDown() throws Exception {
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
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", coin);
        candleDataRepository.saveAll(cs.stream().map(c -> com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                .time(c.getTime()).coinPair(coin).timeframe("H1")
                .open(c.getOpen()).high(c.getHigh()).low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                .build()).toList());
    }

    private static final AtomicInteger BASE_SEQ = new AtomicInteger();

    /** 실제 현재 약 61분 전 시작한 봉이 마지막 닫힌 봉(E ≈ 지금 + 8분). 시험마다 다른 초·밀리초 오프셋으로 회차 키 충돌을 피한다. */
    private static Instant base() {
        return Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)
                .minusSeconds(23L + 7L * BASE_SEQ.incrementAndGet()).plusMillis(829);
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

    /** 세션 하나를 접수하고 회차 E 를 돌려준다. */
    private Instant enqueue(String kind, Long id, String coin, Instant lastOpen) {
        List<Candle> cs = candles(lastOpen);
        insertCandleData(coin, cs);
        when(marketDataSyncService.fetchWithCache(eq(coin), anyString(), anyInt())).thenReturn(cs);
        fake.set(Instant.now());
        if ("LIVE".equals(kind)) {
            ReflectionTestUtils.invokeMethod(AopTestUtils.getUltimateTargetObject(liveTradingService), "evaluateAndExecuteSession",
                    liveRepo.findById(id).orElseThrow());
        } else {
            dynamicTradingService.processTick(dynamicRepo.findById(id).orElseThrow());
        }
        assertThat(rounds.openRounds()).as(kind + " " + coin + " 접수").isEqualTo(1);
        return expiryOf(new EntryPriorityRounds.RoundKey("H1", lastOpen));
    }

    private List<OrderEntity> buys(String kind, Long id) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, id).stream()
                .filter(o -> "BUY".equals(o.getSide())).toList();
    }

    private long openPositions(String kind, Long id) {
        return positionRepository.findBySessionKindAndSessionId(kind, id).stream()
                .filter(p -> "OPEN".equals(p.getStatus()) || "CLOSING".equals(p.getStatus())).count();
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

    /** 실제 잠금 대기 관측 결과. */
    record LockWait(boolean dbWaiting, boolean blockedByHolder, boolean threadWaiting, String dbDetail, String threadDetail) {
        boolean confirmed() { return dbWaiting && blockedByHolder && threadWaiting; }
    }

    /**
     * 승인 스레드가 {@code table} 행 잠금을 실제로 기다리는가 — DB({@code pg_stat_activity}·{@code pg_blocking_pids}) 와 스레드 스택 둘 다.
     *
     * @param frame 승인 스레드 스택에 있어야 할 메서드(LIVE {@code lockAccount}, DYNAMIC {@code refresh})
     */
    private LockWait awaitLockWait(String table, long holderPid, Thread approver, String frame) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        String dbDetail = "";
        String threadDetail = "";
        boolean db = false, blocked = false, th = false;
        while (System.currentTimeMillis() < until && !(db && blocked && th)) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT pid, wait_event_type, wait_event, state, pg_blocking_pids(pid)::text AS blockers, left(query, 160) AS q
                    FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock' AND query ILIKE ?
                    """, "%" + table + "%");
            db = rows.size() == 1;
            blocked = db && String.valueOf(rows.get(0).get("blockers")).matches("\\{(.*,)?" + holderPid + "(,.*)?\\}");
            dbDetail = rows.toString();
            if (approver != null) {
                StackTraceElement[] st = approver.getStackTrace();
                boolean inReserve = Arrays.stream(st).anyMatch(f -> "reserveForBuy".equals(f.getMethodName())
                        && f.getClassName().contains("CapitalReservationService"));
                boolean inFrame = Arrays.stream(st).anyMatch(f -> frame.equals(f.getMethodName()));
                th = inReserve && inFrame;
                threadDetail = approver.getState() + " reserveForBuy=" + inReserve + " " + frame + "=" + inFrame;
            }
            if (!(db && blocked && th)) Thread.sleep(20);
        }
        return new LockWait(db, blocked, th, dbDetail, threadDetail);
    }

    /** 실행 결과 묶음. */
    record Run(List<EntryPriorityRounds.Outcome> out, LockWait wait, Instant expiry) { }

    /**
     * 다른 트랜잭션이 {@code lockSql} 로 행을 쥔 동안 승인 스레드가 E−1ms 로 회차를 처리하게 하고, 실제 잠금 대기를 확인한 뒤
     * {@code moveToE} 면 시계를 E 로 움직이고 잠금을 푼다.
     */
    private Run runWithHeldLock(String kind, Long id, String coin, Instant lastOpen, String lockSql, Object lockArg,
                                String table, String frame, boolean moveToE) throws Exception {
        Instant e = enqueue(kind, id, coin, lastOpen);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderPid = new AtomicLong();
        AtomicReference<Thread> approver = new AtomicReference<>();
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(st -> {
                holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Long.class));
                if (lockArg == null) jdbc.queryForList(lockSql);
                else jdbc.queryForList(lockSql, lockArg);
                held.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).as("다른 트랜잭션이 행 잠금을 쥐었다").isTrue();
            fake.set(e.minusMillis(1));
            Future<List<EntryPriorityRounds.Outcome>> result = pool.submit(() -> {
                fake.fakeFor(Thread.currentThread());
                approver.set(Thread.currentThread());
                return rounds.processDue(e.minusMillis(1));
            });
            while (approver.get() == null) Thread.sleep(1);
            LockWait w = awaitLockWait(table, holderPid.get(), approver.get(), frame);
            assertThat(result.isDone()).as("잠금을 쥔 동안 승인이 끝나지 않았다").isFalse();
            if (moveToE) fake.set(e);   // 실제 행 잠금을 기다리는 동안 E 에 닿았다
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            List<EntryPriorityRounds.Outcome> out = result.get(30, TimeUnit.SECONDS);
            System.out.println("[N15PG] " + kind + " " + coin + " " + table + " 대기 DB=" + w.dbWaiting() + " 쥔 쪽 pid " + holderPid.get()
                    + " 막힘=" + w.blockedByHolder() + " " + w.dbDetail() + " · 스레드 " + w.threadDetail()
                    + " · 시계 " + (moveToE ? "E " + e : "E−1ms 유지") + " → " + reasons(out) + " · 매수 의도 " + buys(kind, id).size()
                    + " · 열린 슬롯 " + openPositions(kind, id) + " · 가용 " + funds(kind, id) + " · 거래소 " + exchangeCalls(coin)
                    + " · 신호 " + Arrays.toString(signal(kind, id, coin)));
            return new Run(out, w, e);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void assertExpired(String tag, String kind, Long id, String coin, Run r, String initial, String stage) {
        assertThat(r.wait().confirmed()).as(tag + " 실제 잠금 대기 확인(DB·쥔 쪽 pid·스레드) " + r.wait()).isTrue();
        assertThat(r.out()).hasSize(1);
        assertThat(r.out().get(0).reason()).as(tag + " 만료 거절").contains(CODE).contains(stage);
        assertThat(buys(kind, id)).as(tag + " 매수 의도 0(모든 상태)").isEmpty();
        assertThat(openPositions(kind, id)).as(tag + " 열린 슬롯 0").isZero();
        assertThat(funds(kind, id)).as(tag + " 원래 가용액").isEqualByComparingTo(initial);
        assertThat(exchangeCalls(coin)).as(tag + " 거래소 0").isZero();
        Object[] sig = signal(kind, id, coin);
        assertThat(sig[0]).isEqualTo(false);
        assertThat((String) sig[1]).as(tag + " 원래 신호에 만료 사유").contains(CODE);
        assertThat(rounds.openRounds()).as("회차 닫힘").isZero();
        assertThat(rounds.processDue(Instant.now().plus(2, ChronoUnit.HOURS))).as("재대기 없음").isEmpty();
    }

    private void assertEntered(String tag, String kind, Long id, String coin, Run r, String after) {
        assertThat(r.wait().confirmed()).as(tag + " 실제 잠금 대기 확인(DB·쥔 쪽 pid·스레드) " + r.wait()).isTrue();
        assertThat(r.out()).hasSize(1);
        assertThat(r.out().get(0).reason()).as(tag + " 정상 진입").isNull();
        assertThat(buys(kind, id)).as(tag + " 매수 의도 1").hasSize(1);
        assertThat(openPositions(kind, id)).as(tag + " 열린 슬롯 1").isEqualTo(1);
        assertThat(funds(kind, id)).as(tag + " 차감").isEqualByComparingTo(after);
    }

    private void btc(Instant base) {
        insertCandleData("KRW-BTC", candles(base));
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(candles(base));
    }

    private static final String ACCOUNT_LOCK = "SELECT id FROM account_capital_lock WHERE id = 1 FOR UPDATE";
    private static final String SESSION_LOCK = "SELECT status FROM dynamic_session WHERE id = ? FOR UPDATE";

    @Test
    @DisplayName("① LIVE 계좌 잠금 행(account_capital_lock) 실제 대기 중 E — '잠금 뒤' ENTRY_EXPIRED, 가용 20000·슬롯·의도·거래소 0")
    void liveAccountLockWaitCrossesExpiry() throws Exception {
        Instant base = base();
        btc(base);
        Long id = live("KRW-CRO").getId();
        Run r = runWithHeldLock("LIVE", id, "KRW-CRO", base.minusSeconds(1), ACCOUNT_LOCK, null, "account_capital_lock", "lockAccount", true);
        assertExpired("①", "LIVE", id, "KRW-CRO", r, "20000", "잠금 뒤");
    }

    @Test
    @DisplayName("② DYNAMIC 세션 행(dynamic_session) refresh 실제 대기 중 E — '차감 직전' ENTRY_EXPIRED, 가용 10000·슬롯·의도·거래소 0")
    void dynamicSessionRowLockWaitCrossesExpiry() throws Exception {
        Instant base = base();
        btc(base);
        Long id = dynamic("KRW-IOTA").getId();
        Run r = runWithHeldLock("DYNAMIC", id, "KRW-IOTA", base.minusSeconds(1), SESSION_LOCK, id, "dynamic_session", "refresh", true);
        assertExpired("②", "DYNAMIC", id, "KRW-IOTA", r, "10000", "차감 직전");
    }

    @Test
    @DisplayName("③ 대조군 LIVE 같은 계좌 잠금 실제 대기, 시계 E−1ms 유지 → 정상 진입(차감 20000→15000·슬롯·매수 의도 1)")
    void controlLiveAccountLockWaitEnters() throws Exception {
        Instant base = base();
        btc(base);
        Long id = live("KRW-CRO").getId();
        Run r = runWithHeldLock("LIVE", id, "KRW-CRO", base.minusSeconds(1), ACCOUNT_LOCK, null, "account_capital_lock", "lockAccount", false);
        assertEntered("③", "LIVE", id, "KRW-CRO", r, "15000");
    }

    @Test
    @DisplayName("④ 대조군 DYNAMIC 같은 세션 행 실제 대기, 시계 E−1ms 유지 → 정상 진입(차감 10000→2000·슬롯·매수 의도 1)")
    void controlDynamicSessionRowLockWaitEnters() throws Exception {
        Instant base = base();
        btc(base);
        Long id = dynamic("KRW-IOTA").getId();
        Run r = runWithHeldLock("DYNAMIC", id, "KRW-IOTA", base.minusSeconds(1), SESSION_LOCK, id, "dynamic_session", "refresh", false);
        assertEntered("④", "DYNAMIC", id, "KRW-IOTA", r, "2000");
    }
}
