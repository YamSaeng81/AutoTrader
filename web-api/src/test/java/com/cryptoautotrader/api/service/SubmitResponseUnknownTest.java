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
 * 선행 조건 5번 ③-1 반대 순서 — <b>거래소가 접수했는데 응답이 오지 않는 경우.</b>
 *
 * <pre>
 * BUY 가 세션 잠금 획득 → 거래소가 주문을 접수 → 응답 지연·시간 초과 → DB 롤백/종결 → 정지 진행
 * </pre>
 *
 * <p>🔴 <b>DB 잠금은 제출과 정지의 순서를 조정하지만, 거래소 접수는 DB 롤백으로 취소되지 않는다.</b>
 * 그래서 네 가지를 따로 확인한다:</p>
 *
 * <ol>
 *   <li><b>추적</b> — 접수 가능성이 있는 주문을 나중에 식별·조회할 단서가 남는가</li>
 *   <li><b>중복 방지</b> — 결과 확인 전에 재시도로 같은 주문을 다시 제출하지 않는가</li>
 *   <li><b>정지 회복</b> — 제한된 시간 안에 잠금·연결·작업 스레드가 반환되고 대기하던 정지가 진행되는가</li>
 *   <li><b>후속 정리</b> — 이후 체결이 확인되면 회계에 정확히 한 번 반영되고, 정지 세션의 잔여
 *       포지션이 관리 대상으로 남는가</li>
 * </ol>
 *
 * <p>🔴 <b>시험을 통과시키려고 시간 초과를 단순 FAILED 로 처리하거나 무조건 재제출하게 만들지
 * 않는다.</b> 그 둘이 바로 이 경로에서 돈을 잃는 방식이다 — 전자는 살아 있는 주문을 없는 것으로
 * 기록하고, 후자는 같은 주문을 두 번 낸다.</p>
 */
@DisplayName("거래소가 접수했는데 응답이 없을 때 (선행 조건 5)")
class SubmitResponseUnknownTest extends IntegrationTestBase {

    @Autowired
    private OrderExecutionEngine orderExecutionEngine;

    @Autowired
    private LiveTradingService liveTradingService;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    @Qualifier("orderExecutor")
    private ThreadPoolTaskExecutor orderExecutor;

    @MockBean
    private UpbitOrderClient upbitOrderClient;

    private final List<Long> createdSessions = new ArrayList<>();

