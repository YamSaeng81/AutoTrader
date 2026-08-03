package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
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
 * 2026-08-03 P0 회귀 테스트 — <b>매수 트랜잭션 롤백으로 인한 동적 세션 KRW 누수</b>.
 *
 * <p><b>사고 재현</b>: 운영 세션 39·40·44가 포지션·주문·신호로그 0건인 채 {@code available_krw}만
 * 10,000 → 2,000으로 줄어 3일간 방치됐다. 원인은 KRW 차감이 {@code REQUIRES_NEW}(선커밋)인데
 * 부모 tx가 롤백되면 차감만 살아남는 구조. 결과적으로 투자가능액이 최소주문 5,000원 미만이 되어
 * 세 세션이 <b>영구 매수 불능</b>이 됐다.</p>
 *
 * <p>두 겹의 방어를 각각 잠근다: ① 롤백 보상(즉시), ② 세션 기준 reconcile(사후 안전망).</p>
 */
class DynamicBalanceLeakTest extends IntegrationTestBase {

    @Autowired
    private DynamicTradingService dynamicTradingService;

    @Autowired
    private DynamicSessionBalanceUpdater balanceUpdater;

    @Autowired
    private DynamicSessionRepository dynamicSessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    @AfterEach
    void cleanup() {
        orderRepository.deleteAll();
        positionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
    }

    private DynamicSessionEntity newRunningSession(BigDecimal available, BigDecimal total) {
        DynamicSessionEntity s = DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC")
                .timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(available)
                .totalAssetKrw(total)
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING")
                .scanState("SCANNING")
                .maxCandidateSize(30)
                .targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000"))
                .maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build();
        return dynamicSessionRepository.saveAndFlush(s);
    }

    /** {@code @PreUpdate}가 매번 now()로 덮으므로, 유예 시간 경과는 네이티브 UPDATE로만 만들 수 있다. */
    private void backdateUpdatedAt(Long sessionId, long minutesAgo) {
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE dynamic_session SET updated_at = :ts WHERE id = :id")
                        .setParameter("ts", Instant.now().minus(minutesAgo, ChronoUnit.MINUTES))
                        .setParameter("id", sessionId)
                        .executeUpdate());
        entityManager.clear();
    }

    @Test
    @DisplayName("① 매수 tx가 롤백되면 선커밋된 KRW 차감이 보상으로 복원된다")
    void buyDeduction_isCompensated_onParentRollback() {
        DynamicSessionEntity session = newRunningSession(new BigDecimal("10000.00"), new BigDecimal("10000.00"));
        Long sid = session.getId();
        BigDecimal deduct = new BigDecimal("8000.00");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            // executeBuy 와 동일한 순서: REQUIRES_NEW 차감(즉시 커밋) → 보상 등록
            balanceUpdater.apply(sid, s -> {
                s.setAvailableKrw(s.getAvailableKrw().subtract(deduct));
                s.setScanState("POSITION_MONITORING");
                s.setCurrentCoinPair("KRW-ETH");
                s.setCurrentPositionId(9999L);
            });
            dynamicTradingService.registerBuyDeductionCompensation(sid, deduct);

            // 차감은 부모 커밋 전에 이미 반영돼 있다 (누수의 전제 조건)
            assertThat(dynamicSessionRepository.findById(sid).orElseThrow().getAvailableKrw())
                    .isEqualByComparingTo("2000.00");

            status.setRollbackOnly();   // 부모 tx 롤백 — 포지션·주문·신호로그가 전부 사라지는 상황
            return null;
        });

        entityManager.clear();
        DynamicSessionEntity after = dynamicSessionRepository.findById(sid).orElseThrow();
        assertThat(after.getAvailableKrw())
                .as("롤백 보상이 없으면 2000.00으로 남아 영구 매수 불능이 된다")
                .isEqualByComparingTo("10000.00");
        assertThat(after.getScanState()).isEqualTo("SCANNING");
        assertThat(after.getCurrentPositionId()).isNull();
        assertThat(after.getCurrentCoinPair()).isNull();
    }

    @Test
    @DisplayName("② 포지션·주문 없이 KRW가 묶인 세션은 reconcile이 복원한다 (운영 39·40·44 상황)")
    void reconcile_restoresOrphanedBalance() {
        DynamicSessionEntity session = newRunningSession(new BigDecimal("2000.00"), new BigDecimal("10000.00"));
        Long sid = session.getId();
        backdateUpdatedAt(sid, 10);

        dynamicTradingService.reconcileDynamicSessionBalance();

        entityManager.clear();
        assertThat(dynamicSessionRepository.findById(sid).orElseThrow().getAvailableKrw())
                .as("포지션도 활성 주문도 없으므로 묶인 8,000원은 대응물 없는 누수다")
                .isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("② 보유 포지션이 있는 세션의 잔고는 reconcile이 건드리지 않는다")
    void reconcile_skipsSessionHoldingPosition() {
        DynamicSessionEntity session = newRunningSession(new BigDecimal("2000.00"), new BigDecimal("10000.00"));
        Long sid = session.getId();
        backdateUpdatedAt(sid, 10);

        positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-ETH")
                .side("BUY")
                .entryPrice(new BigDecimal("2700000"))
                .avgPrice(new BigDecimal("2700000"))
                .size(new BigDecimal("0.00296296"))
                .investedKrw(new BigDecimal("8000"))
                .status("OPEN")
                .sessionId(sid)
                .sessionKind("DYNAMIC")
                .build());

        dynamicTradingService.reconcileDynamicSessionBalance();

        entityManager.clear();
        assertThat(dynamicSessionRepository.findById(sid).orElseThrow().getAvailableKrw())
                .as("정상 보유 중 — 차액 8,000원은 코인으로 바뀐 것이지 누수가 아니다")
                .isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("② 방금 갱신된 세션은 유예 시간 안이라 reconcile이 건드리지 않는다 (매수 진행 중 오탐 방지)")
    void reconcile_respectsGracePeriod() {
        // updated_at = now (매수 차감 직후 ~ 포지션 커밋 전 구간을 모사)
        DynamicSessionEntity session = newRunningSession(new BigDecimal("2000.00"), new BigDecimal("10000.00"));
        Long sid = session.getId();

        dynamicTradingService.reconcileDynamicSessionBalance();

        entityManager.clear();
        assertThat(dynamicSessionRepository.findById(sid).orElseThrow().getAvailableKrw())
                .as("유예 없이 복원하면 진행 중인 정상 매수의 차감을 되돌려 이중 매수를 만든다")
                .isEqualByComparingTo("2000.00");
    }
}
