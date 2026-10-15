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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * 선행 조건 3번 3-e — 코인 우선순위의 <b>종단 간 배선</b> (2026-10-15).
 *
 * <p>{@link EntryPriorityRoundsTest} 는 회차의 접수·승인과 승인 메서드를 직접 불렀다. 여기서는 <b>엔진의 평가 루프부터</b>
 * 돈다 — LIVE {@code evaluateAndExecuteSession}, DYNAMIC {@code processTick} 이 캔들을 읽고 전략을 평가하고 관문을 지나
 * 회차에 접수하는 것, 마감 전에는 주문이 없는 것, 마감 뒤 사전 등록 순서로 승인되어 예약 경계를 지나 주문 의도가 생기는 것.</p>
 *
 * <p>대역은 셋뿐이다 — 신호 원천(항상 BUY 를 내는 시험 전략을 운영 등록부에 등록), 캔들 공급(LIVE 는 {@code market_data_cache}
 * 행을 넣고 DYNAMIC 은 {@code MarketDataSyncService} 가 같은 캔들을 돌려준다), 거래소 주문 클라이언트. 🔴 운영 전략의
 * 신호 계산 자체는 이 시험의 대상이 아니다 — 대상은 신호가 난 뒤의 배선이다.</p>
 *
 * <p>평가 순서는 등록 순서의 반대(INJ → G → CRO → IOTA)로 둔다. 슬롯은 2 — 즉시 진입이었다면 INJ·G 가 들어간다.</p>
 */
@DisplayName("코인 우선순위 종단 간 — 엔진 평가 → 회차 접수 → 마감 뒤 등록 순서 승인 → 주문 의도")
class EntryPriorityEndToEndTest extends IntegrationTestBase {

    private static final String STRATEGY = "E2E_PRIORITY_ALWAYS_BUY";

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

    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private PortfolioSyncService portfolioSyncService;
    /**
     * 🔴 전역 위험 검사({@code checkRisk})만 통과로 고정한다 — 그 손실 집계 질의가 PostgreSQL JSON 연산자({@code ->>})를 써서
     * H2 에서 실행되지 않는다(시험 기반의 한계). 세션 서킷 브레이커 등 나머지는 실제 메서드다.
     */
    @SpyBean private RiskManagementService riskManagementService;
    /** LIVE 엔진의 캔들 원천 — {@code market_data_cache} (엔진 필드 이름은 candleDataRepository 이지만 이 저장소다). */
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository candleDataRepository;

    private String savedStage;
    private BigDecimal savedTotal;
    private BigDecimal savedPer;
    private Integer savedSlots;
    private final List<Long> liveIds = new ArrayList<>();
    private final List<Long> dynIds = new ArrayList<>();
    private final List<String> coins = List.of("KRW-INJ", "KRW-CRO", "KRW-BTC");

    /** 평가 신호의 원천 — 캔들과 무관하게 강도 100 의 BUY. */
    static final class AlwaysBuy implements Strategy {
        @Override public String getName() { return STRATEGY; }
        @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            return StrategySignal.buy(new BigDecimal("100"), "종단 간 시험 신호");
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
        limits.setStage("E2E_" + UUID.randomUUID().toString().substring(0, 8));
        poolLossGuard.setEnabledSetting(true);
        rounds.setForcedForTest(true);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRisk();
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

    /**
     * 닫힌 H1 캔들 300개 — 완만한 상승(EMA200 위, 급락 없음). 마지막 캔들은 1분 전에 닫혔다 → 회차 마감은 약 4분 뒤.
     */
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

    /** 엔티티로 넣는다 — JDBC Timestamp 는 JVM 시간대로 들어가 엔진이 읽는 시각과 어긋난다. */
    private void insertCandleData(String coin, List<Candle> cs) {
        candleDataRepository.saveAll(cs.stream().map(c -> com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
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

    private List<OrderEntity> buys(String kind, Long id) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, id).stream()
                .filter(o -> "BUY".equals(o.getSide())).toList();
    }

    @Test
    @DisplayName("등록 순서의 반대로 평가해도 마감 뒤 IOTA(DYNAMIC)·CRO(LIVE)가 승인되고, 마감 전에는 주문이 없다")
    void engineEvaluationToApprovalFollowsRegisteredOrder() {
        Instant lastOpen = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        List<Candle> cs = candles(lastOpen);
        for (String c : coins) insertCandleData(c, cs);
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(cs);

        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 2);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));

        LiveTradingSessionEntity inj = live("KRW-INJ");
        DynamicSessionEntity g = dynamic("KRW-G");
        LiveTradingSessionEntity cro = live("KRW-CRO");
        DynamicSessionEntity iota = dynamic("KRW-IOTA");

        // ① 엔진 평가 — 등록 순서의 반대로. 각 엔진은 사지 않고 회차에 접수만 한다.
        evaluateLive(inj);
        dynamicTradingService.processTick(dynamicRepo.findById(g.getId()).orElseThrow());
        evaluateLive(cro);
        dynamicTradingService.processTick(dynamicRepo.findById(iota.getId()).orElseThrow());

        EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        assertThat(rounds.openRounds()).as("네 엔진 평가가 한 회차로 묶인다").isEqualTo(1);
        assertThat(buys("LIVE", inj.getId())).as("마감 전 주문 없음").isEmpty();
        assertThat(buys("LIVE", cro.getId())).isEmpty();
        assertThat(buys("DYNAMIC", g.getId())).isEmpty();
        assertThat(buys("DYNAMIC", iota.getId())).isEmpty();
        assertThat(rounds.processDue(Instant.now())).as("마감 전에는 승인하지 않는다").isEmpty();

        // ② 평가 뒤 CRO 가격이 움직였다 — LIVE 승인은 승인 시점 현재가를 쓴다.
        Candle last = cs.get(cs.size() - 1);
        insertCandleData("KRW-CRO", List.of(Candle.builder().time(last.getTime()).open(last.getOpen())
                .high(new BigDecimal("1301")).low(last.getLow()).close(new BigDecimal("1300")).volume(last.getVolume()).build()));

        // ③ 마감 뒤 승인
        List<EntryPriorityRounds.Outcome> out = rounds.processDue(EntryPriorityRounds.deadline(round));
        out.forEach(o -> System.out.println("[종단 간] " + o.rank() + "위 " + o.candidate().coinPair() + " "
                + o.candidate().kind() + "#" + o.candidate().sessionId() + " → " + (o.reason() == null ? "진입" : o.reason())));

        assertThat(out.stream().map(o -> o.candidate().coinPair()).toList())
                .as("승인 처리 순서 = 사전 등록 순서").containsExactly("KRW-IOTA", "KRW-CRO", "KRW-G", "KRW-INJ");
        assertThat(out.stream().filter(o -> o.reason() == null).map(o -> o.candidate().coinPair()).toList())
                .containsExactly("KRW-IOTA", "KRW-CRO");
        assertThat(out.stream().filter(o -> o.reason() != null).map(EntryPriorityRounds.Outcome::reason).toList())
                .as("나머지는 공유 풀 슬롯 상한으로 거절").allSatisfy(r -> assertThat(r).contains("SLOT_LIMIT"));

        assertThat(buys("DYNAMIC", iota.getId())).hasSize(1);
        List<OrderEntity> croBuys = buys("LIVE", cro.getId());
        assertThat(croBuys).hasSize(1);
        assertThat(croBuys.get(0).getSignalPrice()).as("승인 시점 현재가").isEqualByComparingTo("1300");
        assertThat(buys("DYNAMIC", g.getId())).as("슬롯 상한 — 주문 없음").isEmpty();
        assertThat(buys("LIVE", inj.getId())).isEmpty();

        // 1-g (2026-10-15): 두 엔진 모두 포지션에 진입 배정액(= 시장가 매수 요청액)을 남긴다 — 포지션별 CAPITAL_LOSS 의 분모.
        for (OrderEntity buy : List.of(buys("DYNAMIC", iota.getId()).get(0), croBuys.get(0))) {
            assertThat(positionRepository.findById(buy.getPositionId()).orElseThrow().getEntryAllocationKrw())
                    .as(buy.getSessionKind() + " 진입 배정액").isNotNull().isEqualByComparingTo(buy.getQuantity());
        }
    }

    @Test
    @DisplayName("대조 — 회차를 끄면(즉시 진입, 종전 동작) 같은 평가 순서에서 먼저 평가된 INJ·G 가 슬롯을 가져간다")
    void controlImmediateEntryFollowsEvaluationOrder() {
        rounds.setForcedForTest(false);   // 단계가 등록 단계가 아니므로 회차가 꺼진다
        assertThat(rounds.isActive()).isFalse();
        Instant lastOpen = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        List<Candle> cs = candles(lastOpen);
        for (String c : coins) insertCandleData(c, cs);
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(cs);

        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 2);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));

        LiveTradingSessionEntity inj = live("KRW-INJ");
        DynamicSessionEntity g = dynamic("KRW-G");
        LiveTradingSessionEntity cro = live("KRW-CRO");
        DynamicSessionEntity iota = dynamic("KRW-IOTA");

        evaluateLive(inj);
        dynamicTradingService.processTick(dynamicRepo.findById(g.getId()).orElseThrow());
        evaluateLive(cro);
        dynamicTradingService.processTick(dynamicRepo.findById(iota.getId()).orElseThrow());

        System.out.println("[종단 간 대조] INJ=" + buys("LIVE", inj.getId()).size() + " G=" + buys("DYNAMIC", g.getId()).size()
                + " CRO=" + buys("LIVE", cro.getId()).size() + " IOTA=" + buys("DYNAMIC", iota.getId()).size());
        assertThat(rounds.openRounds()).isZero();
        assertThat(buys("LIVE", inj.getId())).hasSize(1);
        assertThat(buys("DYNAMIC", g.getId())).hasSize(1);
        assertThat(buys("LIVE", cro.getId())).isEmpty();
        assertThat(buys("DYNAMIC", iota.getId())).isEmpty();
    }
}
