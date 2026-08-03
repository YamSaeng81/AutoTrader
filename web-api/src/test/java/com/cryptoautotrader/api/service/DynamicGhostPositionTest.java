package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-07-31 P0 회귀 테스트 — <b>매도 후처리 롤백으로 생긴 유령 포지션</b>.
 *
 * <p><b>사고 재현</b>: time stop이 세션 38 KRW-RLUSD를 잡아 매도 8610이 FILLED됐는데
 * 후처리 tx가 롤백돼 포지션이 {@code OPEN}으로 되돌아갔다. 코인은 팔렸는데 DB는 보유 중이라
 * 매 틱 매도가 재발동해 8611·8612·8613이 <b>69초 간격 FAILED</b>(업비트 HTTP 400)를 반복했고,
 * 결국 운영 DB를 손으로 UPDATE해 정리해야 했다.</p>
 *
 * <p>두 결함을 각각 잠근다: ① CLOSING reconcile이 최신 FAILED에 가려 FILLED를 놓치는 문제,
 * ② 애초에 CLOSING이 아니라 OPEN이라 어떤 그물에도 걸리지 않던 문제.</p>
 */
class DynamicGhostPositionTest extends IntegrationTestBase {

    @Autowired
    private DynamicTradingService dynamicTradingService;

    @Autowired
    private DynamicSessionRepository dynamicSessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    @AfterEach
    void cleanup() {
        orderRepository.deleteAll();
        positionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
    }

