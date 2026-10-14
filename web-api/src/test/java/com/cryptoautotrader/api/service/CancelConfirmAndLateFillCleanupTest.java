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
    @Autowired private com.cryptoautotrader.api.repository.TelegramNotificationLogRepository telegramLogRepository;
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
            // 🔴 큐가 비고 실행 중 0 인 순간은 작업이 큐에서 실행으로 넘어가는 사이에도 생긴다(D5 간헐 실패, 2026-10-14) —
            //    제출된 작업 수와 완료된 작업 수가 같아질 때까지 기다린다.
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0
                    && pool.getTaskCount() == pool.getCompletedTaskCount()) return;
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
        stubSellSubmissionsAccepted();   // 보유 포지션의 정지 청산 매도 — 목이 null 을 돌려주지 않게

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

        List<OrderEntity> sells = awaitSubmittedSells(f, 1);
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

        OrderEntity firstSell = awaitSubmittedSells(f, 1).get(0);
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

    /**
     * 제출하는 쪽(정리 작업 호출)이 끝난 **뒤에** 부른다 — 이 포지션의 매도가 {@code n} 건, 모두 거래소 주문번호를 받은
     * 상태가 될 때까지 기다린다(2026-10-14). 실행기 수치(제출 수 = 완료 수)만으로는 제출 직전의 0 = 0 을 완료로 볼 수 있다.
     */
    /** 이 포지션의 매도가 {@code n} 건이고 모두 {@code ok} 를 만족할 때까지 기다린다 — 제출하는 쪽이 끝난 뒤에 부른다. */
    private List<OrderEntity> awaitSells(Fx f, int n, java.util.function.Predicate<OrderEntity> ok) {
        long deadline = System.currentTimeMillis() + 20_000;
        List<OrderEntity> s = sells(f);
        while (System.currentTimeMillis() < deadline) {
            s = sells(f);
            if (s.size() == n && s.stream().allMatch(ok)) {
                awaitDrained();
                return sells(f);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("매도 " + n + "건이 기대 상태가 되지 않았다: "
                + s.stream().map(o -> o.getState() + "/" + o.getExchangeOrderId()).toList());
    }

    private List<OrderEntity> awaitSubmittedSells(Fx f, int n) {
        long deadline = System.currentTimeMillis() + 20_000;
        List<OrderEntity> s = sells(f);
        while (System.currentTimeMillis() < deadline) {
            s = sells(f);
            if (s.size() == n && s.stream().allMatch(o -> o.getExchangeOrderId() != null)) {
                awaitDrained();
                return sells(f);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("매도 " + n + "건이 접수 상태가 되지 않았다: "
                + s.stream().map(o -> o.getState() + "/" + o.getExchangeOrderId()).toList());
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
        awaitSells(f, 2, o -> "FAILED".equals(o.getState()) || o.getExchangeOrderId() != null);
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
        awaitSubmittedSells(f, 1);
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
        OrderEntity lost = awaitSells(f, 1, o -> "UNKNOWN".equals(o.getState())).get(0);
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
        // 식별자 해소는 자기 트랜잭션에서 커밋된 뒤 체결을 조회한다 — 한 회차에 반영된다(2026-10-13 수정 전에는 폴러
        // 트랜잭션에 묶여 다음 회차였다. 실제 PostgreSQL 에서는 그것이 10초 잠금 대기였다).
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
        OrderEntity s1 = awaitSubmittedSells(f, 1).get(0);
        assertThat(s1.getState()).isEqualTo("SUBMITTED");
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
        cleanupWorker(kind);
        List<OrderEntity> all = awaitSubmittedSells(f, 2);
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
        OrderEntity s1 = awaitSubmittedSells(f, 1).get(0);
        assertThat(s1.getState()).isEqualTo("SUBMITTED");
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

    // ── E. 정지·비상 정지가 진행 중 청산 매도를 끊지 않는다 (실제 재시작 시험 S6 에서 발견) ─────────

    private OrderEntity activeSellFor(Fx f) {
        PositionEntity p = position(f);
        p.setSize(FILL);
        p.setStatus("CLOSING");
        p.setClosingAt(Instant.now());
        positionRepository.save(p);
        return orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("SELL").orderType("MARKET").quantity(FILL).state("SUBMITTED")
                .sessionKind(f.kind()).sessionId(f.sessionId()).positionId(f.positionId())
                .clientOrderId("cat-sell-" + System.nanoTime()).exchangeOrderId("uuid-sell-e-" + System.nanoTime())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L).settlementFinal(Boolean.FALSE).settlementAttempts(0)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("E1 정지 — 매도 진행 중인 CLOSING 포지션을 정리 대기로 등록하고, 그 매도는 취소하지 않는다")
    void stopRegistersClosingAndKeepsSell(String kind) {
        Fx f = buy(kind, "RUNNING", "FILLED", true);
        OrderEntity sell = activeSellFor(f);

        stop(f);
        System.out.println("[E1 " + kind + "] " + snap(f) + " 정리대기=" + cleanupRepository.findById(f.positionId()).isPresent());
        Mockito.verify(upbitOrderClient, Mockito.never()).cancelOrder(sell.getExchangeOrderId());
        assertThat(orderRepository.findById(sell.getId()).orElseThrow().getState()).isEqualTo("SUBMITTED");
        assertThat(cleanupRepository.findById(f.positionId()))
                .as("매도가 실패·취소돼 OPEN 으로 돌아가면 정리 작업이 맡는다").isPresent();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("E2 전체 비상 정지의 일괄 취소 — 매수만 취소하고 진행 중 매도는 건드리지 않는다")
    void emergencyCancelAllKeepsSells(String kind) {
        Fx f = buy(kind, "STOPPED", "FILLED", true);
        OrderEntity sell = activeSellFor(f);
        Mockito.doReturn(exchange(sell.getExchangeOrderId(), "wait", BigDecimal.ZERO))
                .when(upbitOrderClient).getOrder(sell.getExchangeOrderId());

        engine.cancelAllActiveOrders();
        Mockito.verify(upbitOrderClient, Mockito.never()).cancelOrder(sell.getExchangeOrderId());
        assertThat(orderRepository.findById(sell.getId()).orElseThrow().getState()).isEqualTo("SUBMITTED");
        assertThat(orderRepository.findById(sell.getId()).orElseThrow().getCancelRequestedAt()).isNull();
    }

    // ── F. 같은 매도의 동시 확정 (실제 재시작 시험 S7 에서 발견) ─────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("F1 청산 확정 둘이 같은 체결 매도를 동시에 확정 — 대금·포지션 반영은 한 번")
    void concurrentSellFinalizeCreditsOnce(String kind) throws Exception {
        Fx f = buy(kind, "STOPPED", "FILLED", true);
        PositionEntity p = position(f);
        p.setSize(FILL);
        p.setStatus("CLOSING");
        p.setClosingAt(Instant.now());
        positionRepository.save(p);
        orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("SELL").orderType("MARKET").quantity(FILL).filledQuantity(FILL)
                .price(new BigDecimal("300")).state("FILLED").filledAt(Instant.now())
                .sessionKind(kind).sessionId(f.sessionId()).positionId(f.positionId())
                .clientOrderId("cat-sell-f-" + System.nanoTime()).exchangeOrderId("uuid-sell-f-" + System.nanoTime())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L).settlementFinal(Boolean.FALSE).settlementAttempts(0)
                .createdAt(Instant.now()).build());

        for (int round = 0; round < 1; round++) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
            Runnable r = () -> {
                try {
                    barrier.await();
                    closingReconcile(kind);
                } catch (Throwable t) {
                    errors.add(t);
                }
            };
            Thread a = new Thread(r);
            Thread b = new Thread(r);
            a.start();
            b.start();
            a.join();
            b.join();
            System.out.println("[F1 " + kind + "] 예외=" + errors.stream().map(t -> t.getClass().getSimpleName()).toList());
        }
        closingReconcile(kind);
        closingReconcile(kind);
        System.out.println("[F1 " + kind + "] " + snap(f));
        // 7,000 + (2,940 − 1.47) = 9,938.53
        assertThat(available(f)).as("🔴 대금 2,938.53 이 한 번 — 두 번이면 12,877.06").isEqualByComparingTo("9938.53");
        assertThat(position(f).getStatus()).isEqualTo("CLOSED");
    }

    // ── G. 매도 실측 수수료 (2026-10-14) ─────────────────────────────────────────────

    /** 정지 세션의 9.8 포지션(CLOSING)과 체결된 매도 하나 — 보고 대금 2,940, 보고 수수료 {@code fee}(null 이면 미보고). */
    private OrderEntity filledSell(Fx f, String fee) {
        PositionEntity p = position(f);
        p.setStatus("CLOSING");
        p.setClosingAt(Instant.now());
        positionRepository.save(p);
        return orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("SELL").orderType("MARKET").quantity(FILL).filledQuantity(FILL)
                .price(new BigDecimal("300")).state("FILLED").filledAt(Instant.now())
                .reportedExecutedFunds(new BigDecimal("2940")).reportedPaidFee(fee == null ? null : new BigDecimal(fee))
                .sessionKind(f.kind()).sessionId(f.sessionId()).positionId(f.positionId())
                .clientOrderId("cat-sell-g-" + System.nanoTime()).exchangeOrderId("uuid-sell-g-" + System.nanoTime())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L).settlementFinal(Boolean.FALSE).settlementAttempts(0)
                .createdAt(Instant.now()).build());
    }

    private void settleWith(OrderEntity sell, String fee) {
        OrderResponse r = sellReport(sell.getExchangeOrderId(), "done", "9.8", "2940");
        r.setPaidFee(fee == null ? null : new BigDecimal(fee));
        Mockito.doReturn(r).when(upbitOrderClient).getOrder(sell.getExchangeOrderId());
        OrderEntity o = orderRepository.findById(sell.getId()).orElseThrow();
        o.setSettlementNextCheckAt(Instant.now().minusSeconds(1));
        orderRepository.save(o);
        engine.checkOrderStatus(sell.getId());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("G1 매도 확정이 거래소 보고 수수료를 쓴다 — 1.20 이면 대금 2,938.80, 추정 0.05%(1.47)가 아니다")
    void sellUsesReportedFee(String kind) {
        Fx f = lateFilledInStoppedSession(kind);
        OrderEntity sell = filledSell(f, "1.20");
        closingReconcile(kind);
        OrderEntity after = orderRepository.findById(sell.getId()).orElseThrow();
        System.out.println("[G1 " + kind + "] " + snap(f) + " 반영수수료=" + after.getAppliedPaidFee() + " 출처=" + after.getFeeSource()
                + " 포지션수수료=" + position(f).getPositionFee() + " 실현손익=" + position(f).getRealizedPnl());
        assertThat(position(f).getStatus()).isEqualTo("CLOSED");
        assertThat(available(f)).as("7,000 + 2,940 − 1.20").isEqualByComparingTo("9938.80");
        assertThat(position(f).getRealizedPnl()).as("2,938.80 − 9.8 × 300").isEqualByComparingTo("-1.20");
        assertThat(after.getAppliedPaidFee()).isEqualByComparingTo("1.20");
        assertThat(after.getFeeSource()).isEqualTo(OrderExecutionEngine.FEE_SOURCE_REPORTED);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("G2 확정 때 수수료 미보고 — 추정(1.47)으로 확정 후, 보고(1.20)가 오면 차이 0.27 만 한 번 반영")
    void sellEstimatedThenReported(String kind) {
        Fx f = lateFilledInStoppedSession(kind);
        OrderEntity sell = filledSell(f, null);
        closingReconcile(kind);
        assertThat(available(f)).as("추정 수수료 1.47").isEqualByComparingTo("9938.53");
        assertThat(orderRepository.findById(sell.getId()).orElseThrow().getFeeSource())
                .isEqualTo(OrderExecutionEngine.FEE_SOURCE_ESTIMATED);

        settleWith(sell, "1.20");
        String first = snap(f) + " pnl=" + position(f).getRealizedPnl();
        System.out.println("[G2 " + kind + "] " + first);
        assertThat(available(f)).isEqualByComparingTo("9938.80");
        assertThat(position(f).getRealizedPnl()).isEqualByComparingTo("-1.20");
        for (int i = 0; i < 3; i++) {
            settleWith(sell, "1.20");
            closingReconcile(kind);
        }
        assertThat(snap(f) + " pnl=" + position(f).getRealizedPnl()).as("반복에서 바뀌면 중복 반영").isEqualTo(first);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("G3 보고 후 수수료 증가는 차이만 한 번, 감소는 자동 반영하지 않고 불일치로 보존")
    void sellFeeIncreaseAndDecreaseAfterReported(String kind) {
        Fx f = lateFilledInStoppedSession(kind);
        OrderEntity sell = filledSell(f, "1.20");
        closingReconcile(kind);
        settleWith(sell, "1.50");
        settleWith(sell, "1.50");
        assertThat(available(f)).as("증가 0.30 한 번").isEqualByComparingTo("9938.50");
        settleWith(sell, "1.40");
        System.out.println("[G3 " + kind + "] " + snap(f));
        assertThat(available(f)).as("감소는 자동 반영하지 않는다").isEqualByComparingTo("9938.50");
        assertThat(discrepancyRepository.findByOrderIdOrderByDetectedAtDesc(sell.getId()))
                .extracting(d -> d.getKind()).contains(com.cryptoautotrader.api.entity.OrderSettlementDiscrepancyEntity.KIND_FEE_DECREASE);
    }

    /**
     * G4 — 매도 <b>생성 응답이 이미 done</b>(보고 체결금액·수수료 포함)일 때도 보고 수수료로 확정한다 (2026-10-14).
     * 실제 프로세스 S12 에서 발견: 종전에는 생성 응답 즉시 처리가 보고값을 기록하지 않고 추정(1.47)으로 확정했고,
     * FILLED 는 다시 조회하지 않아 보고값(1.176)이 영영 반영되지 않았다(9,997.06).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("G4 매도 생성 응답이 이미 done 이어도 보고 수수료(1.176)로 한 번 확정 — 추정(1.47)으로 끝나지 않는다")
    void sellDoneOnCreateUsesReportedFee(String kind) throws Exception {
        Fx f = buy(kind, "RUNNING", "SUBMITTED", true);
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doReturn(exchange(f.uuid(), "wait", BigDecimal.ZERO)).when(upbitOrderClient).cancelOrder(f.uuid());
        String sellUuid = "uuid-sell-" + System.nanoTime();
        OrderResponse sellDone = new OrderResponse();
        sellDone.setUuid(sellUuid);
        sellDone.setSide("ask");
        sellDone.setOrdType("market");
        sellDone.setState("done");
        sellDone.setMarket("KRW-DOGE");
        sellDone.setVolume(FILL);
        sellDone.setExecutedVolume(FILL);
        sellDone.setExecutedFunds(new BigDecimal("2940.00000000"));
        sellDone.setPaidFee(new BigDecimal("1.17600000"));
        Mockito.doAnswer(inv -> new UpbitOrderClient.ExchangeResult(sellDone, "{}"))
                .when(upbitOrderClient).createOrder(Mockito.eq("KRW-DOGE"), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(sellDone).when(upbitOrderClient).getOrder(sellUuid);

        stop(f);
        Mockito.doReturn(exchange(f.uuid(), "done", FILL)).when(upbitOrderClient).getOrder(f.uuid());
        engine.pollActiveOrders();
        assertThat(available(f)).isEqualByComparingTo("7058.53");
        cleanupWorker(kind);
        awaitDrained();
        awaitSubmittedSells(f, 1);
        for (int i = 0; i < 3; i++) {
            engine.pollActiveOrders();
            closingReconcile(kind);
            cleanupWorker(kind);
        }
        awaitDrained();
        List<OrderEntity> sells = sells(f);
        OrderEntity sell = orderRepository.findById(sells.get(0).getId()).orElseThrow();
        System.out.println("[G4 " + kind + "] " + snap(f) + " 반영수수료=" + sell.getAppliedPaidFee() + " 출처=" + sell.getFeeSource());
        assertThat(sells).as("매도 한 번").hasSize(1);
        assertThat(position(f).getStatus()).isEqualTo("CLOSED");
        assertThat(sell.getFeeSource()).isEqualTo(OrderExecutionEngine.FEE_SOURCE_REPORTED);
        assertThat(sell.getAppliedPaidFee()).isEqualByComparingTo("1.176");
        assertThat(available(f)).as("7,058.53 + 2,940 − 1.176 (추정이면 9,997.06)")
                .isCloseTo(new BigDecimal("9997.354"), org.assertj.core.data.Offset.offset(new BigDecimal("0.005")));
    }

    // ── H. 지정가 차단 · I. 거래소 거절 분류 (2026-10-14) ──────────────────────────────

    private com.cryptoautotrader.api.dto.OrderRequest buyRequest(String kind, Long sid, Long posId, String type) {
        com.cryptoautotrader.api.dto.OrderRequest req = new com.cryptoautotrader.api.dto.OrderRequest();
        req.setCoinPair("KRW-DOGE");
        req.setSide("BUY");
        req.setOrderType(type);
        req.setQuantity(new BigDecimal("3000"));
        req.setPrice("LIMIT".equals(type) ? new BigDecimal("300") : null);
        req.setSignalPrice(new BigDecimal("300"));
        req.setReason("거절 분류 시험");
        req.setSessionId(sid);
        req.setSessionKind(kind);
        req.setPositionId(posId);
        return req;
    }

    /** 평가 루프와 같은 경계 — 한 트랜잭션에서 슬롯·예약·의도를 쓰고, 커밋 후 실행기가 claim·제출한다. */
    private Long submitBuy(String kind, Long sid, String type) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager).execute(st -> {
            PositionEntity pos = positionRepository.save(PositionEntity.builder()
                    .coinPair("KRW-DOGE").side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                    .size(BigDecimal.ZERO).investedKrw(new BigDecimal("3000")).status("OPEN")
                    .sessionKind(kind).sessionId(sid).openedAt(Instant.now()).build());
            com.cryptoautotrader.api.dto.OrderRequest req = buyRequest(kind, sid, pos.getId(), type);
            Long orderId = engine.reserveAndPrepareIntent(req);
            engine.submitPreparedOrderAfterCommit(orderId, req);
            return orderId;
        });
    }

    private BigDecimal sessionAvailable(String kind, Long sid) {
        return "DYNAMIC".equals(kind) ? dynamicRepo.findById(sid).orElseThrow().getAvailableKrw()
                : liveRepo.findById(sid).orElseThrow().getAvailableKrw();
    }

    private static RuntimeException upbitError(int status, String name) {
        String body = name == null ? "Internal Server Error"
                : "{\"error\":{\"name\":\"" + name + "\",\"message\":\"" + name + "\"}}";
        // 실제 클라이언트처럼 감싼다 — 엔진은 원인 사슬에서 찾는다
        return new RuntimeException("주문 생성 실패",
                new com.cryptoautotrader.exchange.upbit.UpbitApiException("주문 생성", status, body));
    }

    private OrderEntity awaitOrderState(Long orderId, java.util.function.Predicate<OrderEntity> ok) {
        long deadline = System.currentTimeMillis() + 20_000;
        OrderEntity o = orderRepository.findById(orderId).orElseThrow();
        while (System.currentTimeMillis() < deadline && !ok.test(o)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            o = orderRepository.findById(orderId).orElseThrow();
        }
        awaitDrained();
        return orderRepository.findById(orderId).orElseThrow();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("H1 실자금 세션의 지정가 매수 — 예약 전에 거절, 주문 행·차감·거래소 호출 없음")
    void limitBuyRejected(String kind) {
        Long sid = session(kind, "RUNNING");
        BigDecimal before = sessionAvailable(kind, sid);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> submitBuy(kind, sid, "LIMIT"))
                .isInstanceOf(OrderExecutionEngine.ReservationRejected.class)
                .satisfies(t -> assertThat(((OrderExecutionEngine.ReservationRejected) t).getCode())
                        .isEqualTo(OrderExecutionEngine.ORDER_TYPE_UNSUPPORTED));
        assertThat(sessionAvailable(kind, sid)).isEqualByComparingTo(before);
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, sid)).isEmpty();
        Mockito.verify(upbitOrderClient, Mockito.never()).createOrder(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("H2 실자금 세션의 지정가 매도 — 의도 전에 거절, 주문 행·거래소 호출 없음")
    void limitSellRejected(String kind) {
        Long sid = session(kind, "RUNNING");
        com.cryptoautotrader.api.dto.OrderRequest req = buyRequest(kind, sid, null, "LIMIT");
        req.setSide("SELL");
        req.setQuantity(FILL);
        engine.submitOrder(req);
        awaitDrained();
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, sid)).isEmpty();
        Mockito.verify(upbitOrderClient, Mockito.never()).createOrder(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("I1 명시적 거절(잔고 부족) + 식별자 조회 404 — FAILED/EXCHANGE_REJECTED, 예약 한 번 복원")
    void explicitRejectionConfirmedAbsent(String kind) {
        Long sid = session(kind, "RUNNING");
        Mockito.doThrow(upbitError(400, "insufficient_funds_bid")).when(upbitOrderClient)
                .createOrder(Mockito.eq("KRW-DOGE"), Mockito.eq("bid"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(null).when(upbitOrderClient).getOrderByIdentifier(Mockito.anyString());

        Long orderId = submitBuy(kind, sid, "MARKET");
        OrderEntity o = awaitOrderState(orderId, x -> !"SUBMITTING".equals(x.getState()) && !"INTENT_READY".equals(x.getState()));
        System.out.println("[I1 " + kind + "] state=" + o.getState() + " code=" + o.getFailureCode() + " reason=" + o.getFailedReason());
        assertThat(o.getState()).isEqualTo("FAILED");
        assertThat(o.getFailureCode()).isEqualTo(OrderExecutionEngine.EXCHANGE_REJECTED);
        assertThat(o.getFailedReason()).contains("insufficient_funds_bid").contains("주문 없음 확인");
        assertThat(sessionAvailable(kind, sid)).as("예약 3,000 차감 상태").isEqualByComparingTo("4000");
        orphanReconcile(kind);
        orphanReconcile(kind);
        assertThat(sessionAvailable(kind, sid)).as("거절 확인 — 예약 한 번 복원").isEqualByComparingTo("7000");
        // 🔴 없음 확인은 같은 거래소 클라이언트(같은 계좌 키)로, 이 주문의 식별자로 했다
        Mockito.verify(upbitOrderClient, Mockito.atLeastOnce()).getOrderByIdentifier(o.getClientOrderId());
        assertThat(o.getClientOrderId()).isNotBlank();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("I2 명시적 거절 응답인데 식별자로 주문이 조회된다 — 거절로 닫지 않고 주문번호를 붙여 추적")
    void explicitRejectionButOrderExists(String kind) {
        Long sid = session(kind, "RUNNING");
        Mockito.doThrow(upbitError(400, "insufficient_funds_bid")).when(upbitOrderClient)
                .createOrder(Mockito.eq("KRW-DOGE"), Mockito.eq("bid"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        String uuid = "uuid-exists-" + System.nanoTime();
        Mockito.doReturn(exchange(uuid, "wait", BigDecimal.ZERO)).when(upbitOrderClient).getOrderByIdentifier(Mockito.anyString());

        Long orderId = submitBuy(kind, sid, "MARKET");
        OrderEntity o = awaitOrderState(orderId, x -> "SUBMITTED".equals(x.getState()) || "FAILED".equals(x.getState())
                || "UNKNOWN".equals(x.getState()));
        System.out.println("[I2 " + kind + "] state=" + o.getState() + " uuid=" + o.getExchangeOrderId());
        assertThat(o.getState()).isEqualTo("SUBMITTED");
        assertThat(o.getExchangeOrderId()).isEqualTo(uuid);
        assertThat(o.getFailureCode()).isNull();
        orphanReconcile(kind);
        assertThat(sessionAvailable(kind, sid)).as("주문이 있으므로 예약 유지").isEqualByComparingTo("4000");
    }

    @ParameterizedTest(name = "{0} HTTP {1} {2}")
    @CsvSource({"LIVE,400,create_bid_error", "LIVE,500,", "LIVE,429,", "LIVE,400,",
            "DYNAMIC,400,create_bid_error", "DYNAMIC,500,", "DYNAMIC,429,", "DYNAMIC,400,"})
    @DisplayName("I3 거절로 확정할 수 없는 응답(생성 중 오류·이름 없음·5xx·429) — UNKNOWN, 예약 유지")
    void ambiguousErrorsStayUnknown(String kind, int status, String name) {
        Long sid = session(kind, "RUNNING");
        Mockito.doThrow(upbitError(status, name)).when(upbitOrderClient)
                .createOrder(Mockito.eq("KRW-DOGE"), Mockito.eq("bid"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(null).when(upbitOrderClient).getOrderByIdentifier(Mockito.anyString());

        Long orderId = submitBuy(kind, sid, "MARKET");
        OrderEntity o = awaitOrderState(orderId, x -> "UNKNOWN".equals(x.getState()) || "FAILED".equals(x.getState()));
        assertThat(o.getState()).isEqualTo("UNKNOWN");
        assertThat(o.getFailureCode()).isNull();
        orphanReconcile(kind);
        assertThat(sessionAvailable(kind, sid)).isEqualByComparingTo("4000");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("I4 명시적 거절인데 식별자 조회가 실패 — 거절로 확정하지 않고 UNKNOWN")
    void explicitRejectionLookupFails(String kind) {
        Long sid = session(kind, "RUNNING");
        Mockito.doThrow(upbitError(400, "insufficient_funds_bid")).when(upbitOrderClient)
                .createOrder(Mockito.eq("KRW-DOGE"), Mockito.eq("bid"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doThrow(new RuntimeException("timeout")).when(upbitOrderClient).getOrderByIdentifier(Mockito.anyString());

        Long orderId = submitBuy(kind, sid, "MARKET");
        OrderEntity o = awaitOrderState(orderId, x -> "UNKNOWN".equals(x.getState()) || "FAILED".equals(x.getState()));
        assertThat(o.getState()).isEqualTo("UNKNOWN");
        assertThat(sessionAvailable(kind, sid)).isEqualByComparingTo("4000");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("I5 정리 매도가 거래소에서 거절을 반복 — 거절 2회에서 멈추고 사람 확인으로 둔다")
    void repeatedSellRejectionStops(String kind) {
        Fx f = lateFilledInStoppedSession(kind);
        Mockito.doThrow(upbitError(400, "insufficient_funds_ask")).when(upbitOrderClient)
                .createOrder(Mockito.eq("KRW-DOGE"), Mockito.eq("ask"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(null).when(upbitOrderClient).getOrderByIdentifier(Mockito.anyString());

        for (int i = 0; i < 5; i++) {
            cleanupWorker(kind);
            if (i < 2) {
                awaitSells(f, i + 1, o -> "FAILED".equals(o.getState()));
            } else {
                awaitDrained();
            }
            closingReconcile(kind);
        }
        PositionCleanupEntity c = cleanupRepository.findById(f.positionId()).orElseThrow();
        System.out.println("[I5 " + kind + "] 매도=" + sells(f).stream().map(o -> o.getState() + "/" + o.getFailureCode()).toList()
                + " 메모=" + c.getLastNote());
        assertThat(sells(f)).hasSize(2).allMatch(o -> OrderExecutionEngine.EXCHANGE_REJECTED.equals(o.getFailureCode()));
        assertThat(c.getLastNote()).contains("거절");
        // 🔴 멈춘 뒤에도 잔여 포지션·정리 대기·경보가 유지된다
        assertThat(position(f).getStatus()).as("잔여 포지션은 열린 채").isEqualTo("OPEN");
        assertThat(position(f).getSize()).isEqualByComparingTo("9.8");
        assertThat(c.getDoneAt()).as("정리 대기는 완료가 아니다").isNull();
        String label = kind + "#" + f.sessionId();
        long alerts = 0;
        long deadline = System.currentTimeMillis() + 60_000;   // 텔레그램 실행기(1 스레드)에 다른 재시도 알림이 쌓여 있을 수 있다
        while (System.currentTimeMillis() < deadline) {
            alerts = telegramLogRepository.findAll().stream()
                    .filter(l -> "CLEANUP_NEEDS_HUMAN".equals(l.getType()) && label.equals(l.getSessionLabel())).count();
            if (alerts > 0) break;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        cleanupWorker(kind);
        cleanupWorker(kind);
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        alerts = telegramLogRepository.findAll().stream()
                .filter(l -> "CLEANUP_NEEDS_HUMAN".equals(l.getType()) && label.equals(l.getSessionLabel())).count();
        assertThat(alerts).as("경보는 상태에 들어갈 때 한 번 — 이후 회차에 반복하지 않는다").isEqualTo(1);
        assertThat(cleanupRepository.findById(f.positionId()).orElseThrow().getDoneAt()).isNull();
    }
}
