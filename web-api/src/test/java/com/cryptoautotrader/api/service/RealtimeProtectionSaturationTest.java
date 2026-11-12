package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionCleanupRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.ExecutorBarrier;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.153 F — 실시간 시세 → LIVE·DYNAMIC 실자금 보호 점검 전달이 실행기 포화에서 끊기지 않는가 (2026-11-12).
 *
 * <p>실제 {@code marketDataExecutor}(2/4/100, AbortPolicy — 크기·정책 그대로)를 래치로 포화시키고 실제 발행 경로({@code publishEvent})로 같은
 * 시세 열을 보낸다. 실제 LIVE·DYNAMIC 보유(진입 300·손절가 285) 포지션과 실제 처리기·주문 실행기를 쓰고, 거래소 주문 클라이언트만 대역이다.
 * 완료 신호는 Future·실행기 장벽이다(sleep 판정 없음).</p>
 *
 * <p>이 파일은 운영 기점 b5cd1d0 에서도 컴파일된다(새 경계는 빈 이름·리플렉션으로만 부른다) — 같은 시험으로 수정 전/후를 본다.</p>
 */
@DisplayName("F 실시간 보호 전달 — 실행기 포화 중 발행 지속·시세 장부·LIVE/DYNAMIC 손절 주문 의도·중복 없음·비가격 방어·해제 후 처리")
class RealtimeProtectionSaturationTest extends IntegrationTestBase {

    private static final BigDecimal SIZE = new BigDecimal("9.80000000");
    private static final AtomicInteger COIN_SEQ = new AtomicInteger();
    private static final String DISPATCHER = "realtimeProtectionDispatcher";

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired private MarkPriceBook markPriceBook;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private ApplicationContext ctx;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;
    @Autowired @Qualifier("marketDataExecutor") private ThreadPoolTaskExecutor marketDataExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final AtomicInteger sellSeq = new AtomicInteger();

    private record Fx(String kind, Long sessionId, Long positionId, String coin) { }

