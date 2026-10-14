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
    /** 전역 위험 검사만 통과로 고정 — 손실 집계 질의가 PostgreSQL JSON 연산자를 써서 H2 에서 실행되지 않는다. */
    @org.springframework.boot.test.mock.mockito.SpyBean private RiskManagementService riskManagementService;
    /** LIVE 엔진의 캔들 원천 — {@code market_data_cache} (엔진 필드 이름은 candleDataRepository 이지만 이 저장소다). */
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository candleDataRepository;

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
        org.mockito.Mockito.doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve())
                .when(riskManagementService).checkRisk();
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
        String r1 = liveTradingService.approveQueuedEntry(live, "KRW-IOTA", "시험",
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
    @DisplayName("같은 코인 동순위 — 여러 세션이 같은 코인을 내면 접수 순서와 무관하게 세션 id 가 작은 쪽부터 승인")
    void sameCoinTieBreakIgnoresArrival() throws Exception {
        for (int run = 0; run < 6; run++) {
            limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 2);
            limits.setPerPositionCapKrw(new BigDecimal("20000"));
            limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 4; i++) ids.add(session("KRW-IOTA"));
            List<Long> arrival = new ArrayList<>(ids);
            if (run == 0) {
                Collections.reverse(arrival);   // 가장 큰 id 가 먼저 도착
            } else {
                Collections.shuffle(arrival, new Random(run));
            }
            EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1",
                    Instant.parse("2027-02-01T00:00:00Z").plus(run, ChronoUnit.HOURS));
            Instant beforeDeadline = round.candleOpenTime().plus(61, ChronoUnit.MINUTES);
            ExecutorService pool = Executors.newFixedThreadPool(1 + run % 4);
            CountDownLatch go = new CountDownLatch(1);
            List<java.util.concurrent.Future<String>> fs = new ArrayList<>();
            for (Long id : arrival) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return rounds.submit(new EntryPriorityRounds.Candidate("LIVE", id, "KRW-IOTA", round,
                            () -> reserve(id, "KRW-IOTA")), beforeDeadline);
                }));
            }
            go.countDown();
            for (var f : fs) assertThat(f.get(10, TimeUnit.SECONDS)).isNull();
            pool.shutdown();
            List<EntryPriorityRounds.Outcome> out = rounds.processDue(EntryPriorityRounds.deadline(round));
            List<Long> approved = out.stream().filter(o -> o.reason() == null).map(o -> o.candidate().sessionId()).toList();
            System.out.println("[동순위] 접수순서=" + arrival + " → 승인=" + approved);
            List<Long> sorted = ids.stream().sorted().toList();
            assertThat(approved).as("세션 id 가 작은 두 세션").containsExactly(sorted.get(0), sorted.get(1));
            assertThat(out.stream().filter(o -> o.reason() != null).map(EntryPriorityRounds.Outcome::reason))
                    .hasSize(2).allMatch("SLOT_LIMIT"::equals);
            cleanSessions();
        }
    }

    @Test
    @DisplayName("동순위 규칙 — 같은 코인이면 LIVE 다음 DYNAMIC, 그 안에서 세션 id 순(모든 접수 순서에서 같은 정렬)")
    void tieBreakOrderIsFixedForAllPermutations() {
        List<EntryPriorityRounds.Candidate> cs = List.of(
                cand("DYNAMIC", 5L, "KRW-IOTA"), cand("LIVE", 9L, "KRW-IOTA"), cand("DYNAMIC", 2L, "KRW-IOTA"),
                cand("LIVE", 3L, "KRW-IOTA"), cand("LIVE", 1L, "KRW-CRO"));
        List<String> expected = List.of("LIVE#3", "LIVE#9", "DYNAMIC#2", "DYNAMIC#5", "LIVE#1");
        List<List<EntryPriorityRounds.Candidate>> perms = new ArrayList<>();
        permute(new ArrayList<>(cs), 0, perms);
        assertThat(perms).hasSize(120);
        for (var p : perms) {
            List<EntryPriorityRounds.Candidate> sorted = new ArrayList<>(p);
            sorted.sort(EntryPriorityRounds.ORDER);
            assertThat(sorted.stream().map(c -> c.kind() + "#" + c.sessionId()).toList()).containsExactlyElementsOf(expected);
        }
    }

    private static EntryPriorityRounds.Candidate cand(String kind, Long id, String coin) {
        return new EntryPriorityRounds.Candidate(kind, id, coin,
                new EntryPriorityRounds.RoundKey("H1", Instant.parse("2027-03-01T00:00:00Z")), () -> null);
    }

    private static void permute(List<EntryPriorityRounds.Candidate> a, int k, List<List<EntryPriorityRounds.Candidate>> out) {
        if (k == a.size()) {
            out.add(new ArrayList<>(a));
            return;
        }
        for (int i = k; i < a.size(); i++) {
            Collections.swap(a, k, i);
            permute(a, k + 1, out);
            Collections.swap(a, k, i);
        }
    }

    private Long realDynamic(String status) {
        return dynamicRepo.saveAndFlush(com.cryptoautotrader.api.entity.DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("10000")).totalAssetKrw(new BigDecimal("10000"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status(status).scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000")).watchlistRefreshMin(60)
                .startedAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .build()).getId();
    }

    private PositionEntity dynPosition(Long sessionId, String coin, String status, BigDecimal realized, Instant closedAt) {
        return positionRepository.save(PositionEntity.builder()
                .coinPair(coin).side("BUY").entryPrice(new BigDecimal("100")).avgPrice(new BigDecimal("100"))
                .size(BigDecimal.ONE).investedKrw(new BigDecimal("100")).status(status)
                .realizedPnl(realized).closedAt(closedAt)
                .sessionKind("DYNAMIC").sessionId(sessionId).openedAt(Instant.now().minus(2, ChronoUnit.HOURS)).build());
    }

    private static List<com.cryptoautotrader.strategy.Candle> risingCandles() {
        List<com.cryptoautotrader.strategy.Candle> out = new ArrayList<>();
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(60, ChronoUnit.HOURS);
        for (int i = 0; i < 60; i++) {
            BigDecimal p = new BigDecimal(1000 + i);
            out.add(com.cryptoautotrader.strategy.Candle.builder().time(t0.plus(i, ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.ONE)).low(p.subtract(BigDecimal.ONE)).close(p)
                    .volume(new BigDecimal("1000")).build());
        }
        return out;
    }

    @Test
    @DisplayName("🔴 승인 시점 재검사(DYNAMIC) — 평가 뒤 같은 코인 보유가 상한에 닿거나 손실 청산 쿨다운이 생기면 진입하지 않는다")
    void dynamicApprovalRechecksTimeVaryingGates() {
        Long dyn = realDynamic("RUNNING");
        List<PositionEntity> made = new ArrayList<>();
        try {
            // ① 같은 코인을 다른 동적 세션 둘이 이미 든 상태(같은 회차의 앞 순위가 승인된 상황)
            made.add(dynPosition(-9101L, "KRW-WAVES", "OPEN", null, null));
            made.add(dynPosition(-9102L, "KRW-WAVES", "OPEN", null, null));
            String r1 = dynamicTradingService.approveQueuedEntry(dyn, "KRW-WAVES", risingCandles(),
                    com.cryptoautotrader.strategy.StrategySignal.buy(new BigDecimal("80"), "시험"), BigDecimal.ONE, null);
            System.out.println("[승인 재검사 DYNAMIC] 동일 코인 → " + r1);
            assertThat(r1).contains("승인 차례에").contains("동일코인 노출 상한");

            // ② 평가 뒤 이 세션이 같은 코인을 손실로 청산했다
            made.add(dynPosition(dyn, "KRW-ONG", "CLOSED", new BigDecimal("-50"), Instant.now().minus(5, ChronoUnit.MINUTES)));
            String r2 = dynamicTradingService.approveQueuedEntry(dyn, "KRW-ONG", risingCandles(),
                    com.cryptoautotrader.strategy.StrategySignal.buy(new BigDecimal("80"), "시험"), BigDecimal.ONE, null);
            System.out.println("[승인 재검사 DYNAMIC] 손실 쿨다운 → " + r2);
            assertThat(r2).contains("승인 차례에").contains("손실 청산 쿨다운");

            assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", dyn)).isEmpty();
            assertThat(positionRepository.findBySessionKindAndSessionIdAndStatus("DYNAMIC", dyn, "OPEN")).isEmpty();
        } finally {
            made.forEach(positionRepository::delete);
            dynamicRepo.deleteById(dyn);
        }
    }

    @Test
    @DisplayName("🔴 승인 시점 재검사(LIVE) — 평가 뒤 최신 캔들에 급락이 생기면 블랙스완 관문으로 진입하지 않는다")
    void liveApprovalRechecksLatestCandles() {
        Long live = session("KRW-POLYX");
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(29, ChronoUnit.HOURS);
        try {
            // 마지막(진행 중) 캔들이 1000 → 880, -12% — 평가 때는 없던 급락.
            for (int i = 0; i < 30; i++) {
                boolean last = i == 29;
                candleDataRepository.save(com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                        .time(t0.plus(i, ChronoUnit.HOURS)).coinPair("KRW-POLYX").timeframe("H1")
                        .open(new BigDecimal("1000")).high(new BigDecimal("1001"))
                        .low(last ? new BigDecimal("880") : new BigDecimal("999"))
                        .close(last ? new BigDecimal("880") : new BigDecimal("1000")).volume(new BigDecimal("1000")).build());
            }
            String r = liveTradingService.approveQueuedEntry(live, "KRW-POLYX", "시험",
                    com.cryptoautotrader.strategy.StrategySignal.buy(new BigDecimal("80"), "시험"), "TREND", List.of(), null);
            System.out.println("[승인 재검사 LIVE] 최신 캔들 급락 → " + r);
            assertThat(r).contains("승인 차례에 BLACK_SWAN_GUARD");
            assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", live)).isEmpty();
        } finally {
            jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = 'KRW-POLYX'");
        }
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
