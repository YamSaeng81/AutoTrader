package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.TradeLogEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 제출 스레드와 미해결 제출 해소(폴러)의 커밋 순서 경합 — 총괄 v1.167 §4-1 (2026-11-15).
 *
 * <p>413차 전체 회귀의 SchedulerProfileCleanupTest DYNAMIC 실패(주문359)에서 폴러가 {@code SUBMITTING} 으로 읽은 엔티티를
 * 식별자 조회 뒤 조건 없이 저장해, 제출 스레드가 이미 커밋한 {@code SUBMITTED}·거래소 주문번호를 덮어썼다. 이 시험은 그 순서를
 * <b>신호로 고정</b>한다. 거래소 대역의 {@code createOrder} 와 {@code getOrderByIdentifier} 가 래치에서 멈추고, 시험이 커밋 순서를
 * 정한다. 시간 대기로 순서를 만들지 않는다 — 래치 시간 초과는 실패다.</p>
 *
 * <table>
 *   <tr><th>사례</th><th>순서</th><th>보존해야 하는 것</th></tr>
 *   <tr><td>A</td><td>폴러 SUBMITTING 읽기 → 조회 대기 → 제출 결과 커밋 → 조회 "없음" 으로 재개</td><td>SUBMITTED·주문번호·제출 시각, UNKNOWN 전이 0</td></tr>
 *   <tr><td>B</td><td>폴러 조회 "없음" UNKNOWN 커밋 → 제출 결과</td><td>SUBMITTED·주문번호, 거래로그 UNKNOWN→SUBMITTED, 불명 사유 해제</td></tr>
 *   <tr><td>C</td><td>폴러 SUBMITTING 읽기 → 제출 결과 커밋 → 조회 "찾음" 으로 재개</td><td>응답 원문·제출 시각, SUBMITTED 전이 1회</td></tr>
 *   <tr><td>D</td><td>폴러 SUBMITTING 읽기 → 제출 결과 → 체결·정산 반영 → 조회 "없음" 으로 재개</td><td>FILLED·체결량·정산값·포지션·잔액</td></tr>
 *   <tr><td>E</td><td>제출 호출 대기 중 폴러가 찾아 체결·정산 반영 → 제출 호출이 통신 오류로 끝남</td><td>FILLED·정산값, FILLED→UNKNOWN 0</td></tr>
 * </table>
 *
 * <p>모든 사례에서 거래소 제출은 1회다.</p>
 */
@DisplayName("제출 결과와 미해결 제출 해소의 커밋 순서 경합 (v1.167)")
class SubmitPollRaceTest extends IntegrationTestBase {

    private static final long WAIT_MS = 15_000;

    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private LiveTradingSessionRepository sessionRepository;
    @Autowired private TradeLogRepository tradeLogRepository;
    @Autowired private DynamicSessionRepository dynamicRepo;

