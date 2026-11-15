package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.TradeLogEntity;
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

    @Autowired
    @Qualifier("orderExecutor")
    private ThreadPoolTaskExecutor orderExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> createdSessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
    }

    // ── 고정 장치 ────────────────────────────────────────────────

    private record Fixture(Long sessionId, Long positionId, OrderRequest request) { }

    private Fixture runningSessionWithBuyRequest() {
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

        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(BigDecimal.ZERO)
                .investedKrw(new BigDecimal("3000.00000000"))
                .status("OPEN").sessionKind("LIVE").sessionId(session.getId())
                .openedAt(Instant.now()).build());

        OrderRequest req = new OrderRequest();
        req.setCoinPair("KRW-DOGE");
        req.setSide("BUY");
        req.setOrderType("MARKET");
        req.setQuantity(new BigDecimal("3000.00000000"));
        req.setReason("전략 신호 — 제출·해소 경합 시험");
        req.setSignalPrice(new BigDecimal("300.00000000"));
        req.setSessionId(session.getId());
        req.setSessionKind("LIVE");
        req.setPositionId(pos.getId());
        return new Fixture(session.getId(), pos.getId(), req);
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
    private static final class CreateGate {
        final CountDownLatch atExchange = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<String> identifier = new AtomicReference<>();
    }

    /** 폴러가 식별자 조회 안에서 멈춘다. 그 전에 같은 트랜잭션에서 SUBMITTING 을 읽었다. */
    private static final class QueryGate {
        final CountDownLatch querying = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    private static void await(CountDownLatch latch, String what) throws InterruptedException {
        assertThat(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("신호 시간 초과: " + what).isTrue();
    }

    private void stubCreate(CreateGate gate, String uuid, boolean timeout) {
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
    private void stubIdentifierQuery(QueryGate gate, OrderResponse found) {
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
    private Thread startResolver(Long orderId, AtomicReference<Throwable> failure) {
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

    private void join(Thread t, AtomicReference<Throwable> failure) throws InterruptedException {
        t.join(WAIT_MS);
        assertThat(t.isAlive()).as("해소 스레드가 끝나야 한다").isFalse();
        assertThat(failure.get()).as("해소 스레드 예외").isNull();
    }

    private void awaitSubmitThreadDone() {
        com.cryptoautotrader.api.support.ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), WAIT_MS);
    }

    private Long orderIdOf(Fixture f, String identifier) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", f.sessionId()).stream()
                .filter(o -> identifier.equals(o.getClientOrderId()))
                .map(OrderEntity::getId).findFirst().orElseThrow();
    }

    private OrderEntity reload(Long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    private List<String> transitions(Long orderId) {
        return tradeLogRepository.findAll().stream()
                .filter(l -> orderId.equals(l.getOrderId()) && "STATE_CHANGE".equals(l.getEventType()))
                .sorted(java.util.Comparator.comparing(TradeLogEntity::getId))
                .map(l -> l.getOldState() + "→" + l.getNewState())
                .toList();
    }

    private void verifySubmittedOnce() {
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    /** 순서 A·C·D 의 앞부분: 제출 스레드를 거래소 호출 안에 세우고, 폴러를 조회 안에 세운다. */
    private Long submitAndPark(Fixture f, CreateGate create) throws InterruptedException {
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
