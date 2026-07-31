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

    @Autowired
    private DbResetService dbResetService;

    @Autowired
    private PositionService positionService;

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
    @DisplayName("실전매매 초기화는 DYNAMIC 포지션/주문을 지우지 않는다")
    void resetLiveTrading_preservesDynamicPositionsAndOrders() {
        // 같은 sessionId를 공유하는 LIVE / DYNAMIC 포지션을 각각 생성한다.
        // session_id 만으로 지우면(수정 전 동작) 동적 세션 데이터까지 함께 삭제된다.
        Long sharedSessionId = 888L;

        positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("100000000")).avgPrice(new BigDecimal("100000000"))
                .size(new BigDecimal("0.001")).investedKrw(new BigDecimal("100000"))
                .status("OPEN").sessionId(sharedSessionId).sessionKind("LIVE")
                .build());
        positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-ETH").side("BUY")
                .entryPrice(new BigDecimal("5000000")).avgPrice(new BigDecimal("5000000"))
                .size(new BigDecimal("0.02")).investedKrw(new BigDecimal("100000"))
                .status("OPEN").sessionId(sharedSessionId).sessionKind("DYNAMIC")
                .build());

        dbResetService.resetLiveTrading();

        assertThat(positionRepository
                .findBySessionKindAndSessionIdAndStatus("LIVE", sharedSessionId, "OPEN"))
                .as("LIVE 포지션은 초기화된다")
                .isEmpty();
        assertThat(positionRepository
                .findBySessionKindAndSessionIdAndStatus("DYNAMIC", sharedSessionId, "OPEN"))
                .as("DYNAMIC 포지션은 보존된다")
                .extracting(PositionEntity::getCoinPair)
                .containsExactly("KRW-ETH");
    }

    @Test
    @DisplayName("실전매매 전역 요약은 DYNAMIC 포지션/주문/손익을 집계하지 않는다")
    void globalStatus_excludesDynamicSessionData() {
        // 실전매매 세션은 코인 보유 0, 동적 세션만 포지션을 들고 있는 상황.
        // 필터가 없으면 실전매매 화면의 "열린 포지션"에 동적 세션 보유가 새어 들어온다.
        positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-RLUSD").side("BUY")
                .entryPrice(new BigDecimal("1428")).avgPrice(new BigDecimal("1428"))
                .size(new BigDecimal("5.60224089")).investedKrw(new BigDecimal("8000"))
                .unrealizedPnl(new BigDecimal("500"))
                .status("OPEN").sessionId(38L).sessionKind("DYNAMIC")
                .build());

        assertThat(positionRepository
                .countBySessionKindAndSessionIdIsNotNullAndStatus("LIVE", "OPEN"))
                .as("실전매매 보유 0건")
                .isZero();
        assertThat(positionRepository
                .countBySessionKindAndSessionIdIsNotNullAndStatus("DYNAMIC", "OPEN"))
                .as("동적 세션 보유 1건")
                .isEqualTo(1);

        // 필터 없는 구 카운트는 여전히 섞여 나온다 = 이 테스트가 잠그는 대상
        assertThat(positionRepository.countBySessionIdIsNotNullAndStatus("OPEN")).isEqualTo(1);

        assertThat(liveTradingService.getGlobalStatus().getOpenPositions())
                .as("실전매매 요약에 동적 세션 포지션이 섞이면 안 된다")
                .isZero();
        assertThat(liveTradingService.getGlobalStatus().getTotalPnl())
                .as("실전매매 손익에 동적 세션 미실현 손익이 섞이면 안 된다")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("여러 세션이 같은 코인을 보유해도 미실현 손익은 각자 갱신된다")
    void updateUnrealizedPnl_handlesSameCoinAcrossSessions() {
        // 실전 세션과 동적 세션이 동시에 KRW-BTC를 보유. 단건(Optional) 조회로는
        // NonUniqueResult 이거나 한쪽만 갱신돼 손익이 섞여 보인다.
        PositionEntity live = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("100000000")).avgPrice(new BigDecimal("100000000"))
                .size(new BigDecimal("0.001")).investedKrw(new BigDecimal("100000"))
                .status("OPEN").sessionId(192L).sessionKind("LIVE")
                .build());
        PositionEntity dyn = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.002")).investedKrw(new BigDecimal("180000"))
                .status("OPEN").sessionId(33L).sessionKind("DYNAMIC")
                .build());

        assertThat(positionRepository.findAllByCoinPairAndStatus("KRW-BTC", "OPEN")).hasSize(2);

        positionService.updateUnrealizedPnl("KRW-BTC", new BigDecimal("110000000"));

        // 각 포지션이 자기 평균단가 기준으로 갱신된다 (서로의 값을 덮어쓰지 않음)
        assertThat(positionRepository.findById(live.getId()).orElseThrow().getUnrealizedPnl())
                .isEqualByComparingTo(new BigDecimal("10000"));   // (1.1억-1.0억) × 0.001
        assertThat(positionRepository.findById(dyn.getId()).orElseThrow().getUnrealizedPnl())
                .isEqualByComparingTo(new BigDecimal("40000"));   // (1.1억-0.9억) × 0.002

        // 종류별 손익 합계도 서로 섞이지 않는다
        assertThat(positionService.getTotalPnl("LIVE")).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(positionService.getTotalPnl("DYNAMIC")).isEqualByComparingTo(new BigDecimal("40000"));
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
    @DisplayName("신규 동적 세션은 time stop이 꺼진 상태(maxHoldHours=0)로 생성된다")
    void createSession_defaultsTimeStopOff() {
        // 2026-07-31: V62 time stop 배포 직후 매도 후처리 롤백 P0(주문은 FILLED인데 포지션이
        // OPEN으로 남아 매 틱 매도 재시도)가 드러났다. 원인 규명 전까지 신규 세션이 자동으로
        // 그 경로에 노출되면 안 된다. DB를 매번 손으로 고치는 운영을 막기 위한 잠금.
        DynamicSessionRequest req = new DynamicSessionRequest();
        req.setStrategyType("COMPOSITE_MTF_BTC");
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("10000"));

        var session = dynamicTradingService.createSession(req);

        assertThat(session.getMaxHoldHours())
                .as("기본값은 0(비활성) — 롤백 P0 수정 후 24로 되돌릴 것")
                .isZero();

        // 명시적으로 지정하면 그 값이 그대로 쓰인다 (기능 자체는 살아 있음)
        DynamicSessionRequest explicit = new DynamicSessionRequest();
        explicit.setStrategyType("COMPOSITE_MTF_BTC");
        explicit.setTimeframe("H1");
        explicit.setInitialCapital(new BigDecimal("10000"));
        explicit.setMaxHoldHours(12);

        assertThat(dynamicTradingService.createSession(explicit).getMaxHoldHours())
                .isEqualTo(12);
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
