package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.entity.StrategyTypeEnabledEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyTypeEnabledRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 2026-08-18 신규 — 비활성 전략({@code strategy_type_enabled.is_active=false})이
 * <b>모든</b> 세션 생성 경로에서 막히는지 검증한다.
 *
 * <p><b>왜 필요한가</b>: 이 검사는 원래 {@link DynamicTradingService}에만 인라인으로 있었고
 * LIVE와 PAPER 경로는 검사하지 않았다. kill criteria(docs/KILL_CRITERIA.md §5)가 폐기 시
 * 전략을 비활성화하는 목적은 "세션만 정지하면 같은 전략으로 새 세션을 만들어 그대로 재개할 수
 * 있다"를 막는 것인데, 세 진입점 중 하나만 막혀 있으면 그 목적이 달성되지 않는다 —
 * 폐기 판정을 우회하는 경로가 두 개 열려 있었다.</p>
 *
 * <p>운영 DB의 {@code strategy_type_enabled}는 21행이 전부 {@code is_active=false}지만
 * 가동 중인 composite 전략 대부분은 아예 등재돼 있지 않다. 즉 이 테이블은 <b>차단 목록</b>으로
 * 동작하며, "행이 없으면 허용"이 전제다 — 마지막 테스트가 그 전제를 고정한다.</p>
 */
class StrategyEnablementGateTest extends IntegrationTestBase {

    /** {@link StrategyLiveStatusRegistry}에서 ENABLED라, 판정 변수가 이 게이트 하나만 남는다. */
    private static final String STRATEGY = "COMPOSITE_BREAKOUT";

    @Autowired private StrategyEnablementGate gate;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private PaperTradingService paperTradingService;
    @Autowired private StrategyTypeEnabledRepository enabledRepo;
    @Autowired private DynamicSessionRepository dynamicSessionRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;

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
        enabledRepo.deleteAll();
    }

    /** kill criteria의 {@code applyKill}이 하는 것과 같은 조작. */
    private void disable(String strategy) {
        enabledRepo.saveAndFlush(StrategyTypeEnabledEntity.builder()
                .strategyName(strategy)
                .isActive(false)
                .build());
    }

    private DynamicSessionRequest dynamicRequest() {
        DynamicSessionRequest req = new DynamicSessionRequest();
        req.setStrategyType(STRATEGY);
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("10000"));
        return req;
    }

    private PaperTradingStartRequest paperRequest() {
        PaperTradingStartRequest req = new PaperTradingStartRequest();
        req.setStrategyType(STRATEGY);
        req.setCoinPair("KRW-BTC");
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("10000"));
        return req;
    }

    @Test
    @DisplayName("비활성 전략은 DYNAMIC 세션 생성이 거부된다")
    void disabledStrategy_blocksDynamicSession() {
        disable(STRATEGY);

        assertThatThrownBy(() -> dynamicTradingService.createSession(dynamicRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비활성화");
    }

    @Test
    @DisplayName("비활성 전략은 PAPER 세션 생성도 거부된다 — 폐기 판정 우회 경로였다")
    void disabledStrategy_blocksPaperSession() {
        disable(STRATEGY);

        assertThatThrownBy(() -> paperTradingService.start(paperRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비활성화");
    }

    @Test
    @DisplayName("게이트 판정 자체: 비활성 false / 활성 true / 미등재 true")
    void gateDecision() {
        disable(STRATEGY);
        assertThat(gate.isEnabled(STRATEGY)).isFalse();

        enabledRepo.saveAndFlush(StrategyTypeEnabledEntity.builder()
                .strategyName(STRATEGY).isActive(true).build());
        assertThat(gate.isEnabled(STRATEGY))
                .as("부활 시 is_active=true 로 되돌리면 다시 통과해야 한다")
                .isTrue();

        assertThat(gate.isEnabled("COMPOSITE_NEVER_REGISTERED"))
                .as("이 테이블은 차단 목록이다 — 미등재는 허용 (기존 DynamicTradingService·StrategyController 규칙)")
                .isTrue();
    }

    @Test
    @DisplayName("활성 전략은 정상 생성된다 — 게이트가 무차별 차단하지 않는지 확인")
    void enabledStrategy_passes() {
        assertThat(dynamicTradingService.createSession(dynamicRequest()).getId()).isNotNull();
    }
}
