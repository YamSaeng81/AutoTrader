package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.dto.LiveTradingStartRequest;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
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
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 2026-07-02 종합 감사 후속 — N-1(라이브/동적 sessionId 충돌 시 포지션 오염 방지) +
 * N-2(BLOCKED 전략의 세션 생성 거버넌스 우회) 회귀 테스트.
 *
 * <p>live_trading_session과 dynamic_session은 별도 BIGSERIAL 시퀀스라 같은 sessionId가
 * 우연히 겹칠 수 있다(D-2에서 이미 확인된 사실). 이 테스트는 그 상황을 인위적으로 재현해
 * {@code sessionKind}가 없는 조회 메서드로는 발생했을 교차 오염이, kind-aware 메서드로는
 * 발생하지 않음을 검증한다.</p>
 */
class SessionKindIsolationTest extends IntegrationTestBase {

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private DynamicSessionRepository dynamicSessionRepository;

    @Autowired
    private RiskConfigRepository riskConfigRepository;

    @Autowired
    private LiveTradingService liveTradingService;

    @Autowired
    private DynamicTradingService dynamicTradingService;

    @MockBean
    private TradeLogRepository tradeLogRepository;

    @BeforeEach
    void setUp() {
        when(tradeLogRepository.sumRealizedPnlSince(any())).thenReturn(BigDecimal.ZERO);
        when(tradeLogRepository.sumRealizedLossSince(any())).thenReturn(BigDecimal.ZERO);
        cleanupAll();
    }

    @AfterEach
    void tearDown() {
        cleanupAll();
    }

    private void cleanupAll() {
        orderRepository.deleteAll();
        positionRepository.deleteAll();
        sessionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
        riskConfigRepository.deleteAll();
    }

    @Test
    @DisplayName("N-1: 같은 sessionId를 가진 LIVE/DYNAMIC 포지션은 kind-aware 조회로 서로 격리된다")
    void sessionKindAwareLookup_isolatesLiveFromDynamic() {
        // 동일한 숫자 sessionId=777로 LIVE 포지션과 DYNAMIC 포지션을 각각 생성한다.
        // (실제로는 sessionRepository/dynamicSessionRepo의 별도 시퀀스가 우연히 같은 값을
        // 발급할 때 재현되는 상황 — 여기서는 명시적으로 같은 id를 주입해 직접 재현한다.)
        Long sharedSessionId = 777L;

        PositionEntity livePos = PositionEntity.builder()
                .coinPair("KRW-BTC")
                .side("BUY")
                .entryPrice(new BigDecimal("100000000"))
                .avgPrice(new BigDecimal("100000000"))
                .size(new BigDecimal("0.001"))
                .investedKrw(new BigDecimal("100000"))
                .status("OPEN")
                .sessionId(sharedSessionId)
                .sessionKind("LIVE")
                .build();
        positionRepository.save(livePos);

        PositionEntity dynamicPos = PositionEntity.builder()
                .coinPair("KRW-ETH")
                .side("BUY")
                .entryPrice(new BigDecimal("5000000"))
                .avgPrice(new BigDecimal("5000000"))
                .size(new BigDecimal("0.02"))
                .investedKrw(new BigDecimal("100000"))
                .status("OPEN")
                .sessionId(sharedSessionId)
                .sessionKind("DYNAMIC")
                .build();
        positionRepository.save(dynamicPos);

        // kind 없이 조회하면 두 포지션이 섞여 나옴 (수정 전 버그의 재현)
        List<PositionEntity> mixed = positionRepository.findBySessionIdAndStatus(sharedSessionId, "OPEN");
        assertThat(mixed).hasSize(2);

        // kind-aware 조회는 정확히 자기 종류만 반환한다
        List<PositionEntity> liveOnly =
                positionRepository.findBySessionKindAndSessionIdAndStatus("LIVE", sharedSessionId, "OPEN");
        assertThat(liveOnly).extracting(PositionEntity::getCoinPair).containsExactly("KRW-BTC");

        List<PositionEntity> dynamicOnly =
                positionRepository.findBySessionKindAndSessionIdAndStatus("DYNAMIC", sharedSessionId, "OPEN");
        assertThat(dynamicOnly).extracting(PositionEntity::getCoinPair).containsExactly("KRW-ETH");

        // 코인+상태 단건 조회도 동일하게 격리됨을 확인 (processMonitoringTick 등 실사용 경로)
        assertThat(positionRepository
                .findBySessionKindAndSessionIdAndCoinPairAndStatus("DYNAMIC", sharedSessionId, "KRW-BTC", "OPEN"))
                .isEmpty();
        assertThat(positionRepository
                .findBySessionKindAndSessionIdAndCoinPairAndStatus("LIVE", sharedSessionId, "KRW-BTC", "OPEN"))
                .isPresent();
    }

    @Test
    @DisplayName("N-2: BLOCKED 전략은 라이브 세션 생성이 거부된다")
    void createSession_rejectsBlockedStrategyForLive() {
        LiveTradingStartRequest req = new LiveTradingStartRequest();
        req.setStrategyType("MACD"); // StrategyLiveStatusRegistry: BLOCKED
        req.setCoinPair("KRW-BTC");
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("100000"));

        assertThatThrownBy(() -> liveTradingService.createSession(req))
                .hasMessageContaining("MACD")
                .hasMessageContaining("차단");
    }

    @Test
    @DisplayName("N-2: BLOCKED 전략은 동적 멀티코인 세션 생성도 거부된다")
    void createSession_rejectsBlockedStrategyForDynamic() {
        DynamicSessionRequest req = new DynamicSessionRequest();
        req.setStrategyType("MACD"); // StrategyLiveStatusRegistry: BLOCKED
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("100000"));

        assertThatThrownBy(() -> dynamicTradingService.createSession(req))
                .hasMessageContaining("MACD")
                .hasMessageContaining("차단");
    }

    @Test
    @DisplayName("N-2: ENABLED 전략은 정상적으로 라이브/동적 세션 생성이 허용된다")
    void createSession_allowsEnabledStrategy() {
        LiveTradingStartRequest liveReq = new LiveTradingStartRequest();
        liveReq.setStrategyType("COMPOSITE_BREAKOUT"); // ENABLED
        liveReq.setCoinPair("KRW-BTC");
        liveReq.setTimeframe("H1");
        liveReq.setInitialCapital(new BigDecimal("100000"));
        assertThat(liveTradingService.createSession(liveReq).getId()).isNotNull();

        DynamicSessionRequest dynReq = new DynamicSessionRequest();
        dynReq.setStrategyType("COMPOSITE_BREAKOUT"); // ENABLED
        dynReq.setTimeframe("H1");
        dynReq.setInitialCapital(new BigDecimal("100000"));
        assertThat(dynamicTradingService.createSession(dynReq).getId()).isNotNull();
    }
}
