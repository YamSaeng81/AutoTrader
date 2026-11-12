package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.api.util.TimeframeUtils;
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
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * N15 승인 실행 만료 (2026-11-05, 총괄 v1.106 판정 — 근거 n15-n16-review-4a4c225).
 *
 * <p>계약: 캔들 마감 C, 유예 W = min(T/2, 5분), 수집 완료 D = C+W, 실행 만료 E = D+W. <b>실제 시각이 E 이상이면 새 자금 예약·매수
 * 의도를 만들지 않는다.</b> 후보마다 콜백 직전, 그리고 계좌 잠금 안 예약 직전에 같은 시계로 다시 본다. 만료는 원래 신호의
 * {@code blocked_reason} 에 남고 회차는 닫힌 채다.</p>
 *
 * <p>🔴 기존 재현({@code N15EntryDelayReproTest})은 {@code processDue} 의 시각 인자만 미래로 넣었다. 여기서는 만료 판정 시계
 * ({@code EntryClock})에 가짜 시계를 연결해, 회차 처리 인자와 만료 판정이 같은 시각을 본다. 엔진 관문(캔들 신선도·BTC 판정)은
 * 실제 시계라서 회차를 실제 현재 근처에 둔다(E ≈ 지금 + 9분). 대기는 가짜 시계를 움직여 만들고 긴 sleep 을 쓰지 않는다.</p>
 *
 * <p>수정 전 코드(운영 fe01207)에서도 컴파일되도록 시계 연결·만료 정의 확인은 반사로 한다 — 수정 전에는 시계가 없으므로 만료가
 * 강제되지 않아 거절 기대가 실패한다(실패 먼저 원문용).</p>
 */
@DisplayName("N15 승인 실행 만료 — E=D+W 이상 새 예약·매수 의도 없음")
class N15EntryExpiryTest extends IntegrationTestBase {

    private static final String STRATEGY = "N15_EXPIRY_ALWAYS_BUY";
    private static final String EXPIRED = "승인 실행 만료";

    @Autowired private ApplicationContext ctx;
    @Autowired private EntryPriorityRounds rounds;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private CapitalReservationService reservations;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PlatformTransactionManager txManager;
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
    private final List<String> coins = List.of("KRW-INJ", "KRW-CRO", "KRW-IOTA", "KRW-ONG", "KRW-BTC");
    private final MutableClock fake = new MutableClock(Instant.now());
    private Object entryClock;

