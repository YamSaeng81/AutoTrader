package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.core.portfolio.PortfolioManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선행 조건 3번 3-e — 코인 우선순위 (2026-10-14).
 *
 * <p>같은 평가 회차에 슬롯(3)보다 많은 후보(6)가 들어올 때, <b>후보가 도착한 순서·스레드 실행 순서를 바꿔도</b> 승인되는 코인이
 * 사전 등록 순서의 앞 셋으로 같고, 상한 초과·중복 승인이 없어야 한다. 승인은 실제 예약 경계({@code reserveAndPrepareIntent},
 * 계좌 잠금)를 지난다.</p>
 */
@DisplayName("코인 우선순위 — 같은 회차 후보를 사전 등록 순서로 승인")
class EntryPriorityRoundsTest extends IntegrationTestBase {

    /** 등록 순서: IOTA(1) CRO(3) NEAR(7) HIVE(16) G(21) INJ(22) → 슬롯 3 이면 IOTA·CRO·NEAR. */
    private static final List<String> COINS = List.of("KRW-INJ", "KRW-G", "KRW-CRO", "KRW-IOTA", "KRW-NEAR", "KRW-HIVE");
    private static final List<String> EXPECTED = List.of("KRW-IOTA", "KRW-CRO", "KRW-NEAR");

    @Autowired private EntryPriorityRounds rounds;
    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired private PortfolioManager portfolioManager;
    @Autowired private LiveTradingSessionRepository sessionRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private PortfolioSyncService portfolioSyncService;

