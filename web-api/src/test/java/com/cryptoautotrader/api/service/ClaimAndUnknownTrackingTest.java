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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * claim 규칙과 응답 불명 추적 — 시험 1·2·3·20·21 (2026-10-12, V84).
 *
 * <h3>고치는 문제</h3>
 *
 * <p>체결 응답을 받았을 때의 회계는 V81~V83 으로 맞췄다. 그런데 <b>응답을 잃으면</b> 주문은 여전히
 * {@code FAILED} + {@code exchange_order_id = NULL} 로 떨어졌다. 그 상태에는 두 문제가 있다:</p>
 *
 * <ol>
 *   <li>🔴 <b>추적 단서가 없다.</b> UUID 는 응답에만 오므로, 응답이 유실되면 그 주문을 거래소에
 *       물어볼 방법이 사라진다 — 접수됐는지 아닌지 <b>확인할 수가 없다.</b></li>
 *   <li>🔴 <b>그 상태에서 자금·슬롯이 해제된다.</b> 복구가 {@code FAILED} 매수를 "거래소가 거절했다"
 *       로 읽고 포지션을 닫고 KRW 를 복원한다. 실제로 체결됐다면 <b>쓰인 돈을 되돌리는 것</b>이고,
 *       장부에 없는 코인을 거래소가 들고 있게 된다.</li>
 * </ol>
 *
 * <h3>이 시험이 고정하는 것</h3>
 *
 * <table>
 *   <tr><th>#</th><th>내용</th></tr>
 *   <tr><td>1</td><td>의도·식별자·예약이 <b>외부 호출 전에 커밋</b>되는가</td></tr>
 *   <tr><td>2</td><td>응답 유실 시 <b>불명</b>으로 남고 <b>자금·슬롯이 해제되지 않는가</b></td></tr>
 *   <tr><td>3</td><td>조회 실패가 반복돼도 <b>새 식별자로 재주문하지 않는가</b></td></tr>
 *   <tr><td>20</td><td>{@code SUBMITTING} 복구 작업자가 <b>재제출하지 않고 조회만</b> 하는가</td></tr>
 *   <tr><td>21</td><td>claim 전환이 1행인데 <b>커밋이 실패</b>하면 <b>제출 0회</b>인가</td></tr>
 * </table>
 */
@DisplayName("claim 규칙과 응답 불명 추적 (V84)")
class ClaimAndUnknownTrackingTest extends IntegrationTestBase {

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
    @Qualifier("orderExecutor")
    private ThreadPoolTaskExecutor orderExecutor;

    @MockBean
    private UpbitOrderClient upbitOrderClient;

    @MockBean
    private UpbitRestClient upbitRestClient;

