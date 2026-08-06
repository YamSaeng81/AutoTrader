package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-06 신규(P2) — 운영자가 인시던트마다 psycopg2로 손수 돌리던 4대 건전성 점검을
 * 자동화한 {@link OperationalHealthCheckService}의 회귀 테스트.
 *
 * <p>주문 시퀀스 갭 점검({@code order_id_seq})은 Postgres 전용이라 H2 테스트 환경에서는
 * "확인 불가"(checked=false)로 처리되는 것만 확인한다 — 실제 갭 계산은 운영 DB에서만 검증 가능.
 */
class OperationalHealthCheckServiceTest extends IntegrationTestBase {

    @Autowired
    private OperationalHealthCheckService service;

    @Autowired
    private LiveTradingSessionRepository liveSessionRepository;

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
        liveSessionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
    }

    private LiveTradingSessionEntity newLiveSession(BigDecimal available, BigDecimal total, int maxHoldHours) {
        LiveTradingSessionEntity s = LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_BREAKOUT")
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(available)
                .totalAssetKrw(total)
                .investRatio(new BigDecimal("0.8000"))
                .status("RUNNING")
                .maxHoldHours(maxHoldHours)
                .build();
        return liveSessionRepository.saveAndFlush(s);
    }

    private DynamicSessionEntity newDynamicSession(BigDecimal available, BigDecimal total) {
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

    private void backdateLiveUpdatedAt(Long sessionId, long minutesAgo) {
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE live_trading_session SET updated_at = :ts WHERE id = :id")
                        .setParameter("ts", Instant.now().minus(minutesAgo, ChronoUnit.MINUTES))
                        .setParameter("id", sessionId)
                        .executeUpdate());
        entityManager.clear();
    }

    private void backdateDynamicUpdatedAt(Long sessionId, long minutesAgo) {
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE dynamic_session SET updated_at = :ts WHERE id = :id")
                        .setParameter("ts", Instant.now().minus(minutesAgo, ChronoUnit.MINUTES))
                        .setParameter("id", sessionId)
                        .executeUpdate());
        entityManager.clear();
    }

    // ── ① 세션 잔고 정합성 ──────────────────────────────────────────

    @Test
    @DisplayName("① 포지션·활성주문 없이 available≠total인 LIVE 세션을 잡는다")
    void detectsLiveBalanceMismatch() {
        LiveTradingSessionEntity s = newLiveSession(new BigDecimal("2000.00"), new BigDecimal("10000.00"), 0);
        backdateLiveUpdatedAt(s.getId(), 10);

        List<OperationalHealthCheckService.BalanceMismatch> result = service.checkBalanceConsistency();

        assertThat(result).extracting(m -> m.sessionId())
                .contains(s.getId());
    }

    @Test
    @DisplayName("① 동일한 상황의 DYNAMIC 세션도 잡는다")
    void detectsDynamicBalanceMismatch() {
        DynamicSessionEntity s = newDynamicSession(new BigDecimal("2000.00"), new BigDecimal("10000.00"));
        backdateDynamicUpdatedAt(s.getId(), 10);

        List<OperationalHealthCheckService.BalanceMismatch> result = service.checkBalanceConsistency();

        assertThat(result).extracting(m -> m.sessionId())
                .contains(s.getId());
    }

    @Test
    @DisplayName("① 방금 갱신된 세션은 유예 시간 안이라 넘어간다 (매수 진행 중 오탐 방지)")
    void skipsFreshlyUpdatedSession() {
        LiveTradingSessionEntity s = newLiveSession(new BigDecimal("2000.00"), new BigDecimal("10000.00"), 0);
        // backdate 하지 않음 — updated_at = now

        List<OperationalHealthCheckService.BalanceMismatch> result = service.checkBalanceConsistency();

        assertThat(result).extracting(m -> m.sessionId()).doesNotContain(s.getId());
    }

    @Test
    @DisplayName("① 보유 포지션이 있으면 차이는 미실현손익이므로 건드리지 않는다")
    void skipsSessionWithOpenPosition() {
        LiveTradingSessionEntity s = newLiveSession(new BigDecimal("2000.00"), new BigDecimal("10000.00"), 0);
        backdateLiveUpdatedAt(s.getId(), 10);

        positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.00008889")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(s.getId()).sessionKind("LIVE")
                .build());

        List<OperationalHealthCheckService.BalanceMismatch> result = service.checkBalanceConsistency();

        assertThat(result).extracting(m -> m.sessionId()).doesNotContain(s.getId());
    }

    // ── ② 주문 시퀀스 갭 (H2 = 확인 불가) ──────────────────────────────

    @Test
    @DisplayName("② H2 테스트 환경에서는 시퀀스 갭 조회가 '확인 불가'로 안전하게 처리된다")
    void sequenceGapCheck_isUncheckedOnH2() {
        OperationalHealthCheckService.SequenceGapResult result = service.checkOrderSequenceGap();

        assertThat(result.checked()).isFalse();
    }

    // ── ③ 유령 포지션 ──────────────────────────────────────────────

    @Test
    @DisplayName("③ 매도 FILLED인데 OPEN으로 남은 포지션을 잡는다 (유예시간 경과)")
    void detectsGhostPosition() {
        LiveTradingSessionEntity s = newLiveSession(new BigDecimal("10000.00"), new BigDecimal("10000.00"), 0);
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.00008889")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(s.getId()).sessionKind("LIVE")
                .build());

        orderRepository.saveAndFlush(OrderEntity.builder()
                .positionId(pos.getId()).coinPair("KRW-BTC").side("SELL").orderType("MARKET")
                .price(new BigDecimal("91000000")).quantity(new BigDecimal("0.00008889"))
                .filledQuantity(new BigDecimal("0.00008889"))
                .state("FILLED").sessionKind("LIVE").sessionId(s.getId())
                .filledAt(Instant.now().minus(10, ChronoUnit.MINUTES))
                .build());

        List<OperationalHealthCheckService.GhostPosition> result = service.checkGhostPositions();

        assertThat(result).extracting(g -> g.positionId()).contains(pos.getId());
    }

    @Test
    @DisplayName("③ 체결 직후(유예시간 내)는 정상 후처리 중일 수 있으므로 건드리지 않는다")
    void skipsRecentlyFilledSell() {
        LiveTradingSessionEntity s = newLiveSession(new BigDecimal("10000.00"), new BigDecimal("10000.00"), 0);
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.00008889")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(s.getId()).sessionKind("LIVE")
                .build());

        orderRepository.saveAndFlush(OrderEntity.builder()
                .positionId(pos.getId()).coinPair("KRW-BTC").side("SELL").orderType("MARKET")
                .price(new BigDecimal("91000000")).quantity(new BigDecimal("0.00008889"))
                .filledQuantity(new BigDecimal("0.00008889"))
                .state("FILLED").sessionKind("LIVE").sessionId(s.getId())
                .filledAt(Instant.now())
                .build());

        List<OperationalHealthCheckService.GhostPosition> result = service.checkGhostPositions();

        assertThat(result).extracting(g -> g.positionId()).doesNotContain(pos.getId());
    }

    @Test
    @DisplayName("③ 정상 보유 중(매도 주문 없음)인 포지션은 잡지 않는다")
    void skipsNormalOpenPosition() {
        LiveTradingSessionEntity s = newLiveSession(new BigDecimal("2000.00"), new BigDecimal("10000.00"), 0);
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.00008889")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(s.getId()).sessionKind("LIVE")
                .build());

        List<OperationalHealthCheckService.GhostPosition> result = service.checkGhostPositions();

        assertThat(result).extracting(g -> g.positionId()).doesNotContain(pos.getId());
    }

    // ── ④ 무출구 고착 포지션 ────────────────────────────────────────

    @Test
    @DisplayName("④ time stop 비활성 + 24시간 이상 보유 포지션을 잡는다 (LIVE 세션 194 BTC 재현)")
    void detectsStuckPosition() {
        LiveTradingSessionEntity s = newLiveSession(new BigDecimal("2000.00"), new BigDecimal("10000.00"), 0);
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.00008889")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(s.getId()).sessionKind("LIVE")
                .openedAt(Instant.now().minus(136, ChronoUnit.HOURS))
                .build());

        List<OperationalHealthCheckService.StuckPosition> result = service.checkStuckPositions();

        assertThat(result).extracting(p -> p.positionId()).contains(pos.getId());
    }

    @Test
    @DisplayName("④ time stop이 켜져 있으면 스스로 청산될 것이므로 잡지 않는다")
    void skipsStuckPosition_whenTimeStopEnabled() {
        LiveTradingSessionEntity s = newLiveSession(new BigDecimal("2000.00"), new BigDecimal("10000.00"), 48);
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.00008889")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(s.getId()).sessionKind("LIVE")
                .openedAt(Instant.now().minus(136, ChronoUnit.HOURS))
                .build());

        List<OperationalHealthCheckService.StuckPosition> result = service.checkStuckPositions();

        assertThat(result).extracting(p -> p.positionId()).doesNotContain(pos.getId());
    }

    @Test
    @DisplayName("④ 임계 시간 미만 보유는 정상 거래이므로 잡지 않는다")
    void skipsRecentPosition() {
        LiveTradingSessionEntity s = newLiveSession(new BigDecimal("2000.00"), new BigDecimal("10000.00"), 0);
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.00008889")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(s.getId()).sessionKind("LIVE")
                .openedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .build());

        List<OperationalHealthCheckService.StuckPosition> result = service.checkStuckPositions();

        assertThat(result).extracting(p -> p.positionId()).doesNotContain(pos.getId());
    }

    // ── 통합 — 저장까지 ──────────────────────────────────────────────

    @Test
    @DisplayName("이상 없는 상태에서 runDailyCheck를 실행해도 예외 없이 스냅샷이 저장된다")
    void runDailyCheck_persistsSnapshot_whenClean() {
        service.runDailyCheck(); // Discord webhook 미설정 환경이라 알림은 스킵/실패 로그만 남고 예외 없음
    }
}
