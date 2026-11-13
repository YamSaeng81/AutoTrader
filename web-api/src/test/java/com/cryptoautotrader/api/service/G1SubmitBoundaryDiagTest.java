package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionCleanupEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionCleanupRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.ExecutorBarrier;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.api.support.OrderFlowObservation;
import com.cryptoautotrader.api.support.OutboundNetworkGuard;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔬 G1 조사 — 정지 진행 중 WS 보호 매도 의도의 "미제출" 관측을 같은 주문 번호로 계측한다 (총괄 v1.165 §3-1~4, 2026-11-13, 시험 전용).
 *
 * <p>409차 관측: {@code StopVersusInFlightExitRaceTest} (다)에서 정지 목 응답 안의 대기를 장벽으로 바꾸거나 실행기 시작을 50ms 늦추면
 * 두 번째 매도가 {@code INTENT_READY}·{@code exchangeOrderId=null}·{@code dispatching=false} 로 20초 남았다. 그 상태는 <b>정지 트랜잭션 안의
 * 시험 스레드가 저장소로 읽은 값</b>이다. 이 클래스는 같은 끼어들기 순서를 시간 지연 대신 명시적 신호(실행기 장벽·시작 문)로 만들고, 같은 순간을
 * ① 정지 트랜잭션의 영속성 컨텍스트, ② 독립 트랜잭션(REQUIRES_NEW JDBC), ③ 거래소 대역 호출 수, ④ 소유권 등록부 표본으로 동시에 읽는다.</p>
 *
 * <ul>
 *   <li>{@code STALE_*} — 의도 커밋 뒤·claim 전에 정지 트랜잭션 안에서 한 번 읽고, 제출이 끝난 뒤 다시 읽는다(원 시험의 대기와 같은 읽기).</li>
 *   <li>{@code CONTROL_*} — 제출이 끝날 때까지 정지 트랜잭션 안에서 읽지 않는다(대조).</li>
 *   <li>{@code twoThread*} — 정지를 별도 스레드에서 돌리고 목 응답은 신호만 기다린다. 확인은 정지 밖의 독립 트랜잭션에서 하고, 정지를 정상 커밋한
 *       뒤 원 시험의 나머지 단언(정리 대기·거절 상한)까지 이어 간다.</li>
 *   <li>{@code pollerRecovers*} — G1 상태(CLOSING + 소유 의도 {@code INTENT_READY} + 등록부에 없음)를 만든 뒤 실제 폴러 → 청산 확인 → 정리 작업을
 *       직접 부른다(§3-4). 5초·10초 주기의 실제 회복 시간은 재지 않는다(G3).</li>
 * </ul>
 *
 * <p>외부 연결: 주문·시세 HTTP·WS·텔레그램은 대역이다. {@code G1_OUTBOUND_GUARD=1} 이면 {@link OutboundNetworkGuard} 가 남은 외부 연결 시도를 기록·차단한다
 * (JVM 전역이므로 단언하지 않고 기록만 남긴다).</p>
 */
@DisplayName("🔬 G1 — 정지 중 WS 보호 매도 제출 경계 계측")
@Import(OrderFlowObservation.Config.class)
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.cryptoautotrader.api.support.OrderFlowObservation$Inspector")
class G1SubmitBoundaryDiagTest extends IntegrationTestBase {

    static {
        // 컨텍스트(그 안의 HttpClient)보다 먼저 설치한다. G1_OUTBOUND_GUARD=1 일 때만(기록·차단, 단언 없음 — 전역이라 다른 클래스 시도도 섞인다).
        OutboundNetworkGuard.installIfRequested();
    }

    private static final String TAG = "[G1DIAG]";
    private static final BigDecimal SIZE = new BigDecimal("9.80000000");
    private static final AtomicInteger COIN_SEQ = new AtomicInteger();

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @SpyBean private PositionRepository positionRepository;
    @SpyBean private PositionStateTransition positionStateTransition;
    @PersistenceContext private EntityManager em;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;
    @Autowired @Qualifier("marketDataExecutor") private ThreadPoolTaskExecutor marketDataExecutor;
    @Autowired private RealtimeProtectionDispatcher realtimeProtectionDispatcher;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;
    /** 409차 진단 실행에서 실제 연결·구독이 있었다 — 대역으로 격리한다. */
    @MockBean private UpbitWebSocketClient upbitWebSocketClient;
    @MockBean private TelegramNotificationService telegramNotificationService;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final AtomicInteger sellSeq = new AtomicInteger();
    private final List<String> report = Collections.synchronizedList(new ArrayList<>());
    private long mark;

