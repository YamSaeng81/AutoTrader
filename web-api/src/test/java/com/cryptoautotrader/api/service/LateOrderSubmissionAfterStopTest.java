package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선행 조건 5번 ③-1 — <b>늦은 신규 제출: 정지가 완료된 뒤에는 신규 매수가 제출되지 않아야 한다.</b>
 *
 * <h3>이것은 "정지된 세션을 다시 정지할 수 없다" 와 다른 질문이다</h3>
 *
 * <p>위험 경보에 대응해 사람이 정지를 부르는 그 순간, 60초 평가 루프는 이미 돌고 있을 수 있다.
 * 평가가 세션을 <b>{@code RUNNING} 으로 읽은 뒤</b> 매수를 결정하고, 그 제출이 <b>정지 커밋
 * 이후에</b> 거래소에 닿으면 — 사람은 정지시켰다고 보는데 <b>새 포지션이 생긴다.</b> 정지된 세션에는
 * 평가 루프가 없으므로 그 포지션을 청산할 주체도 없다.</p>
 *
 * <h3>고정하는 순서</h3>
 *
 * <pre>
 * 평가가 RUNNING 을 읽음 → 평가 일시 대기 → 정지 커밋 완료 → 평가 재개
 *   → 비동기 작업 드레인 → 거래소 신규 매수 호출 0회
 * </pre>
 *
 * <p>🔴 스레드 타이밍에 의존하지 않는다. 일시 대기 지점은 <b>제출 경로 안에서 반드시 불리는
 * 지점</b>을 쓴다 — {@code submitOrder} 는 PENDING 주문을 만든 직후 거래소 호출 <b>전에</b>
 * {@code recordTradeLog} 를 부른다. 그 호출이 일어날 때 <b>독립 트랜잭션에서 정지를 커밋</b>하면,
 * 평가가 RUNNING 을 읽은 뒤 정지가 끼어든 상황이 결정적으로 만들어진다.</p>
 *
 * <p>세션 매수({@code positionId != null})는 {@code checkRisk} 를 건너뛰도록 설계돼 있으므로
 * 그쪽은 이음새로 쓸 수 없다.</p>
 *
 * <p>⚠️ 이 시험은 {@code TradeLogRepository.save} 를 스텁하므로 그 구간의 거래 로그는 저장되지
 * 않는다(리포지토리는 인터페이스라 실제 메서드로 위임할 수 없다). 확인 대상이 거래소 호출과 최종
 * 상태이므로 영향이 없다.</p>
 */
@DisplayName("정지가 완료된 뒤에는 신규 매수가 제출되지 않는다 (선행 조건 5)")
class LateOrderSubmissionAfterStopTest extends IntegrationTestBase {

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
    private PlatformTransactionManager txManager;

    @Autowired
    @Qualifier("orderExecutor")
    private ThreadPoolTaskExecutor orderExecutor;

    /** 거래소 경계 — 신규 매수가 여기 닿으면 안 된다. */
    @MockBean
    private UpbitOrderClient upbitOrderClient;

    /** 제출 경로의 일시 대기 지점. */
    @SpyBean
    private TradeLogRepository tradeLogRepository;