    @Autowired
    @Qualifier("orderExecutor")
    private ThreadPoolTaskExecutor orderExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> createdSessions = new ArrayList<>();
    private final List<Long> createdDynamic = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            // 실제 PG 에는 trade_log → order 외래키가 있다(SubmitPollRacePgTest). H2 에서도 같은 순서로 지운다.
            List<Long> orderIds = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).stream()
                    .map(OrderEntity::getId).toList();
            tradeLogRepository.findAll().stream().filter(l -> orderIds.contains(l.getOrderId())).forEach(tradeLogRepository::delete);
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
        createdDynamic.forEach(id -> {
            List<Long> orderIds = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).stream()
                    .map(OrderEntity::getId).toList();
            tradeLogRepository.findAll().stream().filter(l -> orderIds.contains(l.getOrderId())).forEach(tradeLogRepository::delete);
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.deleteById(id);
        });
        createdDynamic.clear();
    }

    // ── 고정 장치 ────────────────────────────────────────────────

    record Fixture(String kind, Long sessionId, Long positionId, OrderRequest request) { }

    Fixture runningSessionWithBuyRequest() {
        return fixture("LIVE", "BUY");
    }

    /** LIVE·DYNAMIC 실자금 세션의 시장가 매수(3,000원 선차감) 또는 보유 9.8개 시장가 매도. */
    Fixture fixture(String kind, String side) {
        boolean sell = "SELL".equals(side);
        Long sessionId;
        if ("DYNAMIC".equals(kind)) {
            sessionId = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                    .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                    .initialCapital(new BigDecimal("10000.00"))
                    .availableKrw(new BigDecimal("7000.00"))
                    .totalAssetKrw(new BigDecimal("10000.00"))
                    .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                    .status("RUNNING").scanState("POSITION_MONITORING").tradingMode("REAL")
                    .maxCandidateSize(30).targetWatchSize(10)
                    .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                    .watchlistRefreshMin(60)
                    .build()).getId();
            createdDynamic.add(sessionId);
        } else {
            sessionId = liveSession();
        }
        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(sell ? new BigDecimal("9.80000000") : BigDecimal.ZERO)
                .investedKrw(new BigDecimal("3000.00000000"))
                .status("OPEN").sessionKind(kind).sessionId(sessionId)
                .openedAt(Instant.now()).build());

        OrderRequest req = new OrderRequest();
        req.setCoinPair("KRW-DOGE");
        req.setSide(side);
        req.setOrderType("MARKET");
        req.setQuantity(sell ? new BigDecimal("9.80000000") : new BigDecimal("3000.00000000"));
        req.setReason("전략 신호 — 제출·해소 경합 시험");
        req.setSignalPrice(new BigDecimal("300.00000000"));
        req.setSessionId(sessionId);
        req.setSessionKind(kind);
        req.setPositionId(pos.getId());
        return new Fixture(kind, sessionId, pos.getId(), req);
    }

    private Long liveSession() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                // 매수 요청액 3,000 은 제출 전에 이미 차감된 상태다(ClaimAndUnknownTrackingTest 와 같은 고정 장치).
                .availableKrw(new BigDecimal("7000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .status("RUNNING").startedAt(Instant.now()).build());
        createdSessions.add(session.getId());
        return session.getId();
    }

    private static OrderResponse response(String uuid, String state) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("bid");
        r.setOrdType("price");
        r.setState(state);
        r.setMarket("KRW-DOGE");
        r.setVolume(new BigDecimal("3000.00000000"));
        if ("done".equals(state)) {
            r.setExecutedVolume(new BigDecimal("9.80000000"));
            r.setExecutedFunds(new BigDecimal("2940.00000000"));
            r.setPaidFee(new BigDecimal("1.47000000"));
        }
        return r;
    }

    private static String rawBody(String uuid) {
        return "{\"uuid\":\"" + uuid + "\",\"state\":\"wait\"}";
    }

    /** 제출 스레드가 거래소 호출 안에서 멈춘다. 호출 시점에 SUBMITTING 이 커밋돼 있다. */
    static final class CreateGate {
        final CountDownLatch atExchange = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<String> identifier = new AtomicReference<>();
    }

    /** 폴러가 식별자 조회 안에서 멈춘다. 그 전에 같은 트랜잭션에서 SUBMITTING 을 읽었다. */
    static final class QueryGate {
        final CountDownLatch querying = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    static void await(CountDownLatch latch, String what) throws InterruptedException {
        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("신호 시간 초과: " + what).isTrue();
    }

    void stubCreate(CreateGate gate, String uuid, boolean timeout) {
        Mockito.doAnswer(inv -> {
            gate.identifier.set(inv.getArgument(5));
            gate.atExchange.countDown();
            if (!gate.release.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("시험 신호 시간 초과(제출)");
            }
            if (timeout) {
                throw new RuntimeException(new java.net.http.HttpTimeoutException("request timed out"));
            }
            return new UpbitOrderClient.ExchangeResult(response(uuid, "wait"), rawBody(uuid));
        }).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    /** @param found null 이면 거래소가 "없다" 고 답한다 */
    void stubIdentifierQuery(QueryGate gate, OrderResponse found) {
        Mockito.doAnswer(inv -> {
            if (gate != null) {
                gate.querying.countDown();
                if (!gate.release.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("시험 신호 시간 초과(조회)");
                }
            }
            return found;
        }).when(upbitOrderClient).getOrderByIdentifier(Mockito.anyString());
    }

    /** 폴러의 해소 한 건을 별도 스레드에서 실행한다 — 운영의 scheduler 스레드에 해당한다. */
    Thread startResolver(Long orderId, AtomicReference<Throwable> failure) {
        Thread t = new Thread(() -> {
            try {
                orderExecutionEngine.resolveUnresolvedSubmission(orderId);
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "race-resolver");
        t.start();
        return t;
    }

    void join(Thread t, AtomicReference<Throwable> failure) throws InterruptedException {
        t.join(WAIT_MS);
        assertThat(t.isAlive()).as("해소 스레드가 끝나야 한다").isFalse();
        assertThat(failure.get()).as("해소 스레드 예외").isNull();
    }

    void awaitSubmitThreadDone() {
        com.cryptoautotrader.api.support.ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), WAIT_MS);
    }

    private Long orderIdOf(Fixture f, String identifier) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(f.kind(), f.sessionId()).stream()
                .filter(o -> identifier.equals(o.getClientOrderId()))
                .map(OrderEntity::getId).findFirst().orElseThrow();
    }

    OrderEntity reload(Long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    List<String> transitions(Long orderId) {
        return tradeLogRepository.findAll().stream()
                .filter(l -> orderId.equals(l.getOrderId()) && "STATE_CHANGE".equals(l.getEventType()))
                .sorted(java.util.Comparator.comparing(TradeLogEntity::getId))
                .map(l -> l.getOldState() + "→" + l.getNewState())
                .toList();
    }

    void verifySubmittedOnce() {
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    /** 순서 A·C·D 의 앞부분: 제출 스레드를 거래소 호출 안에 세우고, 폴러를 조회 안에 세운다. */
    Long submitAndPark(Fixture f, CreateGate create) throws InterruptedException {
        orderExecutionEngine.submitOrder(f.request());
        await(create.atExchange, "제출 스레드의 거래소 호출");
        Long id = orderIdOf(f, create.identifier.get());
        assertThat(reload(id).getState()).as("전제: 거래소 호출 중 SUBMITTING 이 커밋돼 있다").isEqualTo("SUBMITTING");
        return id;
    }

    // ── 사례 A — 관측된 실패 순서 ───────────────────────────────

    @Test
    @DisplayName("A — 폴러가 SUBMITTING 을 읽은 뒤 제출 결과가 커밋되면, 조회 '없음' 이 SUBMITTED·주문번호를 덮지 않는다")
    void resultCommittedWhileResolverQueries_notFound() throws Exception {
        Fixture f = runningSessionWithBuyRequest();
        CreateGate create = new CreateGate();
        QueryGate query = new QueryGate();
        stubCreate(create, "uuid-race-a", false);
        stubIdentifierQuery(query, null);

        Long id = submitAndPark(f, create);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread resolver = startResolver(id, failure);
        await(query.querying, "폴러의 식별자 조회");

        create.release.countDown();
        awaitSubmitThreadDone();
        OrderEntity committed = reload(id);
        assertThat(committed.getState()).as("전제: 제출 결과가 먼저 커밋됐다").isEqualTo("SUBMITTED");
        assertThat(committed.getExchangeOrderId()).isEqualTo("uuid-race-a");

        query.release.countDown();
        join(resolver, failure);

        OrderEntity o = reload(id);
        assertThat(o.getState()).as("🔴 커밋된 SUBMITTED 를 낡은 SUBMITTING 엔티티로 덮지 않는다").isEqualTo("SUBMITTED");
        assertThat(o.getExchangeOrderId()).as("🔴 거래소 주문번호가 남아야 한다").isEqualTo("uuid-race-a");
        assertThat(o.getSubmittedAt()).as("제출 시각이 남아야 한다").isEqualTo(committed.getSubmittedAt());
        assertThat(o.getResponseJson()).as("응답 원문이 남아야 한다").isEqualTo(rawBody("uuid-race-a"));
        assertThat(transitions(id)).as("거래로그: 실제 전이만 남는다")
                .containsExactly("null→INTENT_READY", "INTENT_READY→SUBMITTING", "SUBMITTING→SUBMITTED");
        verifySubmittedOnce();
    }

    // ── 사례 B — 반대 순서 ─────────────────────────────────────

    @Test
    @DisplayName("B — 폴러의 UNKNOWN 이 먼저 커밋되면 제출 결과가 SUBMITTED·주문번호를 붙이고 불명 사유를 해제한다")
    void unknownCommittedBeforeResult() throws Exception {
        Fixture f = runningSessionWithBuyRequest();
        CreateGate create = new CreateGate();
        stubCreate(create, "uuid-race-b", false);
        stubIdentifierQuery(null, null);

        Long id = submitAndPark(f, create);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        join(startResolver(id, failure), failure);
        assertThat(reload(id).getState()).as("전제: 폴러의 UNKNOWN 이 먼저 커밋됐다").isEqualTo("UNKNOWN");

        create.release.countDown();
        awaitSubmitThreadDone();

        OrderEntity o = reload(id);
        assertThat(o.getState()).isEqualTo("SUBMITTED");
        assertThat(o.getExchangeOrderId()).isEqualTo("uuid-race-b");
        assertThat(o.getSubmittedAt()).isNotNull();
        assertThat(o.getUnknownReason()).as("주문번호를 확보했으므로 '조회되지 않음' 사유를 남기지 않는다").isNull();
        assertThat(o.getUnknownProbeCount()).as("조회 메타는 보존한다").isEqualTo(1);
        assertThat(transitions(id)).as("거래로그: 실제 이전/다음 상태")
                .containsExactly("null→INTENT_READY", "INTENT_READY→SUBMITTING", "SUBMITTING→UNKNOWN", "UNKNOWN→SUBMITTED");
        verifySubmittedOnce();
    }

    // ── 사례 C — 조회 성공 경로 ────────────────────────────────

    @Test
    @DisplayName("C — 제출 결과 뒤 조회 '찾음' 으로 재개해도 응답 원문·제출 시각을 덮지 않고 SUBMITTED 전이를 두 번 남기지 않는다")
    void resultCommittedWhileResolverQueries_found() throws Exception {
        Fixture f = runningSessionWithBuyRequest();
        CreateGate create = new CreateGate();
        QueryGate query = new QueryGate();
        stubCreate(create, "uuid-race-c", false);
        stubIdentifierQuery(query, response("uuid-race-c", "wait"));
        Mockito.doReturn(response("uuid-race-c", "wait")).when(upbitOrderClient).getOrder("uuid-race-c");

        Long id = submitAndPark(f, create);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread resolver = startResolver(id, failure);
        await(query.querying, "폴러의 식별자 조회");

        create.release.countDown();
        awaitSubmitThreadDone();
        OrderEntity committed = reload(id);
        assertThat(committed.getState()).as("전제").isEqualTo("SUBMITTED");

        query.release.countDown();
        join(resolver, failure);

        OrderEntity o = reload(id);
        assertThat(o.getState()).isEqualTo("SUBMITTED");
        assertThat(o.getExchangeOrderId()).isEqualTo("uuid-race-c");
        assertThat(o.getResponseJson()).as("🔴 제출 응답 원문을 낡은 엔티티의 null 로 덮지 않는다").isEqualTo(rawBody("uuid-race-c"));
        assertThat(o.getSubmittedAt()).as("제출 시각을 바꾸지 않는다").isEqualTo(committed.getSubmittedAt());
        assertThat(transitions(id)).as("SUBMITTED 전이는 한 번")
                .containsExactly("null→INTENT_READY", "INTENT_READY→SUBMITTING", "SUBMITTING→SUBMITTED");
        verifySubmittedOnce();
    }

    // ── 사례 D — 체결·정산이 먼저 반영된 경우 ───────────────────

    @Test
    @DisplayName("D — 제출 결과와 체결·정산이 반영된 뒤 폴러가 재개해도 FILLED·체결량·정산값·포지션·잔액을 덮지 않는다")
    void settledBeforeResolverResumes() throws Exception {
        Fixture f = runningSessionWithBuyRequest();
        CreateGate create = new CreateGate();
        QueryGate query = new QueryGate();
        stubCreate(create, "uuid-race-d", false);
        stubIdentifierQuery(query, null);
        Mockito.doReturn(response("uuid-race-d", "done")).when(upbitOrderClient).getOrder("uuid-race-d");

        Long id = submitAndPark(f, create);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread resolver = startResolver(id, failure);
        await(query.querying, "폴러의 식별자 조회");

        create.release.countDown();
        awaitSubmitThreadDone();
        orderExecutionEngine.checkOrderStatus(id);   // 다른 폴링 회차가 체결을 반영했다
        OrderEntity settled = reload(id);
        assertThat(settled.getState()).as("전제: 체결 반영").isEqualTo("FILLED");
        String before = snapshot(f, id);

        query.release.countDown();
        join(resolver, failure);

        assertThat(snapshot(f, id)).as("🔴 종결·정산 결과를 낡은 SUBMITTING 엔티티로 덮지 않는다").isEqualTo(before);
        assertThat(transitions(id)).as("FILLED 뒤 전이 없음").doesNotContain("SUBMITTING→UNKNOWN", "FILLED→UNKNOWN");
        verifySubmittedOnce();
    }

    // ── 사례 E — 제출 오류 경로가 늦게 끝나는 경우 ───────────────

    @Test
    @DisplayName("E — 폴러가 식별자로 찾아 체결을 반영한 뒤 제출 호출이 통신 오류로 끝나도 FILLED 를 UNKNOWN 으로 바꾸지 않는다")
    void lateSubmitErrorAfterResolverSettled() throws Exception {
        Fixture f = runningSessionWithBuyRequest();
        CreateGate create = new CreateGate();
        stubCreate(create, "uuid-race-e", true);
        stubIdentifierQuery(null, response("uuid-race-e", "wait"));
        Mockito.doReturn(response("uuid-race-e", "done")).when(upbitOrderClient).getOrder("uuid-race-e");

        Long id = submitAndPark(f, create);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        join(startResolver(id, failure), failure);
        OrderEntity settled = reload(id);
        assertThat(settled.getState()).as("전제: 폴러가 찾아 체결을 반영했다").isEqualTo("FILLED");
        String before = snapshot(f, id);

        create.release.countDown();
        awaitSubmitThreadDone();

        assertThat(snapshot(f, id)).as("🔴 늦은 통신 오류가 종결을 UNKNOWN 으로 되돌리지 않는다").isEqualTo(before);
        assertThat(transitions(id)).doesNotContain("FILLED→UNKNOWN");
        verifySubmittedOnce();
    }

    // ── 사례 A·B 를 LIVE 매도·DYNAMIC 매수·DYNAMIC 매도에서 (총괄 v1.167 §4-4) ─────

    @ParameterizedTest(name = "{0} {1} — 순서 {2}")
    @CsvSource({"LIVE,SELL,A", "LIVE,SELL,B", "DYNAMIC,BUY,A", "DYNAMIC,BUY,B", "DYNAMIC,SELL,A", "DYNAMIC,SELL,B"})
    @DisplayName("A·B — LIVE 매도·DYNAMIC 매수/매도에서도 제출 결과의 SUBMITTED·주문번호를 보존한다")
    void kindsAndSides(String kind, String side, String order) throws Exception {
        Fixture f = fixture(kind, side);
        CreateGate create = new CreateGate();
        String uuid = "uuid-race-" + kind + "-" + side + "-" + order;
        stubCreate(create, uuid, false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Long id;
        if ("A".equals(order)) {
            QueryGate query = new QueryGate();
            stubIdentifierQuery(query, null);
            id = submitAndPark(f, create);
            Thread resolver = startResolver(id, failure);
            await(query.querying, "폴러의 식별자 조회");
            create.release.countDown();
            awaitSubmitThreadDone();
            assertThat(reload(id).getState()).as("전제: 제출 결과가 먼저 커밋됐다").isEqualTo("SUBMITTED");
            query.release.countDown();
            join(resolver, failure);
            assertThat(transitions(id)).containsExactly("null→INTENT_READY", "INTENT_READY→SUBMITTING", "SUBMITTING→SUBMITTED");
        } else {
            stubIdentifierQuery(null, null);
            id = submitAndPark(f, create);
            join(startResolver(id, failure), failure);
            assertThat(reload(id).getState()).as("전제: 폴러의 UNKNOWN 이 먼저 커밋됐다").isEqualTo("UNKNOWN");
            create.release.countDown();
            awaitSubmitThreadDone();
            assertThat(reload(id).getUnknownReason()).isNull();
            assertThat(transitions(id)).containsExactly("null→INTENT_READY", "INTENT_READY→SUBMITTING",
                    "SUBMITTING→UNKNOWN", "UNKNOWN→SUBMITTED");
        }
        OrderEntity o = reload(id);
        assertThat(o.getState()).isEqualTo("SUBMITTED");
        assertThat(o.getExchangeOrderId()).isEqualTo(uuid);
        assertThat(o.getSubmittedAt()).isNotNull();
        verifySubmittedOnce();
    }

    // ── 사례 H — 수동 조정 뒤 늦은 제출 응답 (총괄 v1.168 §3-2) ─────

    @Autowired private UnknownOrderAdjustmentService adjustmentService;
    @Autowired private com.cryptoautotrader.api.repository.OrderSettlementDiscrepancyRepository discrepancyRepository;
    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;
    @org.springframework.boot.test.mock.mockito.SpyBean private UnknownOrderAdjustmentService adjustmentSpy;
    @MockBean private TelegramNotificationService telegram;

    /** {@code recordLaterObservation}(REQUIRES_NEW) 한 번의 관측 — 스레드·소요·예외·반환 직후 별도 연결에서 본 관측값. */
    record LaterCall(String thread, long elapsedMs, Throwable error, String txName, AtomicReference<String> committedObservation) { }

    /** 별도 연결(자동 커밋)로 읽는다 — 호출자 트랜잭션이 아직 열려 있을 때 이 값이 보이면 안쪽이 따로 커밋된 것이다. */
    private String observationViaSeparateConnection(Long orderId) throws java.sql.SQLException {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT later_observation FROM order_manual_adjustment WHERE order_id = ?")) {
            c.setAutoCommit(true);
            ps.setLong(1, orderId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private BigDecimal availableKrw(Fixture f) {
        return "DYNAMIC".equals(f.kind()) ? dynamicRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw()
                : sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw();
    }

    @ParameterizedTest(name = "{0} 매수")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("H — 폴러 UNKNOWN·수동 조정이 커밋된 뒤 제출 응답이 도착하면 UNKNOWN 유지·관측만 기록하고, 반복 응답은 다시 반영하지 않는다")
    void lateSubmitResponseAfterManualAdjustment(String kind) throws Exception {
        Fixture f = fixture(kind, "BUY");
        CreateGate create = new CreateGate();
        String uuid = "uuid-race-h-" + kind;
        stubCreate(create, uuid, false);
        stubIdentifierQuery(null, null);

        List<LaterCall> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        Mockito.doAnswer(inv -> {
            // 대역이 트랜잭션 프록시 안쪽이면 여기서 REQUIRES_NEW 가 이미 열려 있다 — 그 커밋 직후(제출 결과 트랜잭션은 아직 열림)
            // 별도 연결로 읽는다. 바깥이면 호출 반환 뒤(안쪽 커밋 뒤) 읽는다. 어느 쪽이든 제출 스레드가 호출자로 돌아가기 전이다.
            String txName = org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionName();
            boolean inner = txName != null && txName.endsWith("recordLaterObservation");
            AtomicReference<String> seen = new AtomicReference<>("(읽지 않음)");
            Long orderId = inv.getArgument(0);
            if (inner) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                try {
                                    seen.set(observationViaSeparateConnection(orderId));
                                } catch (java.sql.SQLException e) {
                                    seen.set("읽기 실패: " + e);
                                }
                            }
                        });
            }
            long t0 = System.nanoTime();
            Throwable err = null;
            try {
                inv.callRealMethod();
            } catch (Throwable e) {
                err = e;
            }
            if (!inner) seen.set(observationViaSeparateConnection(orderId));
            calls.add(new LaterCall(Thread.currentThread().getName(), (System.nanoTime() - t0) / 1_000_000, err, txName, seen));
            if (err != null) throw err;
            return null;
        }).when(adjustmentSpy).recordLaterObservation(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.any());

        Long id = submitAndPark(f, create);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        join(startResolver(id, failure), failure);
        assertThat(reload(id).getState()).as("전제: 폴러의 UNKNOWN 이 먼저 커밋됐다").isEqualTo("UNKNOWN");
        BigDecimal before = availableKrw(f);

        // 사람이 장기 불명으로 보고 3,000 을 돌려준다(30분 경과 시각으로 기록 — MIN_AGE 전제).
        adjustmentService.record(id, "시험 운영자", "거래소 주문 내역에 없음 확인(시험)", "시험 근거",
                new BigDecimal("3000"), "늦게 접수됐으면 같은 3,000 이 두 번 잡힌다", Instant.now().plus(java.time.Duration.ofMinutes(31)));
        BigDecimal adjusted = availableKrw(f);
        assertThat(adjusted).as("전제: 조정이 3,000 을 한 번 돌려줬다").isEqualByComparingTo(before.add(new BigDecimal("3000")));
        OrderEntity atAdjust = reload(id);

        create.release.countDown();       // 늦은 제출 응답 도착 → recordSubmitOutcome 의 수동 조정 분기
        awaitSubmitThreadDone();

        assertThat(calls).as("제출 스레드가 늦은 관측을 한 번 불렀다").hasSize(1);
        LaterCall first = calls.get(0);
        assertThat(first.error()).as("REQUIRES_NEW 관측 기록 예외 없음 (스레드 %s, %dms)", first.thread(), first.elapsedMs()).isNull();
        System.out.println("[CASE-H] " + kind + " recordLaterObservation thread=" + first.thread() + " elapsedMs=" + first.elapsedMs()
                + " txName=" + first.txName() + " seenBeforeCallerCommit=" + first.committedObservation().get());
        assertThat(first.thread()).as("제출 스레드에서 불렸다").startsWith("order-");
        assertThat(first.committedObservation().get()).as("🔴 제출 결과 트랜잭션이 끝나기 전에 별도 연결에서 관측이 보인다 — 안쪽이 따로 커밋됐다")
                .contains("제출 응답 늦게 도착").contains(uuid);
        assertThat(first.elapsedMs()).as("행 잠금 자기 대기 없음(lock_timeout 10s 미만)").isLessThan(5_000);

        OrderEntity o = reload(id);
        assertThat(o.getState()).as("🔴 수동 조정한 불명 주문은 UNKNOWN 유지").isEqualTo("UNKNOWN");
        assertThat(o.getExchangeOrderId()).as("자동 추적에 넘기지 않는다(폴러의 같은 분기와 같음)").isNull();
        assertThat(o.getFilledQuantity()).isEqualTo(atAdjust.getFilledQuantity());
        assertThat(o.getSettlementVersion()).as("정산값 보존").isEqualTo(atAdjust.getSettlementVersion());
        assertThat(o.getAppliedExecutedFunds()).isNull();
        assertThat(o.getFeeSource()).isEqualTo(atAdjust.getFeeSource());
        assertThat(availableKrw(f)).as("🔴 이미 돌려준 자금을 다시 움직이지 않는다").isEqualByComparingTo(adjusted);
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getSize()).isEqualByComparingTo("0");
        assertThat(transitions(id)).as("거래로그: UNKNOWN 뒤 전이 없음")
                .containsExactly("null→INTENT_READY", "INTENT_READY→SUBMITTING", "SUBMITTING→UNKNOWN");
        UnknownOrderAdjustmentService.Adjustment a = adjustmentService.find(id);
        assertThat(a.laterObservation()).contains("제출 응답 늦게 도착").contains(uuid);
        assertThat(discrepancyRepository.findByOrderIdOrderByDetectedAtDesc(id)).as("불일치 이력 1건")
                .hasSize(1).allSatisfy(d -> assertThat(d.getKind()).isEqualTo(UnknownOrderAdjustmentService.DISCREPANCY_KIND));
        Mockito.verify(telegram, Mockito.times(1)).notifyRiskLimitBreached(Mockito.contains("#" + id));

        // 같은 응답이 한 번 더 도착한다(재전달) — 같은 제출 결과 경로를 같은 트랜잭션 경계로 다시 부른다.
        Object engine = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(orderExecutionEngine);
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(st ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(engine, "recordSubmitOutcome", id,
                        new UpbitOrderClient.ExchangeResult(response(uuid, "wait"), rawBody(uuid))));
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1).error()).isNull();
        assertThat(discrepancyRepository.findByOrderIdOrderByDetectedAtDesc(id)).as("🔴 반복 응답은 불일치를 다시 쌓지 않는다").hasSize(1);
        Mockito.verify(telegram, Mockito.times(1)).notifyRiskLimitBreached(Mockito.anyString());
        assertThat(reload(id).getState()).isEqualTo("UNKNOWN");
        assertThat(availableKrw(f)).isEqualByComparingTo(adjusted);
        assertThat(adjustmentService.find(id).laterObservedAt()).as("같은 관측은 시각도 다시 쓰지 않는다").isEqualTo(a.laterObservedAt());
        verifySubmittedOnce();

        discrepancyRepository.findByOrderIdOrderByDetectedAtDesc(id).forEach(discrepancyRepository::delete);
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("DELETE FROM order_manual_adjustment WHERE order_id = ?", id);
    }

    private String snapshot(Fixture f, Long id) {
        OrderEntity o = reload(id);
        PositionEntity p = positionRepository.findById(f.positionId()).orElseThrow();
        LiveTradingSessionEntity s = sessionRepository.findById(f.sessionId()).orElseThrow();
        return String.join(" | ",
                "state=" + o.getState(), "uuid=" + o.getExchangeOrderId(), "submittedAt=" + o.getSubmittedAt(),
                "filled=" + o.getFilledQuantity(), "price=" + o.getPrice(), "filledAt=" + o.getFilledAt(),
                "ver=" + o.getSettlementVersion(), "final=" + o.getSettlementFinal(), "fee=" + o.getFeeSource(),
                "appliedFunds=" + o.getAppliedExecutedFunds(), "reportedFunds=" + o.getReportedExecutedFunds(),
                "pending=" + o.getFillAccountingPending(), "unknownReason=" + o.getUnknownReason(),
                "pos=" + p.getStatus() + "/" + p.getSize() + "/" + p.getInvestedKrw(),
                "krw=" + s.getAvailableKrw());
    }
}
