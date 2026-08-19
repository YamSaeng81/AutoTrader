package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSellSettlementRepository;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.core.risk.ExitRuleChecker.ExitType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V73 회귀 테스트 — <b>청산 사유가 집계 가능한 형태로 남는가</b>.
 *
 * <p>이전에는 청산 사유가 {@code order.signal_reason} 자유 텍스트에만 있었고 손익률이
 * 문자열 안에 박혀 있어 값이 전부 유일했다. 대시 문자도 {@code —} 와 {@code --} 가 섞여
 * {@code GROUP BY} 도 정규식도 쓸 수 없었다 — <b>"손절 대 익절 대 시간초과 비율"</b> 이라는
 * 가장 기본적인 질문에 답할 수 없었다.</p>
 *
 * <p>더 나빴던 건 {@code ExitRuleChecker.ExitType} 이 이미 SL/TP 를 구분하고 있었는데
 * 호출부가 {@code getReason()} 만 쓰고 <b>타입을 버리고 있었다</b>는 점이다.</p>
 */
class ExitReasonRecordingTest extends IntegrationTestBase {

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private DynamicSessionRepository dynamicSessionRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private DynamicSellSettlementRepository settlementRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    @BeforeEach
    @AfterEach
    void cleanup() {
        settlementRepository.deleteAll();
        orderRepository.deleteAll();
        positionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
    }

    @Test
    @DisplayName("익절 청산은 TAKE_PROFIT 으로 기록된다")
    void takeProfitIsRecorded() {
        assertExitReason(ExitReason.TAKE_PROFIT, "익절 — 현재가 도달");
    }

    @Test
    @DisplayName("손절 청산은 STOP_LOSS 로 기록된다")
    void stopLossIsRecorded() {
        assertExitReason(ExitReason.STOP_LOSS, "실시간 손절(WS) — pnl -3.5%");
    }

    @Test
    @DisplayName("시간 초과 청산은 TIME_STOP 으로 기록된다 — 전략 성과가 아니라 보유 한도의 결과다")
    void timeStopIsRecorded() {
        assertExitReason(ExitReason.TIME_STOP, "시간 초과 청산 — 보유 30시간 ≥ 24시간");
    }

    @Test
    @DisplayName("세션 정지 청산은 FORCED_STOP 으로 기록되고, 전략 성과 집계에서 빠진다")
    void forcedStopIsRecordedAndExcludedFromPerformance() {
        DynamicSessionEntity session = paperSessionWithOpenPosition();

        dynamicTradingService.stopSession(session.getId());

        entityManager.clear();
        PositionEntity pos = positionRepository.findAll().get(0);
        assertThat(pos.getStatus()).isEqualTo("CLOSED");
        assertThat(pos.getExitReason())
                .as("운영자가 끊은 청산은 청산가가 시장이 아니라 정지 시각으로 정해진다 — "
                        + "전략 성과로 세면 전략을 잘못 평가한다 (2026-08-18 일괄 정리가 그랬다)")
                .isEqualTo(ExitReason.FORCED_STOP);
        assertThat(pos.getExitReason().countsTowardStrategyPerformance()).isFalse();
    }

    @Test
    @DisplayName("ExitType 이 ExitReason 으로 손실 없이 옮겨진다 — 이 매핑이 깨지면 SL/TP 구분이 사라진다")
    void exitTypeMapsWithoutLoss() {
        assertThat(ExitReason.from(ExitType.STOP_LOSS)).isEqualTo(ExitReason.STOP_LOSS);
        assertThat(ExitReason.from(ExitType.TAKE_PROFIT)).isEqualTo(ExitReason.TAKE_PROFIT);
        assertThat(ExitReason.from(ExitType.NONE)).isEqualTo(ExitReason.UNKNOWN);
        assertThat(ExitReason.from(null)).isEqualTo(ExitReason.UNKNOWN);
    }

