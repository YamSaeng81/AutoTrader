package com.cryptoautotrader.api.service;

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
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진단 — <b>정지 청산의 제출 경계를 단계별로 고정한다.</b>
 *
 * <h3>왜 측정하는가</h3>
 *
 * <p>2026-10-07 에 ③-1 수정을 철회하면서 "{@code submitOrder} 는 바깥 트랜잭션 안에서 돈다" 고
 * 적었다. 🔴 <b>그것은 근거를 넘어선 결론이었다.</b> 제출은 {@code @Async} 이므로 호출자의 트랜잭션이
 * <b>전달되지 않는다</b>. 이 시험은 생산 코드를 바꾸지 않고 그 경계를 실측해 고정한다.</p>
 *
 * <h3>계약 (총괄 v1.158 결정 3 · review-1eed27d1-boundary §2, 2026-11-12)</h3>
 *
 * <p>종전에는 "최초 거래 기록 저장" 하나를 제출 경계로 보고 그것이 정지 트랜잭션에 합류하지 않는다고 단언했다.
 * 결정 3 으로 CLOSING 과 매도 의도({@code null → INTENT_READY})를 <b>정지 트랜잭션 안에서 함께</b> 저장하게 되어
 * 최초 기록이 바뀌었으므로, 같은 주문 ID 와 전이({@code oldState → newState})로 관측을 연결해 네 경계를 각각 단언한다.</p>
 * <ol>
 *   <li>CLOSING·{@code null → INTENT_READY} — 정지 호출자의 <b>같은</b> 트랜잭션. 그 커밋 전 외부 주문 0.</li>
 *   <li>{@code INTENT_READY → SUBMITTING} claim — 호출자 커밋 <b>뒤</b>, 다른 작업 스레드의 활성 트랜잭션. 호출자 트랜잭션에 합류하지 않음.</li>
 *   <li>거래소 {@code createOrder} — 작업 스레드에서 <b>실제 DB 트랜잭션 비활성</b>. 호출 1회.</li>
 *   <li>{@code SUBMITTING → SUBMITTED} 결과 저장 — 별도의 활성 트랜잭션. 같은 주문의 상태 반영 1회, 최종 주문 행 1개.</li>
 * </ol>
 *
 * <p>🔴 트랜잭션의 동일/분리는 이름 문자열이 아니라 <b>트랜잭션에 묶인 실제 자원</b>(엔티티 관리자 보유자)의
 * 객체 동일성으로 판정한다. 완료 대기는 실행기 큐가 아니라 <b>해당 주문의 결과 저장 트랜잭션 커밋</b>을 기다리며,
 * 정해진 한도 안에 오지 않으면 실패한다.</p>
 */
@DisplayName("진단 — 정지 청산 제출의 스레드·트랜잭션 경계를 단계별로 고정한다")
class SubmitOrderBoundaryProbeTest extends IntegrationTestBase {

    private static final long RESULT_COMMIT_WAIT_SECONDS = 15;

    @Autowired
    private LiveTradingService liveTradingService;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    /** 트랜잭션에 묶이는 실제 자원의 키 — JPA 트랜잭션 관리자는 이 키로 엔티티 관리자 보유자를 묶는다. */
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockBean
    private UpbitOrderClient upbitOrderClient;

    /** 제출 경로 안의 관측 지점 — 모든 상태 전이 기록이 지난다. */
    @SpyBean
    private TradeLogRepository tradeLogRepository;

    /** 청산 경로 안의 관측 지점 — 정지 트랜잭션 안에서 불린다. */
    @SpyBean
    private PositionStateTransition positionStateTransition;

    private final List<Long> createdSessions = new ArrayList<>();

    /** 한 관측 지점 — 순번·스레드·실제 트랜잭션 활성·묶인 자원(객체 동일성 비교용 강한 참조)·이름(보고용). */
    private record Obs(long seq, String thread, boolean txActive, Object txResource, String txName) {
        @Override
        public String toString() {
            return String.format("#%d thread=%s txActive=%s resource=%s txName=%s", seq, thread, txActive,
                    txResource == null ? "null" : Integer.toHexString(System.identityHashCode(txResource)), txName);
        }
    }