    @BeforeEach
    void exchange() {
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        report("uuid-f153-" + sellSeq.incrementAndGet() + "-" + System.nanoTime(), inv.getArgument(0)), "{}"));
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString())).thenAnswer(inv -> report(inv.getArgument(0), null));
    }

    @AfterEach
    void cleanUp() {
        barrier();
        for (Long id : liveSessions) wipe("LIVE", id, () -> liveRepo.deleteById(id));
        for (Long id : dynamicSessions) wipe("DYNAMIC", id, () -> dynamicRepo.deleteById(id));
        liveSessions.clear();
        dynamicSessions.clear();
    }

    private void wipe(String kind, Long sid, Runnable deleteSession) {
        positionRepository.findBySessionKindAndSessionId(kind, sid).forEach(p -> cleanupRepository.deleteById(p.getId()));
        orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, sid).forEach(orderRepository::delete);
        positionRepository.findBySessionKindAndSessionId(kind, sid).forEach(positionRepository::delete);
        deleteSession.run();
    }

    private static OrderResponse report(String uuid, String market) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("wait");
        r.setMarket(market);
        r.setVolume(SIZE);
        r.setExecutedVolume(BigDecimal.ZERO);
        return r;
    }

    /** 실행 중 실자금 세션 + 수량 9.8·진입 300·손절가 285 OPEN 포지션. */
    private Fx holding(String kind, String coin) {
        Long sid;
        if ("DYNAMIC".equals(kind)) {
            sid = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                    .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                    .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                    .totalAssetKrw(new BigDecimal("10000"))
                    .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                    .status("RUNNING").scanState("POSITION_MONITORING").tradingMode("REAL")
                    .currentCoinPair(coin)
                    .maxCandidateSize(30).targetWatchSize(10)
                    .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                    .watchlistRefreshMin(60)
                    .build()).getId();
            dynamicSessions.add(sid);
        } else {
            sid = liveRepo.saveAndFlush(LiveTradingSessionEntity.builder()
                    .sessionType("REAL").strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                    .coinPair(coin).timeframe("M5")
                    .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                    .totalAssetKrw(new BigDecimal("10000"))
                    .status("RUNNING").startedAt(Instant.now()).build()).getId();
            liveSessions.add(sid);
        }
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair(coin).side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(SIZE).investedKrw(new BigDecimal("2940")).entryAllocationKrw(new BigDecimal("3000"))
                .stopLossPrice(new BigDecimal("285"))
                .status("OPEN").sessionKind(kind).sessionId(sid)
                .openedAt(Instant.now()).build());
        if ("DYNAMIC".equals(kind)) {
            DynamicSessionEntity s = dynamicRepo.findById(sid).orElseThrow();
            s.setCurrentPositionId(pos.getId());
            dynamicRepo.saveAndFlush(s);
        }
        return new Fx(kind, sid, pos.getId(), coin);
    }

    private static String newCoin() {
        return "KRW-F" + COIN_SEQ.incrementAndGet() + "X" + (System.nanoTime() % 100000);
    }

    @SuppressWarnings("unchecked")
    private void clearThrottle(String coin) {
        Object lt = AopTestUtils.getTargetObject(liveTradingService);
        ((Map<String, Long>) ReflectionTestUtils.getField(lt, "rtStopLossLastCheckMs")).remove(coin);
        ((Map<String, ?>) ReflectionTestUtils.getField(lt, "priceHistory")).remove(coin);
        Object dt = AopTestUtils.getTargetObject(dynamicTradingService);
        ((Map<String, Long>) ReflectionTestUtils.getField(dt, "rtCheckLastMs")).remove(coin);
    }

    private int exchangeSells(String coin) {
        return (int) Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName()) && coin.equals(i.getArgument(0)))
                .count();
    }

    private int exchangeOrdersTotal() {
        return (int) Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName())).count();
    }

    private String status(Fx f) {
        return positionRepository.findById(f.positionId()).orElseThrow().getStatus();
    }

    private Throwable publish(String coin, BigDecimal price, Instant observedAt) {
        try {
            publisher.publishEvent(new RealtimePriceEvent(coin, price, observedAt, RealtimePriceEvent.WS_TRADE));
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private void barrier() {
        ExecutorBarrier.awaitPriorTasks(marketDataExecutor.getThreadPoolExecutor(), 15_000);
        ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 15_000);
    }

    /** 새 경계가 있으면 그 재접수(운영은 1초 주기 스케줄)를 지금 한 번 부른다 — 시간 대기 대신. b5cd1d0 에는 없다. */
    private boolean retryPending() {
        if (!ctx.containsBean(DISPATCHER)) return false;
        ReflectionTestUtils.invokeMethod(ctx.getBean(DISPATCHER), "retryPending");
        return true;
    }

    private String snap(String engine) {
        if (!ctx.containsBean(DISPATCHER)) return "(경계 없음)";
        return String.valueOf((Object) ReflectionTestUtils.invokeMethod(ctx.getBean(DISPATCHER), "snapshot", engine));
    }

    private long snapLong(String engine, String accessor) {
        Object s = ReflectionTestUtils.invokeMethod(ctx.getBean(DISPATCHER), "snapshot", engine);
        Object v = ReflectionTestUtils.invokeMethod(s, accessor);
        return ((Number) v).longValue();
    }

    /** 실제 실행기 포화 — 대기열 용량을 먼저 고정하고(총괄 첫 탐침의 결함 회피) 코어 래치·대기열 채움·최대까지 래치. */
    private final class Saturation implements AutoCloseable {
        final ThreadPoolExecutor pool = marketDataExecutor.getThreadPoolExecutor();
        final CountDownLatch release = new CountDownLatch(1);
        final List<Future<?>> held = new ArrayList<>();

        Saturation(boolean fillQueue) throws InterruptedException {
            ExecutorBarrier.awaitPriorTasks(pool, 15_000);
            int cap = pool.getQueue().size() + pool.getQueue().remainingCapacity();
            int core = pool.getCorePoolSize(), max = pool.getMaximumPoolSize();
            // 이미 떠 있는 작업자(앞 포화에서 늘어난 비코어 작업자 포함)를 먼저 모두 래치로 잡는다 — 2회차는 코어 수만 잡아 놀던 작업자가
            // 채움 작업을 바로 소비해 대기열이 차지 않았다(하네스 결함, 원문 보존). 작업자 바쁨 시나리오도 같은 이유로 떠 있는 작업자 전부를 잡는다.
            int first = Math.max(core, pool.getPoolSize());
            CountDownLatch running = new CountDownLatch(first);
            java.util.concurrent.atomic.AtomicInteger extraRunning = new java.util.concurrent.atomic.AtomicInteger();
            CountDownLatch extraLatch = new CountDownLatch(Math.max(0, max - first));
            Runnable blocker = () -> {
                running.countDown();
                hold();
            };
            Runnable extraBlocker = () -> {
                extraRunning.incrementAndGet();
                extraLatch.countDown();
                hold();
            };
            for (int i = 0; i < first; i++) held.add(pool.submit(blocker));
            assertThat(running.await(10, TimeUnit.SECONDS)).as("래치 작업 실행 시작").isTrue();
            if (fillQueue) {
                // 3회차: 떠 있던 작업자 4개분 래치를 넣고 곧바로 채웠는데, 놀던 작업자가 래치를 아직 꺼내기 전이라 래치 4개가 대기열 칸을
                //   차지해 채움 작업이 거절됐다(대기열 97 = 래치 일부 + 채움). 하네스 결함, 원문 보존. 처음에는 다른 제출자를 의심했으나 4회차
                //   진단에서 끼어든 작업 0건이었다. 그래서 래치 실행 시작을 먼저 확인한 뒤 채운다. 실제로 찰 때까지 채우고 비코어 작업자는
                //   실제로 생긴 만큼만 세며, 끼어든 작업이 있으면 종류를 남긴다.
                int filled = 0;
                while (pool.getQueue().remainingCapacity() > 0) {
                    try {
                        held.add(pool.submit(() -> { }));
                        filled++;
                    } catch (RejectedExecutionException e) {
                        break;
                    }
                }
                int extraSubmitted = 0;
                for (int i = first; i < max; i++) {
                    try {
                        held.add(pool.submit(extraBlocker));
                        extraSubmitted++;
                    } catch (RejectedExecutionException e) {
                        break;
                    }
                }
                for (int i = extraSubmitted; i < max - first; i++) extraLatch.countDown();
                assertThat(extraLatch.await(10, TimeUnit.SECONDS)).isTrue();
                long foreign = pool.getQueue().stream().filter(r -> held.stream().noneMatch(h -> h == r)).count();
                System.out.println("[F153] 포화 준비: 코어/떠 있던 작업자 " + first + " · 채움 " + filled + " · 비코어 래치 " + extraRunning.get()
                        + " · 작업자 " + pool.getPoolSize() + "/" + max + " · 대기열 " + pool.getQueue().size() + "/" + cap + " · 끼어든 작업 " + foreign
                        + " " + pool.getQueue().stream().filter(r -> held.stream().noneMatch(h -> h == r)).map(r -> r.getClass().getName()).distinct().toList());
            }
            if (fillQueue) assertThat(pool.getQueue().remainingCapacity()).as("대기열 가득").isZero();
        }

        private void hold() {
            try {
                if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("해제 시간 초과");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        void release() throws Exception {
            release.countDown();
            for (Future<?> f : held) f.get(15, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            release();
        }
    }

    // ── 1. 대기열 가득 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("대기열 가득 — 발행 예외 없음·시세 장부 갱신·다음 코인 전달, 해제 전 매도 0, 해제 후 새 시세 없이 LIVE·DYNAMIC 손절 1회씩, 재시세에 중복 없음")
    void queueFullThenRelease() throws Exception {
        String a = newCoin(), b = newCoin();
        Fx la = holding("LIVE", a), da = holding("DYNAMIC", a), lb = holding("LIVE", b);
        clearThrottle(a);
        clearThrottle(b);
        int ordersBefore = exchangeOrdersTotal();
        List<String> out = new ArrayList<>();
        Throwable e1, e2, e3;
        try (Saturation s = new Saturation(true)) {
            e1 = publish(a, new BigDecimal("280"), Instant.now());
            e2 = publish(b, new BigDecimal("280"), Instant.now());
            e3 = publish(a, BigDecimal.ZERO, Instant.now());           // 비가격 — 대기 중인 유효 시세를 덮지 않아야 한다
            out.add("포화 중 발행 예외: " + e1 + " / " + e2 + " / " + e3);
            out.add("포화 중 시세 장부 a=" + markPriceBook.freshPrice(a, Instant.now()) + " b=" + markPriceBook.freshPrice(b, Instant.now()));
            out.add("포화 중 매도: a=" + exchangeSells(a) + " b=" + exchangeSells(b) + " | LIVE " + snap("LIVE") + " | DYNAMIC " + snap("DYNAMIC"));
            assertThat(exchangeSells(a)).as("해제 전 매도 없음").isZero();
            s.release();
        }
        ExecutorBarrier.awaitPriorTasks(marketDataExecutor.getThreadPoolExecutor(), 15_000);
        boolean retried = retryPending();                                   // 새 시세 발행 없음
        barrier();
        out.add("해제·재접수(" + retried + ") 뒤 매도: a=" + exchangeSells(a) + " b=" + exchangeSells(b) + " 상태 la=" + status(la) + " da=" + status(da)
                + " lb=" + status(lb) + " | LIVE " + snap("LIVE") + " | DYNAMIC " + snap("DYNAMIC"));
        clearThrottle(a);
        clearThrottle(b);
        Throwable e4 = publish(a, new BigDecimal("279"), Instant.now());
        barrier();
        out.add("재시세 뒤 매도: a=" + exchangeSells(a) + " b=" + exchangeSells(b) + " 발행 예외 " + e4 + " 거래소 주문 증가 " + (exchangeOrdersTotal() - ordersBefore));
        out.forEach(x -> System.out.println("[F153] " + x));

        assertThat(e1).as("포화 중 발행 쪽 예외 없음(a)").isNull();
        assertThat(e2).as("포화 중 발행 쪽 예외 없음(b, 다음 코인)").isNull();
        assertThat(e3).as("포화 중 발행 쪽 예외 없음(비가격)").isNull();
        assertThat(markPriceBook.freshPrice(b, Instant.now())).as("다음 코인 시세 장부").isEqualByComparingTo("280");
        assertThat(exchangeSells(a)).as("해제 뒤 새 시세 없이 LIVE·DYNAMIC 손절 각 1회, 재시세에도 중복 없음").isEqualTo(2);
        assertThat(exchangeSells(b)).as("다음 코인 LIVE 손절 1회").isEqualTo(1);
        assertThat(status(la)).isEqualTo("CLOSING");
        assertThat(status(da)).isEqualTo("CLOSING");
        assertThat(status(lb)).isEqualTo("CLOSING");
        assertThat(exchangeOrdersTotal() - ordersBefore).as("실자금 주문은 이 세 포지션의 매도뿐").isEqualTo(3);
        assertThat(snapLong("LIVE", "rejected")).as("LIVE 거절이 건수로 드러남").isPositive();
        assertThat(snapLong("DYNAMIC", "rejected")).as("DYNAMIC 거절이 건수로 드러남").isPositive();
        assertThat(snapLong("LIVE", "pendingCoins")).isZero();
        assertThat(snapLong("DYNAMIC", "pendingCoins")).isZero();
    }

    // ── 2. 작업자만 바쁨(대기열 여유) ───────────────────────────────────────────

    @Test
    @DisplayName("작업자 바쁨·대기열 여유 — 발행 예외 없음, 해제 전 매도 0, 해제 뒤 손절 1회(대기 지연 = 앞선 작업 완료까지), 거절 0")
    void workersBusyQueueHasRoom() throws Exception {
        String a = newCoin();
        Fx la = holding("LIVE", a);
        clearThrottle(a);
        long rejBefore = ctx.containsBean(DISPATCHER) ? snapLong("LIVE", "rejected") : 0;
        Throwable e1;
        try (Saturation s = new Saturation(false)) {
            e1 = publish(a, new BigDecimal("280"), Instant.now());
            assertThat(exchangeSells(a)).isZero();
            s.release();
        }
        barrier();
        System.out.println("[F153] 작업자 바쁨: 예외 " + e1 + " 매도 " + exchangeSells(a) + " 상태 " + status(la) + " | LIVE " + snap("LIVE"));
        assertThat(e1).isNull();
        assertThat(exchangeSells(a)).isEqualTo(1);
        assertThat(status(la)).isEqualTo("CLOSING");
        if (ctx.containsBean(DISPATCHER)) assertThat(snapLong("LIVE", "rejected")).as("대기열 여유면 거절 없음").isEqualTo(rejBefore);
    }

    // ── 3. 종료 중 거절(단위) ──────────────────────────────────────────────────

    @Test
    @DisplayName("종료된 실행기 — 발행 예외 없음, 거절 수·고정 사유·대기 유지, 처리기 호출 없음")
    void shutdownExecutorRejects() throws Exception {
        if (!ctx.containsBean(DISPATCHER)) {
            System.out.println("[F153] 종료 거절: 경계 없음(b5cd1d0) — 해당 없음");
            return;
        }
        ThreadPoolTaskExecutor dead = new ThreadPoolTaskExecutor();
        dead.setCorePoolSize(1);
        dead.setMaxPoolSize(1);
        dead.setQueueCapacity(1);
        dead.initialize();
        dead.shutdown();
        LiveTradingService live = Mockito.mock(LiveTradingService.class);
        DynamicTradingService dyn = Mockito.mock(DynamicTradingService.class);
        Class<?> cls = Class.forName("com.cryptoautotrader.api.service.RealtimeProtectionDispatcher");
        Object d = cls.getConstructor(java.util.concurrent.Executor.class, LiveTradingService.class, DynamicTradingService.class)
                .newInstance(dead, live, dyn);
        Throwable ex = null;
        try {
            ReflectionTestUtils.invokeMethod(d, "onPrice", new RealtimePriceEvent("KRW-SHUT", new BigDecimal("100"), Instant.now(), RealtimePriceEvent.WS_TRADE));
            ReflectionTestUtils.invokeMethod(d, "retryPending");
        } catch (Throwable t) {
            ex = t;
        }
        Object snap = ReflectionTestUtils.invokeMethod(d, "snapshot", "LIVE");
        System.out.println("[F153] 종료 거절: 예외 " + ex + " | " + snap);
        assertThat(ex).isNull();
        assertThat(((Number) ReflectionTestUtils.invokeMethod(snap, "rejected")).longValue()).isEqualTo(2);
        assertThat(((Number) ReflectionTestUtils.invokeMethod(snap, "pendingCoins")).intValue()).isEqualTo(1);
        assertThat((String) ReflectionTestUtils.invokeMethod(snap, "lastRejectReason")).isEqualTo("실행기 포화 — 보호 점검 접수 거절, 코인별 최신 시세 대기 유지");
        Mockito.verifyNoInteractions(live, dyn);
    }

    // ── 4. 비교: 호출 스레드 대체 처리(안 A) vs 대기칸 분리(안 B) — 느린 잠금 중 발행 지연 ─────────────────

    /**
     * LIVE 포지션 행을 다른 트랜잭션이 잠근 채로(행 잠금, 400ms 뒤 풀림) 손절 시세를 발행한다. 안 A 는 시험 안에서만 만든 변형이다
     * (포화 실행기에 접수 → 거절 → 같은 처리기를 호출 스레드에서 부른다). 안 B 는 이 후보의 실제 발행 경로다. 지연 주입(400ms)은 측정 자극이며
     * 완료 판정은 Future·장벽이다.
     */
    @Test
    @DisplayName("비교 — 행 잠금 400ms 중: 안 A(호출 스레드) 발행 지연 ≥ 잠금, 안 B(대기칸) 발행 지연 < 잠금, 두 안 모두 손절 1회")
    void compareCallerRunsVersusPendingLane() throws Exception {
        if (!ctx.containsBean(DISPATCHER)) {
            System.out.println("[F153] 비교: 경계 없음(b5cd1d0) — 해당 없음");
            return;
        }
        long holdMs = 400;
        long[] a = measure(holdMs, true);
        long[] b = measure(holdMs, false);
        System.out.println("[F153] 비교(잠금 " + holdMs + "ms): 안 A 발행 " + a[0] + "ms 매도 " + a[1] + " | 안 B 발행 " + b[0] + "ms 매도 " + b[1]);
        assertThat(a[0]).as("안 A — 발행 스레드가 잠금을 기다린다").isGreaterThanOrEqualTo(holdMs - 20);
        assertThat(b[0]).as("안 B — 발행 스레드는 잠금을 기다리지 않는다").isLessThan(holdMs / 2);
        assertThat(a[1]).isEqualTo(1);
        assertThat(b[1]).isEqualTo(1);
    }

    private long[] measure(long holdMs, boolean callerRuns) throws Exception {
        String coin = newCoin();
        Fx f = holding("LIVE", coin);
        clearThrottle(coin);
        CountDownLatch locked = new CountDownLatch(1);
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch unlock = new CountDownLatch(1);
        Future<?> locker = Executors.newSingleThreadExecutor().submit(() -> new TransactionTemplate(txManager).executeWithoutResult(st -> {
            new org.springframework.jdbc.core.JdbcTemplate(ctx.getBean(javax.sql.DataSource.class))
                    .queryForList("SELECT id FROM position WHERE id = ? FOR UPDATE", f.positionId());
            locked.countDown();
            try {
                unlock.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        RealtimePriceEvent ev = new RealtimePriceEvent(coin, new BigDecimal("280"), Instant.now(), RealtimePriceEvent.WS_TRADE);
        try (Saturation s = new Saturation(true)) {
            ses.schedule(unlock::countDown, holdMs, TimeUnit.MILLISECONDS);   // 포화를 만든 뒤부터 잠금 유지 시간을 잰다
            long t0 = System.nanoTime();
            if (callerRuns) {
                try {
                    marketDataExecutor.execute(() -> liveTradingService.onRealtimePriceEvent(ev));
                } catch (RejectedExecutionException rex) {
                    liveTradingService.onRealtimePriceEvent(ev);      // 안 A: 거절된 보호 작업을 호출(발행) 스레드에서
                }
            } else {
                publisher.publishEvent(ev);                          // 안 B: 실제 경계
            }
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            unlock.countDown();
            locker.get(10, TimeUnit.SECONDS);
            s.release();
            ExecutorBarrier.awaitPriorTasks(marketDataExecutor.getThreadPoolExecutor(), 15_000);
            if (!callerRuns) retryPending();
            barrier();
            ses.shutdownNow();
            return new long[]{elapsed, exchangeSells(coin)};
        }
    }
}
