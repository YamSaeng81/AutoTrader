package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V84 보완 검증 — <b>선커밋 증명 · claim·철회 경합 · 종단 간 정확히 한 번</b> (2026-10-12).
 *
 * <p>앞선 시험들은 세 가지를 <b>간접 근거로</b> 주장했다. 지적대로 그것은 증명이 아니다:</p>
 *
 * <table>
 *   <tr><th>간접 근거</th><th>왜 부족한가</th><th>이 시험이 하는 일</th></tr>
 *   <tr><td>거래소가 식별자를 받았다</td>
 *       <td>🔴 <b>식별자 전달 ≠ 선커밋.</b> 같은 트랜잭션 안에서 만든 값을 넘겨도 인자는 전달된다 —
 *           커밋됐는지는 말하지 않는다</td>
 *       <td>거래소 호출 <b>대역 안에서 별도 연결</b>로 조회해, 그 시점에 이미 커밋돼 있음을 본다</td></tr>
 *   <tr><td>철회에 유예 60초를 뒀다</td>
 *       <td>🔴 <b>시간은 경합을 해결하지 않는다.</b> 작업이 유예보다 오래 지연될 수 있다</td>
 *       <td>claim 과 철회를 <b>같은 조건부 전환</b>으로 다투게 해 승자 하나만 처리함을 본다</td></tr>
 *   <tr><td>기존 정산 경로에 넘긴다</td>
 *       <td>🔴 <b>연결 ≠ 정확히 한 번.</b> 넘긴 뒤 무엇이 반영되는지 확인한 적이 없다</td>
 *       <td>응답 유실 → 식별자 조회 → 체결 확인 → 주문·잔액·포지션 반영 → 재조회를 <b>이어서</b> 돌린다</td></tr>
 * </table>
 */
@DisplayName("V84 보완 — 선커밋 증명·claim·철회 경합·종단 간 정확히 한 번")
class IntentPrecommitAndOnceOnlyTest extends IntegrationTestBase {

    @Autowired
    private OrderExecutionEngine orderExecutionEngine;

    @Autowired
    private LiveTradingService liveTradingService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    @Qualifier("orderExecutor")
    private ThreadPoolTaskExecutor orderExecutor;

    @MockBean
    private UpbitOrderClient upbitOrderClient;

    @MockBean
    private UpbitRestClient upbitRestClient;

    private static final List<String> ALL_STATES = List.of(
            "PENDING", "INTENT_READY", "SUBMITTING", "UNKNOWN", "SUBMITTED",
            "PARTIAL_FILLED", "FILLED", "CANCELLED", "FAILED");