    /**
     * 🔴 리포지토리의 {@code @Modifying(flushAutomatically = true)} 질의는 <b>활성 트랜잭션을
     * 요구한다.</b> 시험에서 직접 부를 때 경계를 주지 않으면 {@code TransactionRequiredException}
     * 이 난다 — 이 클래스에 {@code @Transactional} 을 붙이지 않는 이유는 {@code submitOrder} 가
     * {@code @Async} 로 <b>다른 스레드</b>에서 돌아 그 트랜잭션에 참여하지 않기 때문이다.
     */
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(st -> work.get());
    }

    private final List<Long> createdSessions = new ArrayList<>();

    /** 거래소가 접수한 주문 — 응답이 오지 않아도 거래소에는 남는다. */
    private final List<String> accepted = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id)
                    .forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
        accepted.clear();
    }

    private static final List<String> ALL_STATES = List.of(
            "PENDING", "INTENT_READY", "SUBMITTING", "UNKNOWN", "SUBMITTED",
            "PARTIAL_FILLED", "FILLED", "CANCELLED", "FAILED");

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
        throw new AssertionError("orderExecutor 가 비워지지 않았다 — 작업 스레드가 반환되지 않았다");
    }

    /**
     * 이 시험 세션의 주문이 {@code UNKNOWN} 으로 저장되고, 그 식별자가 거래소 접수 기록에 남을 때까지
     * 제한 시간 안에서 기다린다 (리뷰 31차, 2026-10-29).
     *
     * <p>🔴 {@link #awaitDrained()} 만으로는 부족하다. 작업이 큐에서 꺼내졌지만 아직 실행 전인 순간에는
     * 큐 0 · 활성 0 · 작업 수 = 완료 수가 모두 성립해, <b>작업이 돌기 전에 완료로 판단</b>할 수 있다
     * (리뷰가 로컬에서 재현). 그래서 이 주문의 결과 자체를 기다린 뒤, 남은 작업이 끝나도록 비움을 확인한다.
     * 결과의 내용은 각 시험의 단언이 그대로 검사한다.</p>
     */
    private void awaitUnknownCommitted(Fixture f) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            boolean committed = orderRepository
                    .findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", f.sessionId()).stream()
                    .anyMatch(o -> "UNKNOWN".equals(o.getState()) && o.getClientOrderId() != null
                            && accepted.contains(o.getClientOrderId()));
            if (committed) {
                awaitDrained();
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("15초 안에 이 세션의 주문이 UNKNOWN 으로 저장되지 않았거나, "
                + "그 식별자가 접수 기록에 없다 — 접수 기록: " + accepted);
    }

    private record Fixture(Long sessionId, Long positionId, OrderRequest request) { }

    private Fixture runningSessionWithBuyRequest() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                // 🔴 매수 요청액 3,000 은 **이미 차감된 상태**다 (LiveTradingService 가 제출 전에
                //    차감한다). 그래서 잔액은 7,000 으로 시작하고, 복원이 일어나면 10,000 이 된다.
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
        req.setReason("전략 신호 — claim·불명 시험");
        req.setSignalPrice(new BigDecimal("300.00000000"));
        req.setSessionId(session.getId());
        req.setSessionKind("LIVE");
        req.setPositionId(pos.getId());
        return new Fixture(session.getId(), pos.getId(), req);
    }

    /** 접수 기록을 남긴 뒤 응답 시간 초과를 일으킨다 — 거래소에는 주문이 살아 있다. */
    private void stubAcceptThenTimeout() {
        Mockito.doAnswer(inv -> {
            accepted.add(inv.getArgument(5));
            throw new RuntimeException(new java.net.http.HttpTimeoutException("request timed out"));
        }).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());
    }

    private OrderEntity theOrder(Fixture f) {
        List<OrderEntity> orders = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", f.sessionId()).stream().filter(o__ -> (ALL_STATES).contains(o__.getState())).toList();
        assertThat(orders).as("주문은 **정확히 한 행**이어야 한다").hasSize(1);
        return orders.get(0);
    }

    // ── 시험 1·2 : 의도 커밋과 불명 보존 ──────────────────────────

    @Test
    @DisplayName("시험 1·2 — 응답을 잃으면 UNKNOWN 으로 남고 식별자로 추적 가능하며 자금·슬롯이 유지된다")
    void lostResponseStaysUnknownWithIdentifier() {
        Fixture f = runningSessionWithBuyRequest();
        stubAcceptThenTimeout();

        orderExecutionEngine.submitOrder(f.request());
        awaitUnknownCommitted(f);

        OrderEntity o = theOrder(f);

        // ① 의도·식별자가 **외부 호출 전에** 커밋됐다 — 거래소가 그 값을 받았다는 사실이 근거다.
        assertThat(o.getClientOrderId())
                .as("🔴 식별자가 있어야 한다 — 응답을 잃었을 때 **유일한 추적 단서**다")
                .isNotNull();
        assertThat(o.getIntentCommittedAt())
                .as("의도 커밋 시각이 남아야 한다")
                .isNotNull();
        assertThat(accepted)
                .as("🔴 거래소가 **그 식별자를** 받았다 — 의도가 호출 **전에** 커밋됐다는 증거다")
                .containsExactly(o.getClientOrderId());

        // ② 상태는 UNKNOWN 이다. FAILED 가 아니다.
        assertThat(o.getState())
                .as("🔴 시간 초과는 '거래소가 거절했다' 가 아니다. FAILED 로 박으면 살아 있는 주문을 "
                        + "없는 것으로 기록하고 자금을 되돌린다")
                .isEqualTo("UNKNOWN");
        assertThat(o.getUnknownReason())
                .as("불명 판정의 근거가 남아야 한다 — 사람이 종결할 때 읽는다")
                .isNotNull();
        assertThat(o.getClaimedAt())
                .as("claim 시각이 남아야 한다 — 외부에 나갔을 가능성을 배제하지 못한다는 표시다")
                .isNotNull();

        // ③ 자금·슬롯이 해제되지 않았다.
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("🔴 자금이 복원되면 **쓰였을 수 있는 돈을 되돌리는 것**이다 — 7,000 그대로여야 한다")
                .isEqualByComparingTo(new BigDecimal("7000.00"));
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getStatus())
                .as("🔴 포지션(슬롯)도 유지된다 — 체결됐다면 그것이 관리 주체다")
                .isEqualTo("OPEN");

        // ④ 미해결로 집계돼 정지가 **정리 완료**를 표시하지 못한다.
        assertThat(orderRepository.countUnresolvedSubmissions("LIVE", f.sessionId()))
                .as("미해결 제출로 집계돼야 한다")
                .isEqualTo(1L);
    }

    // ── 시험 3 : 조회 실패 반복 ──────────────────────────────────

    @Test
    @DisplayName("시험 3 — 조회가 반복 실패해도 새 식별자로 재주문하지 않고 상태를 유지한다")
    void repeatedProbeFailureNeverResubmits() {
        Fixture f = runningSessionWithBuyRequest();
        stubAcceptThenTimeout();
        orderExecutionEngine.submitOrder(f.request());
        awaitUnknownCommitted(f);

        String identifier = theOrder(f).getClientOrderId();
        Mockito.doThrow(new RuntimeException("조회 실패"))
                .when(upbitOrderClient).getOrderByIdentifier(identifier);

        for (int i = 0; i < 3; i++) {
            orderExecutionEngine.resolveUnresolvedSubmission(theOrder(f).getId());
        }

        OrderEntity o = theOrder(f);
        assertThat(o.getState())
                .as("🔴 묻지 못한 것은 모른다는 사실이 바뀌지 않은 것이다 — 상태를 바꾸지 않는다")
                .isEqualTo("UNKNOWN");
        assertThat(o.getClientOrderId())
                .as("🔴 **같은 식별자를 유지한다.** 새 값을 만들면 거래소에 두 주문이 생기고, "
                        + "업비트 식별자는 재사용할 수 없으므로 되돌릴 수도 없다")
                .isEqualTo(identifier);
        assertThat(o.getUnknownProbeCount())
                .as("시도 횟수는 올라간다 — 사람이 영구 조회 실패를 판단하는 근거다")
                .isGreaterThanOrEqualTo(3);

        // 🔴 제출은 처음 한 번뿐이다.
        assertThat(accepted).as("🔴 재제출 0회").hasSize(1);
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());
    }

    // ── 시험 20 : SUBMITTING 복구 작업자 ─────────────────────────

    @Test
    @DisplayName("시험 20 — SUBMITTING 을 발견한 복구 작업자는 재제출하지 않고 식별자로 조회만 한다")
    void recoveryWorkerOnSubmittingOnlyQueries() {
        Fixture f = runningSessionWithBuyRequest();

        // 재기동 직후를 흉내낸다 — claim 은 커밋됐고 그 프로세스는 사라졌다.
        OrderEntity stuck = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000.00000000"))
                .state("SUBMITTING")
                .clientOrderId("cat-recovery-20")
                .intentCommittedAt(Instant.now())
                .claimedAt(Instant.now())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L)
                .sessionKind("LIVE").sessionId(f.sessionId()).positionId(f.positionId())
                .createdAt(Instant.now()).build());

        // 거래소에는 그 주문이 **살아 있다**.
        OrderResponse live = new OrderResponse();
        live.setUuid("uuid-recovered-20");
        live.setSide("bid");
        live.setOrdType("price");
        live.setState("wait");
        live.setMarket("KRW-DOGE");
        live.setVolume(new BigDecimal("3000.00000000"));
        Mockito.doReturn(live).when(upbitOrderClient).getOrderByIdentifier("cat-recovery-20");
        Mockito.doReturn(live).when(upbitOrderClient).getOrder("uuid-recovered-20");

        orderExecutionEngine.resolveUnresolvedSubmission(stuck.getId());

        // 🔴 핵심: 재제출 0회.
        Mockito.verify(upbitOrderClient, Mockito.never()).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());
        assertThat(accepted)
                .as("🔴 **상태를 읽은 것과 claim 전환에 성공한 것은 다르다.** 상태는 재기동 후에도 "
                        + "보이지만 전환 성공은 한 번만 일어난다 — 보고 제출하면 같은 의도가 두 번 나간다")
                .isEmpty();

        // 조회로 UUID 를 확보해 기존 추적 경로에 연결했다.
        OrderEntity o = orderRepository.findById(stuck.getId()).orElseThrow();
        assertThat(o.getExchangeOrderId())
                .as("식별자 조회로 UUID 를 확보해야 한다 — 이후는 기존 폴링·정산 경로가 받는다")
                .isEqualTo("uuid-recovered-20");
        Mockito.verify(upbitOrderClient, Mockito.times(1)).getOrderByIdentifier("cat-recovery-20");
    }

    @Test
    @DisplayName("시험 20-b — 식별자로 조회되지 않아도 미접수로 단정하지 않는다")
    void notFoundByIdentifierIsNotTreatedAsNeverAccepted() {
        Fixture f = runningSessionWithBuyRequest();
        OrderEntity stuck = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000.00000000"))
                .state("SUBMITTING")
                .clientOrderId("cat-recovery-20b")
                .intentCommittedAt(Instant.now()).claimedAt(Instant.now())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L)
                .sessionKind("LIVE").sessionId(f.sessionId()).positionId(f.positionId())
                .createdAt(Instant.now()).build());

        // 거래소가 "그 식별자의 주문이 없다" 고 답한다.
        Mockito.doReturn(null).when(upbitOrderClient).getOrderByIdentifier("cat-recovery-20b");

        orderExecutionEngine.resolveUnresolvedSubmission(stuck.getId());

        OrderEntity o = orderRepository.findById(stuck.getId()).orElseThrow();
        assertThat(o.getState())
                .as("🔴 '없다' 가 '접수된 적 없다' 와 같지 않다 — 업비트 명세에 식별자 **보존 기간이 "
                        + "없다.** 그래서 종결하지 않고 불명으로 둔다")
                .isEqualTo("UNKNOWN");
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("🔴 자금을 복원하지 않는다")
                .isEqualByComparingTo(new BigDecimal("7000.00"));
        Mockito.verify(upbitOrderClient, Mockito.never()).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());
    }

    // ── 시험 21 : claim 전환 1행인데 커밋 실패 ───────────────────

    @Test
    @DisplayName("시험 21 — claim 전환이 1행이어도 커밋이 실패하면 제출 0회다")
    void claimRollbackMeansNoSubmission() {
        Fixture f = runningSessionWithBuyRequest();

        // claim 과 **같은 트랜잭션** 안에서 실패를 일으킨다. claim UPDATE 는 1행을 돌려받지만
        // 그 트랜잭션이 롤백되므로 외부 호출로 가서는 안 된다.
        //
        // 🔴 claim 커밋 뒤에 외부 호출을 두지 않았다면, 롤백된 claim 으로도 제출이 나간다 —
        //    아무도 claim 하지 않은 주문이 외부로 나가는 것이다.
        Mockito.doAnswer(inv -> {
            throw new IllegalStateException("claim 트랜잭션 커밋 실패 (주입)");
        }).when(upbitOrderClient).getOrderByIdentifier(Mockito.anyString());

        OrderEntity intent = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000.00000000"))
                .state("INTENT_READY")
                .clientOrderId("cat-claim-21")
                .intentCommittedAt(Instant.now())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L)
                .sessionKind("LIVE").sessionId(f.sessionId()).positionId(f.positionId())
                .createdAt(Instant.now()).build());

        // 1회차: claim 을 얻는다.
        int won = inTx(() -> orderRepository.claimForSubmission(intent.getId(), Instant.now()));
        assertThat(won).as("조건부 전환이 1행을 돌려준다").isEqualTo(1);

        // 2회차: 같은 주문에 다시 claim 을 시도한다 — **상태가 더는 INTENT_READY 가 아니다.**
        int second = inTx(() -> orderRepository.claimForSubmission(intent.getId(), Instant.now()));
        assertThat(second)
                .as("🔴 claim 은 **한 번만** 성립한다 — 두 작업자가 다투면 한쪽만 제출한다")
                .isZero();

        // 🔴 claim 을 얻지 못한 쪽은 제출하지 않는다.
        assertThat(accepted).as("🔴 제출 0회").isEmpty();
        Mockito.verify(upbitOrderClient, Mockito.never()).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());
    }

    @Test
    @DisplayName("시험 21-b — 철회는 claim 할 작업이 없음이 확정될 때만 한다 (나이는 근거가 아니다)")
    void intentBeforeClaimIsWithdrawnOnlyWhenOwnerIsGone() {
        Fixture f = runningSessionWithBuyRequest();

        // ① 🔴 **살아 있는 작업이 맡은 의도는 10분이 지나도 철회하지 않는다** (V86).
        //
        //    V84 는 60초가 지나면 철회했다. 그러나 실행기 큐가 밀리면 정상 작업도 그보다 늦게
        //    claim 한다 — 그 의도를 철회하는 것은 **시간 기반 철회**다. 조건부 전환이 경합을 가려
        //    주더라도 철회 자체가 정당한 것은 아니다.
        //
        //    운영 경로(reserveAndPrepareIntent)로 의도를 만들고 **디스패치는 하지 않는다** — 작업이
        //    큐에서 기다리는 상태와 같다. 그다음 의도를 10분 전으로 돌린다.
        Long alive = inTx(() -> orderExecutionEngine.reserveAndPrepareIntent(f.request()));
        OrderEntity aged = orderRepository.findById(alive).orElseThrow();
        aged.setIntentCommittedAt(Instant.now().minusSeconds(600));
        orderRepository.save(aged);
        assertThat(OrderExecutionEngine.isDispatching(alive))
                .as("소유 작업이 등록부에 살아 있다").isTrue();

        orderExecutionEngine.resolveUnresolvedSubmission(alive);

        OrderEntity stillReady = orderRepository.findById(alive).orElseThrow();
        assertThat(stillReady.getState())
                .as("🔴 나이는 철회의 근거가 아니다 — 작업이 살아 있으면 얼마나 오래됐든 그대로 둔다")
                .isEqualTo("INTENT_READY");
        assertThat(stillReady.getFailureCode()).isNull();

        // 그 작업이 디스패치에 실패했다고 알려 오면 그때 철회한다 — 근거가 생겼다.
        assertThat(orderExecutionEngine.withdrawUndispatchedIntent(alive, "시험 — 실행기 거절"))
                .isTrue();
        assertThat(orderRepository.findById(alive).orElseThrow().getFailureCode())
                .isEqualTo(OrderExecutionEngine.WITHDRAWN_DISPATCH_LOST);

        // ② 이전 기동이 만든 의도 — 그 작업 큐는 프로세스와 함께 사라졌다. **방금 만든 것이어도**
        //    철회한다(나이를 보지 않는다).
        OrderEntity orphaned = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000.00000000"))
                .state("INTENT_READY")
                .clientOrderId("cat-claim-21b")
                .intentCommittedAt(Instant.now())
                .intentOwner("inst-previous-boot")
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L)
                .sessionKind("LIVE").sessionId(f.sessionId()).positionId(f.positionId())
                .createdAt(Instant.now()).build());

        orderExecutionEngine.resolveUnresolvedSubmission(orphaned.getId());

        OrderEntity w2 = orderRepository.findById(orphaned.getId()).orElseThrow();
        assertThat(w2.getState())
                .as("🔴 claim 이 제출의 **유일한 관문**이고 그 전환이 커밋된 적이 없으므로 외부 호출은 "
                        + "일어나지 않았다 — 철회가 **안전**하다. 그리고 claim 할 작업이 없으므로 **정당**하다")
                .isEqualTo("FAILED");
        assertThat(w2.getFailureCode()).isEqualTo(OrderExecutionEngine.WITHDRAWN_OWNER_LOST);

        // ③ 이 프로세스가 만들었지만 맡은 작업이 없다(claim 전 예외 등으로 끝났다) — 방금 만든 것이어도 철회.
        OrderEntity abandoned = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-ADA").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("1000.00000000"))
                .state("INTENT_READY")
                .clientOrderId("cat-claim-21b-abandoned")
                .intentCommittedAt(Instant.now())
                .intentOwner(OrderExecutionEngine.INSTANCE_ID)
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L)
                .sessionKind("LIVE").sessionId(f.sessionId())
                .createdAt(Instant.now()).build());

        orderExecutionEngine.resolveUnresolvedSubmission(abandoned.getId());

        OrderEntity w3 = orderRepository.findById(abandoned.getId()).orElseThrow();
        assertThat(w3.getState()).isEqualTo("FAILED");
        assertThat(w3.getFailureCode()).isEqualTo(OrderExecutionEngine.WITHDRAWN_DISPATCH_LOST);

        for (OrderEntity o : List.of(stillReady, w2, w3)) {
            assertThat(orderRepository.findById(o.getId()).orElseThrow().getFailureCode())
                    .as("🔴 우리의 철회는 거래소 거절로 기록되지 않는다")
                    .isNotEqualTo(OrderExecutionEngine.EXCHANGE_REJECTED);
        }
        Mockito.verify(upbitOrderClient, Mockito.never())
                .getOrderByIdentifier(Mockito.anyString());
        Mockito.verify(upbitOrderClient, Mockito.never()).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());
    }

    // ── 정지와의 관계 : 정리 미완료 ──────────────────────────────

    @Test
    @DisplayName("불명 주문이 남은 세션은 정지해도 정리 완료가 아니고 신규 매수가 막힌다")
    void stopWithUnknownOrderIsNotCleanupComplete() {
        Fixture f = runningSessionWithBuyRequest();
        stubAcceptThenTimeout();
        orderExecutionEngine.submitOrder(f.request());
        awaitUnknownCommitted(f);
        assertThat(theOrder(f).getState()).isEqualTo("UNKNOWN");

        liveTradingService.stopSession(f.sessionId());

        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getStatus())
                .as("정지 자체는 성립한다 — 신규 승인은 차단된다")
                .isEqualTo("STOPPED");
        assertThat(orderRepository.countUnresolvedSubmissions("LIVE", f.sessionId()))
                .as("🔴 미해결 제출이 남았으므로 **정리 완료가 아니다.** 그 주문은 접수됐을 수 있고, "
                        + "그렇다면 장부에 없는 포지션이 거래소에 있다")
                .isEqualTo(1L);
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("🔴 정지가 자금을 복원하지 않는다 — 불명 주문은 CANCELLED·FAILED 가 아니다")
                .isEqualByComparingTo(new BigDecimal("7000.00"));
    }
}