    static final class AlwaysBuy implements Strategy {
        @Override public String getName() { return STRATEGY; }
        @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            return StrategySignal.buy(new BigDecimal("100"), "N15 만료 시험 신호");
        }
        @Override public int getMinimumCandleCount() { return 50; }
    }

    /**
     * 시험이 움직이는 시계 — <b>등록한 스레드에만</b> 가짜 시각을 준다. 🔴 운영 스케줄러({@code processDueRounds}, 5초 주기)도 같은
     * 시계를 읽으므로, 모든 스레드에 미래 시각을 주면 스케줄러가 시험 회차를 먼저 처리한다(2차 실행 설계 실수). 다른 스레드는 실제 시각.
     */
    static final class MutableClock extends Clock {
        private volatile Instant now;
        private final java.util.Set<Thread> faked = java.util.concurrent.ConcurrentHashMap.newKeySet();
        MutableClock(Instant start) { this.now = start; }
        void set(Instant t) { this.now = t; }
        void fakeFor(Thread t) { faked.add(t); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return faked.contains(Thread.currentThread()) ? now : Instant.now(); }
    }

    @BeforeEach
    void setUp() throws Exception {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        StrategyRegistry.register(new AlwaysBuy());
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        limits.setStage("N15E_" + UUID.randomUUID().toString().substring(0, 8));
        poolLossGuard.setEnabledSetting(true);
        rounds.setForcedForTest(true);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRisk();
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 8);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        fake.fakeFor(Thread.currentThread());
        wireClock(fake);
    }

    @AfterEach
    void tearDown() throws Exception {
        wireClock(null);
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

    /** 만료 판정 시계에 가짜 시계를 연결한다 — 수정 전 코드에는 시계가 없다(연결 안 됨으로 기록). */
    private void wireClock(Clock c) throws Exception {
        try {
            Class<?> k = Class.forName("com.cryptoautotrader.api.service.EntryClock");
            entryClock = ctx.getBean(k);
            k.getMethod("setForTest", Clock.class).invoke(entryClock, c);
        } catch (ClassNotFoundException e) {
            entryClock = null;
        }
    }

    /** 시험 쪽 정의 — W = min(T/2, 5분), E = D + W. 운영 정의는 ⑥ 에서 대조한다. */
    static Duration windowOf(String tf) {
        Duration t = Duration.ofMinutes(TimeframeUtils.toMinutes(tf));
        return t.dividedBy(2).compareTo(Duration.ofMinutes(5)) < 0 ? t.dividedBy(2) : Duration.ofMinutes(5);
    }

    static Instant expiryOf(EntryPriorityRounds.RoundKey r) {
        return EntryPriorityRounds.deadline(r).plus(windowOf(r.timeframe()));
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

    private void insertCandleData(String coin, List<Candle> cs) {
        candleDataRepository.saveAll(cs.stream().map(c -> com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                .time(c.getTime()).coinPair(coin).timeframe("H1")
                .open(c.getOpen()).high(c.getHigh()).low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                .build()).toList());
    }

    private static final java.util.concurrent.atomic.AtomicInteger BASE_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 실제 현재 약 61분 전 시작한 봉이 마지막 닫힌 봉 — E 는 실제 현재 + 약 8분. 🔴 회차 키는 (타임프레임, 봉 시작)뿐이고 닫힌 회차는
     * 2일 보존되므로, 같은 초에 도는 시험끼리 키가 겹치지 않게 시험마다 기준을 5초씩 더 당긴다(1차 실행 설계 실수). 같은 JVM 에서 먼저
     * 돈 다른 시험 클래스는 초 단위 {@code now−61분} 을 회차 키로 쓰므로, 여기서는 밀리초 오프셋(371ms)을 붙여 그 키와 겹칠 수 없게 한다
     * (관련 시험과 함께 돈 3·4차 실행 설계 실수).
     */
    private static Instant base() {
        return Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)
                .minusSeconds(17L + 5L * BASE_SEQ.incrementAndGet()).plusMillis(371);
    }

    /** 코인마다 자기 회차(마감 봉 시작 시각을 초 단위로 달리한다). */
    private Instant market(String coin, Instant lastOpen) {
        List<Candle> cs = candles(lastOpen);
        insertCandleData(coin, cs);
        when(marketDataSyncService.fetchWithCache(eq(coin), anyString(), anyInt())).thenReturn(cs);
        return lastOpen;
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

    private void evaluateDynamic(DynamicSessionEntity d) {
        dynamicTradingService.processTick(dynamicRepo.findById(d.getId()).orElseThrow());
    }

    private List<OrderEntity> buys(String kind, Long id) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, id).stream()
                .filter(o -> "BUY".equals(o.getSide())).toList();
    }

    private long openPositions(String kind, Long id) {
        return positionRepository.findBySessionKindAndSessionId(kind, id).stream()
                .filter(p -> "OPEN".equals(p.getStatus()) || "CLOSING".equals(p.getStatus())).count();
    }

    private int exchangeCalls(String coin) {
        return (int) org.mockito.Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName()) && coin.equals(i.getArgument(0))).count();
    }

    /** 원래 신호 기록의 [실행 여부, 차단 사유]. */
    private Object[] signal(String kind, Long id, String coin) {
        List<Object[]> rows = jdbc.query("SELECT was_executed, blocked_reason FROM strategy_log WHERE session_type = ? "
                        + "AND session_id = ? AND coin_pair = ? ORDER BY id DESC",
                (rs, n) -> new Object[]{rs.getBoolean(1), rs.getString(2)}, kind, id, coin);
        return rows.isEmpty() ? new Object[]{null, "(신호 기록 없음)"} : rows.get(0);
    }

    private BigDecimal liveFunds(Long id) {
        return liveRepo.findById(id).orElseThrow().getAvailableKrw();
    }

    private BigDecimal dynFunds(Long id) {
        return dynamicRepo.findById(id).orElseThrow().getAvailableKrw();
    }

    private static String reasons(List<EntryPriorityRounds.Outcome> out) {
        return out.stream().map(o -> o.candidate().coinPair() + "=" + (o.reason() == null ? "진입" : o.reason())).toList().toString();
    }

    /**
     * ① LIVE 경계 — 실제 의도 경로. D 직후·E 직전은 진입, E·E 직후는 콜백 없이 만료(예약·의도·거래소 호출 0, 신호에 사유,
     * 회차 닫힘). 이미 확정된 주문은 시계가 E 를 지나도 그대로다.
     */
    @Test
    @DisplayName("① LIVE: D+1ms·E−1ms 진입, E·E+1ms 만료 — 예약·의도·거래소 0, 원래 신호에 만료 사유, 회차 닫힘, 확정 주문 그대로")
    void liveBoundary() {
        Instant base = base();
        insertCandleData("KRW-BTC", candles(base));
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(candles(base));
        List<String> order = List.of("KRW-INJ", "KRW-CRO", "KRW-IOTA", "KRW-ONG");
        Map<String, Long> confirmed = new LinkedHashMap<>();
        Map<Long, BigDecimal> confirmedReserved = new LinkedHashMap<>();
        Map<Long, Long> confirmedSession = new LinkedHashMap<>();
        Instant lastE = null;
        for (int i = 0; i < order.size(); i++) {
            String coin = order.get(i);
            Instant lastOpen = market(coin, base.minusSeconds(i + 1L));
            EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", lastOpen);
            Instant d = EntryPriorityRounds.deadline(round);
            Instant e = expiryOf(round);
            lastE = e;
            Instant at = switch (i) {
                case 0 -> d.plusMillis(1);
                case 1 -> e.minusMillis(1);
                case 2 -> e;
                default -> e.plusMillis(1);
            };
            boolean allowed = i < 2;
            LiveTradingSessionEntity s = live(coin);
            fake.set(Instant.now());
            evaluateLive(s);
            assertThat(rounds.openRounds()).as(coin + " 접수").isEqualTo(1);
            fake.set(at);
            List<EntryPriorityRounds.Outcome> out = rounds.processDue(at);
            List<OrderEntity> b = buys("LIVE", s.getId());
            Object[] sig = signal("LIVE", s.getId(), coin);
            System.out.println("[N15E ①] " + coin + " D " + d + " · E " + e + " · 처리·판정 " + at + " (E 대비 "
                    + Duration.between(e, at).toMillis() + "ms) → " + reasons(out) + " · 매수 의도 " + b.size()
                    + " · 열린 포지션 " + openPositions("LIVE", s.getId()) + " · 가용 " + liveFunds(s.getId())
                    + " · 거래소 호출 " + exchangeCalls(coin) + " · 신호 실행 " + sig[0] + " 사유 " + sig[1]);
            assertThat(out).hasSize(1);
            assertThat(rounds.openRounds()).as("회차 닫힘").isZero();
            if (allowed) {
                assertThat(out.get(0).reason()).as(coin + " 진입").isNull();
                assertThat(b).hasSize(1);
                assertThat(sig[0]).isEqualTo(true);
                confirmed.put(coin, b.get(0).getId());
                confirmedReserved.put(b.get(0).getId(), b.get(0).getReservedKrw());
                confirmedSession.put(b.get(0).getId(), s.getId());
            } else {
                assertThat(out.get(0).reason()).as(coin + " 만료").contains(EXPIRED);
                assertThat(b).as("매수 의도 0").isEmpty();
                assertThat(openPositions("LIVE", s.getId())).as("슬롯 0").isZero();
                assertThat(liveFunds(s.getId())).as("예약 0").isEqualByComparingTo("20000");
                assertThat(exchangeCalls(coin)).as("거래소 호출 0").isZero();
                assertThat(sig[0]).isEqualTo(false);
                assertThat((String) sig[1]).as("원래 신호에 만료 사유").contains(EXPIRED);
            }
            assertThat(rounds.processDue(at.plus(1, ChronoUnit.HOURS))).as("다시 대기열에 넣지 않음").isEmpty();
        }
        // 이미 예약·의도가 확정된 주문 — 시계가 E 를 한참 지나도 만료가 건드리지 않는다(삭제·철회·자금 복원 없음). 상태는 비동기 제출
        // 실행기가 기존 경로대로 진행시킨다(INTENT_READY → SUBMITTING 등 — 4차 실행에서 상태 동일을 단언한 것은 시험 설계 실수).
        fake.set(lastE.plus(10, ChronoUnit.MINUTES));
        rounds.processDue(lastE.plus(10, ChronoUnit.MINUTES));
        for (var c : confirmed.entrySet()) {
            OrderEntity o = orderRepository.findById(c.getValue()).orElseThrow();
            Long sid = confirmedSession.get(o.getId());
            System.out.println("[N15E ①] 확정 주문 " + c.getKey() + " #" + o.getId() + " E+10분 뒤 상태 " + o.getState()
                    + " · 예약액 " + confirmedReserved.get(o.getId()) + " → " + o.getReservedKrw() + " · 세션 가용 " + liveFunds(sid));
            assertThat(o.getState()).as("만료가 철회하지 않는다").isNotEqualTo("FAILED");
            assertThat(o.getReservedKrw()).isEqualByComparingTo(confirmedReserved.get(o.getId()));
            assertThat(liveFunds(sid)).as("자금 복원 없음").isEqualByComparingTo("15000");
        }
    }

    /** ② DYNAMIC 경계 — E−1ms 진입, E 만료(실제 의도 경로). */
    @Test
    @DisplayName("② DYNAMIC: E−1ms 진입, E 만료 — 예약·의도·거래소 0, 원래 신호에 만료 사유")
    void dynamicBoundary() {
        Instant base = base();
        insertCandleData("KRW-BTC", candles(base));
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(candles(base));
        List<String> order = List.of("KRW-IOTA", "KRW-CRO");
        for (int i = 0; i < order.size(); i++) {
            String coin = order.get(i);
            Instant lastOpen = market(coin, base.minusSeconds(i + 1L));
            EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", lastOpen);
            Instant e = expiryOf(round);
            Instant at = i == 0 ? e.minusMillis(1) : e;
            DynamicSessionEntity d = dynamic(coin);
            fake.set(Instant.now());
            evaluateDynamic(d);
            assertThat(rounds.openRounds()).as(coin + " 접수").isEqualTo(1);
            fake.set(at);
            List<EntryPriorityRounds.Outcome> out = rounds.processDue(at);
            List<OrderEntity> b = buys("DYNAMIC", d.getId());
            Object[] sig = signal("DYNAMIC", d.getId(), coin);
            System.out.println("[N15E ②] DYNAMIC " + coin + " E " + e + " · 처리·판정 " + at + " → " + reasons(out)
                    + " · 매수 의도 " + b.size() + " · 열린 포지션 " + openPositions("DYNAMIC", d.getId()) + " · 가용 "
                    + dynFunds(d.getId()) + " · 거래소 호출 " + exchangeCalls(coin) + " · 신호 실행 " + sig[0] + " 사유 " + sig[1]);
            assertThat(out).hasSize(1);
            if (i == 0) {
                assertThat(out.get(0).reason()).isNull();
                assertThat(b).hasSize(1);
            } else {
                assertThat(out.get(0).reason()).contains(EXPIRED);
                assertThat(b).isEmpty();
                assertThat(openPositions("DYNAMIC", d.getId())).isZero();
                assertThat(dynFunds(d.getId())).isEqualByComparingTo("10000");
                assertThat(exchangeCalls(coin)).isZero();
                assertThat(sig[0]).isEqualTo(false);
                assertThat((String) sig[1]).contains(EXPIRED);
            }
        }
    }

    /** ③ 앞 후보 지연 — 1위 후보의 콜백 동안 시각이 E 를 지나면 뒤 후보는 콜백 없이 만료된다(가짜 시계, sleep 없음). */
    @Test
    @DisplayName("③ 앞 후보 승인이 E 를 넘기면 뒤 LIVE 후보는 콜백 없이 만료 — 주문 0, 원래 신호에 사유")
    void headOfLineCrossesExpiry() {
        Instant base = base();
        insertCandleData("KRW-BTC", candles(base));
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(candles(base));
        Instant lastOpen = market("KRW-CRO", base.minusSeconds(1));
        EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        Instant d = EntryPriorityRounds.deadline(round);
        Instant e = expiryOf(round);
        LiveTradingSessionEntity cro = live("KRW-CRO");   // 등록 순서 3위
        fake.set(Instant.now());
        evaluateLive(cro);
        assertThat(rounds.submit(new EntryPriorityRounds.Candidate("LIVE", -15101L, "KRW-IOTA", round, () -> {
            fake.set(e);   // 1위 후보의 승인이 E 까지 걸렸다
            return "N15 지연 후보(주문 없음)";
        }), Instant.now())).isNull();
        fake.set(d.plusMillis(1));
        List<EntryPriorityRounds.Outcome> out = rounds.processDue(d.plusMillis(1));
        Object[] sig = signal("LIVE", cro.getId(), "KRW-CRO");
        System.out.println("[N15E ③] 처리 시작 D+1ms · 1위 콜백 뒤 시계 " + e + " → " + reasons(out) + " · CRO 매수 의도 "
                + buys("LIVE", cro.getId()).size() + " · 신호 사유 " + sig[1]);
        assertThat(out.stream().map(o -> o.candidate().coinPair()).toList()).containsExactly("KRW-IOTA", "KRW-CRO");
        assertThat(out.get(1).reason()).contains(EXPIRED);
        assertThat(buys("LIVE", cro.getId())).isEmpty();
        assertThat(liveFunds(cro.getId())).isEqualByComparingTo("20000");
        assertThat((String) sig[1]).contains(EXPIRED);
    }

    /** ④ 자기 관문 대기 — 콜백 직전은 E 전이지만 엔진 관문 안에서 E 를 지나면 잠금 안 예약 직전 재검사가 막는다. */
    @Test
    @DisplayName("④ 자기 관문 대기 중 E 통과 — LIVE·DYNAMIC 모두 잠금 안 재검사로 예약·의도·슬롯·거래소 0, 신호에 ENTRY_EXPIRED")
    void ownGateCrossesExpiry() {
        Instant base = base();
        insertCandleData("KRW-BTC", candles(base));
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(candles(base));
        // LIVE — 승인 관문의 전역 위험 검사 동안 시각이 E 에 닿는다
        Instant liveOpen = market("KRW-CRO", base.minusSeconds(1));
        Instant eLive = expiryOf(new EntryPriorityRounds.RoundKey("H1", liveOpen));
        LiveTradingSessionEntity l = live("KRW-CRO");
        fake.set(Instant.now());
        evaluateLive(l);
        doAnswer(inv -> {
            fake.set(eLive);
            return com.cryptoautotrader.core.risk.RiskCheckResult.approve();
        }).when(riskManagementService).checkRisk();
        fake.set(eLive.minusMillis(1));
        List<EntryPriorityRounds.Outcome> outL = rounds.processDue(eLive.minusMillis(1));
        Object[] sigL = signal("LIVE", l.getId(), "KRW-CRO");
        System.out.println("[N15E ④] LIVE 콜백 직전 E−1ms · 관문 안에서 E → " + reasons(outL) + " · 매수 의도 "
                + buys("LIVE", l.getId()).size() + " · 포지션 " + positionRepository.findBySessionKindAndSessionId("LIVE", l.getId()).size()
                + " · 가용 " + liveFunds(l.getId()) + " · 거래소 " + exchangeCalls("KRW-CRO") + " · 신호 사유 " + sigL[1]);
        assertThat(outL.get(0).reason()).contains("ENTRY_EXPIRED");
        assertThat(buys("LIVE", l.getId())).isEmpty();
        assertThat(positionRepository.findBySessionKindAndSessionId("LIVE", l.getId())).as("예약 트랜잭션 롤백 — 슬롯 0").isEmpty();
        assertThat(liveFunds(l.getId())).isEqualByComparingTo("20000");
        assertThat(exchangeCalls("KRW-CRO")).isZero();
        assertThat((String) sigL[1]).contains("ENTRY_EXPIRED");

        // DYNAMIC — 승인 관문의 서킷 브레이커 검사 동안 시각이 E 에 닿는다
        Instant dynOpen = market("KRW-IOTA", base.minusSeconds(2));
        Instant eDyn = expiryOf(new EntryPriorityRounds.RoundKey("H1", dynOpen));
        DynamicSessionEntity d = dynamic("KRW-IOTA");
        fake.set(Instant.now());
        evaluateDynamic(d);
        doAnswer(inv -> {
            fake.set(eDyn);
            return inv.callRealMethod();
        }).when(riskManagementService).checkCircuitBreaker(any(DynamicSessionEntity.class));
        fake.set(eDyn.minusMillis(1));
        List<EntryPriorityRounds.Outcome> outD = rounds.processDue(eDyn.minusMillis(1));
        Object[] sigD = signal("DYNAMIC", d.getId(), "KRW-IOTA");
        System.out.println("[N15E ④] DYNAMIC 콜백 직전 E−1ms · 관문 안에서 E → " + reasons(outD) + " · 매수 의도 "
                + buys("DYNAMIC", d.getId()).size() + " · 열린 포지션 " + openPositions("DYNAMIC", d.getId())
                + " · 가용 " + dynFunds(d.getId()) + " · 거래소 " + exchangeCalls("KRW-IOTA") + " · 신호 사유 " + sigD[1]);
        assertThat(outD.get(0).reason()).contains("ENTRY_EXPIRED");
        assertThat(buys("DYNAMIC", d.getId())).isEmpty();
        assertThat(openPositions("DYNAMIC", d.getId())).isZero();
        assertThat(dynFunds(d.getId())).isEqualByComparingTo("10000");
        assertThat(exchangeCalls("KRW-IOTA")).isZero();
        assertThat((String) sigD[1]).contains("ENTRY_EXPIRED");
    }

    /** ⑤ 계좌 잠금 대기 — 다른 트랜잭션이 잠금을 쥔 동안 E 를 지나면, 잠금을 얻은 뒤 예약 직전에 막힌다. */
    @Test
    @DisplayName("⑤ 계좌 잠금 대기 중 E 통과 — 잠금을 얻은 뒤 재검사로 예약·의도 0, 신호에 ENTRY_EXPIRED")
    void lockWaitCrossesExpiry() throws Exception {
        Instant base = base();
        insertCandleData("KRW-BTC", candles(base));
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(candles(base));
        Instant lastOpen = market("KRW-CRO", base.minusSeconds(1));
        Instant e = expiryOf(new EntryPriorityRounds.RoundKey("H1", lastOpen));
        LiveTradingSessionEntity l = live("KRW-CRO");
        fake.set(Instant.now());
        evaluateLive(l);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> approver = new AtomicReference<>();
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(st -> {
                reservations.lockAccountBeforeSession("N15 시험 — 잠금 점유");
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            fake.set(e.minusMillis(1));
            Future<List<EntryPriorityRounds.Outcome>> result = pool.submit(() -> {
                fake.fakeFor(Thread.currentThread());
                approver.set(Thread.currentThread());
                return rounds.processDue(e.minusMillis(1));
            });
            boolean waiting = false;
            long until = System.currentTimeMillis() + 5_000;
            while (!waiting && System.currentTimeMillis() < until) {
                Thread a = approver.get();
                waiting = a != null && Arrays.stream(a.getStackTrace()).anyMatch(f ->
                        "lockAccount".equals(f.getMethodName()) && f.getClassName().contains("CapitalReservationService"));
                if (!waiting) Thread.sleep(2);
            }
            fake.set(e);   // 잠금을 기다리는 동안 E 에 닿았다
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            List<EntryPriorityRounds.Outcome> out = result.get(20, TimeUnit.SECONDS);
            Object[] sig = signal("LIVE", l.getId(), "KRW-CRO");
            System.out.println("[N15E ⑤] 잠금 대기 관측 " + waiting + " · 대기 중 시계 E → " + reasons(out) + " · 매수 의도 "
                    + buys("LIVE", l.getId()).size() + " · 포지션 " + positionRepository.findBySessionKindAndSessionId("LIVE", l.getId()).size()
                    + " · 가용 " + liveFunds(l.getId()) + " · 신호 사유 " + sig[1]);
            assertThat(waiting).as("승인 스레드가 계좌 잠금에서 기다렸다").isTrue();
            assertThat(out.get(0).reason()).contains("ENTRY_EXPIRED");
            assertThat(buys("LIVE", l.getId())).isEmpty();
            assertThat(positionRepository.findBySessionKindAndSessionId("LIVE", l.getId())).isEmpty();
            assertThat(liveFunds(l.getId())).isEqualByComparingTo("20000");
            assertThat((String) sig[1]).contains("ENTRY_EXPIRED");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * ⑥ 정의와 짧은 타임프레임 — 운영 정의(반사)가 시험 쪽 정의와 같고, W=T/2 인 짧은 봉은 E 가 다음 캔들 마감과 같다. M5 회차의
     * 후보는 E−1ms 에 콜백, E 에는 콜백 없음.
     */
    @Test
    @DisplayName("⑥ 정의 W=min(T/2,5분)·D=C+W·E=D+W, H1 09:00→09:05→09:10, M1·M5 는 E=다음 마감 — M5 E−1ms 콜백, E 콜백 없음")
    void definitionAndShortTimeframe() throws Exception {
        java.lang.reflect.Method expiry = EntryPriorityRounds.class.getMethod("expiry", EntryPriorityRounds.RoundKey.class);
        Instant c0 = Instant.parse("2026-12-01T00:00:00Z");   // 09:00 KST 시작 봉의 시작이 아니라 임의 정시
        for (String tf : List.of("M1", "M5", "M15", "H1", "H4", "D1")) {
            EntryPriorityRounds.RoundKey r = new EntryPriorityRounds.RoundKey(tf, c0);
            Instant c = c0.plus(Duration.ofMinutes(TimeframeUtils.toMinutes(tf)));
            Instant e = (Instant) expiry.invoke(null, r);
            System.out.println("[N15E ⑥] " + tf + " C " + c + " · W " + windowOf(tf) + " · D " + EntryPriorityRounds.deadline(r) + " · E " + e);
            assertThat(e).isEqualTo(expiryOf(r));
            assertThat(e).isEqualTo(c.plus(windowOf(tf).multipliedBy(2)));
        }
        // H1: 08:00 KST 시작 봉 → 09:00 마감 → 09:05 수집 완료 → 09:10 부터 금지
        EntryPriorityRounds.RoundKey h1 = new EntryPriorityRounds.RoundKey("H1", Instant.parse("2026-11-30T23:00:00Z"));
        assertThat(EntryPriorityRounds.deadline(h1)).isEqualTo(Instant.parse("2026-12-01T00:05:00Z"));
        assertThat((Instant) expiry.invoke(null, h1)).isEqualTo(Instant.parse("2026-12-01T00:10:00Z"));
        // 짧은 봉: E = 다음 캔들 마감
        for (String tf : List.of("M1", "M5")) {
            EntryPriorityRounds.RoundKey r = new EntryPriorityRounds.RoundKey(tf, c0);
            Duration t = Duration.ofMinutes(TimeframeUtils.toMinutes(tf));
            assertThat((Instant) expiry.invoke(null, r)).as(tf + " E = 다음 캔들 마감").isEqualTo(c0.plus(t.multipliedBy(2)));
        }
        // M5 회차: E−1ms 콜백, E 콜백 없음(회차가 다르도록 시작 시각을 달리한다)
        int[] called = new int[2];
        EntryPriorityRounds.RoundKey m5a = new EntryPriorityRounds.RoundKey("M5", Instant.parse("2027-02-01T00:00:00Z"));
        EntryPriorityRounds.RoundKey m5b = new EntryPriorityRounds.RoundKey("M5", Instant.parse("2027-02-01T00:05:00Z"));
        fake.set(Instant.parse("2027-02-01T00:00:01Z"));
        assertThat(rounds.submit(new EntryPriorityRounds.Candidate("LIVE", -15201L, "KRW-IOTA", m5a, () -> { called[0]++; return "시험(주문 없음)"; }),
                Instant.parse("2027-02-01T00:05:00Z"))).isNull();
        assertThat(rounds.submit(new EntryPriorityRounds.Candidate("LIVE", -15202L, "KRW-IOTA", m5b, () -> { called[1]++; return "시험(주문 없음)"; }),
                Instant.parse("2027-02-01T00:10:00Z"))).isNull();
        Instant ea = expiryOf(m5a);
        Instant eb = expiryOf(m5b);
        fake.set(ea.minusMillis(1));
        List<EntryPriorityRounds.Outcome> outA = rounds.processDue(ea.minusMillis(1));
        fake.set(eb);
        List<EntryPriorityRounds.Outcome> outB = rounds.processDue(eb);
        System.out.println("[N15E ⑥] M5 E−1ms " + reasons(outA) + " 콜백 " + called[0] + " · M5 E " + reasons(outB) + " 콜백 " + called[1]);
        assertThat(called[0]).isEqualTo(1);
        assertThat(called[1]).as("경계 E 는 허용하지 않는다").isZero();
        assertThat(outB.get(0).reason()).contains(EXPIRED);
    }
}
