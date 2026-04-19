package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.RiskConfigRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.core.risk.RiskCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 리스크 체크 로직 단위 테스트 — 20260415_analy.md Tier 1 §5 버그 회귀 방지.
 *
 * <p>검증:</p>
 * <ul>
 *   <li>포트폴리오 한도가 RUNNING 세션 initialCapital 합으로 동적 산출되는가</li>
 *   <li>구간 손실률이 "순손익"이 아닌 "총손실(gross loss)" 기반으로 산출되는가</li>
 * </ul>
 */
class RiskManagementServiceTest {

    private RiskConfigRepository riskConfigRepository;
    private PositionRepository positionRepository;
    private TradeLogRepository tradeLogRepository;
    private LiveTradingSessionRepository sessionRepository;
    private RiskManagementService service;

    @BeforeEach
    void setUp() {
        riskConfigRepository = mock(RiskConfigRepository.class);
        positionRepository   = mock(PositionRepository.class);
        tradeLogRepository   = mock(TradeLogRepository.class);
        sessionRepository    = mock(LiveTradingSessionRepository.class);

        service = new RiskManagementService(
                riskConfigRepository, positionRepository, tradeLogRepository, sessionRepository);

        RiskConfigEntity config = RiskConfigEntity.builder()
                .maxDailyLossPct(new BigDecimal("3.0"))
                .maxWeeklyLossPct(new BigDecimal("7.0"))
                .maxMonthlyLossPct(new BigDecimal("15.0"))
                .maxPositions(3)
                .cooldownMinutes(60)
                .portfolioLimitKrw(new BigDecimal("10000000"))
                .build();
        when(riskConfigRepository.findTopByOrderByIdDesc()).thenReturn(java.util.Optional.of(config));
        when(positionRepository.countRealPositionsByStatus("OPEN")).thenReturn(0L);
        when(tradeLogRepository.sumRealizedPnlSince(any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("소액 세션(1만원) 30만원 손실 — 기존 버그: 3% 미만, 수정 후: 명백히 한도 초과")
    void smallSession_grossLoss_breachesLimit() {
        // given: RUNNING 세션 1개, initialCapital = 10,000원
        LiveTradingSessionEntity session = buildRunningSession(new BigDecimal("10000"));
        when(sessionRepository.findByStatus("RUNNING")).thenReturn(List.of(session));

        // 총손실 30만원 (이익 상쇄 없음)
        when(tradeLogRepository.sumRealizedLossSince(any()))
                .thenReturn(new BigDecimal("-300000"));

        // when
        RiskCheckResult result = service.checkRisk();

        // then: 분모가 10,000원이면 손실률 3000% → 일일 3% 한도 초과로 차단되어야
        assertThat(result.isApproved())
                .as("소액 세션에서 총손실 30만원이면 반드시 차단되어야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("이익이 손실을 상쇄해도 총손실 기준으로 판정 — net=+50k여도 gross loss=200k 이면 한도 평가")
    void grossLossUsed_notNetPnl() {
        // given: 10,000,000원 RUNNING 세션
        LiveTradingSessionEntity session = buildRunningSession(new BigDecimal("10000000"));
        when(sessionRepository.findByStatus("RUNNING")).thenReturn(List.of(session));

        // 순손익은 +50k 이지만 총손실은 -500k (500k 손실 + 550k 이익)
        when(tradeLogRepository.sumRealizedPnlSince(any())).thenReturn(new BigDecimal("50000"));
        when(tradeLogRepository.sumRealizedLossSince(any())).thenReturn(new BigDecimal("-500000"));

        // when
        RiskCheckResult result = service.checkRisk();

        // then: 500k / 10M = 5% > 일일 3% 한도 → 차단
        assertThat(result.isApproved())
                .as("순이익이라도 총손실이 한도를 초과하면 차단되어야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("RUNNING 세션 없으면 config.portfolioLimitKrw 로 폴백")
    void noRunningSessions_fallsBackToConfigLimit() {
        when(sessionRepository.findByStatus("RUNNING")).thenReturn(List.of());
        // config limit(1천만원) × 3% = 30만원 미만 손실 → 통과
        when(tradeLogRepository.sumRealizedLossSince(any())).thenReturn(new BigDecimal("-100000"));

        RiskCheckResult result = service.checkRisk();
        assertThat(result.isApproved()).isTrue();
    }

    private LiveTradingSessionEntity buildRunningSession(BigDecimal initialCapital) {
        LiveTradingSessionEntity session = new LiveTradingSessionEntity();
        session.setStatus("RUNNING");
        session.setInitialCapital(initialCapital);
        return session;
    }
}
