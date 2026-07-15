package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.RiskConfigRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 동적 세션 서킷 브레이커 회귀 테스트 (2026-07-15).
 *
 * <p>진입 2차 완화로 동적 세션이 실제 매매를 시작하면서, 라이브 전용이던 서킷 브레이커
 * (연속 손실 한도·MDD)를 동적 세션에도 적용했다. 검증 포인트:</p>
 * <ol>
 *   <li>이번 가동(startedAt) 이후 연속 손실이 한도에 도달하면 발동</li>
 *   <li>가동 이전(직전 가동분) 손실은 집계하지 않음 — 재시작 시 즉시 재발동 방지</li>
 *   <li>같은 sessionId의 LIVE 포지션 손실은 DYNAMIC 집계에 섞이지 않음 (kind 격리)</li>
 *   <li>MDD 임계 초과 시 발동</li>
 * </ol>
 */
class DynamicCircuitBreakerTest extends IntegrationTestBase {

    @Autowired
    private RiskManagementService riskManagementService;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private DynamicSessionRepository dynamicSessionRepository;

    @Autowired
    private RiskConfigRepository riskConfigRepository;

    @MockBean
    private TradeLogRepository tradeLogRepository;

    @BeforeEach
    void setUp() {
        when(tradeLogRepository.sumRealizedPnlSince(any())).thenReturn(BigDecimal.ZERO);
        when(tradeLogRepository.sumRealizedLossSince(any())).thenReturn(BigDecimal.ZERO);
        positionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
        riskConfigRepository.deleteAll(); // getRiskConfig()가 기본값(CB on, 연속손실 5회) 재생성
    }

    @AfterEach
    void tearDown() {
        positionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
        riskConfigRepository.deleteAll();
    }

    private DynamicSessionEntity saveSession(Instant startedAt) {
        DynamicSessionEntity session = DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC")
                .timeframe("M15")
                .initialCapital(new BigDecimal("10000"))
                .availableKrw(new BigDecimal("10000"))
                .totalAssetKrw(new BigDecimal("10000"))
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING")
                .scanState("SCANNING")
                .maxCandidateSize(30)
                .targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5"))
                .maxSpreadPct(new BigDecimal("0.1"))
                .watchlistRefreshMin(60)
                .startedAt(startedAt)
                .build();
        return dynamicSessionRepository.save(session);
    }

    private void saveClosedLoss(String sessionKind, Long sessionId, Instant closedAt) {
        PositionEntity pos = PositionEntity.builder()
                .coinPair("KRW-ETH")
                .side("BUY")
                .entryPrice(new BigDecimal("5000000"))
                .avgPrice(new BigDecimal("5000000"))
                .size(BigDecimal.ZERO)
                .investedKrw(new BigDecimal("8000"))
                .status("CLOSED")
                .sessionId(sessionId)
                .sessionKind(sessionKind)
                .build();
        pos.setRealizedPnl(new BigDecimal("-100"));
        pos.setClosedAt(closedAt);
        positionRepository.save(pos);
    }

    @Test
    @DisplayName("가동 이후 5연속 손실이면 동적 세션 서킷 브레이커가 발동한다")
    void trigger_onFiveConsecutiveLossesAfterStart() {
        Instant startedAt = Instant.now().minus(1, ChronoUnit.DAYS);
        DynamicSessionEntity session = saveSession(startedAt);
        for (int i = 5; i >= 1; i--) {
            saveClosedLoss("DYNAMIC", session.getId(), Instant.now().minus(i, ChronoUnit.HOURS));
        }

        CircuitBreakerResult result = riskManagementService.checkCircuitBreaker(session);

        assertThat(result.isTriggered()).isTrue();
        assertThat(result.getReason()).contains("연속 손실");
    }

    @Test
    @DisplayName("가동 이전(직전 가동분) 손실은 집계하지 않는다 — 재시작 시 즉시 재발동 방지")
    void noTrigger_whenLossesClosedBeforeRestart() {
        Instant startedAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        DynamicSessionEntity session = saveSession(startedAt);
        // 5연속 손실이지만 전부 startedAt 이전 청산분
        for (int i = 5; i >= 1; i--) {
            saveClosedLoss("DYNAMIC", session.getId(), startedAt.minus(i, ChronoUnit.HOURS));
        }

        CircuitBreakerResult result = riskManagementService.checkCircuitBreaker(session);

        assertThat(result.isTriggered()).isFalse();
    }

    @Test
    @DisplayName("같은 sessionId의 LIVE 손실은 DYNAMIC 서킷 브레이커 집계에 섞이지 않는다")
    void noTrigger_whenLossesBelongToLiveKind() {
        Instant startedAt = Instant.now().minus(1, ChronoUnit.DAYS);
        DynamicSessionEntity session = saveSession(startedAt);
        for (int i = 5; i >= 1; i--) {
            saveClosedLoss("LIVE", session.getId(), Instant.now().minus(i, ChronoUnit.HOURS));
        }

        CircuitBreakerResult result = riskManagementService.checkCircuitBreaker(session);

        assertThat(result.isTriggered()).isFalse();
    }

    @Test
    @DisplayName("MDD 임계(기본 10%) 초과 시 동적 세션 서킷 브레이커가 발동한다")
    void trigger_onMddExceeded() {
        DynamicSessionEntity session = saveSession(Instant.now().minus(1, ChronoUnit.DAYS));
        session.setMddPeakCapital(new BigDecimal("10000"));
        session.setTotalAssetKrw(new BigDecimal("8900")); // -11%
        session = dynamicSessionRepository.save(session);

        CircuitBreakerResult result = riskManagementService.checkCircuitBreaker(session);

        assertThat(result.isTriggered()).isTrue();
        assertThat(result.getReason()).contains("MDD");
    }
}