    private final AtomicLong seq = new AtomicLong();
    private final Map<String, Obs> obs = new ConcurrentHashMap<>();
    private final Map<String, Long> orderIdOf = new ConcurrentHashMap<>();
    private final List<String> transitions = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger createOrderCalls = new AtomicInteger();
    private final AtomicInteger createOrderCallsAtCallerBeforeCommit = new AtomicInteger(-1);
    private final AtomicInteger submittedTransitions = new AtomicInteger();
    private final CountDownLatch resultCommitted = new CountDownLatch(1);

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id)
                    .forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id)
                    .forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
    }

    private Obs here() {
        return new Obs(seq.incrementAndGet(),
                Thread.currentThread().getName(),
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.getResource(entityManagerFactory),
                TransactionSynchronizationManager.getCurrentTransactionName());
    }

    /** 현재 트랜잭션에 커밋 시점 관측을 건다 — 다른 동기화(커밋 후 제출 등)보다 먼저 돈다. */
    private void onCommit(Runnable beforeCommit, Runnable afterCommit) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE;
            }

            @Override
            public void beforeCommit(boolean readOnly) {
                if (beforeCommit != null) beforeCommit.run();
            }

            @Override
            public void afterCommit() {
                if (afterCommit != null) afterCommit.run();
            }
        });
    }

    private UpbitOrderClient.ExchangeResult acceptedSell() {
        OrderResponse r = new OrderResponse();
        r.setUuid("probe-uuid");
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("wait");
        r.setMarket("KRW-DOGE");
        r.setExecutedVolume(BigDecimal.ZERO);
        return new UpbitOrderClient.ExchangeResult(r, "{}");
    }

    @Test
    @DisplayName("CLOSING·의도=호출자 트랜잭션, claim=커밋 뒤 작업 스레드 트랜잭션, 거래소 호출=트랜잭션 밖, 결과=별도 트랜잭션")
    void probeBoundaries() throws InterruptedException {
        String testThread = Thread.currentThread().getName();

        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE")
                .timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("2000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .status("RUNNING")
                .startedAt(Instant.now())
                .build());
        Long sessionId = session.getId();
        createdSessions.add(sessionId);

        positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(new BigDecimal("26.00000000"))
                .investedKrw(new BigDecimal("8000.00000000"))
                .status("OPEN").sessionKind("LIVE").sessionId(sessionId)
                .openedAt(Instant.now()).build());

        // ③ 거래소 호출 — 스레드·실제 트랜잭션 활성·호출 횟수
        Mockito.doAnswer(inv -> {
            createOrderCalls.incrementAndGet();
            obs.putIfAbsent("3-createOrder", here());
            return acceptedSell();
        }).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());

        // ① CLOSING — 정지 트랜잭션. 그 트랜잭션의 커밋 직전·직후를 함께 관측한다.
        Mockito.doAnswer(inv -> {
            if (obs.putIfAbsent("1-markClosing", here()) == null) {
                onCommit(() -> createOrderCallsAtCallerBeforeCommit.set(createOrderCalls.get()),
                        () -> obs.putIfAbsent("1-callerCommitted", here()));
            }
            return inv.callRealMethod();
        }).when(positionStateTransition).markClosingIfOpen(
                Mockito.anyLong(), Mockito.any(), Mockito.any());

        // ①②④ 상태 전이 기록 — 주문 ID 와 oldState→newState 로 단계를 구분한다(첫 save 순서로 구분하지 않는다).
        Mockito.doAnswer(inv -> {
            TradeLogEntity tl = inv.getArgument(0);
            String key = tl.getOldState() + "->" + tl.getNewState();
            Obs o = here();
            transitions.add(tl.getOrderId() + " " + key + " " + o);
            switch (key) {
                case "null->INTENT_READY" -> {
                    obs.putIfAbsent("1-intent", o);
                    orderIdOf.putIfAbsent("1-intent", tl.getOrderId());
                }
                case "INTENT_READY->SUBMITTING" -> {
                    obs.putIfAbsent("2-claim", o);
                    orderIdOf.putIfAbsent("2-claim", tl.getOrderId());
                }
                case "SUBMITTING->SUBMITTED" -> {
                    submittedTransitions.incrementAndGet();
                    if (obs.putIfAbsent("4-result", o) == null) {
                        orderIdOf.putIfAbsent("4-result", tl.getOrderId());
                        if (o.txActive()) {
                            onCommit(null, resultCommitted::countDown);
                        }
                    }
                }
                default -> { }
            }
            return inv.getArgument(0);
        }).when(tradeLogRepository).save(Mockito.any());

        liveTradingService.stopSession(sessionId);

        // 완료 대기 — 실행기 큐가 아니라 해당 주문의 결과 저장 트랜잭션 커밋. 한도 안에 오지 않으면 실패.
        boolean committed = resultCommitted.await(RESULT_COMMIT_WAIT_SECONDS, TimeUnit.SECONDS);
        String report = "관측=" + new java.util.TreeMap<>(obs) + " 주문ID=" + orderIdOf
                + " 전이=" + transitions + " createOrder=" + createOrderCalls.get()
                + " 커밋직전createOrder=" + createOrderCallsAtCallerBeforeCommit.get();
        System.out.println("V158PROBE " + report); // 통과해도 관측값을 XML system-out 에 남긴다
        assertThat(committed)
                .as("결과 저장(SUBMITTING→SUBMITTED) 트랜잭션이 %d초 안에 커밋돼야 한다: %s", RESULT_COMMIT_WAIT_SECONDS, report)
                .isTrue();

        Obs closing = obs.get("1-markClosing");
        Obs intent = obs.get("1-intent");
        Obs callerCommitted = obs.get("1-callerCommitted");
        Obs claim = obs.get("2-claim");
        Obs exchange = obs.get("3-createOrder");
        Obs result = obs.get("4-result");
        assertThat(closing).as("CLOSING 관측: %s", report).isNotNull();
        assertThat(intent).as("null→INTENT_READY 관측: %s", report).isNotNull();
        assertThat(callerCommitted).as("정지 트랜잭션 커밋 관측: %s", report).isNotNull();
        assertThat(claim).as("INTENT_READY→SUBMITTING 관측: %s", report).isNotNull();
        assertThat(exchange).as("createOrder 관측: %s", report).isNotNull();
        assertThat(result).as("SUBMITTING→SUBMITTED 관측: %s", report).isNotNull();

        // 같은 주문
        Long orderId = orderIdOf.get("1-intent");
        assertThat(orderId).as("의도 주문 ID: %s", report).isNotNull();
        assertThat(orderIdOf.get("2-claim")).as("claim 은 같은 주문: %s", report).isEqualTo(orderId);
        assertThat(orderIdOf.get("4-result")).as("결과 저장은 같은 주문: %s", report).isEqualTo(orderId);

        // ① CLOSING·의도 — 정지 호출자의 같은 트랜잭션, 그 커밋 전 외부 주문 0
        assertThat(closing.thread()).as("CLOSING 은 호출자 스레드: %s", report).isEqualTo(testThread);
        assertThat(closing.txActive()).as("CLOSING 은 실제 트랜잭션 안: %s", report).isTrue();
        assertThat(closing.txResource()).as("CLOSING 트랜잭션 자원: %s", report).isNotNull();
        assertThat(intent.thread()).as("의도는 호출자 스레드: %s", report).isEqualTo(testThread);
        assertThat(intent.txResource()).as("🔴 CLOSING 과 의도는 같은 트랜잭션 자원이어야 한다: %s", report)
                .isSameAs(closing.txResource());
        assertThat(createOrderCallsAtCallerBeforeCommit.get())
                .as("🔴 정지 트랜잭션 커밋 전 외부 주문은 0 이어야 한다: %s", report).isZero();

        // ② claim — 호출자 커밋 뒤, 다른 작업 스레드의 활성 트랜잭션, 호출자 트랜잭션에 합류하지 않음
        assertThat(claim.seq()).as("🔴 claim 은 호출자 커밋 뒤: %s", report).isGreaterThan(callerCommitted.seq());
        assertThat(claim.thread()).as("claim 은 다른 작업 스레드: %s", report).isNotEqualTo(testThread);
        assertThat(claim.txActive()).as("claim 은 실제 트랜잭션 안: %s", report).isTrue();
        assertThat(claim.txResource()).as("claim 트랜잭션 자원: %s", report).isNotNull()
                .isNotSameAs(closing.txResource());

        // ③ 거래소 호출 — 작업 스레드, 실제 DB 트랜잭션 비활성, 1회
        assertThat(exchange.thread()).as("거래소 호출은 작업 스레드: %s", report).isNotEqualTo(testThread);
        assertThat(exchange.txActive()).as("🔴 거래소 호출은 DB 트랜잭션 밖: %s", report).isFalse();
        assertThat(exchange.txResource()).as("🔴 거래소 호출 시점에 묶인 트랜잭션 자원이 없어야 한다: %s", report).isNull();
        assertThat(exchange.seq()).as("거래소 호출은 claim 뒤: %s", report).isGreaterThan(claim.seq());
        assertThat(createOrderCalls.get()).as("거래소 호출 1회: %s", report).isEqualTo(1);

        // ④ 결과 저장 — 별도의 활성 트랜잭션, 같은 주문 상태 반영 1회, 최종 주문 행 1개
        assertThat(result.seq()).as("결과 저장은 거래소 호출 뒤: %s", report).isGreaterThan(exchange.seq());
        assertThat(result.txActive()).as("결과 저장은 실제 트랜잭션 안: %s", report).isTrue();
        assertThat(result.txResource()).as("결과 저장 트랜잭션 자원: %s", report).isNotNull()
                .isNotSameAs(closing.txResource())
                .isNotSameAs(claim.txResource());
        assertThat(submittedTransitions.get()).as("같은 주문의 SUBMITTED 반영 1회: %s", report).isEqualTo(1);

        List<OrderEntity> rows = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", sessionId);
        assertThat(rows).as("최종 주문 행은 하나여야 한다 — 둘이면 같은 주문이 중복 저장된 것이다: %s", report).hasSize(1);
        assertThat(rows.get(0).getId()).as("그 행이 관측한 주문: %s", report).isEqualTo(orderId);
        assertThat(rows.get(0).getState()).as("최종 상태: %s", report).isEqualTo("SUBMITTED");
    }
}
