package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
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
import java.time.Duration;
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
 * N15 재현 (2026-11-05, 총괄 낮 작업 지시 §1) — <b>제때 접수된</b> 후보의 승인이 늦게 실행될 때.
 *
 * <p>기존 {@code lateAndDuplicateNotQueued} 는 마감 뒤 <b>도착</b>한 후보의 거절을 본다. 여기서는 마감 전에 접수된 후보를
 * 회차 처리({@code processDue})가 늦게 처리하는 경우다 — 스케줄러 풀(8) 포화·앞 후보 승인 지연으로 처리 시각이 밀린 상황을
 * {@code processDue(now)} 의 시각 인자로 표현한다. 승인 콜백 안의 엔진 관문은 실제 시계를 쓴다.</p>
 *
 * <p>대역은 {@link EntryPriorityEndToEndTest} 와 같다 — 항상 BUY 를 내는 시험 전략, 캔들 공급, 거래소 주문 클라이언트,
 * 텔레그램, 전역 위험 검사(H2 한계).</p>
 */
@DisplayName("N15 재현 — 제때 접수된 매수 후보의 늦은 승인")
class N15EntryDelayReproTest extends IntegrationTestBase {

    private static final String STRATEGY = "N15_ALWAYS_BUY";

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
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository candleDataRepository;

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
    private final List<String> coins = List.of("KRW-INJ", "KRW-CRO", "KRW-IOTA", "KRW-BTC");

    static final class AlwaysBuy implements Strategy {
        @Override public String getName() { return STRATEGY; }
        @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            return StrategySignal.buy(new BigDecimal("100"), "N15 시험 신호");
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
        limits.setStage("N15_" + UUID.randomUUID().toString().substring(0, 8));
        poolLossGuard.setEnabledSetting(true);
        rounds.setForcedForTest(true);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRisk();
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 5);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
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

    /** 닫힌 H1 캔들 300개, 완만한 상승. 마지막 봉 종가 1299. */
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

    /** 같은 캔들, 마지막 봉만 1299 → 1100(−15%) 급락. */
    private static List<Candle> crashed(List<Candle> cs) {
        List<Candle> out = new ArrayList<>(cs.subList(0, cs.size() - 1));
        Candle last = cs.get(cs.size() - 1);
        out.add(Candle.builder().time(last.getTime()).open(last.getOpen()).high(last.getHigh())
                .low(new BigDecimal("1100")).close(new BigDecimal("1100")).volume(last.getVolume()).build());
        return out;
    }

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

    private Instant setUpMarket() {
        Instant lastOpen = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        List<Candle> cs = candles(lastOpen);
        for (String c : coins) insertCandleData(c, cs);
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(cs);
        return lastOpen;
    }

