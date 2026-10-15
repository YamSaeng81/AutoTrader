package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BTC 급락 관문의 <b>데이터 공급</b> — 차단이 작동하고, 정상 데이터가 공급되면 실제 진입도 가능하다 (2026-10-15 리뷰 8회차 후속).
 *
 * <p>대역은 거래소 REST 하나(캔들 응답)와 신호 원천(항상 BUY), 거래소 주문 클라이언트다. 캔들 수집({@link MarketDataSyncService})·DYNAMIC
 * 캐시 조회·엔진 평가·관문·예약은 실제 코드다. KRW-BTC 세션은 만들지 않는다 — 관문이 요구하는 데이터를 세션 구성과 무관하게 수집하는지
 * 본다.</p>
 */
@DisplayName("BTC 관문 데이터 공급 — BTC 세션 없는 LIVE·DYNAMIC: 수집 실패면 차단, 수집되면 진입")
class BtcGateSupplyEndToEndTest extends IntegrationTestBase {

    private static final String STRATEGY = "BTC_SUPPLY_ALWAYS_BUY";
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Autowired private EntryPriorityRounds rounds;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private MarketDataSyncService marketDataSyncService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository cacheRepository;

    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private PortfolioSyncService portfolioSyncService;
    @SpyBean private RiskManagementService riskManagementService;

    private String savedStage;
    private BigDecimal savedTotal;
    private BigDecimal savedPer;
    private Integer savedSlots;
    private final List<Long> liveIds = new ArrayList<>();
    private final List<Long> dynIds = new ArrayList<>();
    private final List<String> coins = List.of("KRW-CRO", "KRW-IOTA", "KRW-BTC");
    /** 거래소 대역이 캔들을 내주는 코인 — 비어 있으면 그 코인 캔들 조회는 실패(503). */
    private final Set<String> serving = ConcurrentHashMap.newKeySet();
    private Instant lastOpen;