    private record Fx(String kind, Long sessionId, Long positionId, String coin) { }

    @BeforeEach
    void exchange() {
        // DrainGapGate 는 실행기를 같은 설정의 새 실행기로 바꾼다 — 관측 꾸밈 장치(시작 문)가 빠져 "의도 커밋 뒤·claim 전" 을 만들 수 없다.
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("DRAIN_GAP_GATE_MS") == null,
                "DrainGapGate 실행에서는 시작 문이 없다 — 이 진단은 게이트 없이 돈다");
        OrderFlowObservation.EXTRA_THREAD_PREFIXES.add("market-data-");
        OrderFlowObservation.EXTRA_THREAD_PREFIXES.add("Test worker");
        OrderFlowObservation.EXTRA_THREAD_PREFIXES.add("g1-stop");
        OrderFlowObservation.orderStartGate = null;
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> {
                    OrderFlowObservation.record("EXCHANGE_CALL", "market=" + inv.getArgument(0) + " identifier=" + inv.getArgument(5));
                    return new UpbitOrderClient.ExchangeResult(
                            report("uuid-g1-" + sellSeq.incrementAndGet() + "-" + System.nanoTime(), inv.getArgument(0), "wait"), "{}");
                });
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString()))
                .thenAnswer(inv -> report(inv.getArgument(0), null, "wait"));
    }

    @AfterEach
    void cleanUp() {
        OrderFlowObservation.orderStartGate = null;
        ExecutorBarrier.awaitPriorTasks(marketDataExecutor.getThreadPoolExecutor(), 20_000);
        ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
        dumpTimeline();
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

    // ── 픽스처(StopVersusInFlightExitRaceTest 와 같다) ─────────────────────────────

    private static OrderResponse report(String uuid, String market, String state) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("ask");
        r.setOrdType("market");
        r.setState(state);
        r.setMarket(market);
        r.setVolume(SIZE);
        r.setExecutedVolume(BigDecimal.ZERO);
        return r;
    }

    private Fx running(String kind) {
        String coin = "KRW-G1" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
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
                .stopLossPrice(new BigDecimal("290"))
                .status("OPEN").sessionKind(kind).sessionId(sid)
                .openedAt(Instant.now()).build());
        if ("DYNAMIC".equals(kind)) {
            DynamicSessionEntity s = dynamicRepo.findById(sid).orElseThrow();
            s.setCurrentPositionId(pos.getId());
            dynamicRepo.saveAndFlush(s);
        }
        return new Fx(kind, sid, pos.getId(), coin);
    }

    private void wsStopLoss(Fx f) {
        OrderFlowObservation.record("TEST_WS_EVENT", f.coin());
        realtimeProtectionDispatcher.onPrice(new RealtimePriceEvent(f.coin(), new BigDecimal("280"), Instant.now(), RealtimePriceEvent.WS_TRADE));
    }

    private void stop(Fx f) {
        if ("DYNAMIC".equals(f.kind())) dynamicTradingService.stopSession(f.sessionId());
        else liveTradingService.stopSession(f.sessionId());
    }

    private void closingReconcile(String kind) {
        if ("DYNAMIC".equals(kind)) dynamicTradingService.reconcileDynamicClosingPositions();
        else liveTradingService.reconcileClosingPositions();
    }

    private void cleanupWorker(String kind) {
        if ("DYNAMIC".equals(kind)) dynamicTradingService.cleanupStoppedSessionPositions();
        else liveTradingService.cleanupStoppedSessionPositions();
    }

    private <T> T newTx(org.springframework.transaction.support.TransactionCallback<T> cb) {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t.execute(cb);
    }

    private String committedStatus(Long positionId) {
        return newTx(st -> jdbc.queryForObject("SELECT status FROM position WHERE id = ?", String.class, positionId));
    }

    /** 독립 트랜잭션(REQUIRES_NEW, JDBC)으로 읽은 이 포지션의 매도 — 정지 트랜잭션의 영속성 컨텍스트를 거치지 않는다. */
    private List<Map<String, Object>> committedSells(Fx f) {
        return newTx(st -> jdbc.queryForList("SELECT id, state, exchange_order_id, failure_code, signal_reason, intent_owner "
                + "FROM \"order\" WHERE position_id = ? AND side = 'SELL' ORDER BY id", f.positionId()));
    }

    /** 현재 스레드의 저장소 읽기 — 정지 목 응답 안에서는 정지 트랜잭션의 영속성 컨텍스트를 거친다(원 시험의 awaitSells 와 같은 읽기). */
    private List<OrderEntity> repoSells(Fx f) {
        return orderRepository.findByPositionIdOrderByCreatedAtDesc(f.positionId()).stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide())).toList();
    }

    private static String view(List<OrderEntity> s) {
        return s.stream().map(o -> o.getId() + ":" + o.getState() + "/" + o.getExchangeOrderId()).toList().toString();
    }

    private static String cview(List<Map<String, Object>> s) {
        return s.stream().map(r -> r.get("ID") + ":" + r.get("STATE") + "/" + r.get("EXCHANGE_ORDER_ID")
                + (r.get("FAILURE_CODE") == null ? "" : "/" + r.get("FAILURE_CODE"))).toList().toString();
    }

    private int exchangeSells(Fx f) {
        return (int) Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName()) && f.coin().equals(i.getArgument(0)))
                .count();
    }

    private void note(String s) {
        String line = TAG + " " + s;
        report.add(line);
        System.out.println(line);
        OrderFlowObservation.record("TEST_NOTE", s);
    }

    private void reject(Long orderId) {
        newTx(st -> {
            jdbc.update("UPDATE \"order\" SET state = 'FAILED', failure_code = ? WHERE id = ?", OrderExecutionEngine.EXCHANGE_REJECTED, orderId);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private void resetWsThrottle(Fx f) {
        if ("DYNAMIC".equals(f.kind())) {
            ((Map<String, Long>) ReflectionTestUtils.getField(dynamicTradingService, "rtCheckLastMs")).remove(f.coin());
        } else {
            ((Map<String, Long>) ReflectionTestUtils.getField(liveTradingService, "rtStopLossLastCheckMs")).remove(f.coin());
            ((Map<String, Long>) ReflectionTestUtils.getField(liveTradingService, "spikeCheckLastMs")).remove(f.coin());
        }
    }

    private List<PositionEntity> scan(String kind, Long sessionId, Collection<String> statuses) {
        return em.createQuery("SELECT p FROM PositionEntity p WHERE p.sessionKind = :k AND p.sessionId = :s AND p.status IN :st",
                        PositionEntity.class)
                .setParameter("k", kind).setParameter("s", sessionId).setParameter("st", statuses)
                .getResultList();
    }

    /** 명시적 완료: 조건이 독립 트랜잭션에서 참이 될 때까지(상한 20초, 넘으면 실패). */
    private void until(String what, java.util.function.BooleanSupplier cond) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("20초 안에 성립하지 않았다: " + what);
            Thread.onSpinWait();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** 소유권 등록부 표본 — 대상 주문의 등록/해제 전이를 시각과 함께 남긴다(제품 코드 불변, 패키지 내부 조회만 사용). */
    private final class DispatchingSampler implements AutoCloseable {
        private final AtomicBoolean run = new AtomicBoolean(true);
        private final AtomicReference<Long> target = new AtomicReference<>();
        private final Thread t;

        DispatchingSampler() {
            t = new Thread(() -> {
                Boolean last = null;
                Long lastId = null;
                while (run.get()) {
                    Long id = target.get();
                    if (id != null) {
                        boolean now = OrderExecutionEngine.isDispatching(id);
                        if (!id.equals(lastId) || last == null || last != now) {
                            OrderFlowObservation.record("DISPATCHING_SAMPLE", "orderId=" + id + " dispatching=" + now);
                            last = now;
                            lastId = id;
                        }
                    }
                    Thread.onSpinWait();
                }
            }, "g1-sampler");
            t.setDaemon(true);
            t.start();
        }

        void watch(Long id) {
            target.set(id);
        }

        @Override
        public void close() throws InterruptedException {
            run.set(false);
            t.join(2000);
        }
    }

    private Long secondSellId(Fx f, Long firstId) {
        return committedSells(f).stream().map(r -> ((Number) r.get("ID")).longValue())
                .filter(id -> !id.equals(firstId)).findFirst().orElse(null);
    }

    /** 처음 매도(WS 손절)를 내고 거래소 거절로 만든다 — (다)의 시작 상태. 반환: 첫 매도 id. */
    private Long firstSellRejected(Fx f) {
        wsStopLoss(f);
        ExecutorBarrier.awaitPriorTasks(marketDataExecutor.getThreadPoolExecutor(), 20_000);
        until("첫 매도 접수", () -> committedSells(f).size() == 1 && committedSells(f).get(0).get("EXCHANGE_ORDER_ID") != null);
        ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
        Long first = ((Number) committedSells(f).get(0).get("ID")).longValue();
        reject(first);
        resetWsThrottle(f);
        assertThat(committedStatus(f.positionId())).isEqualTo("CLOSING");
        return first;
    }

    private void dumpTimeline() {
        List<OrderFlowObservation.Event> events = OrderFlowObservation.since(mark);
        if (events.isEmpty()) return;
        long base = events.get(0).nanos();
        System.out.println(TAG + " ---- 시간순 기록 " + events.size() + "줄 (누락 " + OrderFlowObservation.dropped() + ") ----");
        for (OrderFlowObservation.Event e : events) {
            System.out.println(TAG + " " + e.format(base));
        }
        System.out.println(TAG + " 외부 연결 감시 설치=" + OutboundNetworkGuard.isInstalled() + " 시도(JVM 전체) " + OutboundNetworkGuard.attempts());
    }

    // ── ①② 정지 목 응답 안에서 읽기: 영속성 컨텍스트 대 독립 트랜잭션 ─────────────────────────

    /**
     * 정지의 마지막 등록 조회(목 응답) 안에서 두 번째 WS 매도를 내고, 주문 실행기의 시작 문을 닫아 "의도 커밋 뒤·claim 전" 을 명시적으로 만든다.
     * {@code readBeforeClaim} 이면 그 순간 정지 트랜잭션 안에서 저장소로 한 번 읽는다(원 시험 awaitSells 의 첫 읽기와 같다).
     */
    private void insideStopAnswer(String kind, boolean readBeforeClaim) {
        insideStopAnswer(kind, readBeforeClaim, false);
    }

    private void insideStopAnswer(String kind, boolean readBeforeClaim, boolean failLikeOriginal) {
        Fx f = running(kind);
        mark = OrderFlowObservation.mark("시작 " + kind + (readBeforeClaim ? " STALE" : " CONTROL"));
        Long first = firstSellRejected(f);
        AtomicInteger phase = new AtomicInteger();
        AtomicReference<Long> secondId = new AtomicReference<>();
        Map<String, Object> seen = new java.util.LinkedHashMap<>();

        try (DispatchingSampler sampler = new DispatchingSampler()) {
            Runnable reopen = () -> {
                newTx(st -> {
                    closingReconcile(kind);
                    return null;
                });
                assertThat(committedStatus(f.positionId())).isEqualTo("OPEN");
            };
            Runnable reclose = () -> {
                CountDownLatch gate = new CountDownLatch(1);
                OrderFlowObservation.orderStartGate = gate;
                wsStopLoss(f);
                // 명시적 완료 ①: WS 작업(의도 저장·커밋·커밋 뒤 실행기 접수)이 끝났다.
                ExecutorBarrier.awaitPriorTasks(marketDataExecutor.getThreadPoolExecutor(), 20_000);
                Long id2 = secondSellId(f, first);
                secondId.set(id2);
                sampler.watch(id2);
                seen.put("claim전_독립", cview(committedSells(f)));
                seen.put("claim전_등록부", id2 == null ? "-" : OrderExecutionEngine.isDispatching(id2));
                seen.put("claim전_거래소호출", exchangeSells(f));
                if (readBeforeClaim) {
                    seen.put("claim전_정지tx저장소", view(repoSells(f)));
                }
                gate.countDown();
                // 명시적 완료 ②: 거래소 대역이 두 번째 매도를 받고 결과 기록이 커밋됐다(독립 트랜잭션), 그리고 주문 실행기 장벽.
                until("두 번째 매도 접수(독립 트랜잭션)", () -> {
                    List<Map<String, Object>> c = committedSells(f);
                    return c.size() == 2 && c.stream().allMatch(r -> r.get("EXCHANGE_ORDER_ID") != null);
                });
                ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
                List<OrderEntity> inStop = repoSells(f);
                seen.put("접수후_정지tx저장소", view(inStop));
                seen.put("접수후_독립", cview(committedSells(f)));
                seen.put("접수후_등록부", id2 == null ? "-" : OrderExecutionEngine.isDispatching(id2));
                seen.put("접수후_거래소호출", exchangeSells(f));
                OrderEntity e2 = inStop.stream().filter(o -> o.getId().equals(id2)).findFirst().orElse(null);
                if (e2 != null) {
                    seen.put("접수후_영속성컨텍스트포함", em.contains(e2));
                    if (failLikeOriginal) {
                        // 원 시험과 같이 정지 목 응답 안에서 단언 실패를 던진다 — 정지 트랜잭션이 어떻게 끝나는지 본다.
                        throw new AssertionError("매도 2건이 접수 상태가 되지 않았다(재현): " + view(inStop));
                    }
                    em.refresh(e2);
                    seen.put("refresh후", e2.getState() + "/" + e2.getExchangeOrderId());
                }
            };
            Mockito.doAnswer(inv -> {
                String status = inv.getArgument(2);
                List<PositionEntity> result = scan(kind, f.sessionId(), List.of(status));
                if ("OPEN".equals(status) && phase.compareAndSet(0, 1)) reopen.run();
                else if ("CLOSING".equals(status) && phase.compareAndSet(1, 2)) reclose.run();
                return result;
            }).when(positionRepository).findBySessionKindAndSessionIdAndStatus(Mockito.eq(kind), Mockito.eq(f.sessionId()), Mockito.anyString());
            Mockito.doAnswer(inv -> {
                List<PositionEntity> result = scan(kind, f.sessionId(), inv.getArgument(2));
                if (phase.compareAndSet(1, 2)) reclose.run();
                return result;
            }).when(positionRepository).findBySessionKindAndSessionIdAndStatusIn(Mockito.eq(kind), Mockito.eq(f.sessionId()), Mockito.any());

            if (failLikeOriginal) {
                Throwable thrown = null;
                try {
                    stop(f);
                } catch (Throwable t) {
                    thrown = t;
                }
                OrderFlowObservation.record("TEST_STOP_THREW", String.valueOf(thrown));
                seen.forEach((k, v) -> note(kind + " FAIL " + k + " = " + v));
                note(kind + " 원 시험식 실패 뒤 독립: 예외=" + (thrown == null ? "없음" : thrown.getClass().getSimpleName())
                        + " 세션=" + sessionStatus(f) + " 포지션=" + committedStatus(f.positionId())
                        + " 매도=" + cview(committedSells(f)) + " 거래소호출=" + exchangeSells(f)
                        + " 정리대기=" + cleanupRepository.findById(f.positionId()).isPresent());
                assertThat(thrown).as("단언 실패가 정지 밖으로 나온다").isInstanceOf(AssertionError.class);
                assertThat(exchangeSells(f)).as("두 번째 매도는 실제로 나갔다").isEqualTo(2);
                assertThat(sessionStatus(f)).as("🔬 정지 트랜잭션 롤백 — 세션은 RUNNING 으로 남는다").isEqualTo("RUNNING");
                return;
            }
            stop(f);
            OrderFlowObservation.record("TEST_STOP_RETURNED", "정지 커밋 반환");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }

        assertThat(phase.get()).as("두 끼어들기가 모두 일어났다").isEqualTo(2);
        seen.forEach((k, v) -> note(kind + (readBeforeClaim ? " STALE " : " CONTROL ") + k + " = " + v));
        note(kind + " 정지 커밋 뒤 독립: 세션=" + sessionStatus(f) + " 포지션=" + committedStatus(f.positionId())
                + " 매도=" + cview(committedSells(f)) + " 거래소호출=" + exchangeSells(f)
                + " 정리대기=" + cleanupRepository.findById(f.positionId()).isPresent()
                + " 등록부=" + (secondId.get() == null ? "-" : OrderExecutionEngine.isDispatching(secondId.get())));

        // 사실 단언 — 실제 제출은 이루어졌다(독립 트랜잭션·거래소 대역).
        assertThat(exchangeSells(f)).as("두 번째 매도는 거래소 대역에 1회 나갔다(총 2)").isEqualTo(2);
        assertThat(seen.get("접수후_독립").toString()).doesNotContain("INTENT_READY");
        if (readBeforeClaim) {
            assertThat(seen.get("claim전_정지tx저장소").toString()).as("claim 전 정지 트랜잭션 읽기는 INTENT_READY").contains("INTENT_READY");
            assertThat(seen.get("접수후_정지tx저장소").toString())
                    .as("🔬 정지 트랜잭션의 영속성 컨텍스트는 처음 읽은 INTENT_READY 를 그대로 돌려준다(시험 읽기 결함 재현)")
                    .contains("INTENT_READY");
        } else {
            assertThat(seen.get("접수후_정지tx저장소").toString()).as("claim 전에 읽지 않으면 접수 상태가 보인다").doesNotContain("INTENT_READY");
        }
        assertThat(sessionStatus(f)).isEqualTo("STOPPED");
        assertThat(committedStatus(f.positionId())).isEqualTo("CLOSING");
        assertThat(cleanupRepository.findById(f.positionId())).isPresent();
    }

    private String sessionStatus(Fx f) {
        return "DYNAMIC".equals(f.kind())
                ? dynamicRepo.findById(f.sessionId()).orElseThrow().getStatus()
                : liveRepo.findById(f.sessionId()).orElseThrow().getStatus();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("① claim 전에 정지 트랜잭션 안에서 읽으면 접수 뒤에도 INTENT_READY/null 로 보인다 — 실제 제출·독립 읽기는 정상")
    void staleReadInsideStopTransaction(String kind) {
        insideStopAnswer(kind, true);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("② 대조 — claim 전에 읽지 않으면 정지 트랜잭션 안에서도 접수 상태가 보인다")
    void controlNoEarlyRead(String kind) {
        insideStopAnswer(kind, false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("⑤ 원 시험식 — 낡은 읽기로 정지 목 응답 안에서 단언 실패를 던지면 정지 트랜잭션이 롤백된다(제출은 이미 나감)")
    void originalFailureRollsBackStop(String kind) {
        insideStopAnswer(kind, true, true);
    }

    // ── ③ 두 스레드·명시적 신호 — 정지 정상 커밋 뒤 독립 트랜잭션 확인 ─────────────────────────

    @ParameterizedTest(name = "{0} 시작문={1}")
    @org.junit.jupiter.params.provider.CsvSource({"LIVE,false", "DYNAMIC,false", "LIVE,true", "DYNAMIC,true"})
    @DisplayName("③ 정지를 별도 스레드에서 멈춰 두고 WS 두 번째 매도 → 정지 정상 커밋 → 독립 확인과 원 시험의 나머지 단언")
    void twoThreadInterleaving(String kind, boolean holdStart) throws Exception {
        Fx f = running(kind);
        mark = OrderFlowObservation.mark("시작 " + kind + " 두스레드 hold=" + holdStart);
        Long first = firstSellRejected(f);
        AtomicInteger phase = new AtomicInteger();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);

        Mockito.doAnswer(inv -> {
            String status = inv.getArgument(2);
            List<PositionEntity> result = scan(kind, f.sessionId(), List.of(status));
            if ("OPEN".equals(status) && phase.compareAndSet(0, 1)) {
                newTx(st -> {
                    closingReconcile(kind);
                    return null;
                });
            } else if ("CLOSING".equals(status) && phase.compareAndSet(1, 2)) {
                OrderFlowObservation.record("STOP_PAUSED", "마지막 등록 조회 결과를 읽은 직후");
                paused.countDown();
                if (!resume.await(30, TimeUnit.SECONDS)) throw new AssertionError("재개 신호 없음");
            }
            return result;
        }).when(positionRepository).findBySessionKindAndSessionIdAndStatus(Mockito.eq(kind), Mockito.eq(f.sessionId()), Mockito.anyString());
        Mockito.doAnswer(inv -> {
            List<PositionEntity> result = scan(kind, f.sessionId(), inv.getArgument(2));
            if (phase.compareAndSet(1, 2)) {
                OrderFlowObservation.record("STOP_PAUSED", "마지막 등록 조회 결과를 읽은 직후");
                paused.countDown();
                if (!resume.await(30, TimeUnit.SECONDS)) throw new AssertionError("재개 신호 없음");
            }
            return result;
        }).when(positionRepository).findBySessionKindAndSessionIdAndStatusIn(Mockito.eq(kind), Mockito.eq(f.sessionId()), Mockito.any());

        AtomicReference<Throwable> stopError = new AtomicReference<>();
        Thread stopper = new Thread(() -> {
            try {
                stop(f);
                OrderFlowObservation.record("TEST_STOP_RETURNED", "정지 커밋 반환");
            } catch (Throwable t) {
                stopError.set(t);
            }
        }, "g1-stop");
        try (DispatchingSampler sampler = new DispatchingSampler()) {
            stopper.start();
            assertThat(paused.await(20, TimeUnit.SECONDS)).as("정지가 마지막 등록 조회에서 멈췄다").isTrue();
            assertThat(committedStatus(f.positionId())).isEqualTo("OPEN");
            CountDownLatch gate = holdStart ? new CountDownLatch(1) : null;
            OrderFlowObservation.orderStartGate = gate;
            wsStopLoss(f);
            ExecutorBarrier.awaitPriorTasks(marketDataExecutor.getThreadPoolExecutor(), 20_000);
            Long id2 = secondSellId(f, first);
            sampler.watch(id2);
            note(kind + " 두스레드 hold=" + holdStart + " 의도 커밋 뒤 독립=" + cview(committedSells(f))
                    + " 등록부=" + (id2 == null ? "-" : OrderExecutionEngine.isDispatching(id2)) + " 거래소호출=" + exchangeSells(f));
            if (gate != null) gate.countDown();
            until("두 번째 매도 접수(독립 트랜잭션)", () -> {
                List<Map<String, Object>> c = committedSells(f);
                return c.size() == 2 && c.stream().allMatch(r -> r.get("EXCHANGE_ORDER_ID") != null);
            });
            ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
            assertThat(committedStatus(f.positionId())).isEqualTo("CLOSING");
            resume.countDown();
            stopper.join(30_000);
            assertThat(stopper.isAlive()).isFalse();
            assertThat(stopError.get()).as("정지 예외 없음(정상 커밋)").isNull();
            note(kind + " 두스레드 hold=" + holdStart + " 정지 커밋 뒤 독립: 세션=" + sessionStatus(f) + " 포지션=" + committedStatus(f.positionId())
                    + " 매도=" + cview(committedSells(f)) + " 거래소호출=" + exchangeSells(f)
                    + " 정리대기=" + cleanupRepository.findById(f.positionId()).isPresent()
                    + " 등록부=" + (id2 == null ? "-" : OrderExecutionEngine.isDispatching(id2)));
        }

        // 원 시험 (다)의 단언 그대로.
        assertThat(phase.get()).isEqualTo(2);
        assertThat(sessionStatus(f)).isEqualTo("STOPPED");
        assertThat(exchangeSells(f)).as("정지는 매도를 내지 않았다 — WS 매도 2건뿐").isEqualTo(2);
        assertThat(committedStatus(f.positionId())).isEqualTo("CLOSING");
        assertThat(cleanupRepository.findById(f.positionId())).isPresent();
        Long latest = committedSells(f).stream().map(r -> ((Number) r.get("ID")).longValue()).max(Long::compare).orElseThrow();
        reject(latest);
        closingReconcile(kind);
        assertThat(committedStatus(f.positionId())).isEqualTo("OPEN");
        for (int i = 0; i < 3; i++) {
            cleanupWorker(kind);
            ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
        }
        PositionCleanupEntity c = cleanupRepository.findById(f.positionId()).orElseThrow();
        note(kind + " 두스레드 거절 후: 포지션=" + committedStatus(f.positionId()) + " 정리 메모=" + c.getLastNote());
        assertThat(exchangeSells(f)).as("거절 상한 — 정리 작업은 다시 팔지 않는다").isEqualTo(2);
        assertThat(c.getLastNote()).contains("2회 거절").contains("사람 확인 필요");
        assertThat(c.getDoneAt()).isNull();
    }

    // ── ④ G1 상태에서 실제 회복 경로 직접 호출 ─────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("④ G1 상태(CLOSING + 소유 의도 INTENT_READY + 등록부 없음) → 폴러 철회 → 청산 확인 OPEN → 정리 작업 재매도 1회")
    void pollerRecoversG1State(String kind) {
        Fx f = running(kind);
        mark = OrderFlowObservation.mark("시작 " + kind + " 폴러 회복");
        Long first = firstSellRejected(f);
        // 정지 — CLOSING 포지션을 정리 대기로 등록한다(끼어들기 없음).
        stop(f);
        assertThat(sessionStatus(f)).isEqualTo("STOPPED");
        assertThat(cleanupRepository.findById(f.positionId())).isPresent();
        closingReconcile(kind);
        assertThat(committedStatus(f.positionId())).isEqualTo("OPEN");

        // G1 상태를 만든다: 같은 포지션을 CLOSING 으로 바꾸고 이 프로세스 소유의 INTENT_READY 매도를 커밋한다. 커밋 뒤 제출을 등록하지
        // 않으므로 claim 할 작업이 없다(등록부에 없음) — 409차 관측(dispatching=false)과 같은 상태.
        Long g1 = newTx(st -> {
            assertThat(positionStateTransition.markClosingIfOpen(f.positionId(), Instant.now(), ExitReason.STOP_LOSS)).isEqualTo(1);
            OrderEntity o = OrderEntity.builder()
                    .coinPair(f.coin()).side("SELL").orderType("MARKET").quantity(SIZE)
                    .state("INTENT_READY").signalReason("실시간 손절(G1 재현)")
                    .sessionId(f.sessionId()).sessionKind(kind).positionId(f.positionId())
                    .clientOrderId("g1-" + java.util.UUID.randomUUID())
                    .intentCommittedAt(Instant.now()).intentOwner(OrderExecutionEngine.INSTANCE_ID)
                    .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING).settlementVersion(0L)
                    .settlementFinal(Boolean.FALSE).settlementAttempts(0)
                    .build();
            return orderRepository.save(o).getId();
        });
        assertThat(OrderExecutionEngine.isDispatching(g1)).isFalse();
        note(kind + " G1 상태: 포지션=" + committedStatus(f.positionId()) + " 매도=" + cview(committedSells(f)) + " 등록부=false");

        // 청산 확인을 먼저 부르면 — 의도가 살아 있으므로(INTENT_READY 는 활성) OPEN 으로 되돌리지 않아야 한다.
        closingReconcile(kind);
        note(kind + " 폴러 전 청산 확인: 포지션=" + committedStatus(f.positionId()));
        assertThat(committedStatus(f.positionId())).as("활성 의도가 있는 동안은 CLOSING 유지").isEqualTo("CLOSING");

        orderExecutionEngine.pollActiveOrders();
        Map<String, Object> row = newTx(st -> jdbc.queryForMap("SELECT state, failure_code FROM \"order\" WHERE id = ?", g1));
        note(kind + " 폴러 뒤: " + row);
        assertThat(row.get("STATE")).isEqualTo("FAILED");
        assertThat(row.get("FAILURE_CODE")).isEqualTo(OrderExecutionEngine.WITHDRAWN_DISPATCH_LOST);
        assertThat(exchangeSells(f)).as("철회는 외부 호출 0").isEqualTo(1);

        closingReconcile(kind);
        note(kind + " 청산 확인 뒤: 포지션=" + committedStatus(f.positionId()));
        assertThat(committedStatus(f.positionId())).isEqualTo("OPEN");

        for (int i = 0; i < 3; i++) {
            cleanupWorker(kind);
            ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
        }
        PositionCleanupEntity c = cleanupRepository.findById(f.positionId()).orElseThrow();
        note(kind + " 정리 작업 뒤: 포지션=" + committedStatus(f.positionId()) + " 매도=" + cview(committedSells(f))
                + " 거래소호출=" + exchangeSells(f) + " 정리 메모=" + c.getLastNote());
        assertThat(exchangeSells(f)).as("정리 작업이 정지 정책으로 한 번 다시 판다 — 반복 실행에도 중복 없음").isEqualTo(2);
        List<Map<String, Object>> sells = committedSells(f);
        Map<String, Object> last = sells.get(sells.size() - 1);
        assertThat(last.get("EXCHANGE_ORDER_ID")).as("새 매도 거래소 접수").isNotNull();
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
        assertThat(c.getDoneAt()).isNull();
    }
}
