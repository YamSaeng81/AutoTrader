package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.entity.BacktestRunEntity;
import com.cryptoautotrader.api.repository.BacktestRunRepository;
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
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 2026-08-06 신규 — 신호 기대값 검증 게이트를 켰을 때({@code require-walk-forward-gate=true})
 * 실제 동적 세션 생성 경로에서 차단/통과가 배선대로 동작하는지 검증한다.
 *
 * <p>"COMPOSITE_BREAKOUT"을 테스트 전략으로 쓴다 — {@link StrategyLiveStatusRegistry}에서
 * ENABLED라 그 게이트는 항상 통과하고, Walk Forward 게이트만이 판정 변수가 되도록 격리한다.
 * (LIVE 쪽 배선은 코드가 동일한 {@code walkForwardValidationGate.throwIfBlocked} 호출을
 * 공유하므로 DYNAMIC 경로 검증으로 충분하다 — 별도 서비스 중복 테스트는 생략.)</p>
 */
@TestPropertySource(properties = "strategy-validation.require-walk-forward-gate=true")
class WalkForwardGateEnabledSessionCreationTest extends IntegrationTestBase {

    private static final String STRATEGY = "COMPOSITE_BREAKOUT";

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private DynamicSessionRepository dynamicSessionRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private RiskConfigRepository riskConfigRepository;
    @Autowired private BacktestRunRepository backtestRunRepository;

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
        backtestRunRepository.deleteAll();
    }

    @Test
    @DisplayName("Walk Forward 이력이 없으면 세션 생성이 거부된다")
    void 이력없으면_거부() {
        DynamicSessionRequest req = new DynamicSessionRequest();
        req.setStrategyType(STRATEGY);
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("10000"));

        assertThatThrownBy(() -> dynamicTradingService.createSession(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Walk Forward")
                .hasMessageContaining("이력 없음");
    }

    @Test
    @DisplayName("OOS 기대값이 양수인 통과 이력이 있으면 세션 생성이 성공한다")
    void 통과이력있으면_생성성공() {
        backtestRunRepository.save(walkForwardRun("ACCEPTABLE", new BigDecimal("1.5"), 30));

        DynamicSessionRequest req = new DynamicSessionRequest();
        req.setStrategyType(STRATEGY);
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("10000"));

        var session = dynamicTradingService.createSession(req);
        assertThat(session.getId()).isNotNull();
    }

    @Test
    @DisplayName("OOS 기대값이 음수인 이력만 있으면 여전히 거부된다")
    void 음수기대값이면_거부() {
        backtestRunRepository.save(walkForwardRun("ACCEPTABLE", new BigDecimal("-2.0"), 30));

        DynamicSessionRequest req = new DynamicSessionRequest();
        req.setStrategyType(STRATEGY);
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("10000"));

        assertThatThrownBy(() -> dynamicTradingService.createSession(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 이하");
    }

    @Test
    @DisplayName("verdict=OVERFITTING이면 기대값이 양수여도 거부된다")
    void 오버피팅이면_거부() {
        backtestRunRepository.save(walkForwardRun("OVERFITTING", new BigDecimal("3.0"), 30));

        DynamicSessionRequest req = new DynamicSessionRequest();
        req.setStrategyType(STRATEGY);
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("10000"));

        assertThatThrownBy(() -> dynamicTradingService.createSession(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OVERFITTING");
    }

    private BacktestRunEntity walkForwardRun(String verdict, BigDecimal expectancyPct, int totalTrades) {
        Map<String, Object> aggregated = new HashMap<>();
        aggregated.put("expectancyPct", expectancyPct);
        aggregated.put("totalTrades", totalTrades);
        Map<String, Object> result = new HashMap<>();
        result.put("verdict", verdict);
        result.put("aggregatedOutSample", aggregated);

        return BacktestRunEntity.builder()
                .strategyName(STRATEGY)
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .startDate(Instant.parse("2026-01-01T00:00:00Z"))
                .endDate(Instant.parse("2026-06-01T00:00:00Z"))
                .initialCapital(new BigDecimal("10000"))
                .configJson(Map.of())
                .isWalkForward(true)
                .wfResultJson(result)
                .build();
    }
}
