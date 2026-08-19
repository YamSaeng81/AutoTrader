package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.DynamicSellSettlementEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.repository.DynamicSellSettlementRepository;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-19 P0 회귀 테스트 — <b>정지 실패 루프에 의한 매도대금 중복 지급</b>.
 *
 * <p><b>사고 재현</b>: 운영 동적 세션 49(PAPER)가 정지도 삭제도 되지 않았고, 그 사이
 * {@code available_krw}가 10,000 → 174,752로 불어났다. 초기자본의 17배다.</p>
 *
 * <p><b>원인</b>: {@code stopSession()}이 세션을 version N으로 읽은 뒤
 * {@code closeOpenPositions()}를 호출하면, 그 안의 {@code finalizeDynamicSell}(매도대금 반영)과
 * {@code transitionToScanning}(SCANNING 복귀)이 {@link DynamicSessionBalanceUpdater}
 * ({@code REQUIRES_NEW})로 세션을 두 번 <b>선커밋</b>한다. 이어서 낡은 엔티티를 그대로
 * {@code save()}하면 {@code @Version}이 어긋나 낙관적 락 예외가 나고 <b>바깥 트랜잭션만</b>
 * 롤백된다. 별도 트랜잭션으로 커밋된 매도대금은 살아남고 포지션은 OPEN으로 되돌아오므로,
 * 정지를 누를 때마다 대금이 다시 지급되는 무한 증식 루프가 된다.</p>
 *
 * <p>{@link DynamicBalanceLeakTest}가 잠근 <b>매수측</b> 선커밋 누수의 매도측 대칭 사고다.</p>
 */
class DynamicStopOptimisticLockTest extends IntegrationTestBase {

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private DynamicSessionRepository dynamicSessionRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private DynamicSellSettlementRepository settlementRepository;
    @Autowired private DynamicSessionBalanceUpdater balanceUpdater;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private static final BigDecimal AVG_PRICE = new BigDecimal("94.24122062");
    private static final BigDecimal SIZE      = new BigDecimal("83.46343509");

    @BeforeEach
    @AfterEach
    void cleanup() {
        settlementRepository.deleteAll();
        orderRepository.deleteAll();
        positionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
    }

