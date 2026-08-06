package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 2026-08-06 신규 — 신호 기대값 검증 게이트({@link WalkForwardValidationGate})는
 * {@code strategy-validation.require-walk-forward-gate=false}(기본값)에서 세션 생성 경로에
 * 배선되어 있어도 기존 동작을 전혀 바꾸지 않아야 한다.
 *
 * <p>대부분의 기존 전략은 Walk Forward 실행 이력이 없다 — 기본값에서마저 차단되면
 * 배포 즉시 신규 세션 생성이 전면 중단된다. 이 테스트가 그 회귀를 잠근다.
 * 게이트 활성 시의 차단/통과 동작은 {@link WalkForwardGateEnabledSessionCreationTest} 참조.</p>
 */
class WalkForwardGateDisabledSessionCreationTest extends IntegrationTestBase {

    private static final String STRATEGY = "COMPOSITE_BREAKOUT"; // StrategyLiveStatusRegistry: ENABLED

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private DynamicSessionRepository dynamicSessionRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private RiskConfigRepository riskConfigRepository;

    @MockBean private TradeLogRepository tradeLogRepository;

    @BeforeEach
    void setUp() {
        when(tradeLogRepository.sumRealizedPnlSince(any())).thenReturn(BigDecimal.ZERO);
        when(tradeLogRepository.sumRealizedLossSince(any())).thenReturn(BigDecimal.ZERO);
        cleanup();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        orderRepository.deleteAll();
        positionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
        riskConfigRepository.deleteAll();
    }

    @Test
    @DisplayName("Walk Forward 이력이 전혀 없어도 세션 생성이 성공한다 (게이트 비활성 = 기본 배포 상태)")
    void 이력없어도_생성성공() {
        DynamicSessionRequest req = new DynamicSessionRequest();
        req.setStrategyType(STRATEGY);
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("10000"));

        var session = dynamicTradingService.createSession(req);

        assertThat(session.getId()).isNotNull();
        assertThat(session.getStrategyType()).isEqualTo(STRATEGY);
    }
}