    private final List<Long> createdSessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id)
                    .forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
    }

    private void awaitDrained() {
        var pool = orderExecutor.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 20_000;
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

    private record Fixture(Long sessionId, Long positionId, OrderRequest request) { }

    private Fixture runningSessionWithBuy() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                // 요청액 3,000 은 이미 차감된 상태다 (LiveTradingService 가 제출 전에 차감한다).
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
        req.setReason("전략 신호 — V84 보완 검증");
        req.setSignalPrice(new BigDecimal("300.00000000"));
        req.setSessionId(session.getId());
        req.setSessionKind("LIVE");
        req.setPositionId(pos.getId());
        return new Fixture(session.getId(), pos.getId(), req);
    }

    private OrderEntity theOrder(Fixture f) {
        List<OrderEntity> rows = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", f.sessionId()).stream().filter(o__ -> (ALL_STATES).contains(o__.getState())).toList();
        assertThat(rows).as("주문은 정확히 한 행이어야 한다").hasSize(1);
        return rows.get(0);
    }

    // ── ① 선커밋 증명 ───────────────────────────────────────────

    /**
     * 🔴 <b>별도 연결에서 보이면 커밋된 것이다.</b>
     *
     * <p>거래소 호출 대역 안에서 <b>다른 스레드 · 다른 트랜잭션</b>으로 주문을 조회한다. 제출
     * 스레드의 트랜잭션에 참여하지 않으므로, 거기서 보이는 데이터는 <b>커밋된 데이터뿐</b>이다 —
     * {@code REQUIRES_NEW} 를 같은 스레드에서 쓰면 커넥션은 달라도 같은 스레드의 동기 호출이라
     * 혼동을 부르므로, 스레드를 아예 분리한다.</p>
     */
    @Test
    @DisplayName("① 외부 호출 시점에 의도·식별자·예약이 이미 커밋돼 있다 (별도 연결 조회)")
    void intentIsCommittedBeforeTheExchangeCall() throws Exception {
        Fixture f = runningSessionWithBuy();
        AtomicReference<String> seenFromOtherConnection = new AtomicReference<>("(조회 전)");
        AtomicReference<String> identifierPassed = new AtomicReference<>();

        Mockito.doAnswer(inv -> {
            identifierPassed.set(inv.getArgument(5));
            // 🔴 **다른 스레드 · 다른 트랜잭션**에서 조회한다.
            Thread probe = new Thread(() -> {
                TransactionTemplate fresh = new TransactionTemplate(txManager);
                fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                seenFromOtherConnection.set(fresh.execute(st -> {
                    List<OrderEntity> rows = orderRepository
                            .findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", f.sessionId()).stream().filter(o__ -> (ALL_STATES).contains(o__.getState())).toList();
                    if (rows.size() != 1) {
                        return "행 수=" + rows.size();
                    }
                    OrderEntity o = rows.get(0);
                    return String.format("state=%s 식별자=%s 의도커밋=%s claim=%s 포지션=%s",
                            o.getState(), o.getClientOrderId(),
                            o.getIntentCommittedAt() != null, o.getClaimedAt() != null,
                            o.getPositionId());
                }));
            });
            probe.start();
            probe.join(10_000);
            throw new RuntimeException(new java.net.http.HttpTimeoutException("timed out"));
        }).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());

        orderExecutionEngine.submitOrder(f.request());
        awaitDrained();

        OrderEntity finalOrder = theOrder(f);
        String seen = seenFromOtherConnection.get();
        System.out.println("[선커밋 증명] 별도 연결에서 본 상태: " + seen);

        assertThat(seen)
                .as("🔴 **별도 연결에서 보인다 = 커밋됐다.** 식별자 전달만으로는 증명되지 않는 사실이다")
                .contains("state=SUBMITTING")
                .contains("식별자=" + finalOrder.getClientOrderId())
                .contains("의도커밋=true")
                .contains("claim=true")
                .contains("포지션=" + f.positionId());
        assertThat(identifierPassed.get())
                .as("거래소에 넘긴 식별자가 커밋된 값과 같아야 한다 — 다르면 추적이 불가능하다")
                .isEqualTo(finalOrder.getClientOrderId());

        // 예약도 그 시점에 이미 반영돼 있다 — 자금은 제출 전에 차감되고, 슬롯은 포지션이다.
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("자금 예약(차감)이 유지된다")
                .isEqualByComparingTo(new BigDecimal("7000.00"));
    }

    // ── ② claim·철회 경합 ──────────────────────────────────────

    @Test
    @DisplayName("② claim 과 철회는 같은 조건부 전환을 다투고 승자 하나만 처리한다")
    void claimAndWithdrawRaceHasExactlyOneWinner() throws Exception {
        Fixture f = runningSessionWithBuy();
        OrderEntity intent = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000.00000000"))
                .state("INTENT_READY")
                .clientOrderId("cat-race-02")
                // 🔴 **오래된 의도다.** "작업이 60초 이상 지연될 수 있다" 는 지적이 바로 이 경우다 —
                //    정상 작업자가 아직 claim 하려는 중인데 철회가 들어온다. 이 시험은 **경합**만 본다
                //    (승자 하나). 철회가 **정당한지**는 V86 의 소유자 판정이 맡는다(시험 21-b).
                .intentCommittedAt(Instant.now().minusSeconds(600))
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L)
                .sessionKind("LIVE").sessionId(f.sessionId()).positionId(f.positionId())
                .createdAt(Instant.now().minusSeconds(600)).build());

        CountDownLatch go = new CountDownLatch(1);
        List<String> winners = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        Runnable claimer = () -> {
            try {
                go.await(5, TimeUnit.SECONDS);
                TransactionTemplate tx = new TransactionTemplate(txManager);
                int won = tx.execute(st ->
                        orderRepository.claimForSubmission(intent.getId(), Instant.now()));
                if (won == 1) winners.add("claim");
            } catch (Throwable e) {
                errors.add(e);
            }
        };
        Runnable withdrawer = () -> {
            try {
                go.await(5, TimeUnit.SECONDS);
                TransactionTemplate tx = new TransactionTemplate(txManager);
                int won = tx.execute(st -> orderRepository.withdrawIntentIfStillReady(
                        intent.getId(), "소유 프로세스 소실 — 제출하지 않았다",
                        OrderExecutionEngine.WITHDRAWN_OWNER_LOST));
                if (won == 1) winners.add("withdraw");
            } catch (Throwable e) {
                errors.add(e);
            }
        };

        Thread t1 = new Thread(claimer);
        Thread t2 = new Thread(withdrawer);
        t1.start();
        t2.start();
        go.countDown();
        t1.join(20_000);
        t2.join(20_000);

        System.out.println("[claim·철회 경합] 승자=" + winners + " 오류=" + errors);
        assertThat(errors).as("경합 경로에서 예외가 나지 않아야 한다").isEmpty();
        assertThat(winners)
                .as("🔴 **승자는 정확히 하나다.** 둘 다 성공하면 제출은 나갔는데 주문은 철회된 "
                        + "상태가 된다 — 시간이 아니라 **조건부 전환**이 이것을 막는다")
                .hasSize(1);

        OrderEntity after = orderRepository.findById(intent.getId()).orElseThrow();
        if (winners.contains("claim")) {
            assertThat(after.getState()).isEqualTo("SUBMITTING");
            assertThat(after.getClaimedAt()).isNotNull();
        } else {
            assertThat(after.getState()).isEqualTo("FAILED");
            assertThat(after.getClaimedAt())
                    .as("🔴 철회가 이겼으면 claim 은 성립한 적이 없다 — 나간 적이 없다는 뜻이다")
                    .isNull();
        }
    }

    @Test
    @DisplayName("② -b 철회가 이기면 예약이 해제되고 claim 측은 밀렸음을 알게 된다")
    void withdrawWinnerReleasesReservation() {
        Fixture f = runningSessionWithBuy();
        OrderEntity intent = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000.00000000"))
                .state("INTENT_READY")
                .clientOrderId("cat-race-02b")
                .intentCommittedAt(Instant.now().minusSeconds(600))
                // 🔴 V86: 철회의 근거는 나이가 아니라 **소유자 소실**이다 — 이전 기동이 만든 의도다.
                .intentOwner("inst-previous-boot")
                .reservedKrw(new BigDecimal("3000.00000000"))
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L)
                .sessionKind("LIVE").sessionId(f.sessionId()).positionId(f.positionId())
                .createdAt(Instant.now().minusSeconds(600)).build());

        // 철회가 먼저 성립한다.
        boolean resolved = orderExecutionEngine.resolveUnresolvedSubmission(intent.getId());
        assertThat(resolved).as("철회로 해소됐다").isTrue();
        assertThat(orderRepository.findById(intent.getId()).orElseThrow().getState())
                .isEqualTo("FAILED");

        // 🔴 claim 측은 **0행을 받아** 제출하지 않는다 — 결과가 호출자에게 전달되는 경로다.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        int lateClaim = tx.execute(st -> orderRepository.claimForSubmission(intent.getId(), Instant.now()));
        assertThat(lateClaim)
                .as("🔴 철회 뒤의 claim 은 0행이다 — 그래서 `submitOrder` 가 외부 호출로 가지 않는다")
                .isZero();

        // 🔴 예약 해제: FAILED 매수 + size=0 포지션은 **고아 매수 정리**가 닫고 KRW 를 복원한다.
        //    (전환에 성공한 쪽만 복원하므로 이중 복원이 없다 — 2026-10-07 에 그 패턴으로 맞췄다.)
        assertThat(orderRepository.countUnresolvedSubmissions("LIVE", f.sessionId()))
                .as("🔴 철회된 의도는 미해결 제출이 아니다 — 세션이 정리 완료로 갈 수 있다")
                .isZero();
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getSize())
                .as("체결이 없었으므로 슬롯 수량은 0 그대로다")
                .isEqualByComparingTo(BigDecimal.ZERO);

        OrderEntity withdrawn = orderRepository.findById(intent.getId()).orElseThrow();
        assertThat(withdrawn.getFailureCode())
                .as("🔴 V86: 철회의 근거가 남는다 — 거래소 거절(EXCHANGE_REJECTED)과 구분된다")
                .isEqualTo(OrderExecutionEngine.WITHDRAWN_OWNER_LOST);

        // 🔴 **예약 해제를 실제로 돌린다** — 종전 시험은 미해결 수만 봤다.
        liveTradingService.reconcileOrphanBuyPositions();
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("🔴 철회된 매수의 예약액(reserved_krw 3,000)만 정확히 돌아온다: 7,000 → 10,000")
                .isEqualByComparingTo("10000.00");
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getStatus())
                .as("슬롯도 해제된다")
                .isEqualTo("CLOSED");
        // 두 번 돌려도 두 번 복원하지 않는다.
        liveTradingService.reconcileOrphanBuyPositions();
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .isEqualByComparingTo("10000.00");
    }

    // ── ③ 종단 간 : 정확히 한 번 ────────────────────────────────

    @Test
    @DisplayName("③ 응답 유실 → 식별자 조회 → 체결 확인 → 반영 → 재조회에서 중복 반영이 없다")
    void endToEndExactlyOnce() {
        Fixture f = runningSessionWithBuy();

        // 1) 접수 후 응답 유실.
        Mockito.doAnswer(inv -> {
            throw new RuntimeException(new java.net.http.HttpTimeoutException("timed out"));
        }).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());

        orderExecutionEngine.submitOrder(f.request());
        awaitDrained();

        OrderEntity unknown = theOrder(f);
        assertThat(unknown.getState()).isEqualTo("UNKNOWN");
        String identifier = unknown.getClientOrderId();

        // 2) 식별자로 조회하니 **이미 체결돼 있었다.**
        //    요청 3,000 중 2,940 사용, 9.8 개, 수수료 1.47 → 평균단가 300.
        OrderResponse filled = new OrderResponse();
        filled.setUuid("uuid-e2e");
        filled.setSide("bid");
        filled.setOrdType("price");
        filled.setState("done");
        filled.setMarket("KRW-DOGE");
        filled.setVolume(new BigDecimal("3000.00000000"));
        filled.setExecutedVolume(new BigDecimal("9.80000000"));
        filled.setExecutedFunds(new BigDecimal("2940.00000000"));
        filled.setPaidFee(new BigDecimal("1.47000000"));
        Mockito.doReturn(filled).when(upbitOrderClient).getOrderByIdentifier(identifier);
        Mockito.doReturn(filled).when(upbitOrderClient).getOrder("uuid-e2e");

        // 3) 복구가 조회해 회계 경로로 연결한다.
        orderExecutionEngine.resolveUnresolvedSubmission(unknown.getId());

        OrderEntity afterFirst = theOrder(f);
        BigDecimal krwAfterFirst = sessionRepository.findById(f.sessionId())
                .orElseThrow().getAvailableKrw();
        PositionEntity posAfterFirst = positionRepository.findById(f.positionId()).orElseThrow();

        assertThat(afterFirst.getState())
                .as("체결이 확인됐으므로 종결 상태가 된다")
                .isEqualTo("FILLED");
        assertThat(afterFirst.getExchangeOrderId())
                .as("식별자 조회로 UUID 를 확보했다")
                .isEqualTo("uuid-e2e");
        assertThat(afterFirst.getFillAccountingPending())
                .as("🔴 회계를 반영했으면 대기 표시가 해제된다 — 남으면 신규 매수가 영구히 막힌다")
                .isFalse();
        assertThat(afterFirst.getAppliedExecutedFunds())
                .as("주문 반영값은 실제 체결금액이다")
                .isEqualByComparingTo(new BigDecimal("2940.00000000"));
        assertThat(afterFirst.getAppliedPaidFee())
                .as("수수료는 거래소 보고값이다")
                .isEqualByComparingTo(new BigDecimal("1.47000000"));

        assertThat(posAfterFirst.getSize())
                .as("포지션 수량이 반영된다")
                .isEqualByComparingTo(new BigDecimal("9.80000000"));
        assertThat(posAfterFirst.getAvgPrice())
                .as("평균단가는 실제 사용액 기준 300 이다 (2940 / 9.8)")
                .isEqualByComparingTo(new BigDecimal("300.00000000"));
        assertThat(posAfterFirst.getPositionFee())
                .as("포지션 수수료도 보고값 1.47 이다")
                .isEqualByComparingTo(new BigDecimal("1.47000000"));

        assertThat(krwAfterFirst)
                .as("미사용액 58.53 이 복원된다 (7000 + 3000 − 2940 − 1.47)")
                .isEqualByComparingTo(new BigDecimal("7058.53"));

        assertThat(orderRepository.countUnresolvedSubmissions("LIVE", f.sessionId()))
                .as("불명이 해소됐으므로 미해결 제출이 0 이다")
                .isZero();

        // 4) 🔴 재조회 — 같은 응답이 여러 번 와도 **한 번만** 반영돼야 한다.
        orderExecutionEngine.checkOrderStatus(afterFirst.getId());
        orderExecutionEngine.checkOrderStatus(afterFirst.getId());
        orderExecutionEngine.resolveUnresolvedSubmission(afterFirst.getId());

        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("🔴 잔액이 또 움직이면 쓰이지 않은 돈이 생긴다")
                .isEqualByComparingTo(krwAfterFirst);
        PositionEntity posAfterRepeat = positionRepository.findById(f.positionId()).orElseThrow();
        assertThat(posAfterRepeat.getSize())
                .as("🔴 수량이 또 더해지면 장부에 없는 코인을 보유한 것으로 기록된다")
                .isEqualByComparingTo(new BigDecimal("9.80000000"));
        assertThat(posAfterRepeat.getPositionFee())
                .as("수수료도 한 번만 반영된다")
                .isEqualByComparingTo(new BigDecimal("1.47000000"));
        assertThat(theOrder(f).getAppliedExecutedFunds())
                .as("주문 반영값도 누적되지 않는다")
                .isEqualByComparingTo(new BigDecimal("2940.00000000"));

        // 🔴 재제출 0회 — 복구는 조회만 한다.
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());
    }
}
