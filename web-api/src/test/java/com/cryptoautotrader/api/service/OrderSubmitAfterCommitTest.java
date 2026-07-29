package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-07-29 P0 회귀 — 매수 주문은 <b>호출자 트랜잭션이 커밋된 뒤에</b> 제출되어야 한다.
 *
 * <p><b>사고 내용</b>: {@code executeBuy}가 {@code @Transactional} 안에서 포지션을 저장한 직후,
 * <b>커밋 전에</b> {@code @Async} {@code submitOrder}에 그 {@code positionId}를 넘겼다. async 쪽은
 * 별도 트랜잭션이라 미커밋 position이 보이지 않아 {@code order_position_id_fkey} 검사가 부모 커밋까지
 * 락 대기에 걸렸고, 타임아웃/데드락으로 <b>주문 INSERT가 통째로 롤백</b>됐다. 운영에서 동적 세션
 * 포지션 6건이 생성됐는데 주문 행은 0건이었고({@code order_id_seq}만 소비), 실체결이 한 건도
 * 성립하지 못했다.</p>
 *
 * <p><b>테스트가 잠그는 계약</b>: H2 스키마에는 운영과 달리 해당 FK가 없어 락 경합 자체는 재현되지
 * 않는다. 대신 근본 원인인 <b>호출 시점</b>을 검증한다 — 커밋 전에는 주문이 발행되지 않고, 커밋
 * 이후에만 발행되며, 롤백되면 아예 발행되지 않는다. {@code submitOrderAfterCommit}을 다시
 * {@code submitOrder}로 되돌리면 첫 번째·세 번째 테스트가 깨진다.</p>
 */
class OrderSubmitAfterCommitTest extends IntegrationTestBase {

    /** @Async 주문 스레드가 행을 남길 때까지의 최대 대기 */
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private OrderExecutionEngine engine;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @AfterEach
    void cleanup() {
        orderRepository.deleteAll();
        positionRepository.deleteAll();
    }

    @Test
    @DisplayName("P0 회귀 — 커밋 전에는 주문이 제출되지 않고, 커밋 이후에 제출된다")
    void submitOrderAfterCommit_notPublishedBeforeCommit() {
        AtomicLong countSeenInsideTx = new AtomicLong(-1);

        newTransaction().executeWithoutResult(status -> {
            PositionEntity pos = savePosition("KRW-BTC");
            engine.submitOrderAfterCommit(buyRequest(pos.getId(), "KRW-BTC"));

            // 커밋 전 — 주문은 아직 발행되면 안 된다 (이 지점이 사고의 핵심).
            // @Async 스레드가 실행될 시간을 충분히 준 뒤에 센다. 대기 없이 즉시 세면
            // 즉시 제출(구 동작)이어도 async 가 아직 시작 전이라 0이 나와 회귀를 놓친다.
            sleep(1_500);
            countSeenInsideTx.set(orderRepository.count());
        });

        assertThat(countSeenInsideTx.get())
                .as("커밋 전에 주문이 제출되면 async 스레드가 미커밋 position 을 참조하게 된다")
                .isZero();

        assertThat(awaitOrderCount(1))
                .as("커밋 이후에는 주문이 실제로 제출되어야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("P0 회귀 — 트랜잭션이 롤백되면 주문은 발행되지 않는다")
    void submitOrderAfterCommit_notPublishedOnRollback() {
        newTransaction().executeWithoutResult(status -> {
            PositionEntity pos = savePosition("KRW-ETH");
            engine.submitOrderAfterCommit(buyRequest(pos.getId(), "KRW-ETH"));
            status.setRollbackOnly();
        });

        sleep(500);

        assertThat(orderRepository.count())
                .as("포지션 없이 주문만 거래소로 나가는 사고를 막아야 한다")
                .isZero();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 호출하면 즉시 제출로 폴백한다")
    void submitOrderAfterCommit_fallsBackWhenNoTransaction() {
        PositionEntity pos = savePosition("KRW-XRP");

        engine.submitOrderAfterCommit(buyRequest(pos.getId(), "KRW-XRP"));

        assertThat(awaitOrderCount(1)).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private TransactionTemplate newTransaction() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private PositionEntity savePosition(String coinPair) {
        return positionRepository.save(PositionEntity.builder()
                .coinPair(coinPair)
                .side("BUY")
                .entryPrice(new BigDecimal("100"))
                .avgPrice(new BigDecimal("100"))
                .size(BigDecimal.ZERO)
                .investedKrw(new BigDecimal("8000"))
                .status("OPEN")
                .openedAt(Instant.now())
                .build());
    }

    private OrderRequest buyRequest(Long positionId, String coinPair) {
        OrderRequest request = new OrderRequest();
        request.setCoinPair(coinPair);
        request.setSide("BUY");
        request.setOrderType("MARKET");
        request.setQuantity(new BigDecimal("8000"));
        request.setPositionId(positionId);
        return request;
    }

    /** 거래소 클라이언트 유무와 무관하게 주문 행 자체는 남는다(미등록이면 FAILED로 전이). */
    private boolean awaitOrderCount(long expected) {
        Instant deadline = Instant.now().plus(ASYNC_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (orderRepository.count() >= expected) return true;
            sleep(100);
        }
        return false;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }
}