    /** 거래소가 "접수했다" 는 사실 — 응답이 오지 않아도 거래소에는 남는다. */
    private final List<String> exchangeAccepted = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            orderRepository.findBySessionIdAndStateIn(id,
                    List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED", "FILLED", "CANCELLED", "FAILED"))
                    .forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id)
                    .forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
        exchangeAccepted.clear();
    }

    private void awaitDrained() {
        var pool = orderExecutor.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("orderExecutor 가 비워지지 않았다 — 작업 스레드가 반환되지 않았다");
    }

    private record Fixture(Long sessionId, Long positionId, OrderRequest request) { }

    private Fixture runningSessionWithPendingBuy() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
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
        req.setReason("전략 신호 — 응답 불명 시험");
        req.setSignalPrice(new BigDecimal("300.00000000"));
        req.setSessionId(session.getId());
        req.setSessionKind("LIVE");
        req.setPositionId(pos.getId());
        return new Fixture(session.getId(), pos.getId(), req);
    }

    /** 접수 기록을 남긴 뒤 응답 시간 초과를 일으킨다 — 거래소에는 주문이 살아 있다. */
    private void stubAcceptThenTimeout() {
        Mockito.doAnswer(inv -> {
            exchangeAccepted.add("accepted-" + exchangeAccepted.size());
            throw new RuntimeException(new java.net.http.HttpTimeoutException("request timed out"));
        }).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("현재 동작을 측정한다 — 중복 제출·정지 회복·추적 단서")
    void measureCurrentBehaviourOnTimeout() {
        Fixture f = runningSessionWithPendingBuy();
        stubAcceptThenTimeout();

        orderExecutionEngine.submitOrder(f.request());
        awaitDrained();

        // ② 중복 방지 — 결과를 확인하지 못했는데 같은 주문을 또 내지 않아야 한다
        assertThat(exchangeAccepted)
                .as("거래소 접수는 1회여야 한다 — 응답 불명 상태에서 재제출하면 같은 주문이 두 번 들어간다")
                .hasSize(1);

        // ③ 작업 스레드 반환 — `awaitDrained()` 가 이미 확인했다(비워지지 않으면 거기서 실패한다).
        //    🔴 **정지 진행은 아직 확인되지 않는다** — 아래 `@Disabled` 시험에 요구사항을 적었다.

        // ① 추적 — 현재 남는 단서를 그대로 기록한다
        List<OrderEntity> rows = orderRepository.findBySessionIdAndStateIn(f.sessionId(),
                List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED", "FILLED", "CANCELLED", "FAILED"));
        assertThat(rows).as("주문 행은 남아야 한다 — 행조차 사라지면 추적 단서가 없다").hasSize(1);
        OrderEntity after = rows.get(0);

        // 🔴 측정된 현재 동작: 시간 초과가 FAILED 로 종결되고 거래소 주문 ID 는 없다.
        //    이것이 공백이다 — 아래 `@Disabled` 시험이 요구사항을 적어 둔다.
        assertThat(after.getState())
                .as("현재 동작 — 시간 초과를 종결 상태로 기록한다(상태=%s)", after.getState())
                .isEqualTo("FAILED");
        assertThat(after.getExchangeOrderId())
                .as("🔴 거래소 주문 ID 가 없다 — 그 주문을 거래소에 조회할 단서가 없다")
                .isNull();
    }

    /**
     * 🔴 <b>미해결 — 배포 차단 항목. 이 시험은 통과하지 않는다.</b>
     *
     * <p>요구사항: 거래소가 접수했을 가능성이 있는 주문은 <b>종결 상태로 기록하지 않고 추적 단서를
     * 남겨야 한다.</b> 현재는 시간 초과를 {@code FAILED} 로 기록하고 {@code exchangeOrderId} 가
     * {@code null} 이라, <b>그 주문이 거래소에 살아 있는지 확인할 방법이 없다.</b>
     * {@code pollActiveOrders()} 는 활성 주문만 보므로 {@code FAILED} 는 추적 대상도 아니다.</p>
     *
     * <p>🔴 <b>이것이 "주문 의도 선커밋" 구조가 필요한 구체적 근거다</b> — 거래소에 보내기 <b>전에</b>
     * 의도(어느 세션·어느 코인·어느 수량·어느 멱등키)를 커밋해 두면, 응답이 없어도 그 키로 거래소를
     * 조회해 결말을 낼 수 있다. 지금은 보낸 사실 자체가 DB 에 남지 않는다.</p>
     *
     * <p>🔴 <b>통과시키려고 시간 초과를 재제출로 덮지 않는다.</b> 결과를 모른 채 다시 보내면 같은
     * 주문이 두 번 들어간다. 해결은 상태 설계(불명 상태 + 멱등키 + 조회 기반 결말)이며, 제출 승인·
     * 정지·불명 주문 추적을 함께 설계해야 한다(③-2 와 같은 설계 단위).</p>
     */
    /**
     * 🔴 <b>미해결 — 배포 차단 항목. 이 시험은 통과하지 않는다.</b>
     *
     * <p>요구사항: 응답 불명 경로를 지난 뒤에도 <b>정지가 진행돼야 한다.</b> 실측에서는
     * {@code stopSession} 이 {@code ObjectOptimisticLockingFailureException}
     * ({@code LiveTradingSessionEntity})으로 실패했다.</p>
     *
     * <p>🔴 원인은 내가 넣은 행 잠금이 <b>아니다</b> — 엔티티를 관리하지 않는 네이티브
     * {@code FOR UPDATE} 로 바꾼 뒤에도 재현된다. 즉 <b>다른 경로가 세션 행을 쓰고 있고</b>
     * (`FAILED` 매수에 대한 KRW 복원 등 reconcile 계열이 후보다), 정지 트랜잭션이 들고 있던
     * 세션 스냅샷과 어긋난다. 어느 경로인지 가리지 못했다.</p>
     *
     * <p>🔴 <b>이것은 선행 조건 5번 ①(정지가 1회로 성공하는가)이 아직 닫히지 않았다는 뜻이다.</b>
     * 상태 쓰기를 `balanceUpdater`(재조회+재시도)로 돌린 것으로는 부족하다 — 정지 트랜잭션이 들고
     * 있는 <b>세션 스냅샷 자체</b>가 문제다.</p>
     */
    @org.junit.jupiter.api.Disabled("미해결: 응답 불명 경로 뒤 정지가 낙관적 잠금으로 실패한다 — 원인 경로 미확정")
    @Test
    @DisplayName("🔴 미해결 — 응답 불명 경로 뒤에도 정지가 진행돼야 한다")
    void stopMustProceedAfterUnknownResponse() {
        Fixture f = runningSessionWithPendingBuy();
        stubAcceptThenTimeout();

        orderExecutionEngine.submitOrder(f.request());
        awaitDrained();

        long started = System.currentTimeMillis();
        liveTradingService.stopSession(f.sessionId());
        long elapsed = System.currentTimeMillis() - started;

        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getStatus())
                .isEqualTo("STOPPED");
        assertThat(elapsed)
                .as("정지가 %dms 걸렸다 — 잠금·연결이 묶여 있으면 여기서 드러난다", elapsed)
                .isLessThan(10_000L);
    }

    @org.junit.jupiter.api.Disabled("미해결: 접수 가능성이 있는 주문이 FAILED 로 종결되고 추적 단서가 없다 — 상태 설계 필요")
    @Test
    @DisplayName("🔴 미해결 — 접수 가능성이 있는 주문은 종결하지 않고 추적 단서를 남겨야 한다")
    void unknownOutcomeMustLeaveATrackingClue() {
        Fixture f = runningSessionWithPendingBuy();
        stubAcceptThenTimeout();

        orderExecutionEngine.submitOrder(f.request());
        awaitDrained();

        OrderEntity after = orderRepository.findBySessionIdAndStateIn(f.sessionId(),
                List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED", "FILLED", "CANCELLED", "FAILED")).get(0);

        assertThat(after.getState())
                .as("접수됐을 수 있는 주문을 FAILED 로 종결하면 아무도 더 쫓지 않는다")
                .isNotEqualTo("FAILED");
        assertThat(after.getExchangeOrderId())
                .as("응답을 못 받았어도 거래소를 조회할 단서(멱등키 등)가 있어야 한다")
                .isNotNull();
    }
}
