package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-18 회귀 테스트 — <b>LIVE 중복 매도</b>.
 *
 * <p><b>사고 재현</b>: time stop 최초 발동으로 LIVE 세션 198(KRW-XRP, 259시간 보유)이
 * SELL 8724를 내 08:55:59에 FILLED됐는데, 60초 뒤 다음 틱이 같은 포지션에 SELL 8725를
 * 다시 제출했다. 업비트가 {@code insufficient_funds_ask}(HTTP 400)로 거절해 실피해는
 * 없었지만, 같은 계정을 쓰는 DYNAMIC 48도 그때 XRP를 들고 있었으므로 타이밍이 어긋났다면
 * <b>다른 세션 포지션의 코인을 팔 수 있었다</b>.</p>
 *
 * <p>원인은 DYNAMIC이 07-31/08-03에 받은 방어 두 가지가 LIVE에 이식되지 않은 것이었다:
 * ① 원자적 CLOSING 전환({@code markClosingIfOpen}) 대신 {@code setStatus + save} 사용,
 * ② CLOSING reconcile이 {@code sellOrders.get(0)}(최신순)만 봐서 FAILED 재시도에 가려
 * FILLED를 놓치고 OPEN 롤백 분기를 타는 문제.</p>
 *
 * @see DynamicGhostPositionTest 같은 사고의 DYNAMIC 판(07-31 P0)
 */
class LiveSellIdempotencyTest extends IntegrationTestBase {

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

    /** {@code @Modifying} 쿼리는 활성 트랜잭션을 요구한다 — LiveTradingReliabilityTest와 동일 패턴. */
    private TransactionTemplate tx;

    @BeforeEach
    void initTx() {
        tx = new TransactionTemplate(txManager);
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        orderRepository.deleteAll();
        positionRepository.deleteAll();
        sessionRepository.deleteAll();
    }

    /** 매수 직후 상태: 8,000원이 코인으로 나가 available 2,000 / total 10,000 (운영 198 재현). */
    private LiveTradingSessionEntity holdingSession() {
        return sessionRepository.saveAndFlush(LiveTradingSessionEntity.builder()
                .strategyType("COMPOSITE_MEANREV_BB")
                .coinPair("KRW-XRP")
                .timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("2000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING")
                .build());
    }

    private PositionEntity position(Long sessionId, String status, Instant closingAt) {
        return positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-XRP")
                .side("BUY")
                .entryPrice(new BigDecimal("1442"))
                .avgPrice(new BigDecimal("1442"))
                .size(new BigDecimal("5.54785020"))
                .investedKrw(new BigDecimal("8000"))
                .status(status)
                .closingAt(closingAt)
                .sessionId(sessionId)
                .sessionKind("LIVE")
                .build());
    }

    private OrderEntity sellOrder(Long sessionId, Long positionId, String state, Instant createdAt) {
        // createdAt 은 @PrePersist 가 null일 때만 채우므로 빌더 값이 그대로 유지된다
        // (reconcile 이 createdAt DESC 로 정렬하므로 순서 재현에 필요)
        OrderEntity o = OrderEntity.builder()
                .coinPair("KRW-XRP")
                .side("SELL")
                .orderType("MARKET")
                .quantity(new BigDecimal("5.54785020"))
                .state(state)
                .sessionId(sessionId)
                .sessionKind("LIVE")
                .positionId(positionId)
                .createdAt(createdAt)
                .build();
        if ("FILLED".equals(state)) {
            o.setPrice(new BigDecimal("1415"));
            o.setFilledQuantity(new BigDecimal("5.54785020"));
            o.setFilledAt(createdAt);
        }
        return orderRepository.saveAndFlush(o);
    }

    /** {@code @Modifying} 쿼리라 트랜잭션 안에서 실행해야 한다. */
    private int markClosing(Long positionId) {
        Integer affected = tx.execute(s -> positionRepository.markClosingIfOpen(positionId, Instant.now()));
        return affected == null ? 0 : affected;
    }

    @Test
    @DisplayName("markClosingIfOpen: OPEN일 때만 1 반환 — 두 번째 매도 시도는 주문을 내지 않는다")
    void markClosingIfOpen_isAtomicGuard() {
        LiveTradingSessionEntity session = holdingSession();
        PositionEntity pos = position(session.getId(), "OPEN", null);

        int first = markClosing(pos.getId());
        int second = markClosing(pos.getId());

        assertThat(first)
                .as("첫 매도 경로만 주문 제출 권한을 얻는다")
                .isEqualTo(1);
        assertThat(second)
                .as("이미 CLOSING — 여기서 0이 아니면 시장가 매도가 두 번 나간다 (운영 8724/8725)")
                .isZero();

        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus())
                .isEqualTo("CLOSING");
    }

    @Test
    @DisplayName("CLOSING reconcile: FAILED 재시도가 뒤에 쌓여도 FILLED 주문으로 정산한다")
    void closingReconcile_prefersFilledOverLatestFailed() {
        LiveTradingSessionEntity session = holdingSession();
        PositionEntity pos = position(session.getId(), "CLOSING",
                Instant.now().minus(10, ChronoUnit.MINUTES));

        Instant base = Instant.now().minus(10, ChronoUnit.MINUTES);
        sellOrder(session.getId(), pos.getId(), "FILLED", base);              // 운영 8724
        sellOrder(session.getId(), pos.getId(), "FAILED", base.plusSeconds(60)); // 운영 8725

        liveTradingService.reconcileClosingPositions();

        PositionEntity after = positionRepository.findById(pos.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("최신 FAILED를 보면 OPEN 롤백 — 이미 판 코인을 보유 중으로 되살려 재매도 루프가 된다")
                .isEqualTo("CLOSED");
        assertThat(after.getRealizedPnl()).isNotNull().isNotEqualByComparingTo("0");

        assertThat(sessionRepository.findById(session.getId()).orElseThrow().getAvailableKrw())
                .as("매도대금이 세션 KRW로 복원돼야 한다")
                .isGreaterThan(new BigDecimal("9000.00"));
    }

    @Test
    @DisplayName("CLOSING reconcile: 체결이 하나도 없이 FAILED만 있으면 기존대로 OPEN 롤백한다")
    void closingReconcile_rollsBackWhenOnlyFailed() {
        LiveTradingSessionEntity session = holdingSession();
        PositionEntity pos = position(session.getId(), "CLOSING",
                Instant.now().minus(10, ChronoUnit.MINUTES));
        sellOrder(session.getId(), pos.getId(), "FAILED", Instant.now().minus(9, ChronoUnit.MINUTES));

        liveTradingService.reconcileClosingPositions();

        PositionEntity after = positionRepository.findById(pos.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("코인이 안 팔렸으므로 되살려 다음 틱에 재시도하는 것이 맞다")
                .isEqualTo("OPEN");
        assertThat(after.getClosingAt()).isNull();
    }
}
