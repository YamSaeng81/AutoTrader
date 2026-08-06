package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.LiveTradingStartRequest;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.core.portfolio.PortfolioManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-06 신규 — LIVE/DYNAMIC 청산 엔진 통합(P1) 중 time stop 배선(V64 마이그레이션 +
 * {@code LiveTradingSessionEntity.maxHoldHours}) 검증.
 *
 * <p>배경: dynamic_session에는 time stop(max_hold_hours)이 있었지만 live_trading_session에는
 * 컬럼 자체가 없었다 — LIVE 세션 194의 BTC 포지션이 136시간 청산되지 못한 원인. 이 테스트는
 * "요청에 값을 안 주면 0(비활성)", "값을 주면 그대로 저장된다"는 두 가지 계약을 잠근다.
 * 실제 시간 초과 시 청산이 트리거되는지는 {@link ExitRuleCalculatorTest}가 순수 판정 로직
 * 레벨에서 검증한다(틱 루프 자체는 기존 SL/TP 로직도 별도 통합 테스트가 없는 것과 동일하게
 * 실거래 모니터링으로 확인하는 영역이다).
 */
class LiveTradingMaxHoldHoursTest extends IntegrationTestBase {

    @Autowired
    private LiveTradingService liveTradingService;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private PortfolioManager portfolioManager;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        portfolioManager.syncTotalCapital(new BigDecimal("10000000"));
    }

    @AfterEach
    void tearDown() {
        sessionRepository.deleteAll();
        portfolioManager.syncTotalCapital(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("maxHoldHours를 지정하지 않으면 0(비활성)으로 저장된다 — 기존 세션과 동일 동작")
    void 미지정시_비활성으로_저장() {
        LiveTradingStartRequest req = new LiveTradingStartRequest();
        req.setStrategyType("COMPOSITE_BREAKOUT");
        req.setCoinPair("KRW-BTC");
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("10000"));

        LiveTradingSessionEntity session = liveTradingService.createSession(req);

        assertThat(session.getMaxHoldHours()).isZero();

        LiveTradingSessionEntity reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getMaxHoldHours()).isZero();
    }

    @Test
    @DisplayName("maxHoldHours를 명시하면 그 값이 그대로 저장된다")
    void 명시하면_그값_저장() {
        LiveTradingStartRequest req = new LiveTradingStartRequest();
        req.setStrategyType("COMPOSITE_BREAKOUT");
        req.setCoinPair("KRW-BTC");
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("10000"));
        req.setMaxHoldHours(24);

        LiveTradingSessionEntity session = liveTradingService.createSession(req);

        assertThat(session.getMaxHoldHours()).isEqualTo(24);

        LiveTradingSessionEntity reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getMaxHoldHours()).isEqualTo(24);
    }
}
