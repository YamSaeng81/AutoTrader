package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.RiskConfigRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.core.risk.RiskCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 실전매매 시스템 신뢰도 통합 테스트 — 6단계
 *
 * 검증 항목:
 * 1. size=0 고아 포지션이 리스크 카운팅에서 제외되는지
 * 2. closeIfOpen()의 원자적 멱등성 (이중 KRW 복원 방지)
 * 3. 리스크 체크: 고아 포지션이 maxPositions 한도를 차지하지 않는지
 * 4. reconcileOrphanBuyPositions(): FAILED 매수 후 KRW 복원 E2E
 *
 * @Transactional: 각 테스트 메서드가 하나의 트랜잭션 안에서 실행되고
 *                 테스트 완료 후 자동 롤백 → DB 오염 없음
 * @MockBean TradeLogRepository: PostgreSQL 전용 JSON 쿼리(detail_json->>'...')가
 *                               H2에서 실행되지 않으므로 mock으로 대체
 */
@Transactional
class LiveTradingReliabilityTest extends IntegrationTestBase {

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private RiskConfigRepository riskConfigRepository;

    @Autowired
    private RiskManagementService riskManagementService;

    @Autowired
    private LiveTradingService liveTradingService;

    /** PostgreSQL JSON 문법(detail_json->>'realizedPnl')이 H2에서 실행 불가 → mock 대체 */
    @MockBean
    private TradeLogRepository tradeLogRepository;