    /**
     * ① LIVE — 같은 후보를 제때 접수한 뒤 마감 직후(대조)·마감 10분 뒤·다음 H1 회차 마감 뒤에 처리한다.
     * 세 경우를 서로 다른 세션·회차로 나눈다(회차 키 = 타임프레임·마감 봉 시작 시각).
     */
    @Test
    @DisplayName("① LIVE: 제때 접수된 후보는 마감 직후·10분 뒤·다음 H1 마감 뒤 어느 시각에 처리돼도 주문 의도가 생긴다")
    void liveLateApprovalStillOrders() {
        // 세 경우를 서로 다른 회차로 — 코인마다 마감 봉 시작 시각을 몇 초씩 달리한 자기 캔들 계열(같은 회차면 처리 뒤 접수가
        // '회차 마감 뒤 도착'으로 거절된다 — 1차 실행에서 확인한 시험 설계 실수).
        Instant base = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        insertCandleData("KRW-BTC", candles(base));
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(candles(base));
        List<String> order = List.of("KRW-INJ", "KRW-CRO", "KRW-IOTA");
        Map<String, Instant> processAt = new java.util.LinkedHashMap<>();
        Map<String, Instant> deadlines = new java.util.HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            String coin = order.get(i);
            Instant lastOpen = base.minusSeconds(i + 1L);
            insertCandleData(coin, candles(lastOpen));
            Instant deadline = EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("H1", lastOpen));
            Instant nextRoundDeadline = EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("H1", lastOpen.plus(1, ChronoUnit.HOURS)));
            deadlines.put(coin, deadline);
            processAt.put(coin, switch (i) {
                case 0 -> deadline;                                         // 대조: 마감 직후
                case 1 -> deadline.plus(10, ChronoUnit.MINUTES);            // 마감 10분 뒤
                default -> nextRoundDeadline.plus(1, ChronoUnit.MINUTES);   // 다음 H1 회차 마감도 지난 뒤
            });
        }

        for (var e : processAt.entrySet()) {
            String coin = e.getKey();
            Instant deadline = deadlines.get(coin);
            LiveTradingSessionEntity s = live(coin);
            evaluateLive(s);
            assertThat(rounds.openRounds()).as(coin + " 마감 전 접수").isEqualTo(1);
            assertThat(buys("LIVE", s.getId())).as(coin + " 접수만, 주문 없음").isEmpty();

            List<EntryPriorityRounds.Outcome> out = rounds.processDue(e.getValue());
            Duration late = Duration.between(deadline, e.getValue());
            List<OrderEntity> b = buys("LIVE", s.getId());
            System.out.println("[N15 ①] " + coin + " 수집 마감 " + deadline + " · 처리 " + e.getValue()
                    + " (마감 뒤 " + late.toMinutes() + "분) → 결과 "
                    + out.stream().map(o -> o.reason() == null ? "진입" : o.reason()).toList()
                    + " · 매수 의도 " + b.size() + "건"
                    + (b.isEmpty() ? "" : " · 신호가 " + b.get(0).getSignalPrice() + " · 상태 " + b.get(0).getState()
                    + " · 포지션 배정액 " + positionRepository.findById(b.get(0).getPositionId()).orElseThrow().getEntryAllocationKrw()));
            assertThat(out).hasSize(1);
            assertThat(out.get(0).reason()).as(coin + " 승인").isNull();
            assertThat(b).as(coin + " 매수 의도 1건").hasSize(1);
            assertThat(rounds.openRounds()).isZero();
            // 같은 회차는 다시 처리되지 않는다(중복 주문 없음).
            assertThat(rounds.processDue(e.getValue().plus(1, ChronoUnit.HOURS))).isEmpty();
            assertThat(buys("LIVE", s.getId())).hasSize(1);
        }
    }

    /**
     * ② DYNAMIC — 접수 뒤 같은 코인이 급락했고, 처리가 다음 H1 회차 마감 뒤로 밀렸다. 승인 경로는 평가 때 캔들의 종가로
     * 주문·손절가를 만든다(승인 시점 시세를 읽지 않는다). LIVE 는 같은 상황에서 최신 캔들의 블랙스완 관문으로 거절한다(대조).
     */
    @Test
    @DisplayName("② DYNAMIC: 다음 H1 마감 뒤 늦은 승인은 평가 때 종가(1299)로 주문·손절가를 만든다 — 그 사이 1100 급락은 보지 않는다")
    void dynamicLateApprovalUsesEvalPrice() {
        Instant lastOpen = setUpMarket();
        List<Candle> cs = candles(lastOpen);
        DynamicSessionEntity d = dynamic("KRW-IOTA");
        LiveTradingSessionEntity l = live("KRW-CRO");
        dynamicTradingService.processTick(dynamicRepo.findById(d.getId()).orElseThrow());
        evaluateLive(l);
        assertThat(rounds.openRounds()).isEqualTo(1);

        // 접수 뒤 두 코인 모두 급락(캔들 저장소·DYNAMIC 캔들 공급 모두).
        List<Candle> down = crashed(cs);
        insertCandleData("KRW-IOTA", down);
        insertCandleData("KRW-CRO", down);
        when(marketDataSyncService.fetchWithCache(eq("KRW-IOTA"), anyString(), anyInt())).thenReturn(down);
        when(marketDataSyncService.fetchWithCache(eq("KRW-CRO"), anyString(), anyInt())).thenReturn(down);

        EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        Instant late = EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("H1", lastOpen.plus(1, ChronoUnit.HOURS)))
                .plus(1, ChronoUnit.MINUTES);
        List<EntryPriorityRounds.Outcome> out = rounds.processDue(late);
        out.forEach(o -> System.out.println("[N15 ②] " + o.candidate().kind() + " " + o.candidate().coinPair()
                + " 수집 마감 " + EntryPriorityRounds.deadline(round) + " · 처리 " + late + " → "
                + (o.reason() == null ? "진입" : o.reason())));

        List<OrderEntity> db = buys("DYNAMIC", d.getId());
        assertThat(db).as("DYNAMIC 늦은 승인도 매수 의도 생성").hasSize(1);
        PositionEntity p = positionRepository.findById(db.get(0).getPositionId()).orElseThrow();
        System.out.println("[N15 ②] DYNAMIC 신호가 " + db.get(0).getSignalPrice() + " · 진입가 " + p.getEntryPrice()
                + " · 손절가 " + p.getStopLossPrice() + " · 승인 시점 최신 종가 1100");
        assertThat(db.get(0).getSignalPrice()).as("평가 때 종가").isEqualByComparingTo("1299");
        assertThat(p.getStopLossPrice()).as("손절가가 이미 승인 시점 시세(1100)보다 위").isGreaterThan(new BigDecimal("1100"));

        assertThat(buys("LIVE", l.getId())).as("LIVE 대조: 최신 캔들 급락으로 거절").isEmpty();
        assertThat(out.stream().filter(o -> "LIVE".equals(o.candidate().kind())).findFirst().orElseThrow().reason())
                .contains("BLACK_SWAN");
    }

    /**
     * ③ 한 회차 안의 줄 서기 — 앞 순위 후보의 승인 콜백이 늦게 끝나면 뒤 후보는 그만큼 늦게 승인된다. 회차 처리는 처리 시작 시각
     * 하나만 받고, 후보마다 경과를 다시 보지 않는다. (시험 시간을 아끼려 지연은 1.5초 — 경과를 비교하는 코드가 없으므로 길이와 무관)
     */
    @Test
    @DisplayName("③ 앞 후보 승인이 지연되면 뒤 후보는 그만큼 늦게 승인된다 — 후보별 경과 재검사 없음")
    void headOfLineDelayPassesThrough() {
        Instant lastOpen = setUpMarket();
        LiveTradingSessionEntity cro = live("KRW-CRO");   // 등록 순서 3위
        evaluateLive(cro);
        EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        long[] slowEnd = new long[1];
        // 등록 순서 1위(IOTA) 후보 — 승인 콜백이 1.5초 걸린다(계좌 잠금 대기·거래소 지연을 흉내).
        assertThat(rounds.submit(new EntryPriorityRounds.Candidate("LIVE", -15001L, "KRW-IOTA", round, () -> {
            try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            slowEnd[0] = System.nanoTime();
            return "N15 지연 후보(주문 없음)";
        }), Instant.now())).isNull();

        long start = System.nanoTime();
        List<EntryPriorityRounds.Outcome> out = rounds.processDue(EntryPriorityRounds.deadline(round));
        long end = System.nanoTime();
        List<OrderEntity> b = buys("LIVE", cro.getId());
        System.out.println("[N15 ③] 처리 순서 " + out.stream().map(o -> o.candidate().coinPair()).toList()
                + " · 앞 후보 지연 " + (slowEnd[0] - start) / 1_000_000 + "ms · 전체 " + (end - start) / 1_000_000
                + "ms · CRO 결과 " + out.get(1).reason() + " · CRO 매수 의도 " + b.size() + "건 · 생성 "
                + (b.isEmpty() ? "-" : b.get(0).getCreatedAt()));
        assertThat(out.stream().map(o -> o.candidate().coinPair()).toList()).containsExactly("KRW-IOTA", "KRW-CRO");
        assertThat(out.get(1).reason()).as("뒤 후보는 지연 뒤에도 승인").isNull();
        assertThat(b).hasSize(1);
        assertThat(slowEnd[0] - start).isGreaterThanOrEqualTo(1_500_000_000L);
    }
}