    /** 매수 직후 상태: 8,000원이 코인으로 나가 available 2,000 / total 10,000. */
    private DynamicSessionEntity holdingSession() {
        return dynamicSessionRepository.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC")
                .timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("2000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING")
                .scanState("POSITION_MONITORING")
                .currentCoinPair("KRW-RLUSD")
                .maxCandidateSize(30)
                .targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.3000"))
                .maxSpreadPct(new BigDecimal("0.1500"))
                .watchlistRefreshMin(60)
                .build());
    }

    private PositionEntity position(Long sessionId, String status, Instant closingAt) {
        return positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-RLUSD")
                .side("BUY")
                .entryPrice(new BigDecimal("1428"))
                .avgPrice(new BigDecimal("1428"))
                .size(new BigDecimal("5.60224089"))
                .investedKrw(new BigDecimal("8000"))
                .status(status)
                .closingAt(closingAt)
                .sessionId(sessionId)
                .sessionKind("DYNAMIC")
                .build());
    }

    private OrderEntity sellOrder(Long sessionId, Long positionId, String state, Instant createdAt) {
        // createdAt 은 @PrePersist 가 **null일 때만** 채우므로 빌더로 직접 주면 그대로 유지된다
        // (reconcile 이 createdAt DESC 로 정렬하므로 순서 재현에 필요)
        OrderEntity o = OrderEntity.builder()
                .coinPair("KRW-RLUSD")
                .side("SELL")
                .orderType("MARKET")
                .quantity(new BigDecimal("5.60224089"))
                .state(state)
                .sessionId(sessionId)
                .sessionKind("DYNAMIC")
                .positionId(positionId)
                .createdAt(createdAt)
                .build();
        if ("FILLED".equals(state)) {
            o.setPrice(new BigDecimal("1417"));
            o.setFilledQuantity(new BigDecimal("5.60224089"));
            o.setFilledAt(createdAt);
        }
        return orderRepository.saveAndFlush(o);
    }

    @Test
    @DisplayName("① CLOSING 포지션: FAILED 재시도가 뒤에 쌓여도 FILLED 주문으로 정산한다")
    void closingReconcile_prefersFilledOverLatestFailed() {
        DynamicSessionEntity session = holdingSession();
        PositionEntity pos = position(session.getId(), "CLOSING", Instant.now().minus(10, ChronoUnit.MINUTES));

        Instant base = Instant.now().minus(10, ChronoUnit.MINUTES);
        sellOrder(session.getId(), pos.getId(), "FILLED", base);
        // 유령 포지션 상태에서 매 틱 재발동해 쌓인 실패 주문들 (운영 8611~8613)
        sellOrder(session.getId(), pos.getId(), "FAILED", base.plus(1, ChronoUnit.MINUTES));
        sellOrder(session.getId(), pos.getId(), "FAILED", base.plus(2, ChronoUnit.MINUTES));

        dynamicTradingService.reconcileDynamicClosingPositions();

        PositionEntity after = positionRepository.findById(pos.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("최신 FAILED를 보면 OPEN 롤백 — 이미 판 코인을 보유 중으로 되살린다")
                .isEqualTo("CLOSED");
        assertThat(after.getRealizedPnl()).isNotNull().isNotEqualByComparingTo("0");

        // 매도대금이 KRW로 복원됐는지 (proceeds 7938.375 - fee 3.97 ≈ 7934.4)
        assertThat(dynamicSessionRepository.findById(session.getId()).orElseThrow().getAvailableKrw())
                .isGreaterThan(new BigDecimal("9900.00"));
    }

    @Test
    @DisplayName("② 유령 포지션: 매도 FILLED인데 OPEN으로 남은 포지션을 자동 정산한다 (07-31 P0)")
    void ghostReconcile_finalizesOpenPositionWithFilledSell() {
        DynamicSessionEntity session = holdingSession();
        PositionEntity pos = position(session.getId(), "OPEN", null);   // 롤백으로 CLOSING이 사라진 상태
        // 운영 세션 38과 동일하게 세션이 이 포지션을 계속 보고 있는 상태를 재현
        session.setCurrentPositionId(pos.getId());
        dynamicSessionRepository.saveAndFlush(session);
        sellOrder(session.getId(), pos.getId(), "FILLED", Instant.now().minus(10, ChronoUnit.MINUTES));

        dynamicTradingService.reconcileDynamicGhostPositions();

        PositionEntity after = positionRepository.findById(pos.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("코인은 이미 팔렸다 — OPEN으로 두면 매도 재시도 루프가 계속된다")
                .isEqualTo("CLOSED");

        DynamicSessionEntity afterSession =
                dynamicSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(afterSession.getAvailableKrw()).isGreaterThan(new BigDecimal("9900.00"));
        assertThat(afterSession.getScanState())
                .as("세션도 POSITION_MONITORING 고착에서 풀려야 한다")
                .isEqualTo("SCANNING");
    }

    @Test
    @DisplayName("② 방금 체결된 매도는 유예 시간 안이라 건드리지 않는다 (정상 경로 침범 방지)")
    void ghostReconcile_respectsGracePeriod() {
        DynamicSessionEntity session = holdingSession();
        PositionEntity pos = position(session.getId(), "OPEN", null);
        sellOrder(session.getId(), pos.getId(), "FILLED", Instant.now());   // 방금 체결

        dynamicTradingService.reconcileDynamicGhostPositions();

        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus())
                .as("정상 매도 후처리가 진행 중일 수 있는 구간")
                .isEqualTo("OPEN");
    }

    @Test
    @DisplayName("② 부분 체결로 정산된 포지션을 다시 정산하지 않는다 (KRW 이중 복원 방지)")
    void ghostReconcile_doesNotDoubleFinalizePartialFill() {
        DynamicSessionEntity session = holdingSession();
        PositionEntity pos = position(session.getId(), "OPEN", null);
        // 부분 체결 정산 후 상태: 잔여 수량만 남고 realizedPnl 이 이미 쌓여 있다
        pos.setSize(new BigDecimal("2.00000000"));
        pos.setRealizedPnl(new BigDecimal("-30.00000000"));
        positionRepository.saveAndFlush(pos);
        sellOrder(session.getId(), pos.getId(), "FILLED", Instant.now().minus(10, ChronoUnit.MINUTES));

        BigDecimal before = dynamicSessionRepository.findById(session.getId())
                .orElseThrow().getAvailableKrw();

        dynamicTradingService.reconcileDynamicGhostPositions();

        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus())
                .as("이미 정산된 건을 다시 정산하면 매도대금이 두 번 들어온다")
                .isEqualTo("OPEN");
        assertThat(dynamicSessionRepository.findById(session.getId()).orElseThrow().getAvailableKrw())
                .isEqualByComparingTo(before);
    }
}