    static final class AlwaysBuy implements Strategy {
        @Override public String getName() { return STRATEGY; }
        @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            return StrategySignal.buy(new BigDecimal("100"), "BTC 공급 시험 신호");
        }
        @Override public int getMinimumCandleCount() { return 50; }
    }

    @BeforeEach
    void setUp() throws Exception {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        StrategyRegistry.register(new AlwaysBuy());
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        limits.setStage("BTCS_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        poolLossGuard.setEnabledSetting(true);
        rounds.setForcedForTest(false);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRisk();
        for (String c : coins) jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", c);
        // 🔴 KRW-BTC 세션이 없어야 한다 — 다른 시험이 남긴 실행 중 BTC 세션은 이 시험 동안 멈춘다(관문 데이터가 세션 구성에 기대지 않음을 본다)
        jdbc.update("UPDATE live_trading_session SET status = 'STOPPED' WHERE status = 'RUNNING' AND coin_pair = 'KRW-BTC'");
        // 직전에 닫힌 봉의 시작 — 지금이 10:20 이면 09:00
        lastOpen = Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        when(upbitRestClient.getCandles(anyString(), eq("minutes"), eq(60), any(Instant.class), anyInt()))
                .thenAnswer(inv -> candlesBefore(inv.getArgument(0), inv.getArgument(3), inv.getArgument(4)));
    }

    @AfterEach
    void tearDown() {
        for (Long id : liveIds) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
        for (Long id : dynIds) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.findById(id).ifPresent(dynamicRepo::delete);
        }
        for (String c : coins) jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", c);
        jdbc.update("DELETE FROM capital_stage_baseline WHERE stage = ?", limits.getStage());
        limits.setStage(savedStage);
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
    }

    /** 업비트 캔들 API 흉내 — {@code to} 이전 봉을 최신순으로 {@code count} 개. 내주지 않는 코인은 503. */
    private List<UpbitCandleResponse> candlesBefore(String coin, Instant to, int count) {
        if (!serving.contains(coin)) throw new RuntimeException("Upbit 캔들 API 호출 실패: 503 (시험)");
        List<UpbitCandleResponse> out = new ArrayList<>();
        for (int i = 0; i < 520 && out.size() < count; i++) {
            Instant t = lastOpen.minus(i, ChronoUnit.HOURS);
            if (!t.isBefore(to)) continue;
            BigDecimal p = new BigDecimal(1000 + (519 - i));
            UpbitCandleResponse r = new UpbitCandleResponse();
            r.setMarket(coin);
            r.setCandleDateTimeUtc(UTC.format(t));
            r.setOpeningPrice(p);
            r.setHighPrice(p.add(BigDecimal.ONE));
            r.setLowPrice(p.subtract(BigDecimal.ONE));
            r.setTradePrice(p);
            r.setCandleAccTradeVolume(new BigDecimal("1000"));
            out.add(r);
        }
        return out;
    }

    private LiveTradingSessionEntity live(String coin) {
        LiveTradingSessionEntity s = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType(STRATEGY).coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("20000")).availableKrw(new BigDecimal("20000"))
                .totalAssetKrw(new BigDecimal("20000")).status("RUNNING").startedAt(Instant.now()).build());
        liveIds.add(s.getId());
        return s;
    }

    private DynamicSessionEntity dynamic(String coin) {
        DynamicSessionEntity s = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType(STRATEGY).timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("10000")).totalAssetKrw(new BigDecimal("10000"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000")).watchlistRefreshMin(60)
                .watchlistJson("[\"" + coin + "\"]").watchlistRefreshedAt(Instant.now())
                .startedAt(Instant.now())
                .build());
        dynIds.add(s.getId());
        return s;
    }

    private void evaluateLive(LiveTradingSessionEntity s) {
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        ReflectionTestUtils.invokeMethod(target, "evaluateAndExecuteSession", liveRepo.findById(s.getId()).orElseThrow());
    }

    private List<OrderEntity> buys(String kind, Long id) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, id).stream()
                .filter(o -> "BUY".equals(o.getSide())).toList();
    }

    private Instant btcCacheLast() {
        return cacheRepository.findMaxTime("KRW-BTC", "H1");
    }

    @Test
    @DisplayName("LIVE(BTC 세션 없음): BTC 수집 실패 → 캐시 없음·매수 0, 수집 복구 → 수집 회차가 KRW-BTC H1 을 채우고 매수 1")
    void liveWithoutBtcSession() throws Exception {
        serving.add("KRW-CRO");   // 세션 코인은 정상 — BTC 만 실패

        LiveTradingSessionEntity a = live("KRW-CRO");
        marketDataSyncService.syncMarketData();
        assertThat(cacheRepository.findMaxTime("KRW-CRO", "H1")).as("세션 코인은 수집됨").isEqualTo(lastOpen);
        assertThat(btcCacheLast()).as("BTC 수집 실패 — 캐시 없음").isNull();
        evaluateLive(a);
        assertThat(buys("LIVE", a.getId())).as("BTC 판정 불가 — 차단").isEmpty();
        verify(upbitRestClient, atLeastOnce()).getCandles(eq("KRW-BTC"), eq("minutes"), eq(60), any(Instant.class), anyInt());

        // 복구 — BTC 세션을 만들지 않고 수집만 돌린다
        serving.add("KRW-BTC");
        marketDataSyncService.syncMarketData();
        assertThat(btcCacheLast()).as("관문 요구 데이터로 수집됨").isEqualTo(lastOpen);
        LiveTradingSessionEntity b = live("KRW-CRO");
        evaluateLive(b);
        assertThat(buys("LIVE", b.getId())).as("정상 데이터가 공급되면 실제 진입").hasSize(1);
    }

    @Test
    @DisplayName("DYNAMIC(BTC 세션 없음): BTC REST 실패 → 매수 0, 복구 → 엔진 자체 조회가 BTC 를 받아 매수 1")
    void dynamicWithoutBtcSession() {
        serving.add("KRW-IOTA");

        DynamicSessionEntity a = dynamic("KRW-IOTA");
        dynamicTradingService.processTick(dynamicRepo.findById(a.getId()).orElseThrow());
        assertThat(buys("DYNAMIC", a.getId())).as("BTC 판정 불가 — 차단").isEmpty();

        serving.add("KRW-BTC");
        DynamicSessionEntity b = dynamic("KRW-IOTA");
        dynamicTradingService.processTick(dynamicRepo.findById(b.getId()).orElseThrow());
        assertThat(buys("DYNAMIC", b.getId())).as("정상 데이터가 공급되면 실제 진입").hasSize(1);
    }

    @Test
    @DisplayName("수집 대상: 실행 중 LIVE·DYNAMIC 의 타임프레임마다 KRW-BTC — 세션 구성과 무관")
    void requirementsFollowRunningTimeframes() {
        live("KRW-CRO");
        dynamic("KRW-IOTA");
        RiskGateDataRequirements req = new RiskGateDataRequirements(liveRepo, dynamicRepo);
        assertThat(req.requiredCandles()).anySatisfy(p -> assertThat(p).containsExactly("KRW-BTC", "H1"));
        assertThat(req.requiredCandles()).allSatisfy(p -> assertThat(p[0]).isEqualTo("KRW-BTC"));
    }
}