    @BeforeEach
    void stubTradeLog() {
        // 손익 합계는 0으로 고정 — 포지션 수 한도 검증에만 집중
        when(tradeLogRepository.sumRealizedPnlSince(any())).thenReturn(BigDecimal.ZERO);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 1. countRealPositionsByStatus: size=0 고아 포지션 제외 검증
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("countRealPositionsByStatus: size=0 고아 포지션은 카운팅에서 제외된다")
    void countRealPositions_excludesGhostPositions() {
        // given: size=0 고아 포지션 2개 + 실제 체결 포지션 2개
        savePosition("KRW-BTC", BigDecimal.ZERO, "OPEN");
        savePosition("KRW-ETH", BigDecimal.ZERO, "OPEN");
        savePosition("KRW-BTC", new BigDecimal("0.001"), "OPEN");
        savePosition("KRW-SOL", new BigDecimal("0.5"), "OPEN");

        // when
        long realCount = positionRepository.countRealPositionsByStatus("OPEN");
        long totalCount = positionRepository.countByStatus("OPEN");

        // then
        assertThat(realCount).isEqualTo(2); // size>0 만 카운팅
        assertThat(totalCount).isEqualTo(4); // 전체는 4개
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. closeIfOpen(): 원자적 멱등성 — 이중 KRW 복원 방지
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("closeIfOpen: 첫 번째 호출은 1 반환(성공), 두 번째 호출은 0 반환(이미 CLOSED)")
    void closeIfOpen_isIdempotent() {
        // given
        PositionEntity pos = savePosition("KRW-BTC", BigDecimal.ZERO, "OPEN");

        // when
        int firstResult = positionRepository.closeIfOpen(pos.getId(), Instant.now());
        int secondResult = positionRepository.closeIfOpen(pos.getId(), Instant.now());

        // then: 첫 호출만 성공 → 두 번째는 이미 CLOSED라 0
        assertThat(firstResult).isEqualTo(1);
        assertThat(secondResult).isEqualTo(0);

        PositionEntity closed = positionRepository.findById(pos.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getClosedAt()).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. 리스크 체크: maxPositions=2일 때 고아 포지션은 한도를 차지하지 않는다
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("리스크 체크: size=0 고아 포지션 2개는 maxPositions=2 한도를 차지하지 않는다")
    void riskCheck_ghostPositionsDoNotConsumeSlots() {
        // given: maxPositions = 2
        riskConfigRepository.save(buildRiskConfig(2));
        // size=0 고아 포지션 2개 (FAILED 매수 직후 상태)
        savePosition("KRW-BTC", BigDecimal.ZERO, "OPEN");
        savePosition("KRW-ETH", BigDecimal.ZERO, "OPEN");

        // when
        RiskCheckResult result = riskManagementService.checkRisk();

        // then: 고아 포지션은 카운팅 안 되므로 승인
        assertThat(result.isApproved())
                .as("size=0 고아 포지션 2개는 maxPositions 한도를 차지하지 않아야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("리스크 체크: size>0 실 포지션이 maxPositions=2에 도달하면 차단된다")
    void riskCheck_realPositionsBlockNewBuys() {
        // given: maxPositions = 2
        riskConfigRepository.save(buildRiskConfig(2));
        // 실제 체결된 포지션 2개
        savePosition("KRW-BTC", new BigDecimal("0.001"), "OPEN");
        savePosition("KRW-ETH", new BigDecimal("0.01"), "OPEN");

        // when
        RiskCheckResult result = riskManagementService.checkRisk();

        // then: 한도 도달 → 차단
        assertThat(result.isApproved())
                .as("size>0 포지션 2개가 maxPositions=2 한도에 도달하면 차단되어야 한다")
                .isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. reconcileOrphanBuyPositions(): FAILED 매수 후 KRW 복원 E2E
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reconcileOrphanBuyPositions: FAILED 매수 후 30초 reconcile 시 KRW 복원 + 포지션 CLOSED")
    void reconcile_failedBuy_restoresKrw() {
        // given: 세션 초기 자금 100,000 KRW
        BigDecimal initialKrw = new BigDecimal("100000");
        BigDecimal investAmount = new BigDecimal("50000");

        LiveTradingSessionEntity session = sessionRepository.save(buildSession(initialKrw));

        // 매수 시도 직후 상태: 포지션 OPEN(size=0), KRW 차감됨
        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-BTC")
                .side("LONG")
                .entryPrice(BigDecimal.ZERO)
                .avgPrice(BigDecimal.ZERO)
                .size(BigDecimal.ZERO)
                .status("OPEN")
                .sessionId(session.getId())
                .investedKrw(investAmount)
                .positionFee(BigDecimal.ZERO)
                .openedAt(Instant.now())
                .build());

        // FAILED 매수 주문 (quantity = 투자금액)
        orderRepository.save(OrderEntity.builder()
                .positionId(pos.getId())
                .sessionId(session.getId())
                .coinPair("KRW-BTC")
                .side("BUY")
                .orderType("MARKET")
                .quantity(investAmount)
                .state("FAILED")
                .failedReason("잔액 부족")
                .build());

        // 세션 KRW를 차감된 상태로 세팅 (매수 시도 시 차감된 것처럼)
        session.setAvailableKrw(initialKrw.subtract(investAmount));
        sessionRepository.save(session);

        // when: reconcile 실행
        liveTradingService.reconcileOrphanBuyPositions();

        // then: KRW 복원 확인
        LiveTradingSessionEntity updatedSession = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(updatedSession.getAvailableKrw())
                .as("FAILED 매수 후 KRW가 복원되어야 한다")
                .isEqualByComparingTo(initialKrw);

        // then: 포지션 CLOSED 확인
        PositionEntity closedPos = positionRepository.findById(pos.getId()).orElseThrow();
        assertThat(closedPos.getStatus())
                .as("고아 포지션이 CLOSED로 정리되어야 한다")
                .isEqualTo("CLOSED");
        assertThat(closedPos.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("reconcileOrphanBuyPositions: closeIfOpen 원자성으로 이중 KRW 복원이 방지된다")
    void reconcile_alreadyClosedPosition_skipsKrwRestore() {
        // given: 세션 availableKrw = initialKrw (이미 다른 경로에서 복원 완료 시뮬레이션)
        BigDecimal initialKrw = new BigDecimal("100000");
        LiveTradingSessionEntity session = sessionRepository.save(buildSession(initialKrw));

        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-BTC")
                .side("LONG")
                .entryPrice(BigDecimal.ZERO)
                .avgPrice(BigDecimal.ZERO)
                .size(BigDecimal.ZERO)
                .status("OPEN")
                .sessionId(session.getId())
                .investedKrw(new BigDecimal("50000"))
                .positionFee(BigDecimal.ZERO)
                .openedAt(Instant.now())
                .build());

        orderRepository.save(OrderEntity.builder()
                .positionId(pos.getId())
                .sessionId(session.getId())
                .coinPair("KRW-BTC")
                .side("BUY")
                .orderType("MARKET")
                .quantity(new BigDecimal("50000"))
                .state("FAILED")
                .failedReason("거래소 오류")
                .build());

        // 다른 경로(executeSessionSell)가 먼저 CLOSED 처리 → reconcile은 closeIfOpen() 반환값 0 보고 KRW 복원 스킵
        positionRepository.closeIfOpen(pos.getId(), Instant.now());
        // KRW는 이미 복원된 상태로 세팅
        session.setAvailableKrw(initialKrw);
        sessionRepository.save(session);

        // when: reconcile 재실행 (이미 CLOSED인 포지션 처리 시도)
        liveTradingService.reconcileOrphanBuyPositions();

        // then: KRW가 추가로 더해지지 않아야 함 (이중 복원 방지)
        LiveTradingSessionEntity updatedSession = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(updatedSession.getAvailableKrw())
                .as("이미 처리된 포지션에 대해 KRW가 이중 복원되면 안 된다")
                .isEqualByComparingTo(initialKrw);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────

    private PositionEntity savePosition(String coinPair, BigDecimal size, String status) {
        return positionRepository.save(PositionEntity.builder()
                .coinPair(coinPair)
                .side("LONG")
                .entryPrice(new BigDecimal("50000000"))
                .avgPrice(new BigDecimal("50000000"))
                .size(size)
                .status(status)
                .positionFee(BigDecimal.ZERO)
                .openedAt(Instant.now())
                .build());
    }

    private RiskConfigEntity buildRiskConfig(int maxPositions) {
        return RiskConfigEntity.builder()
                .maxDailyLossPct(new BigDecimal("3.0"))
                .maxWeeklyLossPct(new BigDecimal("7.0"))
                .maxMonthlyLossPct(new BigDecimal("15.0"))
                .maxPositions(maxPositions)
                .cooldownMinutes(60)
                .circuitBreakerEnabled(true)
                .consecutiveLossLimit(5)
                .build();
    }

    private LiveTradingSessionEntity buildSession(BigDecimal initialKrw) {
        return LiveTradingSessionEntity.builder()
                .strategyType("EMA_CROSS")
                .coinPair("KRW-BTC")
                .timeframe("1h")
                .initialCapital(initialKrw)
                .availableKrw(initialKrw)
                .totalAssetKrw(initialKrw)
                .status("RUNNING")
                .investRatio(new BigDecimal("0.8000"))
                .build();
    }
}