    private String savedStage;
    private BigDecimal savedTotal;
    private BigDecimal savedPer;
    private Integer savedSlots;
    private final List<Long> sessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        // 손실 한도 기준을 이 시험만의 단계로 분리한다(앞선 시험의 현금 흐름이 섞이지 않게).
        limits.setStage("PRIO_" + UUID.randomUUID().toString().substring(0, 8));
        poolLossGuard.setEnabledSetting(true);
        rounds.setForcedForTest(true);
        portfolioManager.syncTotalCapital(new BigDecimal("1000000000000"));
    }

    @AfterEach
    void tearDown() {
        rounds.setForcedForTest(false);
        cleanSessions();
        jdbc.update("DELETE FROM capital_stage_baseline WHERE stage = ?", limits.getStage());
        limits.setStage(savedStage);
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
    }

    private void cleanSessions() {
        for (Long id : sessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            sessionRepository.findById(id).ifPresent(sessionRepository::delete);
        }
        sessions.clear();
    }

    /** 슬롯을 지금 점유 + 3 으로 — 앞선 시험이 남긴 열린 포지션 위에 정확히 3 자리. */
    private void threeFreeSlots() {
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 3);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
    }

    private Long session(String coin) {
        Long id = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("20000")).availableKrw(new BigDecimal("20000"))
                .totalAssetKrw(new BigDecimal("20000")).status("RUNNING").startedAt(Instant.now()).build()).getId();
        sessions.add(id);
        return id;
    }

    /** 운영 매수와 같은 경계 — 슬롯 포지션 + 예약 + 의도를 한 트랜잭션에서. @return 진입이면 null, 아니면 거절 코드 */
    private String reserve(Long sessionId, String coin) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            tx.execute(st -> {
                PositionEntity pos = positionRepository.save(PositionEntity.builder()
                        .coinPair(coin).side("BUY").entryPrice(new BigDecimal("100")).avgPrice(new BigDecimal("100"))
                        .size(BigDecimal.ZERO).investedKrw(new BigDecimal("10000"))
                        .status("OPEN").sessionKind("LIVE").sessionId(sessionId).openedAt(Instant.now()).build());
                OrderRequest req = new OrderRequest();
                req.setCoinPair(coin);
                req.setSide("BUY");
                req.setOrderType("MARKET");
                req.setQuantity(new BigDecimal("10000"));
                req.setReason("코인 우선순위 시험");
                req.setSignalPrice(new BigDecimal("100"));
                req.setSessionId(sessionId);
                req.setSessionKind("LIVE");
                req.setPositionId(pos.getId());
                return orderExecutionEngine.reserveAndPrepareIntent(req);
            });
            return null;
        } catch (OrderExecutionEngine.ReservationRejected r) {
            return r.getCode();
        }
    }

    private record Run(List<String> approved, Map<String, Integer> calls, List<EntryPriorityRounds.Outcome> outcomes,
                       long slotsBefore, long slotsAfter) {}

    /**
     * 한 회차: 후보 6개를 {@code order} 순서로, 스레드 {@code threads} 개가 무작위 지연을 두고 동시에 접수한다. 마감 뒤 승인을
     * 두 스레드가 동시에 부른다(한 회차는 한 번만 처리돼야 한다).
     */
    private Run runRound(List<String> order, int threads, long seed) throws Exception {
        threeFreeSlots();
        Map<String, Long> ids = new java.util.HashMap<>();
        for (String c : COINS) ids.put(c, session(c));
        Instant candle = Instant.parse("2026-10-14T00:00:00Z").plus(new Random(seed).nextInt(10_000), ChronoUnit.HOURS);
        EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", candle);
        Instant beforeDeadline = candle.plus(61, ChronoUnit.MINUTES);
        Map<String, Integer> calls = new ConcurrentHashMap<>();
        long slotsBefore = positionRepository.countPoolSlots(-1L);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        Random rnd = new Random(seed);
        List<java.util.concurrent.Future<String>> fs = new ArrayList<>();
        for (String coin : order) {
            int delay = rnd.nextInt(30);
            fs.add(pool.submit(() -> {
                go.await();
                Thread.sleep(delay);
                return rounds.submit(new EntryPriorityRounds.Candidate("LIVE", ids.get(coin), coin, round, () -> {
                    calls.merge(coin, 1, Integer::sum);
                    return reserve(ids.get(coin), coin);
                }), beforeDeadline);
            }));
        }
        go.countDown();
        for (var f : fs) assertThat(f.get(10, TimeUnit.SECONDS)).as("마감 전 접수").isNull();

        // 마감 전에는 아무것도 승인하지 않는다.
        assertThat(rounds.processDue(beforeDeadline)).isEmpty();
        assertThat(calls).isEmpty();

        Instant after = EntryPriorityRounds.deadline(round);
        List<EntryPriorityRounds.Outcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go2 = new CountDownLatch(1);
        var a = pool.submit(() -> { go2.await(); outcomes.addAll(rounds.processDue(after)); return null; });
        var b = pool.submit(() -> { go2.await(); outcomes.addAll(rounds.processDue(after)); return null; });
        go2.countDown();
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        List<String> approved = outcomes.stream().filter(o -> o.reason() == null)
                .sorted(java.util.Comparator.comparingInt(EntryPriorityRounds.Outcome::rank))
                .map(o -> o.candidate().coinPair()).collect(Collectors.toList());
        Run r = new Run(approved, calls, new ArrayList<>(outcomes), slotsBefore, positionRepository.countPoolSlots(-1L));
        System.out.println("[우선순위] 접수순서=" + order + " 스레드=" + threads + " → 승인=" + approved
                + " 거절=" + outcomes.stream().filter(o -> o.reason() != null).map(o -> o.candidate().coinPair() + ":" + o.reason()).toList()
                + " 슬롯 " + slotsBefore + "→" + r.slotsAfter());
        return r;
    }

    private void assertDeterministic(Run r) {
        assertThat(r.approved()).as("사전 등록 순서의 앞 셋").containsExactlyElementsOf(EXPECTED);
        assertThat(r.calls()).as("후보마다 승인 시도는 한 번(두 처리 스레드가 겹쳐도)").hasSize(6)
                .allSatisfy((k, v) -> assertThat(v).isEqualTo(1));
        assertThat(r.outcomes()).hasSize(6);
        assertThat(r.outcomes().stream().filter(o -> o.reason() != null).map(EntryPriorityRounds.Outcome::reason))
                .as("나머지는 슬롯 상한으로 거절").allMatch("SLOT_LIMIT"::equals);
        assertThat(r.slotsAfter() - r.slotsBefore()).as("상한 초과 없음 — 정확히 3 슬롯").isEqualTo(3);
        assertThat(r.outcomes().stream().map(EntryPriorityRounds.Outcome::rank).toList())
                .as("등록 순서대로 처리").containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
    }

    @Test
    @DisplayName("도착 순서·스레드 수를 바꿔도 승인 코인이 같다(IOTA·CRO·NEAR), 상한 초과·중복 승인 없음")
    void sameApprovalRegardlessOfArrivalAndThreads() throws Exception {
        List<List<String>> orders = new ArrayList<>();
        orders.add(COINS);
        List<String> reversed = new ArrayList<>(COINS);
        Collections.reverse(reversed);
        orders.add(reversed);
        for (long seed = 1; seed <= 4; seed++) {
            List<String> shuffled = new ArrayList<>(COINS);
            Collections.shuffle(shuffled, new Random(seed));
            orders.add(shuffled);
        }
        int[] threadCounts = {1, 6, 3, 6, 2, 6};
        for (int i = 0; i < orders.size(); i++) {
            Run r = runRound(orders.get(i), threadCounts[i], 100 + i);
            assertDeterministic(r);
            cleanSessions();
        }
    }

    @Test
    @DisplayName("대조 — 회차 없이 도착 순서대로 즉시 예약하면(종전 동작) 승인 코인이 도착 순서를 따른다")
    void immediateEntryFollowsArrivalOrder() {
        threeFreeSlots();
        List<String> arrival = List.of("KRW-INJ", "KRW-G", "KRW-CRO", "KRW-IOTA", "KRW-NEAR", "KRW-HIVE");
        List<String> approved = new ArrayList<>();
        for (String coin : arrival) {
            if (reserve(session(coin), coin) == null) approved.add(coin);
        }
        System.out.println("[우선순위 대조] 즉시 진입 도착순서=" + arrival + " → 승인=" + approved);
        assertThat(approved).containsExactly("KRW-INJ", "KRW-G", "KRW-CRO").isNotEqualTo(EXPECTED);
    }

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private com.cryptoautotrader.api.repository.DynamicSessionRepository dynamicRepo;

    @Test
    @DisplayName("승인 차례의 재확인 — 평가 뒤 정지된 세션(LIVE·DYNAMIC)은 진입하지 않는다")
    void approvalRechecksSessionState() {
        Long live = session("KRW-IOTA");
        jdbc.update("UPDATE live_trading_session SET status = 'STOPPED' WHERE id = ?", live);
        String r1 = liveTradingService.approveQueuedEntry(live, "KRW-IOTA", new BigDecimal("100"), "시험",
                com.cryptoautotrader.strategy.StrategySignal.buy(new BigDecimal("80"), "시험"), "TREND", List.of(), null);
        assertThat(r1).contains("실행 중이 아니다");

        Long dyn = dynamicRepo.saveAndFlush(com.cryptoautotrader.api.entity.DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("10000")).totalAssetKrw(new BigDecimal("10000"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("STOPPED").scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000")).watchlistRefreshMin(60)
                .build()).getId();
        try {
            String r2 = dynamicTradingService.approveQueuedEntry(dyn, "KRW-IOTA", List.of(),
                    com.cryptoautotrader.strategy.StrategySignal.buy(new BigDecimal("80"), "시험"), BigDecimal.ONE, null);
            assertThat(r2).contains("실행 중이 아니다");
            assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", dyn)).isEmpty();
        } finally {
            dynamicRepo.deleteById(dyn);
        }
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", live)).isEmpty();
    }

    @Test
    @DisplayName("마감 뒤 도착한 후보와 같은 세션의 두 번째 후보는 받지 않는다")
    void lateAndDuplicateNotQueued() {
        threeFreeSlots();
        Long s = session("KRW-IOTA");
        EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", Instant.parse("2027-01-01T00:00:00Z"));
        AtomicInteger called = new AtomicInteger();
        EntryPriorityRounds.Candidate c = new EntryPriorityRounds.Candidate("LIVE", s, "KRW-IOTA", round,
                () -> { called.incrementAndGet(); return null; });
        assertThat(rounds.submit(c, EntryPriorityRounds.deadline(round).plusSeconds(1))).contains("회차 마감 뒤 도착");
        assertThat(rounds.submit(c, round.candleOpenTime().plus(61, ChronoUnit.MINUTES))).isNull();
        assertThat(rounds.submit(c, round.candleOpenTime().plus(62, ChronoUnit.MINUTES))).contains("이미 있다");
        assertThat(rounds.processDue(EntryPriorityRounds.deadline(round))).hasSize(1);
        // 처리된 회차에는 다시 넣을 수 없다(시각이 마감 전이라도).
        assertThat(rounds.submit(c, round.candleOpenTime().plus(61, ChronoUnit.MINUTES))).contains("회차 마감 뒤 도착");
        assertThat(called.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("마감 시각 — H1 은 캔들 마감 + 5분, M5 는 마감 + 2분 30초")
    void deadlineDefinition() {
        Instant t = Instant.parse("2026-10-14T03:00:00Z");
        assertThat(EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("H1", t))).isEqualTo(Instant.parse("2026-10-14T04:05:00Z"));
        assertThat(EntryPriorityRounds.deadline(new EntryPriorityRounds.RoundKey("M5", t))).isEqualTo(Instant.parse("2026-10-14T03:07:30Z"));
    }

    @Test
    @DisplayName("등록 단계(STAGE_1·STAGE_2)에서만 켜진다 — 시험 단계는 즉시 진입")
    void activeOnlyInRegisteredStages() {
        rounds.setForcedForTest(false);
        String keep = limits.getStage();
        try {
            limits.setStage("STAGE_1");
            assertThat(rounds.isActive()).isTrue();
            limits.setStage("STAGE_2");
            assertThat(rounds.isActive()).isTrue();
            limits.setStage("TEST");
            assertThat(rounds.isActive()).isFalse();
        } finally {
            limits.setStage(keep);
        }
    }
}
