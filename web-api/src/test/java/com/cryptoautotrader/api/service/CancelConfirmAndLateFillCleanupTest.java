package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionCleanupEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionCleanupRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 식별자 취소와 늦은 체결 정리 — 하나의 흐름 (2026-10-13).
 *
 * <p>요청 3,000 / 체결 2,940 / 수수료 1.47 → 미사용 58.53. 세션 잔액은 예약 후 7,000 에서 시작한다.</p>
 * <ul>
 *   <li><b>A. 취소 확인과 체결량 확인</b> — 취소 요청의 성공은 CANCELLED 가 아니다. 체결분은 회계에 반영하고
 *       미사용 예약만 푼다(7,058.53). 체결량 0 으로 확인된 취소만 전액 복원한다(10,000, 한 번).</li>
 *   <li><b>B. 원자 경로 밖 CANCELLED 의 체결 여부</b> — 확인되지 않은 CANCELLED 매수는 복원하지 않고 재조회로
 *       확인한다.</li>
 *   <li><b>C. 정지 후 식별자 취소</b> — 응답을 잃은 주문을 식별자로 취소하고, 404 는 미접수로 읽지 않는다.</li>
 *   <li><b>D. 늦은 체결 정리</b> — 정지 후 수량이 생긴 포지션은 정리 대기에 오르고, 정리 작업이 정지의 청산
 *       정책으로 한 번만 매도하며, CLOSED 확인 전에는 완료로 표시하지 않는다.</li>
 * </ul>
 */
@DisplayName("식별자 취소·취소 확인·늦은 체결 정리")
class CancelConfirmAndLateFillCleanupTest extends IntegrationTestBase {

    private static final BigDecimal CAPITAL = new BigDecimal("10000");

    @Autowired private OrderExecutionEngine engine;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired private PositionCleanupRegistrar cleanupRegistrar;
    @Autowired private com.cryptoautotrader.api.repository.OrderSettlementDiscrepancyRepository discrepancyRepository;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private void markClosing(Fx f) {
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(st ->
                positionRepository.markClosingIfOpen(f.positionId(), Instant.now(),
                        com.cryptoautotrader.api.entity.ExitReason.FORCED_STOP));
    }

    // AtomicPathBypassTest 와 같은 목 조합 — 컨텍스트를 공유한다.
    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();

