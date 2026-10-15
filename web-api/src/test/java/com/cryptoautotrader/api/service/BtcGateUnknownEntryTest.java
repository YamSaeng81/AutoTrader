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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * BTC 급락 관문의 <b>판정 불가</b> — 엔진 배선 (2026-10-15 리뷰 8회차).
 *
 * <p>{@code CandleFreshnessGuardTest} 는 관문 판정 자체를 본다. 여기서는 엔진 네 곳 — LIVE 평가 회차·승인 시점, DYNAMIC 평가 회차·승인
 * 시점 — 이 그 판정으로 실제로 매수를 막는지 본다(7회차 교훈: 보조 메서드를 직접 부르는 시험은 연결 제거를 잡지 못했다). 변수는 BTC
 * 캔들 하나뿐이다 — 같은 조건에서 BTC 캔들만 최신으로 바꾸면 매수가 나간다(대조).</p>
 *
 * <p>대역: 항상 BUY 를 내는 시험 전략, 캔들 공급(LIVE 는 {@code market_data_cache} 행, DYNAMIC 은 {@code MarketDataSyncService}),
 * 거래소 주문 클라이언트. 전역 위험 검사는 H2 에서 돌지 않아 통과로 고정한다(종단 간 시험과 같은 한계).</p>
 */
@DisplayName("BTC 급락 관문 판정 불가 — 캔들이 없거나 낡으면 LIVE·DYNAMIC 의 평가·승인 시점 매수를 막고, 최신이면 나간다")
class BtcGateUnknownEntryTest extends IntegrationTestBase {

    private static final String STRATEGY = "BTC_GATE_ALWAYS_BUY";

    @Autowired private EntryPriorityRounds rounds;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository cacheRepository;

    @MockBean private MarketDataSyncService marketDataSyncService;
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
    private final List<String> coins = List.of("KRW-CRO", "KRW-BTC");