    @Test
    @DisplayName("정상 청산은 전부 성과 집계 대상이고, UNKNOWN·FORCED_STOP 만 빠진다")
    void onlyUnattributableExitsAreExcluded() {
        for (ExitReason r : ExitReason.values()) {
            boolean expected = r != ExitReason.FORCED_STOP && r != ExitReason.UNKNOWN;
            assertThat(r.countsTowardStrategyPerformance())
                    .as("%s 의 성과 집계 포함 여부", r)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("동적 세션 포지션에 진입 레짐이 남는다 — 그동안 0/36 이었다")
    void dynamicPositionsCarryEntryRegime() {
        // 진입 경로 전체를 태우는 대신, 컬럼이 매핑돼 실제로 저장·조회되는지를 잠근다.
        // (레짐 판정 자체는 MarketRegimeDetector 의 책임이고 별도 테스트가 있다.)
        DynamicSessionEntity session = paperSessionWithOpenPosition();
        PositionEntity pos = positionRepository.findAll().get(0);
        pos.setMarketRegime("TREND");
        positionRepository.saveAndFlush(pos);

        entityManager.clear();
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getMarketRegime())
                .isEqualTo("TREND");
        assertThat(session.getId()).isNotNull();
    }

    @Test
    @DisplayName("실거래 경로: CLOSING 전환이 청산 사유를 함께 남긴다 — 비동기 매도가 확정될 때까지 보존된다")
    void realPathPersistsReasonAtClosingTransition() {
        // 실거래는 매도가 비동기라 사유를 아는 시점(executeSell)과 CLOSED 확정 시점(reconcile)이
        // 다르다. 사유를 CLOSING 전환과 같은 UPDATE 로 남기지 않으면 reconcile 이 DB 에서
        // 새로 읽는 순간 사유가 사라진다. PAPER 경로는 동기라 이 구멍이 드러나지 않는다.
        paperSessionWithOpenPosition();
        PositionEntity pos = positionRepository.findAll().get(0);

        int marked = markClosing(pos.getId(), ExitReason.TIME_STOP);
        assertThat(marked).isEqualTo(1);

        entityManager.clear();
        PositionEntity reloaded = positionRepository.findById(pos.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("CLOSING");
        assertThat(reloaded.getExitReason())
                .as("CLOSING 전환과 사유 기록이 분리되면 reconcile 시점에 사유가 유실된다")
                .isEqualTo(ExitReason.TIME_STOP);
    }

    @Test
    @DisplayName("이미 CLOSING 인 포지션은 사유를 덮어쓰지 않는다 — 먼저 발동한 쪽이 진짜 원인이다")
    void closingTransitionDoesNotOverwriteEarlierReason() {
        paperSessionWithOpenPosition();
        PositionEntity pos = positionRepository.findAll().get(0);

        markClosing(pos.getId(), ExitReason.STOP_LOSS);
        int second = markClosing(pos.getId(), ExitReason.STRATEGY_SIGNAL);

        entityManager.clear();
        assertThat(second).as("두 번째 전환은 거부되어야 한다 (시장가 이중 매도 방지)").isZero();
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getExitReason())
                .as("WS 손절과 틱 전략신호가 경합하면 먼저 발동한 손절이 실제 청산 원인이다")
                .isEqualTo(ExitReason.STOP_LOSS);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** {@code @Modifying} 쿼리라 트랜잭션 안에서만 돈다 — 프로덕션에서는 매도 경로가 열어준다. */
    private int markClosing(Long positionId, ExitReason reason) {
        Integer n = new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(st -> positionRepository.markClosingIfOpen(positionId, Instant.now(), reason));
        return n == null ? 0 : n;
    }

    /** 매도 경로를 태우고 포지션에 남은 청산 사유를 확인한다. */
    private void assertExitReason(ExitReason expected, String freeTextReason) {
        DynamicSessionEntity session = paperSessionWithOpenPosition();
        PositionEntity pos = positionRepository.findAll().get(0);

        dynamicTradingService.executeSell(session, pos, new BigDecimal("100.0"),
                freeTextReason, expected);

        entityManager.clear();
        PositionEntity reloaded = positionRepository.findById(pos.getId()).orElseThrow();
        assertThat(reloaded.getExitReason())
                .as("자유 텍스트만 남으면 손익률이 박혀 있어 GROUP BY 가 불가능하다")
                .isEqualTo(expected);
    }

    private DynamicSessionEntity paperSessionWithOpenPosition() {
        DynamicSessionEntity s = dynamicSessionRepository.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MEANREV_BB")
                .timeframe("H1")
                .tradingMode("PAPER")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("2000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
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
                .entryPrice(new BigDecimal("94.00"))
                .avgPrice(new BigDecimal("94.00"))
                .size(new BigDecimal("80.00000000"))
                .investedKrw(new BigDecimal("7520.00"))
                .unrealizedPnl(BigDecimal.ZERO)
                .realizedPnl(BigDecimal.ZERO)
                .positionFee(BigDecimal.ZERO)
                .status("OPEN")
                .openedAt(Instant.now())
                .sessionId(s.getId())
                .sessionKind("DYN_PAPER")
                .build());

        s.setCurrentPositionId(pos.getId());
        s = dynamicSessionRepository.saveAndFlush(s);
        entityManager.clear();
        return s;
    }
}