    private final List<Long> createdSessions = new ArrayList<>();

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
    }

    private void awaitOrderExecutorDrained() {
        var pool = orderExecutor.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0) {
                sleep(50);
                if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0) return;
            }
            sleep(50);
        }
        throw new AssertionError("orderExecutor 가 15초 안에 비워지지 않았다 — 비동기 작업이 정체됐다");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 🔴 <b>미해결 — 배포 차단 항목이다. 이 시험은 통과하지 않는다.</b>
     *
     * <p>재현은 유효하다(고치기 전 결과: 정지 커밋 뒤 거래소 매수가 실제로 나갔다). 그러나
     * 2026-10-07 에 시도한 수정은 <b>구조적으로 틀렸고 철회했다.</b></p>
     *
     * <p>시도한 것: 세션 행을 배타 잠금으로 읽고 상태를 확인한 뒤 <b>같은 트랜잭션에서</b> 거래소에
     * 제출하도록 승인 구간에 {@code REQUIRES_NEW} 트랜잭션을 뒀다. 결과는 두 가지로 드러났다 —</p>
     * <ol>
     *   <li>분리된 주문 엔티티를 그 트랜잭션에서 저장하자 <b>같은 주문이 두 행으로 저장됐다</b>
     *       (거래소 호출 1회·{@code trade_log} 1건인데 주문 행 2건, 모든 필드가 마이크로초까지 동일).</li>
     *   <li>엔티티를 그 트랜잭션 안에서 다시 읽게 하자 <b>주문을 찾지 못해 제출 자체가 사라졌다</b>
     *       (거래소 호출 0회).</li>
     * </ol>
     *
     * <p>🔴 두 결과가 같은 사실을 가리킨다 — <b>{@code submitOrder} 는 바깥 트랜잭션 안에서 돌고,
     * 주문 행은 그 트랜잭션이 커밋되기 전까지 별도 트랜잭션에 보이지 않는다.</b> 그러므로
     * {@code REQUIRES_NEW} 승인은 애초에 성립할 수 없다. 다시 설계해야 한다 — 잠금을
     * {@code PROPAGATION_REQUIRED} 로 바깥 트랜잭션에 걸거나, 승인 경계를 제출 경로 바깥(평가
     * 쪽)으로 올리는 방향이 후보다. {@code LiveTradingSessionRepository.findByIdForUpdate} 는
     * 그 용도로 남겨 둔다.</p>
     */
    @Test
    @DisplayName("평가가 RUNNING 을 읽은 뒤 정지가 커밋되면, 그 평가의 매수는 거래소에 닿지 않는다")
    void buyDecidedBeforeStopMustNotReachExchange() {
        // ── 평가가 RUNNING 을 읽는 시점 ──────────────────────────────
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE")
                .timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("7000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .status("RUNNING")
                .startedAt(Instant.now())
                .build());
        Long sessionId = session.getId();
        createdSessions.add(sessionId);
        assertThat(session.getStatus()).as("평가는 RUNNING 을 읽었다").isEqualTo("RUNNING");

        // 평가가 매수를 결정하고 만드는 임시 포지션 (size=0 — 체결 대기)
        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE")
                .side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(BigDecimal.ZERO)
                .investedKrw(new BigDecimal("3000.00000000"))
                .status("OPEN")
                .sessionKind("LIVE")
                .sessionId(sessionId)
                .openedAt(Instant.now())
                .build());

        OrderRequest request = new OrderRequest();
        request.setCoinPair("KRW-DOGE");
        request.setSide("BUY");
        request.setOrderType("MARKET");
        request.setQuantity(new BigDecimal("3000.00000000"));
        request.setReason("전략 신호 — 평가가 RUNNING 을 읽은 뒤 결정했다");
        request.setSignalPrice(new BigDecimal("300.00000000"));
        request.setSessionId(sessionId);
        request.setSessionKind("LIVE");
        request.setPositionId(pos.getId());

        // ── 평가 일시 대기 → 정지 커밋 완료 → 평가 재개 ─────────────
        TransactionTemplate requiresNew = new TransactionTemplate(txManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicInteger seam = new AtomicInteger();

        Mockito.doAnswer(inv -> {
            if (seam.getAndIncrement() == 0) {
                // 독립 트랜잭션에서 정지를 **커밋**한다 — 제출 트랜잭션에 묶이면 시나리오가 깨진다
                requiresNew.executeWithoutResult(st -> liveTradingService.stopSession(sessionId));
            }
            return inv.getArgument(0);
        }).when(tradeLogRepository).save(Mockito.any());

        orderExecutionEngine.submitOrder(request);

        // ── 비동기 작업 드레인 ──────────────────────────────────────
        awaitOrderExecutorDrained();

        assertThat(seam.get())
                .as("일시 대기 지점을 실제로 지났는지 — 0 이면 재현 실패이므로 시험이 무의미하다")
                .isGreaterThan(0);
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus())
                .as("정지가 커밋돼 있어야 한다")
                .isEqualTo("STOPPED");

        // ── 거래소 신규 매수 호출 0회 ───────────────────────────────
        Mockito.verify(upbitOrderClient, Mockito.never()).createOrder(
                Mockito.any(), Mockito.eq("bid"), Mockito.any(), Mockito.any(), Mockito.any());

        // ── 최종 상태: 활성 주문·임시 포지션이 남지 않는다 ──────────
        List<OrderEntity> active = orderRepository.findBySessionIdAndStateIn(sessionId,
                List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED"));
        assertThat(active)
                .as("제출되지 않은 주문이 활성으로 남으면 폴러가 영원히 쫓는다")
                .isEmpty();

        assertThat(positionRepository.findBySessionKindAndSessionIdAndCoinPairAndStatus(
                "LIVE", sessionId, "KRW-DOGE", "OPEN"))
                .as("체결되지 않은 임시 포지션이 OPEN 으로 남으면 관리 주체 없는 포지션이 된다")
                .isEmpty();
    }
}