    static final class AlwaysBuy implements Strategy {
        @Override public String getName() { return STRATEGY; }
        @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            return StrategySignal.buy(new BigDecimal("100"), "BTC 관문 시험 신호");
        }
        @Override public int getMinimumCandleCount() { return 50; }
    }

    @BeforeEach
    void setUp() {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        StrategyRegistry.register(new AlwaysBuy());
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        limits.setStage("BTCG_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        poolLossGuard.setEnabledSetting(true);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRisk();
        for (String c : coins) jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", c);
    }

    @AfterEach
    void tearDown() {
        rounds.setForcedForTest(false);
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

    /** 닫힌 H1 캔들 300개 — 완만한 상승(EMA200 위, 급락 없음). */
    private static List<Candle> candles(Instant lastOpen) {
        List<Candle> out = new ArrayList<>();
        for (int i = 299; i >= 0; i--) {
            BigDecimal p = new BigDecimal(1000 + (299 - i));
            out.add(Candle.builder().time(lastOpen.minus(i, ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.ONE)).low(p.subtract(BigDecimal.ONE)).close(p)
                    .volume(new BigDecimal("1000")).build());
        }
        return out;
    }

    private static Instant freshLastOpen() {
        return Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    }

    /** 수집이 5시간 전에 멎은 캐시 — 봉 수는 충분하다. */
    private static Instant staleLastOpen() {
        return Instant.now().minus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    }

    private void insertCache(String coin, List<Candle> cs) {
        cacheRepository.saveAll(cs.stream().map(c -> com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                .time(c.getTime()).coinPair(coin).timeframe("H1")
                .open(c.getOpen()).high(c.getHigh()).low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                .build()).toList());
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

    private void tickDynamic(DynamicSessionEntity s) {
        dynamicTradingService.processTick(dynamicRepo.findById(s.getId()).orElseThrow());
    }

    private List<OrderEntity> buys(String kind, Long id) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, id).stream()
                .filter(o -> "BUY".equals(o.getSide())).toList();
    }

    /** DYNAMIC 캔들 공급 — 거래 코인은 늘 최신, BTC 만 {@code btc}. */
    private void dynamicFeed(List<Candle> coin, List<Candle> btc) {
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(coin);
        when(marketDataSyncService.fetchWithCache(eq("KRW-BTC"), anyString(), anyInt())).thenReturn(btc);
    }

    @Test
    @DisplayName("LIVE 평가 회차: BTC 캔들 없음·낡음 → 매수 없음, 최신 → 매수 (즉시 진입)")
    void liveEvaluation() {
        rounds.setForcedForTest(false);
        insertCache("KRW-CRO", candles(freshLastOpen()));

        LiveTradingSessionEntity none = live("KRW-CRO");
        evaluateLive(none);
        assertThat(buys("LIVE", none.getId())).as("BTC 캔들 없음 — 판정 불가").isEmpty();

        insertCache("KRW-BTC", candles(staleLastOpen()));
        LiveTradingSessionEntity stale = live("KRW-CRO");
        evaluateLive(stale);
        assertThat(buys("LIVE", stale.getId())).as("BTC 캔들 5시간 전에 멈춤 — 판정 불가").isEmpty();

        // 데이터 복구 → 다시 판정
        insertCache("KRW-BTC", candles(freshLastOpen()));
        LiveTradingSessionEntity ok = live("KRW-CRO");
        evaluateLive(ok);
        assertThat(buys("LIVE", ok.getId())).as("대조 — BTC 만 최신으로 바꾸면 매수").hasSize(1);
    }

    @Test
    @DisplayName("DYNAMIC 평가 회차: BTC 캔들 없음·낡음 → 매수 없음, 최신 → 매수 (즉시 진입)")
    void dynamicEvaluation() {
        rounds.setForcedForTest(false);
        List<Candle> coin = candles(freshLastOpen());

        dynamicFeed(coin, List.of());
        DynamicSessionEntity none = dynamic("KRW-IOTA");
        tickDynamic(none);
        assertThat(buys("DYNAMIC", none.getId())).as("BTC 캔들 없음 — 판정 불가").isEmpty();

        dynamicFeed(coin, candles(staleLastOpen()));
        DynamicSessionEntity stale = dynamic("KRW-IOTA");
        tickDynamic(stale);
        assertThat(buys("DYNAMIC", stale.getId())).as("BTC 캔들 5시간 전에 멈춤 — 판정 불가").isEmpty();

        dynamicFeed(coin, candles(freshLastOpen()));
        DynamicSessionEntity ok = dynamic("KRW-IOTA");
        tickDynamic(ok);
        assertThat(buys("DYNAMIC", ok.getId())).as("대조 — BTC 만 최신으로 바꾸면 매수").hasSize(1);
    }

    @Test
    @DisplayName("승인 시점: 평가 때 최신이던 BTC 캔들이 승인 전에 사라지면 LIVE·DYNAMIC 모두 '판정 불가' 로 거절, 다시 오면 승인")
    void approvalTime() {
        rounds.setForcedForTest(true);
        Instant lastOpen = freshLastOpen();
        List<Candle> cs = candles(lastOpen);
        insertCache("KRW-CRO", cs);
        insertCache("KRW-BTC", cs);
        dynamicFeed(cs, cs);

        LiveTradingSessionEntity l = live("KRW-CRO");
        DynamicSessionEntity d = dynamic("KRW-IOTA");
        evaluateLive(l);
        tickDynamic(d);
        assertThat(rounds.openRounds()).as("평가 때는 관문 통과 — 회차에 접수").isEqualTo(1);

        // 평가 뒤 BTC 수집이 멎었다(캐시 비움 · DYNAMIC 은 빈 응답)
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1'");
        dynamicFeed(cs, List.of());
        EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        List<EntryPriorityRounds.Outcome> out = rounds.processDue(EntryPriorityRounds.deadline(round));
        out.forEach(o -> System.out.println("[BTC 관문 승인] " + o.candidate().kind() + " " + o.candidate().coinPair() + " → " + o.reason()));
        assertThat(out).hasSize(2);
        assertThat(out).allSatisfy(o -> assertThat(o.reason()).contains("BTC_MARKET_GUARD 판정 불가"));
        assertThat(buys("LIVE", l.getId())).isEmpty();
        assertThat(buys("DYNAMIC", d.getId())).isEmpty();

        // 대조 — 같은 절차에서 BTC 캔들이 남아 있으면 둘 다 승인된다. 처리된 회차는 늦은 접수를 받지 않으므로 다른 회차(마지막 봉 5초 앞)로.
        Instant lastOpen2 = lastOpen.minusSeconds(5);
        List<Candle> cs2 = candles(lastOpen2);
        for (String c : coins) jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", c);
        insertCache("KRW-CRO", cs2);
        insertCache("KRW-BTC", cs2);
        dynamicFeed(cs2, cs2);
        LiveTradingSessionEntity l2 = live("KRW-CRO");
        DynamicSessionEntity d2 = dynamic("KRW-IOTA");
        evaluateLive(l2);
        tickDynamic(d2);
        EntryPriorityRounds.RoundKey round2 = new EntryPriorityRounds.RoundKey("H1", lastOpen2);
        List<EntryPriorityRounds.Outcome> out2 = rounds.processDue(EntryPriorityRounds.deadline(round2));
        out2.forEach(o -> System.out.println("[BTC 관문 승인 대조] " + o.candidate().kind() + " " + o.candidate().coinPair() + " → " + o.reason()));
        assertThat(out2).hasSize(2).allSatisfy(o -> assertThat(o.reason()).isNull());
        assertThat(buys("LIVE", l2.getId())).hasSize(1);
        assertThat(buys("DYNAMIC", d2.getId())).hasSize(1);
    }
}