    @BeforeEach
    void defaultExchange() {
        // 공유 DB 에 남은 다른 활성 주문은 "대기 중" 으로 답한다.
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString())).thenAnswer(inv -> exchange(inv.getArgument(0), "wait", null));
    }

    @AfterEach
    void cleanUp() {
        awaitDrained();
        for (Long id : liveSessions) {
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(p -> cleanupRepository.deleteById(p.getId()));
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.deleteById(id);
        }
        for (Long id : dynamicSessions) {
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(p -> cleanupRepository.deleteById(p.getId()));
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.deleteById(id);
        }
        liveSessions.clear();
        dynamicSessions.clear();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private record Fx(String kind, Long sessionId, Long positionId, Long orderId, String uuid, String identifier) { }

    private Long session(String kind, String status) {
        if ("DYNAMIC".equals(kind)) {
            Long id = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                    .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                    .initialCapital(CAPITAL).availableKrw(new BigDecimal("7000")).totalAssetKrw(CAPITAL)
                    .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                    .status(status).scanState("POSITION_MONITORING").tradingMode("REAL")
                    .maxCandidateSize(30).targetWatchSize(10)
                    .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                    .watchlistRefreshMin(60)
                    .build()).getId();
            dynamicSessions.add(id);
            return id;
        }
        Long id = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(CAPITAL).availableKrw(new BigDecimal("7000")).totalAssetKrw(CAPITAL)
                .status(status).startedAt(Instant.now()).build()).getId();
        liveSessions.add(id);
        return id;
    }

    /** size=0 포지션과 3,000 을 예약한 시장가 매수. {@code uuid} 가 null 이면 응답을 잃은 주문이다. */
    private Fx buy(String kind, String sessionStatus, String orderState, boolean withUuid) {
        Long sid = session(kind, sessionStatus);
        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(BigDecimal.ZERO).investedKrw(new BigDecimal("3000"))
                .status("OPEN").sessionKind(kind).sessionId(sid)
                .openedAt(Instant.now()).build());
        String uuid = withUuid ? "uuid-cc-" + kind + "-" + System.nanoTime() : null;
        String identifier = "cat-cc-" + System.nanoTime();
        Instant at = Instant.now().minusSeconds(10);
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000")).reservedKrw(new BigDecimal("3000"))
                .state(orderState)
                .sessionKind(kind).sessionId(sid).positionId(pos.getId())
                .clientOrderId(identifier).exchangeOrderId(uuid)
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L).settlementFinal(Boolean.FALSE).settlementAttempts(0)
                .createdAt(at).submittedAt(withUuid ? at : null).build());
        return new Fx(kind, sid, pos.getId(), o.getId(), uuid, identifier);
    }

    private static OrderResponse exchange(String uuid, String state, BigDecimal executedVolume) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("bid");
        r.setOrdType("price");
        r.setState(state);
        r.setMarket("KRW-DOGE");
        r.setVolume(new BigDecimal("3000"));
        r.setExecutedVolume(executedVolume);
        if (executedVolume != null && executedVolume.signum() > 0) {
            r.setExecutedFunds(new BigDecimal("2940.00000000"));
            r.setPaidFee(new BigDecimal("1.47000000"));
        }
        return r;
    }

    private static final BigDecimal FILL = new BigDecimal("9.80000000");

    private BigDecimal available(Fx f) {
        return "DYNAMIC".equals(f.kind())
                ? dynamicRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw()
                : liveRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw();
    }

    private OrderEntity order(Fx f) {
        return orderRepository.findById(f.orderId()).orElseThrow();
    }

    private PositionEntity position(Fx f) {
        return positionRepository.findById(f.positionId()).orElseThrow();
    }

    private void orphanReconcile(String kind) {
        if ("DYNAMIC".equals(kind)) dynamicTradingService.reconcileDynamicOrphanBuyPositions();
        else liveTradingService.reconcileOrphanBuyPositions();
    }

    private void stop(Fx f) {
        if ("DYNAMIC".equals(f.kind())) dynamicTradingService.stopSession(f.sessionId());
        else liveTradingService.stopSession(f.sessionId());
    }

    private void cleanupWorker(String kind) {
        if ("DYNAMIC".equals(kind)) dynamicTradingService.cleanupStoppedSessionPositions();
        else liveTradingService.cleanupStoppedSessionPositions();
    }

    private void closingReconcile(String kind) {
        if ("DYNAMIC".equals(kind)) dynamicTradingService.reconcileDynamicClosingPositions();
        else liveTradingService.reconcileClosingPositions();
    }

    private void expireCancelRetry(Fx f) {
        OrderEntity o = order(f);
        if (o.getCancelRequestedAt() != null) {
            o.setCancelRequestedAt(Instant.now().minusSeconds(60));
            orderRepository.save(o);
        }
    }

    private String snap(Fx f) {
        OrderEntity o = order(f);
        PositionEntity p = position(f);
        return String.format("order{state=%s filled=%s ver=%s confirmed=%s} pos{status=%s size=%s} available=%s",
                o.getState(), o.getFilledQuantity(), o.getSettlementVersion(),
                o.getCancelConfirmedAt() != null, p.getStatus(), p.getSize(), available(f));
    }

    private void awaitDrained() {
        var pool = orderExecutor.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("orderExecutor 가 비워지지 않았다");
    }

    // ── A. 취소 확인과 체결량 확인 ─────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("A1 취소 요청 성공 + 체결 후 취소 — CANCELLED 로 닫지 않고 체결분 반영, 미사용만 해제, 고아 정리 무복원")
    void cancelSucceededButFilled(String kind) {
        Fx f = buy(kind, "RUNNING", "SUBMITTED", true);
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO),
                exchange(f.uuid(), "cancel", FILL)).when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).cancelOrder(f.uuid());

        engine.cancelOrder(f.orderId());
        System.out.println("[A1 " + kind + " 취소 직후] " + snap(f));
        assertThat(order(f).getState())
                .as("🔴 취소 요청이 성공했어도 체결이 있으면 CANCELLED 가 아니다 — 종전에는 여기서 CANCELLED 였고 "
                        + "고아 정리가 예약 3,000 을 전액 복원했다(코인도 돈도 생긴다)")
                .isEqualTo("SUBMITTED");

        engine.pollActiveOrders();
        orphanReconcile(kind);
        orphanReconcile(kind);
        System.out.println("[A1 " + kind + " 반영 후] " + snap(f));

        assertThat(available(f)).as("체결분 반영 + 미사용 58.53 만 해제").isEqualByComparingTo("7058.53");
        assertThat(position(f).getSize()).isEqualByComparingTo("9.8");
        assertThat(position(f).getStatus()).isEqualTo("OPEN");
        assertThat(order(f).getState()).isEqualTo("FILLED");
        assertThat(order(f).getSettlementVersion()).isEqualTo(1L);
        // 🔴 부분 체결 후 잔량 취소의 종결 상태 — FILLED(체결분 기준) + 거래소 보고 상태 'cancel' 보존.
        //    체결 회계와 미사용 해제가 끝났고 활성 목록에서 빠진다(영구 활성 아님).
        assertThat(order(f).getReportedState()).isEqualTo("cancel");
        assertThat(order(f).getFillAccountingPending()).isFalse();
        assertThat(order(f).getCancelConfirmedAt()).as("체결 0 확인이 아니다").isNull();
        assertThat(engine.getActiveOrders()).extracting(OrderEntity::getId).doesNotContain(f.orderId());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("A6 진짜 부분 체결(3,000 중 1,470) 후 잔량 취소 — 요청량·체결량·cancel 상태 보존, 금액 표시·투입금은 실제 사용액")
    void partialFillThenCancelPreservesFacts(String kind) {
        Fx f = buy(kind, "RUNNING", "SUBMITTED", true);
        OrderResponse partial = exchange(f.uuid(), "cancel", new BigDecimal("4.90000000"));
        partial.setExecutedFunds(new BigDecimal("1470.00000000"));
        partial.setPaidFee(new BigDecimal("0.73500000"));
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO), partial).when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).cancelOrder(f.uuid());

        engine.cancelOrder(f.orderId());
        engine.pollActiveOrders();
        orphanReconcile(kind);
        OrderEntity o = order(f);
        PositionEntity p = position(f);
        System.out.println("[A6 " + kind + "] " + snap(f) + " 요청=" + o.getQuantity() + " 체결=" + o.getFilledQuantity()
                + " 사용=" + o.getAppliedExecutedFunds() + " 보고상태=" + o.getReportedState()
                + " 표시금액=" + com.cryptoautotrader.api.util.OrderAmounts.krwAmount(o) + " 투입금=" + p.getInvestedKrw());
        assertThat(o.getState()).isEqualTo("FILLED");
        assertThat(o.getQuantity()).as("요청량 보존").isEqualByComparingTo("3000");
        assertThat(o.getFilledQuantity()).as("실제 체결량").isEqualByComparingTo("4.9");
        assertThat(o.getAppliedExecutedFunds()).as("실제 사용액").isEqualByComparingTo("1470");
        assertThat(o.getReportedState()).as("거래소 cancel 상태 보존").isEqualTo("cancel");
        assertThat(com.cryptoautotrader.api.util.OrderAmounts.krwAmount(o))
                .as("🔴 화면·CSV 금액 — 종전에는 요청액 3,000 이 나왔다").isEqualByComparingTo("1470");
        assertThat(p.getInvestedKrw()).as("🔴 투입금 = 체결금액 + 수수료 — 종전에는 요청액 3,000").isEqualByComparingTo("1470.735");
        assertThat(p.getSize()).isEqualByComparingTo("4.9");
        // 7,000 + (3,000 − 1,470 − 0.735) = 8,529.265 → 세션 잔액 열 2자리
        assertThat(available(f)).isBetween(new BigDecimal("8529.26"), new BigDecimal("8529.27"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("A5 체결 후 취소인데 체결금액이 아직 미보고 — 회계 대기로 활성 유지·복원 없음, 보고되면 그때 종결")
    void cancelWithFillButFundsMissing(String kind) {
        Fx f = buy(kind, "RUNNING", "SUBMITTED", true);
        OrderResponse noFunds = exchange(f.uuid(), "cancel", FILL);
        noFunds.setExecutedFunds(null);
        noFunds.setPaidFee(null);
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO), noFunds)
                .when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).cancelOrder(f.uuid());

        engine.cancelOrder(f.orderId());
        engine.pollActiveOrders();
        orphanReconcile(kind);
        System.out.println("[A5 " + kind + " 금액 미보고] " + snap(f));
        assertThat(order(f).getState()).isEqualTo("SUBMITTED");
        assertThat(order(f).getFillAccountingPending()).as("관측은 남고 회계 대기").isTrue();
        assertThat(available(f)).as("복원 없음").isEqualByComparingTo("7000");
        assertThat(position(f).getStatus()).isEqualTo("OPEN");

        Mockito.doReturn(exchange(f.uuid(), "cancel", FILL)).when(upbitOrderClient).getOrder(f.uuid());
        engine.pollActiveOrders();
        System.out.println("[A5 " + kind + " 금액 보고 후] " + snap(f));
        assertThat(order(f).getState()).isEqualTo("FILLED");
        assertThat(available(f)).isEqualByComparingTo("7058.53");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("A2 체결량 0 으로 확인된 취소 — CANCELLED·확인 시각, 고아 정리가 예약을 정확히 한 번 복원")
    void cancelConfirmedUnfilled(String kind) {
        Fx f = buy(kind, "RUNNING", "SUBMITTED", true);
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO),
                exchange(f.uuid(), "cancel", BigDecimal.ZERO)).when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).cancelOrder(f.uuid());

        engine.cancelOrder(f.orderId());
        assertThat(order(f).getState()).isEqualTo("CANCELLED");
        assertThat(order(f).getCancelConfirmedAt()).isNotNull();

        orphanReconcile(kind);
        orphanReconcile(kind);
        System.out.println("[A2 " + kind + "] " + snap(f));
        assertThat(available(f)).isEqualByComparingTo(CAPITAL);
        assertThat(position(f).getStatus()).isEqualTo("CLOSED");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("A3 체결량을 보고하지 않은 취소 — 확인이 아니다: 활성 유지, 복원 없음")
    void cancelWithoutReportedVolume(String kind) {
        Fx f = buy(kind, "RUNNING", "SUBMITTED", true);
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO),
                exchange(f.uuid(), "cancel", null)).when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).cancelOrder(f.uuid());

        engine.cancelOrder(f.orderId());
        engine.pollActiveOrders();
        orphanReconcile(kind);
        System.out.println("[A3 " + kind + "] " + snap(f));
        assertThat(order(f).getState()).isEqualTo("SUBMITTED");
        assertThat(order(f).getCancelRequestedAt()).isNotNull();
        assertThat(available(f)).isEqualByComparingTo("7000");
        assertThat(position(f).getStatus()).isEqualTo("OPEN");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("A4 실행 중 세션의 5분 타임아웃 — 취소를 요청하되 체결 후 취소를 원자 경로로 반영")
    void timeoutCancelThenFill(String kind) {
        Fx f = buy(kind, "RUNNING", "SUBMITTED", true);
        OrderEntity o = order(f);
        o.setSubmittedAt(Instant.now().minus(Duration.ofMinutes(6)));
        orderRepository.save(o);
        // 타임아웃 직전 조회(대기) → 취소 전 조회(대기) → 취소 → 확인 조회(체결 후 취소)
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO),
                exchange(f.uuid(), "wait", BigDecimal.ZERO),
                exchange(f.uuid(), "cancel", FILL)).when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).cancelOrder(f.uuid());

        engine.pollActiveOrders();
        engine.pollActiveOrders();
        orphanReconcile(kind);
        System.out.println("[A4 " + kind + "] " + snap(f));
        Mockito.verify(upbitOrderClient, Mockito.times(1)).cancelOrder(f.uuid());
        assertThat(available(f)).isEqualByComparingTo("7058.53");
        assertThat(order(f).getState()).isEqualTo("FILLED");
        assertThat(position(f).getSize()).isEqualByComparingTo("9.8");
    }

    // ── B. 원자 경로 밖 CANCELLED 의 체결 여부 ─────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("B1 확인되지 않은 CANCELLED 매수에 체결이 있었다 — 복원하지 않고, 재조회가 체결분을 반영")
    void unconfirmedCancelledHadFill(String kind) {
        Fx f = buy(kind, "RUNNING", "CANCELLED", true);
        Mockito.doReturn(exchange(f.uuid(), "cancel", FILL)).when(upbitOrderClient).getOrder(f.uuid());

        orphanReconcile(kind);
        assertThat(available(f))
                .as("🔴 확인되지 않은 CANCELLED — 종전 규칙이면 여기서 3,000 이 복원돼 10,000 이 된다")
                .isEqualByComparingTo("7000");
        assertThat(position(f).getStatus()).isEqualTo("OPEN");

        engine.pollActiveOrders();
        String first = snap(f);
        System.out.println("[B1 " + kind + "] " + first);
        assertThat(available(f)).isEqualByComparingTo("7058.53");
        assertThat(position(f).getSize()).isEqualByComparingTo("9.8");
        assertThat(order(f).getState()).isEqualTo("FILLED");

        for (int i = 0; i < 3; i++) {
            OrderEntity o = order(f);
            o.setSettlementNextCheckAt(Instant.now().minusSeconds(1));
            o.setCancelCheckedAt(null);
            orderRepository.save(o);
            engine.pollActiveOrders();
            engine.checkOrderStatus(f.orderId());
            orphanReconcile(kind);
        }
        assertThat(snap(f)).as("반복 처리에서 바뀌면 중복 반영").isEqualTo(first);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("B2 확인되지 않은 CANCELLED 매수가 체결량 0 으로 확인됨 — 그때 한 번 복원")
    void unconfirmedCancelledHadNoFill(String kind) {
        Fx f = buy(kind, "RUNNING", "CANCELLED", true);
        Mockito.doReturn(exchange(f.uuid(), "cancel", BigDecimal.ZERO)).when(upbitOrderClient).getOrder(f.uuid());

        orphanReconcile(kind);
        assertThat(available(f)).isEqualByComparingTo("7000");

        engine.pollActiveOrders();
        assertThat(order(f).getCancelConfirmedAt()).isNotNull();
        orphanReconcile(kind);
        orphanReconcile(kind);
        System.out.println("[B2 " + kind + "] " + snap(f));
        assertThat(available(f)).isEqualByComparingTo(CAPITAL);
        assertThat(position(f).getStatus()).isEqualTo("CLOSED");
    }

    // ── C. 정지 후 식별자 취소 ────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("C1 정지 — 응답 불명 매수를 식별자로 취소, 체결 0 확인되면 정지 정리가 한 번 복원")
    void stopCancelsUnknownByIdentifier(String kind) {
        Fx f = buy(kind, "RUNNING", "UNKNOWN", false);
        String found = "uuid-found-" + System.nanoTime();
        Mockito.doReturn(null).when(upbitOrderClient).getOrderByIdentifier(f.identifier());
        Mockito.doReturn(exchange(found, "wait", BigDecimal.ZERO)).when(upbitOrderClient).cancelOrderByIdentifier(f.identifier());
        Mockito.doReturn(exchange(found, "cancel", BigDecimal.ZERO)).when(upbitOrderClient).getOrder(found);

        stop(f);
        Mockito.verify(upbitOrderClient).cancelOrderByIdentifier(f.identifier());
        System.out.println("[C1 " + kind + " 정지 직후] " + snap(f));
        assertThat(order(f).getExchangeOrderId()).isEqualTo(found);
        assertThat(order(f).getState()).isEqualTo("CANCELLED");
        assertThat(order(f).getCancelConfirmedAt()).isNotNull();

        orphanReconcile(kind);
        cleanupWorker(kind);
        cleanupWorker(kind);
        System.out.println("[C1 " + kind + " 정리 후] " + snap(f));
        assertThat(available(f)).as("체결 0 이 확인된 취소 — 예약 3,000 을 한 번").isEqualByComparingTo(CAPITAL);
        assertThat(position(f).getStatus()).isEqualTo("CLOSED");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("C2 정지 — 식별자 취소가 404: 미접수로 읽지 않는다(상태 유지·복원 없음·정리 대기)")
    void identifierCancelNotFound(String kind) {
        Fx f = buy(kind, "RUNNING", "UNKNOWN", false);
        Mockito.doReturn(null).when(upbitOrderClient).getOrderByIdentifier(f.identifier());
        Mockito.doReturn(null).when(upbitOrderClient).cancelOrderByIdentifier(f.identifier());

        stop(f);
        expireCancelRetry(f);
        engine.pollActiveOrders();
        orphanReconcile(kind);
        cleanupWorker(kind);
        System.out.println("[C2 " + kind + "] " + snap(f));
        Mockito.verify(upbitOrderClient, Mockito.atLeast(2)).cancelOrderByIdentifier(f.identifier());
        assertThat(order(f).getState()).isEqualTo("UNKNOWN");
        assertThat(available(f)).isEqualByComparingTo("7000");
        assertThat(position(f).getStatus()).isEqualTo("OPEN");
        PositionCleanupEntity c = cleanupRepository.findById(f.positionId()).orElseThrow();
        assertThat(c.getDoneAt()).as("확인 전에는 정리 완료가 아니다").isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("C3 정지 — 취소 요청 실패 + 거래소에 주문이 살아 있음: 정지가 깨지지 않고 추적·재요청이 이어진다")
    void stopSurvivesCancelFailure(String kind) {
        Fx f = buy(kind, "RUNNING", "SUBMITTED", true);
        // 같은 세션의 보유 포지션 — 정지의 **바깥 트랜잭션**이 CLOSING 으로 바꾼다. 종전 실패에서는 세션 상태만
        // 별도 연결로 STOPPED 가 되고 바깥(이 전환 포함)은 롤백됐다. 이것이 CLOSING 이면 바깥이 커밋된 것이다.
        PositionEntity held = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(new BigDecimal("5")).investedKrw(new BigDecimal("1500"))
                .status("OPEN").sessionKind(kind).sessionId(f.sessionId())
                .openedAt(Instant.now()).build());
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doThrow(new RuntimeException("timeout")).when(upbitOrderClient).cancelOrder(f.uuid());

        // 🔴 종전 LIVE 정지는 여기서 `cancelOrder` 가 던진 예외가 정지 트랜잭션을 롤백 전용으로 만들어
        //    UnexpectedRollbackException 으로 깨졌다(세션 상태만 별도 연결로 STOPPED 가 됐다 — 2026-10-13 실측).
        stop(f);

        String status = "DYNAMIC".equals(kind)
                ? dynamicRepo.findById(f.sessionId()).orElseThrow().getStatus()
                : liveRepo.findById(f.sessionId()).orElseThrow().getStatus();
        System.out.println("[C3 " + kind + "] 세션=" + status + " " + snap(f));
        assertThat(status).isEqualTo("STOPPED");
        assertThat(order(f).getState()).isEqualTo("SUBMITTED");
        assertThat(order(f).getCancelRequestedAt()).isNotNull();
        assertThat(cleanupRepository.findById(f.positionId())).isPresent();
        assertThat(positionRepository.findById(held.getId()).orElseThrow().getStatus())
                .as("🔴 정지의 바깥 트랜잭션이 커밋됐다 — 보유 포지션 청산 전환이 남아 있다").isEqualTo("CLOSING");
        assertThat(available(f)).as("예약 유지").isEqualByComparingTo("7000");

        expireCancelRetry(f);
        engine.pollActiveOrders();
        Mockito.verify(upbitOrderClient, Mockito.times(2)).cancelOrder(f.uuid());
    }

    // ── D. 늦은 체결 정리 ─────────────────────────────────────────────────

    /**
     * 정지 → 정지 후 체결 → 정리 대기 → 청산 매도 1회 → CLOSED 확인 후 완료.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("D1 정지 후 늦은 체결 — 정리 대기, 청산 매도 1회(반복·동시 실행에도), CLOSED 확인 후에만 완료")
    void lateFillAfterStopIsLiquidatedOnce(String kind) throws Exception {
        Fx f = buy(kind, "RUNNING", "SUBMITTED", true);
        // 정지 시 취소 요청: 취소 전 조회(대기) → 취소 → 확인 조회(대기 — 취소 처리 중)
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).cancelOrder(f.uuid());
        String sellUuid = "uuid-sell-" + System.nanoTime();
        Mockito.doAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        exchange(sellUuid, "wait", BigDecimal.ZERO), "{}"))
                .when(upbitOrderClient).createOrder(Mockito.eq("KRW-DOGE"), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        stop(f);
        assertThat(position(f).getStatus()).as("진행 중 매수의 size=0 포지션은 열어 둔다").isEqualTo("OPEN");
        assertThat(cleanupRepository.findById(f.positionId())).as("정지가 정리 대기로 등록한다").isPresent();

        // 정지 후 체결
        Mockito.doReturn(exchange(f.uuid(), "done", FILL)).when(upbitOrderClient).getOrder(f.uuid());
        engine.pollActiveOrders();
        System.out.println("[D1 " + kind + " 늦은 체결 반영] " + snap(f));
        assertThat(available(f)).isEqualByComparingTo("7058.53");
        assertThat(position(f).getSize()).isEqualByComparingTo("9.8");

        // 정리 작업 — 동시에 둘, 그 뒤 반복
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable worker = () -> {
            try {
                barrier.await();
                cleanupWorker(kind);
            } catch (Throwable t) {
                errors.add(t);
            }
        };
        Thread t1 = new Thread(worker);
        Thread t2 = new Thread(worker);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertThat(errors).isEmpty();
        awaitDrained();
        for (int i = 0; i < 3; i++) {
            cleanupWorker(kind);
        }
        awaitDrained();

        List<OrderEntity> sells = orderRepository.findByPositionIdOrderByCreatedAtDesc(f.positionId()).stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide())).toList();
        System.out.println("[D1 " + kind + " 정리 작업 후] " + snap(f) + " 매도=" + sells.size()
                + " " + sells.stream().map(o -> o.getState() + "/" + o.getQuantity()).toList());
        assertThat(sells).as("🔴 청산 매도는 한 번만 — 동시·반복 실행에도").hasSize(1);
        assertThat(sells.get(0).getQuantity()).isEqualByComparingTo("9.8");
        assertThat(position(f).getStatus()).isEqualTo("CLOSING");
        assertThat(position(f).getExitReason()).isEqualTo(com.cryptoautotrader.api.entity.ExitReason.FORCED_STOP);
        assertThat(cleanupRepository.findById(f.positionId()).orElseThrow().getDoneAt())
                .as("🔴 매도를 제출했다는 사실로 완료 처리하지 않는다").isNull();

        // 매도 체결 → 청산 확정 → 정리 완료
        OrderResponse sellDone = new OrderResponse();
        sellDone.setUuid(sellUuid);
        sellDone.setSide("ask");
        sellDone.setOrdType("market");
        sellDone.setState("done");
        sellDone.setMarket("KRW-DOGE");
        sellDone.setVolume(FILL);
        sellDone.setExecutedVolume(FILL);
        sellDone.setExecutedFunds(new BigDecimal("2960.00000000"));
        sellDone.setPaidFee(new BigDecimal("1.48000000"));
        Mockito.doReturn(sellDone).when(upbitOrderClient).getOrder(sellUuid);
        engine.pollActiveOrders();
        closingReconcile(kind);
        cleanupWorker(kind);
        System.out.println("[D1 " + kind + " 청산 후] " + snap(f));
        assertThat(position(f).getStatus()).isEqualTo("CLOSED");
        assertThat(cleanupRepository.findById(f.positionId()).orElseThrow().getDoneAt()).isNotNull();
    }

    /**
     * D2 — 앞선 청산 매도가 종결됐을 때 다시 내는가는 <b>체결 없음의 근거</b>로 정한다 (2026-10-13).
     *
     * <ul>
     *   <li>{@code EXCHANGE_REJECTED} — 거래소의 명시적 거절. 🔴 현재 제출 경로는 4xx 를 분류하지 않아 이 코드를 만들지
     *       않는다(제출 중 예외는 전부 UNKNOWN). 여기서는 그 분류가 생겼을 때의 규칙을 확인한다.</li>
     *   <li>근거 코드 없는 FAILED — 종전 데이터·수동 종결. 다시 내지 않는다.</li>
     *   <li>확인 없는 CANCELLED — 체결됐을 수 있다. 다시 내지 않는다.</li>
     * </ul>
     * 실제 제출 경로에서 "외부로 나가지 않았음이 확정된" 실패(철회·의도 단계 거절)는 D3 이 본다.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"LIVE,EXCHANGE_REJECTED,2", "LIVE,FAILED_NO_CODE,1", "LIVE,CANCELLED_UNCONFIRMED,1",
            "DYNAMIC,EXCHANGE_REJECTED,2", "DYNAMIC,FAILED_NO_CODE,1", "DYNAMIC,CANCELLED_UNCONFIRMED,1"})
    @DisplayName("D2 청산 매도가 체결 없이 끝났다 — 근거가 있을 때만 다시 내고, 그동안 완료가 아니다")
    void liquidationFailureIsRetried(String kind, String ending, int expectedSells) {
        Fx f = buy(kind, "RUNNING", "SUBMITTED", true);
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).cancelOrder(f.uuid());
        Mockito.doAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        exchange("uuid-sell-" + System.nanoTime(), "wait", BigDecimal.ZERO), "{}"))
                .when(upbitOrderClient).createOrder(Mockito.eq("KRW-DOGE"), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        stop(f);
        Mockito.doReturn(exchange(f.uuid(), "done", FILL)).when(upbitOrderClient).getOrder(f.uuid());
        engine.pollActiveOrders();
        cleanupWorker(kind);
        awaitDrained();

        OrderEntity firstSell = orderRepository.findByPositionIdOrderByCreatedAtDesc(f.positionId()).stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide())).findFirst().orElseThrow();
        switch (ending) {
            case "EXCHANGE_REJECTED" -> {
                firstSell.setState("FAILED");
                firstSell.setFailureCode(OrderExecutionEngine.EXCHANGE_REJECTED);
            }
            case "FAILED_NO_CODE" -> firstSell.setState("FAILED");
            default -> firstSell.setState("CANCELLED");   // 확인 시각 없음
        }
        orderRepository.save(firstSell);
        closingReconcile(kind);
        assertThat(position(f).getStatus()).as("종결된 매도 — OPEN 롤백").isEqualTo("OPEN");

        for (int i = 0; i < 3; i++) {
            cleanupWorker(kind);
            awaitDrained();
        }
        long sells = orderRepository.findByPositionIdOrderByCreatedAtDesc(f.positionId()).stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide())).count();
        PositionCleanupEntity c = cleanupRepository.findById(f.positionId()).orElseThrow();
        System.out.println("[D2 " + kind + " " + ending + "] " + snap(f) + " 매도=" + sells + " 메모=" + c.getLastNote());
        assertThat(sells).isEqualTo(expectedSells);
        verifySellSubmits(expectedSells);
        assertThat(c.getDoneAt()).isNull();
    }

    // ── D3~D5. 정리 작업의 "매도 한 번" — 재시작·응답 유실·부분 체결 ─────────────────

    /** 정지된 세션의, 늦은 체결로 수량 9.8 이 생긴 포지션과 정리 대기 행. */
    private Fx lateFilledInStoppedSession(String kind) {
        Fx f = buy(kind, "STOPPED", "FILLED", true);
        PositionEntity p = position(f);
        p.setSize(FILL);
        positionRepository.save(p);
        OrderEntity b = order(f);
        b.setFilledQuantity(FILL);
        orderRepository.save(b);
        cleanupRegistrar.register(f.positionId(), kind, f.sessionId(), PositionCleanupEntity.REASON_LATE_FILL);
        return f;
    }

    private final java.util.concurrent.atomic.AtomicInteger sellSeq = new java.util.concurrent.atomic.AtomicInteger();

    private void stubSellSubmissionsAccepted() {
        Mockito.doAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        exchange("uuid-sell-" + sellSeq.incrementAndGet() + "-" + System.nanoTime(), "wait", BigDecimal.ZERO), "{}"))
                .when(upbitOrderClient).createOrder(Mockito.eq("KRW-DOGE"), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    private List<OrderEntity> sells(Fx f) {
        return orderRepository.findByPositionIdOrderByCreatedAtDesc(f.positionId()).stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide())).toList();
    }

    private void verifySellSubmits(int times) {
        Mockito.verify(upbitOrderClient, Mockito.times(times)).createOrder(Mockito.eq("KRW-DOGE"), Mockito.eq("ask"),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    private void setCleanupLastAttempt(Fx f, Instant at) {
        PositionCleanupEntity c = cleanupRepository.findById(f.positionId()).orElseThrow();
        c.setLastAttemptAt(at);
        cleanupRepository.save(c);
    }

    private static OrderResponse sellReport(String uuid, String state, String vol, String funds) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("ask");
        r.setOrdType("market");
        r.setState(state);
        r.setMarket("KRW-DOGE");
        r.setVolume(FILL);
        r.setExecutedVolume(new BigDecimal(vol));
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPaidFee(new BigDecimal(funds).multiply(new BigDecimal("0.0005")));
        return r;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("D3 재시작 — 제출 전 의도만 남고 소유 프로세스가 사라짐: 철회 후 한 번만 다시 낸다(거래소 제출 1회)")
    void restartAfterIntentBeforeClaim(String kind) {
        Fx f = lateFilledInStoppedSession(kind);
        stubSellSubmissionsAccepted();
        // 앞 프로세스의 정리 작업이 CLOSING 으로 바꾸고 매도 의도를 커밋한 직후 죽었다.
        markClosing(f);
        OrderEntity dead = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("SELL").orderType("MARKET").quantity(FILL)
                .state("INTENT_READY").sessionKind(kind).sessionId(f.sessionId()).positionId(f.positionId())
                .clientOrderId("cat-dead-" + System.nanoTime()).intentOwner("inst-previous-process")
                .intentCommittedAt(Instant.now().minusSeconds(30))
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L).settlementFinal(Boolean.FALSE).settlementAttempts(0).build());

        engine.pollActiveOrders();     // 복구 — 소유자가 사라진 의도를 철회
        closingReconcile(kind);        // FAILED → OPEN
        cleanupWorker(kind);
        awaitDrained();
        for (int i = 0; i < 3; i++) {
            engine.pollActiveOrders();
            closingReconcile(kind);
            cleanupWorker(kind);
            awaitDrained();
        }
        System.out.println("[D3 " + kind + "] " + snap(f) + " 매도=" + sells(f).stream().map(OrderEntity::getState).toList());
        assertThat(orderRepository.findById(dead.getId()).orElseThrow().getFailureCode())
                .isEqualTo(OrderExecutionEngine.WITHDRAWN_OWNER_LOST);
        verifySellSubmits(1);
        assertThat(sells(f)).hasSize(2);
        assertThat(position(f).getStatus()).isEqualTo("CLOSING");
        assertThat(cleanupRepository.findById(f.positionId()).orElseThrow().getDoneAt()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("D3b 재시작 — 의도조차 남기기 전에 사라짐: 유예 안에서는 기다리고, 유예 뒤 한 번 낸다")
    void restartBeforeIntent(String kind) {
        Fx f = lateFilledInStoppedSession(kind);
        stubSellSubmissionsAccepted();
        markClosing(f);
        setCleanupLastAttempt(f, Instant.now().minusSeconds(10));

        closingReconcile(kind);        // 매도 주문 없음 → OPEN
        cleanupWorker(kind);
        awaitDrained();
        assertThat(sells(f)).as("유예 안 — 직전 제출이 아직 보이지 않으면 다시 내지 않는다").isEmpty();
        verifySellSubmits(0);

        setCleanupLastAttempt(f, Instant.now().minusSeconds(180));
        cleanupWorker(kind);
        awaitDrained();
        cleanupWorker(kind);
        awaitDrained();
        System.out.println("[D3b " + kind + "] " + snap(f) + " 매도=" + sells(f).size());
        verifySellSubmits(1);
        assertThat(sells(f)).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("D4 응답 유실 — 매도가 불명이면 CLOSING 시간 초과로 OPEN 이 돼도 다시 내지 않고, 체결 확인 뒤 그 매도로 닫는다")
    void responseLossDoesNotResubmit(String kind) {
        Fx f = lateFilledInStoppedSession(kind);
        Mockito.doThrow(new RuntimeException("read timeout"))
                .when(upbitOrderClient).createOrder(Mockito.eq("KRW-DOGE"), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(null).when(upbitOrderClient).getOrderByIdentifier(Mockito.anyString());

        cleanupWorker(kind);
        awaitDrained();
        OrderEntity lost = sells(f).get(0);
        assertThat(lost.getState()).isEqualTo("UNKNOWN");

        PositionEntity p = position(f);
        p.setClosingAt(Instant.now().minus(Duration.ofMinutes(9)));   // CLOSING 시간 초과(8분) 경과
        positionRepository.save(p);
        closingReconcile(kind);
        assertThat(position(f).getStatus()).isEqualTo("OPEN");
        for (int i = 0; i < 3; i++) {
            cleanupWorker(kind);
            engine.pollActiveOrders();
            awaitDrained();
        }
        verifySellSubmits(1);
        assertThat(cleanupRepository.findById(f.positionId()).orElseThrow().getDoneAt()).isNull();

        // 불명 매도가 식별자로 체결 확인된다
        String uuid = "uuid-lost-sell-" + System.nanoTime();
        OrderResponse found = sellReport(uuid, "done", "9.8", "2940");
        Mockito.doReturn(found).when(upbitOrderClient).getOrderByIdentifier(lost.getClientOrderId());
        Mockito.doReturn(found).when(upbitOrderClient).getOrder(uuid);
        OrderEntity l = orderRepository.findById(lost.getId()).orElseThrow();
        l.setUnknownProbedAt(null);
        orderRepository.save(l);
        // 식별자 해소의 SUBMITTED 전이는 폴러 트랜잭션과 함께 커밋되므로 체결 반영은 다음 회차다(종전 동작).
        engine.pollActiveOrders();
        engine.pollActiveOrders();
        assertThat(orderRepository.findById(lost.getId()).orElseThrow().getState()).isEqualTo("FILLED");

        cleanupWorker(kind);           // 다시 팔지 않고 CLOSING 으로만
        closingReconcile(kind);        // 그 체결로 확정
        cleanupWorker(kind);
        awaitDrained();
        System.out.println("[D4 " + kind + "] " + snap(f) + " 매도=" + sells(f).stream().map(OrderEntity::getState).toList());
        verifySellSubmits(1);
        assertThat(position(f).getStatus()).isEqualTo("CLOSED");
        assertThat(cleanupRepository.findById(f.positionId()).orElseThrow().getDoneAt()).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("D5 청산 매도 부분 체결 — 정리 대기 유지, 남은 수량만 한 번 더, 대금은 매도마다 한 번, 전량 확인 뒤 CLOSED")
    void partialLiquidation(String kind) {
        Fx f = lateFilledInStoppedSession(kind);
        stubSellSubmissionsAccepted();

        cleanupWorker(kind);
        awaitDrained();
        OrderEntity s1 = sells(f).get(0);
        Mockito.doReturn(sellReport(s1.getExchangeOrderId(), "cancel", "4.9", "1470"))
                .when(upbitOrderClient).getOrder(s1.getExchangeOrderId());
        engine.pollActiveOrders();
        closingReconcile(kind);
        closingReconcile(kind);
        System.out.println("[D5 " + kind + " 1차 부분 체결] " + snap(f));
        assertThat(position(f).getStatus()).isEqualTo("OPEN");
        assertThat(position(f).getSize()).isEqualByComparingTo("4.9");
        // 세션 잔액 열은 소수 2자리다 — 7,000 + 1,469.265 → 8,469.27
        assertThat(available(f)).as("1차 대금 1,470 − 수수료 0.735 (2자리 반올림)").isEqualByComparingTo("8469.27");
        assertThat(cleanupRepository.findById(f.positionId()).orElseThrow().getDoneAt()).isNull();

        cleanupWorker(kind);
        awaitDrained();
        cleanupWorker(kind);
        awaitDrained();
        List<OrderEntity> all = sells(f);
        assertThat(all).hasSize(2);
        OrderEntity s2 = all.get(0);
        assertThat(s2.getQuantity()).as("남은 수량만").isEqualByComparingTo("4.9");
        closingReconcile(kind);
        assertThat(available(f)).as("🔴 2차가 진행 중일 때 1차를 다시 반영하면 대금이 두 번 들어간다").isEqualByComparingTo("8469.27");
        assertThat(position(f).getStatus()).isEqualTo("CLOSING");

        Mockito.doReturn(sellReport(s2.getExchangeOrderId(), "done", "4.9", "1470"))
                .when(upbitOrderClient).getOrder(s2.getExchangeOrderId());
        engine.pollActiveOrders();
        closingReconcile(kind);
        cleanupWorker(kind);
        System.out.println("[D5 " + kind + " 청산 완료] " + snap(f));
        verifySellSubmits(2);
        assertThat(position(f).getStatus()).isEqualTo("CLOSED");
        assertThat(available(f)).as("8,469.27 + 1,469.265 (2자리 반올림) — 대금이 매도마다 한 번")
                .isBetween(new BigDecimal("9938.53"), new BigDecimal("9938.54"));
        assertThat(cleanupRepository.findById(f.positionId()).orElseThrow().getDoneAt()).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("D6 반영된 매도의 누적 체결량이 뒤늦게 늘어난 응답 — 조용히 건너뛰지 않고 불일치로 보존")
    void sellVolumeIncreaseAfterApplyIsPreserved(String kind) {
        Fx f = lateFilledInStoppedSession(kind);
        stubSellSubmissionsAccepted();
        cleanupWorker(kind);
        awaitDrained();
        OrderEntity s1 = sells(f).get(0);
        Mockito.doReturn(sellReport(s1.getExchangeOrderId(), "cancel", "4.9", "1470"))
                .when(upbitOrderClient).getOrder(s1.getExchangeOrderId());
        engine.pollActiveOrders();
        closingReconcile(kind);
        assertThat(orderRepository.findById(s1.getId()).orElseThrow().getSellAppliedAt()).isNotNull();
        String before = snap(f);

        // 같은 매도에 대해 체결량이 6.0 으로 늘어난 응답이 온다
        Mockito.doReturn(sellReport(s1.getExchangeOrderId(), "cancel", "6.0", "1800"))
                .when(upbitOrderClient).getOrder(s1.getExchangeOrderId());
        OrderEntity applied = orderRepository.findById(s1.getId()).orElseThrow();
        applied.setSettlementNextCheckAt(Instant.now().minusSeconds(1));
        orderRepository.save(applied);
        engine.checkOrderStatus(s1.getId());
        closingReconcile(kind);

        long found = discrepancyRepository.findByOrderIdOrderByDetectedAtDesc(s1.getId()).stream()
                .filter(d -> com.cryptoautotrader.api.entity.OrderSettlementDiscrepancyEntity.KIND_VOLUME_AFTER_APPLY
                        .equals(d.getKind())).count();
        System.out.println("[D6 " + kind + "] 불일치=" + found + " " + snap(f));
        assertThat(found).as("늘어난 체결량을 불일치로 보존").isGreaterThanOrEqualTo(1);
        assertThat(snap(f)).as("자동 반영하지 않는다 — 포지션·잔액 불변").isEqualTo(before);
    }
}
