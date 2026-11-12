package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * 같은 입력·같은 시각 LIVE 고정 코인 ↔ PAPER 비교 실행기의 공통 대역 (2026-11-10, 총괄 v1.143/v1.144 B·C).
 *
 * <p>운영 소스의 <b>실제 호출부</b>를 그대로 탄다 — LIVE 는 {@code evaluateAndExecuteSession} → 회차 접수 →
 * {@link EntryPriorityRounds#processDue} 승인 → {@code approveQueuedEntry} → 예약·의도 → 실행기, 실시간 손절은 {@code onRealtimePriceEvent}
 * 본문. PAPER 는 운영 스케줄러가 세션마다 부르는 {@code runSessionStrategy}. 전략 함수를 복사한 모의 엔진은 없다. 거래소 주문 클라이언트·
 * 텔레그램·시세 동기화는 목이고 외부 주문 호출은 0이다(목 주문 클라이언트는 접수(wait)만 답한다).</p>
 *
 * <p>세션 조건은 SID 203 과 같은 숫자다 — 초기 20,200 · 투자 비율 0.995 · 1회 상한 20,000 · 손절 하한 5% · 시간 청산 0(끔).
 * 각 시나리오의 기대(같음/다름)는 소스 읽기로 실행 전에 적었다. 관측이 기대와 다르면 실패한다 — 기대값을 고쳐 통과시키지 않는다.</p>
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractSma20SameInputFixture extends IntegrationTestBase {

    protected static final String BTC = "KRW-BTC";
    protected static final String ETH = "KRW-ETH";
    protected static final BigDecimal CAP = new BigDecimal("20000");

    @Autowired protected EntryPriorityRounds rounds;
    @Autowired protected EntryClock entryClock;
    @Autowired protected CapitalPoolLimits limits;
    @Autowired protected PoolLossGuard poolLossGuard;
    @Autowired protected MarkPriceBook markPriceBook;
    @Autowired protected LiveTradingService liveTradingService;
    @Autowired protected LiveTradingSessionRepository liveRepo;
    @Autowired protected PositionRepository positionRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected StrategyLogRepository strategyLogRepository;
    @Autowired protected PaperTradingService paperTradingService;
    @Autowired protected VirtualBalanceRepository balanceRepo;
    @Autowired protected PaperPositionRepository paperPositionRepo;
    @Autowired protected PaperOrderRepository paperOrderRepo;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager txManager;
    @Autowired protected com.cryptoautotrader.api.repository.MarketDataCacheRepository cacheRepository;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("orderExecutor")
    protected org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor orderExecutor;

    @MockBean protected MarketDataSyncService marketDataSyncService;
    @MockBean protected UpbitRestClient upbitRestClient;
    @MockBean protected com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;
    @MockBean protected TelegramNotificationService telegramService;
    @MockBean protected PortfolioSyncService portfolioSyncService;
    @SpyBean protected RiskManagementService riskManagementService;

    /** 결과 표 — 시나리오 · 항목 · LIVE · PAPER · 기대 · 관측. */
    protected final List<String[]> ROWS = Collections.synchronizedList(new ArrayList<>());

    /** PAPER 세션의 strategy_params — 하위 클래스가 모드를 정한다. */
    protected abstract Map<String, Object> paperParams();

    /** 결과 표 파일 이름(build/sma20-same-input/&lt;이름&gt;.tsv). */
    protected abstract String reportName();

    protected String savedStage;
    protected BigDecimal savedTotal;
    protected BigDecimal savedPer;
    protected Integer savedSlots;
    protected final List<Long> liveIds = new ArrayList<>();
    protected final List<Long> paperIds = new ArrayList<>();
    protected Instant lastOpen;

    private static final java.util.concurrent.atomic.AtomicReference<Instant> LAST_ROUND =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.MAX);

    @BeforeEach
    void setUp() {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        limits.setStage("SAME_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        limits.setPerPositionCapKrw(CAP);
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        poolLossGuard.setEnabledSetting(true);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRisk();
        rounds.setForcedForTest(true);
        when(upbitOrderClient.createOrder(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> new com.cryptoautotrader.exchange.upbit.UpbitOrderClient.ExchangeResult(
                        accepted(inv.getArgument(0), inv.getArgument(1), inv.getArgument(4)), "{}"));
        deleteCache();
        // 🔴 (총괄 v1.155 §5 회귀 조사) 종전 "앞 시험보다 1초 이르게"는 JVM 하나에서 누적돼, 첫 fixture 시험 뒤 실행 시간이 4분쯤 지나면
        //    봉 마감 + 유예(D = 시작 + 60분 + 5분)가 실제 시각보다 앞서 LIVE 회차 접수가 "회차 마감 뒤 도착"이 됐다(전체 회귀에서만 실패 16건,
        //    탐침: 이 값 재설정만으로 A~H+S 666/666). 키는 처리된 같은 키만 거절되므로, 유일성은 지키되 앞으로 1ms 씩만 움직인다 —
        //    지금 −61분 근처(마감 뒤·유예 안)를 벗어나지 않는다.
        Instant base = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        lastOpen = LAST_ROUND.updateAndGet(prev -> prev.equals(Instant.MAX) || base.isAfter(prev) ? base : prev.plusMillis(1));
        useCloseHour(lastOpen);
        clearRealtimeThrottle();
    }

    @AfterEach
    void tearDown() {
        awaitDrained();
        entryClock.setForTest(null);
        rounds.setForcedForTest(false);
        StrategyRegistry.register(new DailySmaTrendStrategy());
        for (Long id : liveIds) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(strategyLogRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
        TransactionTemplate tx = new TransactionTemplate(txManager);
        for (Long id : paperIds) {
            tx.executeWithoutResult(st -> paperOrderRepo.deleteBySessionId(id));
            paperPositionRepo.findBySessionId(id).forEach(paperPositionRepo::delete);
            strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("PAPER", id).forEach(strategyLogRepository::delete);
            balanceRepo.findById(id).ifPresent(balanceRepo::delete);
        }
        liveIds.clear();
        paperIds.clear();
        deleteCache();
        jdbc.update("DELETE FROM capital_stage_baseline WHERE stage = ?", limits.getStage());
        limits.setStage(savedStage);
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
    }

    @AfterAll
    void writeReport() throws IOException {
        List<String[]> rows = new ArrayList<>(ROWS);
        rows.sort((a, b) -> (a[0] + a[1]).compareTo(b[0] + b[1]));
        StringBuilder sb = new StringBuilder("scenario\titem\tLIVE\tPAPER\tdeclared\tobserved\n");
        for (String[] r : rows) sb.append(String.join("\t", r)).append('\n');
        Path out = Path.of("build", "sma20-same-input", reportName() + ".tsv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[같은 입력 비교] " + out.toAbsolutePath() + "\n" + sb);
    }

    // ── 대역 ────────────────────────────────────────────────────────

    protected static com.cryptoautotrader.exchange.upbit.dto.OrderResponse accepted(String market, String side, String ordType) {
        com.cryptoautotrader.exchange.upbit.dto.OrderResponse r = new com.cryptoautotrader.exchange.upbit.dto.OrderResponse();
        r.setUuid("same-input-" + UUID.randomUUID());
        r.setSide(side);
        r.setOrdType(ordType);
        r.setState("wait");
        r.setMarket(market);
        return r;
    }

    /**
     * 주문 실행기의 앞선 작업이 모두 끝날 때까지 — 확정 신호(실행기 장벽)로 기다린다 (2026-11-11, 총괄 v1.147 3-2d). 종전 "큐 비었음·active 0·
     * taskCount == completed" 조건은 꺼낸 뒤·실행 전 창에서 참이었다. 시간 초과는 실패다.
     */
    protected void awaitDrained() {
        com.cryptoautotrader.api.support.ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 15_000);
    }

    protected void deleteCache() {
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair IN (?, ?) AND timeframe = 'H1'", BTC, ETH);
    }

    protected static void useCloseHour(Instant closeBarOpen) {
        StrategyRegistry.register(new DailySmaTrendStrategy(closeBarOpen.atZone(ZoneOffset.UTC).getHour()));
    }

    /** 실시간 손절 처리기의 코인별 5초 간격·급변 기록 — 시나리오끼리 섞이지 않게 비운다(운영 동작은 그대로). */
    @SuppressWarnings("unchecked")
    protected void clearRealtimeThrottle() {
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        ((Map<String, ?>) ReflectionTestUtils.getField(target, "rtStopLossLastCheckMs")).clear();
        ((Map<String, ?>) ReflectionTestUtils.getField(target, "priceHistory")).clear();
    }

    // ── 자료 ────────────────────────────────────────────────────────

    /** 닫힌 H1 500봉, 마지막 봉 시작 = lastBar. */
    protected static List<Candle> series(Instant lastBar, IntFunction<Long> price) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            BigDecimal c = BigDecimal.valueOf(price.apply(i));
            BigDecimal o = BigDecimal.valueOf(price.apply(Math.max(0, i - 1)));
            out.add(Candle.builder().time(lastBar.minus(499 - i, ChronoUnit.HOURS))
                    .open(o).high(c.max(o).add(BigDecimal.valueOf(1000))).low(c.min(o).subtract(BigDecimal.valueOf(1000)))
                    .close(c).volume(new BigDecimal("100")).build());
        }
        return out;
    }

    protected static List<Candle> uptrend(Instant lastBar) {
        return series(lastBar, i -> 10_000_000L + 5_000L * i);
    }

    protected static List<Candle> downtrend(Instant lastBar) {
        return series(lastBar, i -> 20_000_000L - 5_000L * i);
    }

    protected void insertCache(String coin, List<Candle> cs) {
        cacheRepository.saveAll(cs.stream().map(c -> com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                .time(c.getTime()).coinPair(coin).timeframe("H1")
                .open(c.getOpen()).high(c.getHigh()).low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                .build()).toList());
    }

    /** 진행 중 봉(마지막 닫힌 봉 다음) — 시작은 지금보다 앞, 끝은 지금보다 뒤. */
    protected void insertInProgress(String coin, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        insertCache(coin, List.of(Candle.builder().time(lastOpen.plus(1, ChronoUnit.HOURS))
                .open(open).high(high).low(low).close(close).volume(new BigDecimal("10")).build()));
    }

    protected static BigDecimal lastClose(List<Candle> cs) {
        return cs.get(cs.size() - 1).getClose();
    }

    // ── 세션 ────────────────────────────────────────────────────────

    protected LiveTradingSessionEntity live(String coin) {
        LiveTradingSessionEntity s = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType(DailySmaTrendStrategy.NAME).coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("20200")).availableKrw(new BigDecimal("20200")).totalAssetKrw(new BigDecimal("20200"))
                .investRatio(new BigDecimal("0.995")).maxInvestment(CAP)
                .stopLossPct(new BigDecimal("5.00")).maxHoldHours(0)
                .status("RUNNING").startedAt(Instant.now().minus(30, ChronoUnit.DAYS)).build());
        liveIds.add(s.getId());
        return s;
    }

    protected VirtualBalanceEntity paper(String coin) {
        PaperTradingStartRequest req = new PaperTradingStartRequest();
        req.setStrategyType(DailySmaTrendStrategy.NAME);
        req.setCoinPair(coin);
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("20200"));
        req.setInvestRatio(new BigDecimal("0.995"));
        req.setStopLossPct(new BigDecimal("5.00"));
        req.setMaxHoldHours(0);
        req.setStrategyParams(new HashMap<>(paperParams()));
        VirtualBalanceEntity s = paperTradingService.start(req);
        paperIds.add(s.getId());
        return s;
    }

    /** 이미 보유 중인 같은 포지션 — LIVE·PAPER 같은 진입가·SL·TP·보유 시작. */
    protected PositionEntity livePos(LiveTradingSessionEntity s, BigDecimal entry, BigDecimal sl, BigDecimal tp, Instant openedAt) {
        BigDecimal size = CAP.divide(entry, 8, RoundingMode.DOWN);
        liveRepo.findById(s.getId()).ifPresent(x -> {
            x.setAvailableKrw(new BigDecimal("200"));
            liveRepo.save(x);
        });
        return positionRepository.save(PositionEntity.builder()
                .coinPair(s.getCoinPair()).side("BUY").entryPrice(entry).avgPrice(entry).size(size)
                .investedKrw(CAP).entryAllocationKrw(CAP).stopLossPrice(sl).takeProfitPrice(tp)
                .status("OPEN").sessionId(s.getId()).sessionKind("LIVE").openedAt(openedAt).build());
    }

    protected PaperPositionEntity paperPos(VirtualBalanceEntity s, BigDecimal entry, BigDecimal sl, BigDecimal tp, Instant openedAt) {
        BigDecimal size = CAP.divide(entry, 8, RoundingMode.DOWN);
        VirtualBalanceEntity b = balanceRepo.findById(s.getId()).orElseThrow();
        b.setAvailableKrw(new BigDecimal("200"));
        balanceRepo.save(b);
        return paperPositionRepo.save(PaperPositionEntity.builder()
                .sessionKind("PAPER").sessionId(s.getId()).coinPair(s.getCoinPair()).side("BUY")
                .entryPrice(entry).avgPrice(entry).size(size).positionFee(BigDecimal.ZERO).investedKrw(CAP)
                .stopLossPrice(sl).takeProfitPrice(tp).status("OPEN").openedAt(openedAt).build());
    }

    // ── 엔진 호출(운영 호출부) ───────────────────────────────────────

    protected void evaluateLive(LiveTradingSessionEntity s) {
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        ReflectionTestUtils.invokeMethod(target, "evaluateAndExecuteSession", liveRepo.findById(s.getId()).orElseThrow());
    }

    protected List<EntryPriorityRounds.Outcome> approveRound() {
        EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        return rounds.processDue(EntryPriorityRounds.deadline(round));
    }

    protected void runPaper(VirtualBalanceEntity s) {
        Object target = AopTestUtils.getUltimateTargetObject(paperTradingService);
        ReflectionTestUtils.invokeMethod(target, "runSessionStrategy", balanceRepo.findById(s.getId()).orElseThrow());
    }

    protected void realtimeTick(String coin, BigDecimal price) {
        clearRealtimeThrottle();
        // 운영 진입점은 @Async(market-data 실행기)다 — 프록시를 거치면 처리 전에 읽게 된다(1회차 S06 이 그 경합으로 실패, 손절 로그는 남음).
        // 같은 메서드 본문을 프록시 없이 이 스레드에서 부른다. 처리 규칙은 같고, 대기·sleep 은 없다.
        LiveTradingService target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        target.onRealtimePriceEvent(new RealtimePriceEvent(coin, price, Instant.now(), RealtimePriceEvent.WS_TRADE));
    }

    // ── 관측 ────────────────────────────────────────────────────────

    protected List<PositionEntity> livePositions(LiveTradingSessionEntity s) {
        return positionRepository.findBySessionKindAndSessionId("LIVE", s.getId());
    }

    protected List<PaperPositionEntity> paperPositions(VirtualBalanceEntity s) {
        return paperPositionRepo.findBySessionId(s.getId());
    }

    protected List<OrderEntity> liveOrders(LiveTradingSessionEntity s, String side) {
        awaitDrained();
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", s.getId()).stream()
                .filter(o -> side.equals(o.getSide())).toList();
    }

    protected String lastLog(String kind, Long id) {
        List<StrategyLogEntity> logs = strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc(kind, id);
        if (logs.isEmpty()) return "로그 없음";
        StrategyLogEntity l = logs.get(0);
        return l.getSignal() + " / " + l.getReason() + (l.getBlockedReason() != null ? " / 차단=" + l.getBlockedReason() : "");
    }

    protected static String entryState(int n, String status) {
        return n == 0 ? "포지션 없음" : n + "건 " + status;
    }

    protected static BigDecimal pctBelow(BigDecimal entry, BigDecimal level) {
        return entry.subtract(level).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    protected static BigDecimal pctAbove(BigDecimal entry, BigDecimal level) {
        return level.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    protected static String s(Object o) {
        if (o instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        return String.valueOf(o).replace('\t', ' ').replace('\n', ' ');
    }

    /** 표에 남기고, 관측이 실행 전 기대와 같은지 돌려준다. */
    protected boolean row(String scenario, String item, Object live, Object paper, boolean expectSame) {
        boolean same = Objects.equals(s(live), s(paper));
        ROWS.add(new String[]{scenario, item, s(live), s(paper), expectSame ? "같음" : "다름", same ? "같음" : "다름"});
        return same == expectSame;
    }

    protected static void expect(boolean ok, String what) {
        assertThat(ok).as(what + " — 실행 전 기대와 관측이 다르다(기대값을 고치지 말고 보고)").isTrue();
    }

}