    @Test
    @DisplayName("보유 포지션이 있는 PAPER 세션의 정지가 성공하고, 매도대금은 정확히 한 번만 지급된다")
    void stopSession_withOpenPosition_succeedsAndCreditsOnce() {
        DynamicSessionEntity session = runningPaperSessionHoldingPosition();
        Long sid = session.getId();
        BigDecimal before = session.getAvailableKrw();

        dynamicTradingService.stopSession(sid);

        entityManager.clear();
        DynamicSessionEntity after = dynamicSessionRepository.findById(sid).orElseThrow();

        assertThat(after.getStatus())
                .as("낙관적 락 예외로 바깥 tx가 롤백되면 RUNNING 그대로 남아 정지도 삭제도 불가능해진다")
                .isEqualTo("STOPPED");
        assertThat(after.getStoppedAt()).isNotNull();

        PositionEntity pos = positionRepository.findAll().get(0);
        assertThat(pos.getStatus())
                .as("대금만 지급되고 포지션이 OPEN 으로 되돌아오면 다음 정지 시도에서 또 지급된다")
                .isEqualTo("CLOSED");
        assertThat(pos.getClosedAt()).isNotNull();

        // 매도대금은 대략 size × avgPrice 규모(슬리피지·수수료만큼 소폭 적다). 정확한 값보다
        // "한 번만 들어왔는가"가 핵심이므로 1회분 상한으로 잠근다.
        BigDecimal grossOnce = SIZE.multiply(AVG_PRICE);
        assertThat(after.getAvailableKrw())
                .as("정지 1회에 매도대금이 2회 이상 지급되면 여기서 걸린다 (운영 세션 49: 21회 중복)")
                .isLessThanOrEqualTo(before.add(grossOnce))
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("정지된 세션은 다시 정지되지 않는다 — 재시도로 대금이 또 지급될 여지를 없앤다")
    void stopSession_isNotRepeatable() {
        Long sid = runningPaperSessionHoldingPosition().getId();

        dynamicTradingService.stopSession(sid);
        entityManager.clear();
        BigDecimal afterFirstStop = dynamicSessionRepository.findById(sid).orElseThrow().getAvailableKrw();

        assertThatIllegalState(() -> dynamicTradingService.stopSession(sid));

        entityManager.clear();
        assertThat(dynamicSessionRepository.findById(sid).orElseThrow().getAvailableKrw())
                .as("두 번째 정지 시도가 잔고를 건드리면 안 된다")
                .isEqualByComparingTo(afterFirstStop);
    }

    @Test
    @DisplayName("비상 정지도 같은 경로를 쓴다 — 정지가 막혔을 때의 탈출구가 같은 버그로 막히면 안 된다")
    void emergencyStop_withOpenPosition_succeeds() {
        Long sid = runningPaperSessionHoldingPosition().getId();

        dynamicTradingService.emergencyStop(sid);

        entityManager.clear();
        assertThat(dynamicSessionRepository.findById(sid).orElseThrow().getStatus())
                .isEqualTo("EMERGENCY_STOPPED");
        assertThat(positionRepository.findAll().get(0).getStatus()).isEqualTo("CLOSED");
    }


    // ── 근본 원인: 선커밋된 매도대금의 멱등성 ────────────────────────────────────

    @Test
    @DisplayName("바깥 트랜잭션이 롤백돼도 매도대금은 정확히 한 번만 남는다 — 재시도해도 더 붙지 않는다")
    void settlementSurvivesRollbackButIsNeverCreditedTwice() {
        DynamicSessionEntity session = runningPaperSessionHoldingPosition();
        Long sid = session.getId();
        Long pid = positionRepository.findAll().get(0).getId();
        BigDecimal before = session.getAvailableKrw();
        BigDecimal proceeds = new BigDecimal("8038.00");

        // 1회차: 바깥 tx 가 롤백된다 (운영에서 낙관적 락 예외로 일어난 상황)
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        tx.execute(status -> {
            boolean credited = balanceUpdater.applySettlementOnce(
                    settlementOf(sid, pid, proceeds), s -> s.setAvailableKrw(s.getAvailableKrw().add(proceeds)));
            assertThat(credited).isTrue();
            status.setRollbackOnly();
            return null;
        });

        entityManager.clear();
        BigDecimal afterRollback = dynamicSessionRepository.findById(sid).orElseThrow().getAvailableKrw();
        assertThat(afterRollback)
                .as("REQUIRES_NEW 선커밋이므로 대금은 롤백에 딸려가지 않는다 — 이게 사고의 전제 조건")
                .isEqualByComparingTo(before.add(proceeds));

        // 2회차: 포지션이 OPEN 으로 되돌아온 뒤의 재시도. 여기서 또 지급되면 무한 증식이 시작된다.
        boolean creditedAgain = balanceUpdater.applySettlementOnce(
                settlementOf(sid, pid, proceeds), s -> s.setAvailableKrw(s.getAvailableKrw().add(proceeds)));

        entityManager.clear();
        assertThat(creditedAgain).as("이미 정산된 매도는 반영하지 않는다").isFalse();
        assertThat(dynamicSessionRepository.findById(sid).orElseThrow().getAvailableKrw())
                .as("재시도가 대금을 또 붙이면 운영 세션 49 와 같은 증식이 재발한다")
                .isEqualByComparingTo(before.add(proceeds));
    }

    @Test
    @DisplayName("21회 재시도해도 대금은 1회분만 남는다 — 운영 세션 49 시나리오 그대로")
    void twentyOneRetriesCreditOnlyOnce() {
        DynamicSessionEntity session = runningPaperSessionHoldingPosition();
        Long sid = session.getId();
        Long pid = positionRepository.findAll().get(0).getId();
        BigDecimal before = session.getAvailableKrw();
        BigDecimal proceeds = new BigDecimal("8038.00");

        for (int i = 0; i < 21; i++) {
            balanceUpdater.applySettlementOnce(
                    settlementOf(sid, pid, proceeds), s -> s.setAvailableKrw(s.getAvailableKrw().add(proceeds)));
        }

        entityManager.clear();
        assertThat(dynamicSessionRepository.findById(sid).orElseThrow().getAvailableKrw())
                .as("수정 전이라면 before + 8038×21 = 초기자본의 17배가 된다")
                .isEqualByComparingTo(before.add(proceeds));
        assertThat(settlementRepository.findByPositionId(pid))
                .as("정산 표식은 매도 1건당 1행")
                .hasSize(1);
    }

    @Test
    @DisplayName("멱등 키가 없으면 정산을 거부한다 — 보장 못 하는 채로 돈을 움직이지 않는다")
    void settlementWithoutKeyIsRejected() {
        DynamicSessionEntity session = runningPaperSessionHoldingPosition();
        DynamicSellSettlementEntity noKey = settlementOf(
                session.getId(), positionRepository.findAll().get(0).getId(), new BigDecimal("100"));
        noKey.setOrderRef(null);

        try {
            balanceUpdater.applySettlementOnce(noKey, s -> s.setAvailableKrw(s.getAvailableKrw().add(BigDecimal.TEN)));
            throw new AssertionError("키 없는 정산이 통과했다");
        } catch (IllegalArgumentException expected) {
            // 기대한 동작
        }
    }

    @Test
    @DisplayName("페이퍼 매도의 멱등 키는 포지션에서 결정된다 — 주문 행이 롤백돼도 같은 값이 나온다")
    void paperSellRefIsDerivedFromPosition() {
        DynamicSessionEntity session = runningPaperSessionHoldingPosition();
        Long sid = session.getId();

        dynamicTradingService.stopSession(sid);

        Long pid = positionRepository.findAll().get(0).getId();
        assertThat(settlementRepository.findByPositionId(pid))
                .singleElement()
                .satisfies(row -> assertThat(row.getOrderRef())
                        .as("order.id 기반이면 롤백 후 재시도마다 키가 바뀌어 멱등성이 깨진다")
                        .isEqualTo("PAPER-DYNAMIC-SELL-" + pid));
    }

    private DynamicSellSettlementEntity settlementOf(Long sessionId, Long positionId, BigDecimal proceeds) {
        return DynamicSellSettlementEntity.builder()
                .orderRef("PAPER-DYNAMIC-SELL-" + positionId)
                .positionId(positionId)
                .sessionId(sessionId)
                .sessionKind("DYN_PAPER")
                .soldQty(SIZE)
                .netProceeds(proceeds)
                .realizedPnl(BigDecimal.ZERO)
                .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assertThatIllegalState(Runnable r) {
        try {
            r.run();
            throw new AssertionError("이미 정지된 세션의 재정지가 예외 없이 통과했다");
        } catch (IllegalStateException expected) {
            // 기대한 동작
        }
    }

    /** 운영 세션 49 + 포지션 2458 과 같은 형태 — PAPER, RUNNING, OPEN 포지션 보유. */
    private DynamicSessionEntity runningPaperSessionHoldingPosition() {
        DynamicSessionEntity s = dynamicSessionRepository.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MEANREV_BB")
                .timeframe("H1")
                .tradingMode("PAPER")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("1966.42"))
                .totalAssetKrw(new BigDecimal("10012.30"))
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING")
                .scanState("POSITION_MONITORING")
                .currentCoinPair("KRW-CAP")
                .maxCandidateSize(30)
                .targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000"))
                .maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build());

        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-CAP")
                .side("BUY")
                .entryPrice(new BigDecimal("94.19410000"))
                .avgPrice(AVG_PRICE)
                .size(SIZE)
                .investedKrw(new BigDecimal("7865.69600000"))
                .unrealizedPnl(new BigDecimal("180.17914266"))
                .realizedPnl(BigDecimal.ZERO)
                .positionFee(BigDecimal.ZERO)
                .status("OPEN")
                .sessionId(s.getId())
                .sessionKind("DYN_PAPER")
                .build());

        s.setCurrentPositionId(pos.getId());
        s = dynamicSessionRepository.saveAndFlush(s);
        entityManager.clear();
        return s;
    }
}
