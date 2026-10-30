package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 12월 제한 실거래 시험 — "DAILY_SMA20 신호 + 운영 보호 규칙" 결합의 LIVE 주문 경로 (docs/LIMITED_LIVE_TEST_PLAN.md §5-2).
 *
 * <p>LIVE 평가 회차({@code evaluateAndExecuteSession})를 실제로 돌려, 신호 비교가 아니라 <b>주문이 나가는지</b>를 본다 —
 * 일봉 마감 봉에서만 진입, EMA200 관문 차단, 시간 청산이 같은 회차의 BUY 를 막음, 전략 SELL 의 본전 차단과 손실 탈출,
 * 청산 뒤 다음 마감의 재진입, 그리고 시험 세션 설정(투자 비율·1회 상한)이 만드는 매수금.</p>
 *
 * <p>대역: 캔들은 {@code market_data_cache} 행, 거래소 주문 클라이언트·텔레그램은 목. 전역 위험 검사는 H2 에서 돌지 않아
 * 통과로 고정한다(다른 종단 간 시험과 같은 한계). 엔진은 벽시계로 "닫힌 최신 봉"을 판단하므로, 전략은 같은 규칙에
 * 일봉 경계만 옮긴 인스턴스({@code new DailySmaTrendStrategy(h)})를 같은 이름으로 잠시 등록한다 — 끝나면 운영 인스턴스로 되돌린다.</p>
 */
@DisplayName("DAILY_SMA20 LIVE 주문 경로 — 마감 봉 진입·EMA200 차단·시간 청산·SELL 차단·재진입·매수금")
class DailySma20LiveOrderPathTest extends IntegrationTestBase {

    private static final String COIN = "KRW-BTC";

    @Autowired private EntryPriorityRounds rounds;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private StrategyLogRepository strategyLogRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository cacheRepository;
    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private com.cryptoautotrader.api.repository.PositionCleanupRepository cleanupRepository;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("orderExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor orderExecutor;

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
    private Instant lastOpen;

    @BeforeEach
    void setUp() {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        limits.setStage("SMA20_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        poolLossGuard.setEnabledSetting(true);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRisk();
        // 운영은 STAGE_1 이 등록돼 있어 진입이 회차 접수 → 마감 뒤 승인 경로를 탄다. 시험도 그 경로로 돌린다.
        rounds.setForcedForTest(true);
        // 정상 주문은 거래소가 접수(wait)로 답한다 — 대역이 null 을 돌려주면 주문 스레드가 접수 기록에서 죽고(NPE)
        // 행 생성만 기다리는 단언은 그 실패를 놓친다(2026-10-30 리뷰: 18건 통과 중 주문 스레드 예외 7건).
        when(upbitOrderClient.createOrder(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> new com.cryptoautotrader.exchange.upbit.UpbitOrderClient.ExchangeResult(
                        accepted(inv.getArgument(1), inv.getArgument(4)), "{}"));
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", COIN);
        // 닫힌 최신 봉 = 61분 전에 시작한 봉(회차 마감 전). 그 봉을 일봉 마감 봉으로 둔다.
        // 회차 키는 시험마다 앞 시험보다 이르게 둔다 — 처리된 회차는 늦은 접수를 받지 않는다("회차 마감 뒤 도착").
        // 🔴 (2026-10-30) 예전 "현재 − 순번(초)" 은 시험 하나가 1초쯤 걸리면 시각 증가와 순번 증가가 상쇄돼 앞 시험과
        //    같은 키가 되었다 — 묶음 실행 1회차의 "BUY 주문 0건" 3건이 이것이다(1.1초 지연으로 재현, 문서 §5-9).
        Instant base = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        lastOpen = LAST_ROUND.updateAndGet(prev -> base.isBefore(prev) ? base : prev.minusSeconds(1));
        useCloseHour(lastOpen.atZone(ZoneOffset.UTC).getHour());
    }

    private static final java.util.concurrent.atomic.AtomicReference<Instant> LAST_ROUND =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.MAX);

    @AfterEach
    void tearDown() {
        // 비동기 주문 작업이 끝난 뒤 지운다 — 지운 행을 주문 스레드가 다시 찾는 경합을 막는다.
        awaitDrained();
        rounds.setForcedForTest(false);
        StrategyRegistry.register(new DailySmaTrendStrategy());
        for (Long id : liveIds) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(strategyLogRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", COIN);
        jdbc.update("DELETE FROM capital_stage_baseline WHERE stage = ?", limits.getStage());
        limits.setStage(savedStage);
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
    }

    private static com.cryptoautotrader.exchange.upbit.dto.OrderResponse accepted(String side, String ordType) {
        com.cryptoautotrader.exchange.upbit.dto.OrderResponse r = new com.cryptoautotrader.exchange.upbit.dto.OrderResponse();
        r.setUuid("sma20-new-" + UUID.randomUUID());
        r.setSide(side);
        r.setOrdType(ordType);
        r.setState("wait");
        r.setMarket(COIN);
        return r;
    }

    private void awaitDrained() {
        var pool = orderExecutor.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0
                    && pool.getTaskCount() == pool.getCompletedTaskCount()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("orderExecutor 가 비워지지 않았다 — 작업 스레드가 반환되지 않았다");
    }

    private static void useCloseHour(int hourUtc) {
        StrategyRegistry.register(new DailySmaTrendStrategy(hourUtc));
    }

    // ── 자료 ────────────────────────────────────────────────────────

    /** 닫힌 H1 500봉(운영 조회 한도). i 번째 봉 종가 = price(i), 마지막 봉 = lastOpen. */
    private List<Candle> series(IntFunction<Long> price) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            BigDecimal c = BigDecimal.valueOf(price.apply(i));
            BigDecimal o = BigDecimal.valueOf(price.apply(Math.max(0, i - 1)));
            out.add(Candle.builder().time(lastOpen.minus(499 - i, ChronoUnit.HOURS))
                    .open(o).high(c.max(o).add(BigDecimal.valueOf(1000))).low(c.min(o).subtract(BigDecimal.valueOf(1000)))
                    .close(c).volume(new BigDecimal("100")).build());
        }
        return out;
    }

    /** 완만한 상승(시간당 +0.05% 안팎) — 마감 종가 > SMA20, EMA200 위, 추세 레짐, 급락 없음. */
    private List<Candle> uptrend() {
        return series(i -> 10_000_000L + 5_000L * i);
    }

    /** 완만한 하락 — 마감 종가 < SMA20. */
    private List<Candle> downtrend() {
        return series(i -> 20_000_000L - 5_000L * i);
    }

    /** 400봉 상승 뒤 100봉 완만한 하락 — 일봉 종가는 아직 SMA20 위(BUY), H1 종가는 EMA200 아래. */
    private List<Candle> riseThenPullback() {
        return series(i -> i < 400 ? 10_000_000L + 20_000L * i : 10_000_000L + 20_000L * 399 - 20_000L * (i - 399));
    }

    private void insertCache(List<Candle> cs) {
        cacheRepository.saveAll(cs.stream().map(c -> com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                .time(c.getTime()).coinPair(COIN).timeframe("H1")
                .open(c.getOpen()).high(c.getHigh()).low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                .build()).toList());
    }

    private BigDecimal lastClose(List<Candle> cs) {
        return cs.get(cs.size() - 1).getClose();
    }

    // ── 세션·포지션 ──────────────────────────────────────────────────

    /** 시험 세션 설정(문서 §5-1 v1.2): 초기 20,200 · 투자 비율 0.995 · 1회 상한 20,000 · 손절 하한 5% · 시간 청산 24h. */
    private LiveTradingSessionEntity session(BigDecimal availableKrw, BigDecimal maxInvestment) {
        return session(availableKrw, maxInvestment, 24);
    }

    private LiveTradingSessionEntity session(BigDecimal availableKrw, BigDecimal maxInvestment, int maxHoldHours) {
        LiveTradingSessionEntity s = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType(DailySmaTrendStrategy.NAME).coinPair(COIN).timeframe("H1")
                .initialCapital(new BigDecimal("20200")).availableKrw(availableKrw).totalAssetKrw(availableKrw)
                .investRatio(new BigDecimal("0.995")).maxInvestment(maxInvestment)
                .stopLossPct(new BigDecimal("5.00")).maxHoldHours(maxHoldHours)
                .status("RUNNING").startedAt(Instant.now().minus(30, ChronoUnit.DAYS)).build());
        liveIds.add(s.getId());
        return s;
    }

    private LiveTradingSessionEntity testSession() {
        return session(new BigDecimal("20200"), new BigDecimal("20000"));
    }

    /** 운영자 결정 (나)(2026-10-30) 뒤의 시험 세션 — 시간 청산만 끈다(maxHoldHours=0). 다른 값은 그대로. */
    private LiveTradingSessionEntity noTimeStopSession() {
        return session(new BigDecimal("20200"), new BigDecimal("20000"), 0);
    }

    private PositionEntity openPosition(LiveTradingSessionEntity s, BigDecimal avgPrice, Instant openedAt) {
        return position(s, avgPrice, openedAt, new BigDecimal("0.001"), "OPEN");
    }

    private PositionEntity position(LiveTradingSessionEntity s, BigDecimal avgPrice, Instant openedAt,
                                    BigDecimal size, String status) {
        return positionRepository.save(PositionEntity.builder()
                .coinPair(COIN).side("BUY").entryPrice(avgPrice).avgPrice(avgPrice).size(size)
                .investedKrw(avgPrice.multiply(size)).entryAllocationKrw(avgPrice.multiply(size))
                .status(status).sessionId(s.getId()).sessionKind("LIVE").openedAt(openedAt).build());
    }

    /** 응답을 잃은 주문(UNKNOWN) — 식별자는 있고 거래소 주문번호는 없다. */
    private OrderEntity unknownOrder(LiveTradingSessionEntity s, PositionEntity p, String side, BigDecimal quantity) {
        return orderRepository.save(OrderEntity.builder()
                .positionId(p.getId()).coinPair(COIN).side(side).orderType("MARKET").quantity(quantity)
                .state("UNKNOWN").clientOrderId("sma20-unknown-" + UUID.randomUUID())
                .createdAt(Instant.now().minus(10, ChronoUnit.MINUTES))
                .sessionId(s.getId()).sessionKind("LIVE").build());
    }

    /** 평가 회차만 돌린다(매수는 회차에 접수될 뿐). */
    private void evaluateOnly(LiveTradingSessionEntity s) {
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        ReflectionTestUtils.invokeMethod(target, "evaluateAndExecuteSession", liveRepo.findById(s.getId()).orElseThrow());
    }

    /** 이 봉의 회차를 마감해 승인한다. */
    private List<EntryPriorityRounds.Outcome> approveRound() {
        EntryPriorityRounds.RoundKey round = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        List<EntryPriorityRounds.Outcome> out = rounds.processDue(EntryPriorityRounds.deadline(round));
        out.forEach(o -> System.out.println("[SMA20 회차 승인] LIVE#" + o.candidate().sessionId() + " → "
                + (o.reason() == null ? "진입" : o.reason())));
        return out;
    }

    /** 마지막 평가의 흐름 — 주문이 0건이면 실패 메시지에 싣는다(신호 → 후보 접수 → 회차 승인 결과). */
    private String lastTrace = "평가 없음";

    /** 평가 회차 + 회차 승인 — 운영 한 번의 마감 처리와 같다. */
    private void evaluate(LiveTradingSessionEntity s) {
        evaluateOnly(s);
        List<StrategyLogEntity> logs = strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("LIVE", s.getId());
        String signal = logs.isEmpty() ? "로그 없음"
                : logs.get(0).getSignal() + " / 접수 거절=" + logs.get(0).getBlockedReason();
        List<EntryPriorityRounds.Outcome> out = approveRound();
        lastTrace = String.format("회차 키 H1 %s · 신호 %s · 승인 %s", lastOpen, signal,
                out.stream().map(o -> "LIVE#" + o.candidate().sessionId() + "→" + (o.reason() == null ? "진입" : o.reason())).toList());
    }

    private List<OrderEntity> orders(LiveTradingSessionEntity s, String side) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", s.getId()).stream()
                .filter(o -> side.equals(o.getSide())).toList();
    }

    /**
     * 매도 주문은 {@code OrderExecutionEngine.submitOrder}(@Async) 가 다른 스레드에서 만든다 — 전체 시험 실행 중에는
     * 주문 실행기가 바빠 바로 단언하면 행이 아직 없다(2026-10-29 전체 실행에서 2건 실패). 최대 10초 기다린다.
     */
    private List<OrderEntity> awaitOrders(LiveTradingSessionEntity s, String side, int expected) {
        long until = System.currentTimeMillis() + 10_000;
        List<OrderEntity> found = orders(s, side);
        while (found.size() < expected && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            found = orders(s, side);
        }
        return found;
    }

    /**
     * 정상 주문은 행 생성이 아니라 <b>접수 기록(SUBMITTED)과 거래소 호출 1회</b>까지 확인한다.
     * 주문 실행기가 빈 뒤에 보므로 접수 기록이 실패하면 상태가 SUBMITTED 가 아니어서 잡힌다.
     */
    private OrderEntity assertSubmittedOnce(LiveTradingSessionEntity s, String side) {
        awaitOrders(s, side, 1);
        awaitDrained();
        List<OrderEntity> found = orders(s, side);
        assertThat(found).as(side + " 주문 1건 — " + lastTrace).hasSize(1);
        assertThat(found.get(0).getState()).as(side + " 접수 기록").isEqualTo("SUBMITTED");
        assertThat(found.get(0).getExchangeOrderId()).as("거래소 주문번호").startsWith("sma20-new-");
        verify(upbitOrderClient, times(1)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
        verify(upbitOrderClient, times(1)).createOrder(eq(COIN), eq("BUY".equals(side) ? "bid" : "ask"),
                any(), any(), anyString(), anyString());
        return found.get(0);
    }

    private StrategyLogEntity lastLog(LiveTradingSessionEntity s) {
        List<StrategyLogEntity> logs = strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("LIVE", s.getId());
        assertThat(logs).as("전략 로그").isNotEmpty();
        return logs.get(0);
    }

    private PositionEntity reload(PositionEntity p) {
        return positionRepository.findById(p.getId()).orElseThrow();
    }

    // ── 시험 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("일봉 마감 봉 + 종가 > SMA20 + 관문 통과 → 매수 1건, 금액 = min(20,200 × 0.995, 20,000) = 20,000")
    void buysAtDailyClose() {
        insertCache(uptrend());
        LiveTradingSessionEntity s = testSession();
        evaluate(s);
        System.out.println("[SMA20 진입] " + lastLog(s).getSignal() + " / " + lastLog(s).getReason());
        OrderEntity buy = assertSubmittedOnce(s, "BUY");
        assertThat(buy.getQuantity()).isEqualByComparingTo("20000");
        assertThat(lastLog(s).getSignal()).isEqualTo("BUY");
    }

    @Test
    @DisplayName("같은 자료라도 마지막 닫힌 봉이 마감 봉이 아니면 HOLD — 주문 없음")
    void noOrderOffDailyClose() {
        useCloseHour((lastOpen.atZone(ZoneOffset.UTC).getHour() + 1) % 24);
        insertCache(uptrend());
        LiveTradingSessionEntity s = testSession();
        evaluate(s);
        assertThat(orders(s, "BUY")).isEmpty();
        assertThat(lastLog(s).getSignal()).isEqualTo("HOLD");
        assertThat(lastLog(s).getReason()).contains("일봉 마감 봉 아님");
    }

    @Test
    @DisplayName("일봉 신호는 BUY 여도 H1 종가가 EMA200 아래면 EMA200 관문이 막는다 — 주문 없음, 사유 기록")
    void ema200GateBlocks() {
        List<Candle> cs = riseThenPullback();
        // 전제 확인: 순수 규칙으로는 BUY
        assertThat(new DailySmaTrendStrategy(lastOpen.atZone(ZoneOffset.UTC).getHour())
                .evaluate(cs.subList(1, cs.size()), java.util.Map.of()).getAction())
                .isEqualTo(com.cryptoautotrader.strategy.StrategySignal.Action.BUY);
        insertCache(cs);
        LiveTradingSessionEntity s = testSession();
        evaluate(s);
        System.out.println("[SMA20 EMA200 관문] " + lastLog(s).getReason());
        assertThat(orders(s, "BUY")).isEmpty();
        assertThat(lastLog(s).getReason()).contains("EMA200");
    }

    @Test
    @DisplayName("보유 24시간 경과 → 시간 청산 매도, 같은 회차의 BUY 신호로 다시 사지 않는다")
    void timeStopPreemptsBuy() {
        List<Candle> cs = uptrend();
        insertCache(cs);
        LiveTradingSessionEntity s = testSession();
        PositionEntity p = openPosition(s, lastClose(cs), Instant.now().minus(25, ChronoUnit.HOURS));
        evaluate(s);
        PositionEntity after = reload(p);
        assertThat(after.getStatus()).isEqualTo("CLOSING");
        assertThat(after.getExitReason()).isEqualTo(ExitReason.TIME_STOP);
        assertSubmittedOnce(s, "SELL");
        assertThat(orders(s, "BUY")).as("시간 청산 회차는 return — 재진입은 다음 마감 봉").isEmpty();
        assertThat(lastLog(s).getSignal()).as("신호 자체는 BUY 였다").isEqualTo("BUY");
    }

    @Test
    @DisplayName("보유 중 BUY 신호는 추가 매수하지 않는다(재조정 없음)")
    void noPyramiding() {
        List<Candle> cs = uptrend();
        insertCache(cs);
        LiveTradingSessionEntity s = testSession();
        PositionEntity p = openPosition(s, lastClose(cs), Instant.now().minus(20, ChronoUnit.HOURS));
        evaluate(s);
        assertThat(orders(s, "BUY")).isEmpty();
        assertThat(reload(p).getStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("마감 종가 ≤ SMA20, 손실 −2% → 전략 SELL 체결(손실 탈출 −0.30% 아래)")
    void strategySellOnLoss() {
        List<Candle> cs = downtrend();
        insertCache(cs);
        LiveTradingSessionEntity s = testSession();
        BigDecimal avg = lastClose(cs).divide(new BigDecimal("0.98"), 0, java.math.RoundingMode.HALF_UP);
        PositionEntity p = openPosition(s, avg, Instant.now().minus(20, ChronoUnit.HOURS));
        evaluate(s);
        PositionEntity after = reload(p);
        assertThat(after.getStatus()).isEqualTo("CLOSING");
        assertThat(after.getExitReason()).isEqualTo(ExitReason.STRATEGY_SIGNAL);
        assertSubmittedOnce(s, "SELL");
    }

    @Test
    @DisplayName("마감 종가 ≤ SMA20 이어도 손익 −0.30% ~ +0.30% 이면 본전 차단 — 매도 없음, 다음 회차로 미뤄진다")
    void strategySellBlockedAtBreakeven() {
        List<Candle> cs = downtrend();
        insertCache(cs);
        LiveTradingSessionEntity s = testSession();
        PositionEntity p = openPosition(s, lastClose(cs), Instant.now().minus(20, ChronoUnit.HOURS));
        evaluate(s);
        assertThat(reload(p).getStatus()).isEqualTo("OPEN");
        assertThat(orders(s, "SELL")).isEmpty();
        StrategyLogEntity log = lastLog(s);
        assertThat(log.getSignal()).isEqualTo("SELL");
        assertThat(log.getBlockedReason()).contains("본전 청산 차단");
    }

    @Test
    @DisplayName("이전 포지션이 청산된 세션은 다음 마감 봉의 BUY 로 다시 들어간다(재진입)")
    void reentersAfterClose() {
        insertCache(uptrend());
        LiveTradingSessionEntity s = testSession();
        PositionEntity old = openPosition(s, new BigDecimal("15000000"), Instant.now().minus(3, ChronoUnit.DAYS));
        old.setStatus("CLOSED");
        old.setClosedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        positionRepository.save(old);
        evaluate(s);
        assertSubmittedOnce(s, "BUY");
    }

    @Test
    @DisplayName("이익으로 잔액 25,000 — 1회 상한이 있으면 20,000 매수, 없으면 24,875 로 포지션당 한도에 걸려 매수 없음")
    void maxInvestmentKeepsEntryUnderPerPositionCap() {
        insertCache(uptrend());
        LiveTradingSessionEntity capped = session(new BigDecimal("25000"), new BigDecimal("20000"));
        LiveTradingSessionEntity uncapped = session(new BigDecimal("25000"), null);
        evaluateOnly(capped);
        evaluateOnly(uncapped);
        List<EntryPriorityRounds.Outcome> out = approveRound();
        assertThat(out).hasSize(2);

        assertThat(assertSubmittedOnce(capped, "BUY").getQuantity()).isEqualByComparingTo("20000");

        assertThat(orders(uncapped, "BUY")).as("24,875 > 포지션당 20,000 → 예약 거절").isEmpty();
        assertThat(lastLog(uncapped).getBlockedReason()).contains("PER_POSITION_CAP");
    }

    // ── 운영자 결정 (나), 2026-10-30: 이 세션만 maxHoldHours=0 (문서 §5-7) ──────────────────

    @Test
    @DisplayName("maxHoldHours=0 — 생성 요청(POST) → 저장 → 재조회(GET·DB)까지 0 이 유지되고 다른 설정도 그대로다")
    void maxHoldZeroSurvivesCreateAndReload() throws Exception {
        String body = """
                {"strategyType":"DAILY_SMA20","coinPair":"KRW-BTC","timeframe":"H1",
                 "initialCapital":20200,"investRatio":0.995,"maxInvestment":20000,
                 "stopLossPct":5,"maxHoldHours":0}""";
        String created = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/trading/sessions")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode data = objectMapper.readTree(created).get("data");
        long id = data.get("id").asLong();
        liveIds.add(id);
        assertThat(data.get("maxHoldHours").asInt()).as("생성 응답").isZero();

        String fetched = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/trading/sessions/" + id))
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode got = objectMapper.readTree(fetched).get("data");
        System.out.println("[SMA20 생성·재조회] " + got.get("status") + " maxHoldHours=" + got.get("maxHoldHours")
                + " maxInvestment=" + got.get("maxInvestment") + " investRatio=" + got.get("investRatio")
                + " stopLossPct=" + got.get("stopLossPct") + " initialCapital=" + got.get("initialCapital"));
        assertThat(got.get("maxHoldHours").asInt()).as("GET 재조회").isZero();

        LiveTradingSessionEntity row = liveRepo.findById(id).orElseThrow();
        assertThat(row.getMaxHoldHours()).as("DB 재조회 — 기본값 24 로 바뀌지 않는다").isZero();
        assertThat(row.getMaxInvestment()).isEqualByComparingTo("20000");
        assertThat(row.getInvestRatio()).isEqualByComparingTo("0.995");
        assertThat(row.getStopLossPct()).isEqualByComparingTo("5");
        assertThat(row.getInitialCapital()).isEqualByComparingTo("20200");
        assertThat(row.getStatus()).as("생성만 — 시작은 별도 요청").isEqualTo("CREATED");
    }

    @Test
    @DisplayName("maxHoldHours=0 — 보유 72시간에도 시간 청산·추가 매수가 없다(일봉 BUY 유지 중)")
    void noTimeStopAndNoPyramidingWhenMaxHoldZero() {
        List<Candle> cs = uptrend();
        insertCache(cs);
        LiveTradingSessionEntity s = noTimeStopSession();
        PositionEntity p = openPosition(s, lastClose(cs), Instant.now().minus(72, ChronoUnit.HOURS));
        evaluate(s);
        assertThat(reload(p).getStatus()).isEqualTo("OPEN");
        assertThat(reload(p).getExitReason()).isNull();
        assertThat(orders(s, "SELL")).isEmpty();
        assertThat(orders(s, "BUY")).isEmpty();
        assertThat(lastLog(s).getSignal()).isEqualTo("BUY");
    }

    @Test
    @DisplayName("maxHoldHours=0 — 손절은 그대로 작동한다(보유 72시간, −10%)")
    void stopLossStillFiresWhenMaxHoldZero() {
        List<Candle> cs = uptrend();
        insertCache(cs);
        LiveTradingSessionEntity s = noTimeStopSession();
        BigDecimal avg = lastClose(cs).divide(new BigDecimal("0.90"), 0, java.math.RoundingMode.HALF_UP);
        PositionEntity p = openPosition(s, avg, Instant.now().minus(72, ChronoUnit.HOURS));
        evaluate(s);
        assertThat(reload(p).getStatus()).isEqualTo("CLOSING");
        assertThat(reload(p).getExitReason()).isEqualTo(ExitReason.STOP_LOSS);
        assertSubmittedOnce(s, "SELL");
        assertThat(orders(s, "BUY")).isEmpty();
    }

    @Test
    @DisplayName("maxHoldHours=0 — 익절은 그대로 작동한다(보유 72시간, 저장된 익절가 도달)")
    void takeProfitStillFiresWhenMaxHoldZero() {
        List<Candle> cs = uptrend();
        insertCache(cs);
        LiveTradingSessionEntity s = noTimeStopSession();
        BigDecimal avg = lastClose(cs).divide(new BigDecimal("1.10"), 0, java.math.RoundingMode.HALF_UP);
        PositionEntity p = openPosition(s, avg, Instant.now().minus(72, ChronoUnit.HOURS));
        p.setTakeProfitPrice(avg.multiply(new BigDecimal("1.08")));
        positionRepository.save(p);
        evaluate(s);
        assertThat(reload(p).getStatus()).isEqualTo("CLOSING");
        assertThat(reload(p).getExitReason()).isEqualTo(ExitReason.TAKE_PROFIT);
        assertSubmittedOnce(s, "SELL");
    }

    @Test
    @DisplayName("maxHoldHours=0 — 마감 종가 ≤ SMA20 이면 전략 SELL 이 그대로 나간다(보유 72시간, −2%)")
    void strategySellStillFiresWhenMaxHoldZero() {
        List<Candle> cs = downtrend();
        insertCache(cs);
        LiveTradingSessionEntity s = noTimeStopSession();
        BigDecimal avg = lastClose(cs).divide(new BigDecimal("0.98"), 0, java.math.RoundingMode.HALF_UP);
        PositionEntity p = openPosition(s, avg, Instant.now().minus(72, ChronoUnit.HOURS));
        evaluate(s);
        assertThat(reload(p).getStatus()).isEqualTo("CLOSING");
        assertThat(reload(p).getExitReason()).isEqualTo(ExitReason.STRATEGY_SIGNAL);
        assertSubmittedOnce(s, "SELL");
    }

    // ── 신호 → 진입 승인 → 주문 접수 → 공통 정산·DB 반영 (문서 §5-9, 2026-10-30) ──────────────────
    //
    // 위 시험들은 접수(SUBMITTED)에서 멈춘다. 여기서는 같은 회차가 낸 실제 주문 행을 정산 폴러
    // ({@code checkOrderStatus})로 이어 받아, 주문·포지션·세션 잔액이 거래소 보고값과 맞는지 본다.
    // 거래소 응답만 대역이다. 예약 20,000 = 체결대금 + 수수료 + 미사용 복원.

    /** 시장가 매수(ord_type=price) 조회 응답. */
    private static com.cryptoautotrader.exchange.upbit.dto.OrderResponse buyReport(
            String uuid, String state, String volume, String funds, String fee) {
        com.cryptoautotrader.exchange.upbit.dto.OrderResponse r = new com.cryptoautotrader.exchange.upbit.dto.OrderResponse();
        r.setUuid(uuid);
        r.setSide("bid");
        r.setOrdType("price");
        r.setState(state);
        r.setMarket(COIN);
        r.setPrice(new BigDecimal("20000"));
        r.setExecutedVolume(new BigDecimal(volume));
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPaidFee(new BigDecimal(fee));
        return r;
    }

    /** 신호부터 접수까지 실제 회차로 돌리고, 접수 직후 잔액(20,200 − 예약 20,000 = 200)을 확인한다. */
    private OrderEntity submittedSma20Buy(LiveTradingSessionEntity s) {
        insertCache(uptrend());
        evaluate(s);
        OrderEntity buy = assertSubmittedOnce(s, "BUY");
        assertThat(buy.getQuantity()).isEqualByComparingTo("20000");
        assertThat(available(s)).as("접수 직후 — 예약만 빠진다").isEqualByComparingTo("200");
        return buy;
    }

    /**
     * 대역 거래소의 보유량 장부 — 주문별 마지막 보고 체결 수량(매수 +, 매도 −). 같은 주문의 반복·정정 보고는 덮어쓴다.
     * 내부 기록(포지션 수량·주문 체결 수량)과 맞는지 {@link #assertHoldingsMatch} 가 본다.
     */
    private final java.util.Map<String, BigDecimal> exchangeFills = new java.util.HashMap<>();

    private void settle(OrderEntity o, com.cryptoautotrader.exchange.upbit.dto.OrderResponse report) {
        BigDecimal vol = report.getExecutedVolume() == null ? BigDecimal.ZERO : report.getExecutedVolume();
        exchangeFills.put(report.getUuid(), "ask".equals(report.getSide()) ? vol.negate() : vol);
        doReturn(report).when(upbitOrderClient).getOrder(o.getExchangeOrderId());
        OrderEntity cur = orderRepository.findById(o.getId()).orElseThrow();
        // 백오프는 시각 문제다 — 차분 반영만 본다(SettlementIdempotencyTest 와 같은 방식).
        cur.setSettlementNextCheckAt(Instant.now().minusSeconds(1));
        orderRepository.save(cur);
        orderExecutionEngine.checkOrderStatus(o.getId());
    }

    private BigDecimal available(LiveTradingSessionEntity s) {
        return liveRepo.findById(s.getId()).orElseThrow().getAvailableKrw();
    }

    private PositionEntity entryPosition(OrderEntity o) {
        return positionRepository.findById(o.getPositionId()).orElseThrow();
    }

    private String settled(LiveTradingSessionEntity s, OrderEntity o) {
        OrderEntity cur = orderRepository.findById(o.getId()).orElseThrow();
        PositionEntity p = entryPosition(o);
        return String.format("order{state=%s reported=%s filled=%s funds=%s fee=%s src=%s ver=%s} "
                        + "pos{status=%s size=%s avg=%s invested=%s} available=%s",
                cur.getState(), cur.getReportedState(), cur.getFilledQuantity(), cur.getAppliedExecutedFunds(),
                cur.getAppliedPaidFee(), cur.getFeeSource(), cur.getSettlementVersion(),
                p.getStatus(), p.getSize(), p.getAvgPrice(), p.getInvestedKrw(), available(s));
    }

    @Test
    @DisplayName("통합 ① 전량 체결 — 체결대금 19,980·수수료 9.99·BTC 0.0016, 미사용 10.01 복원 → 잔액 210.01")
    void sma20BuyFullFillSettles() {
        LiveTradingSessionEntity s = noTimeStopSession();
        OrderEntity buy = submittedSma20Buy(s);
        settle(buy, buyReport(buy.getExchangeOrderId(), "done", "0.0016", "19980", "9.99"));
        System.out.println("[SMA20 통합 ① 전량 체결] " + settled(s, buy));

        OrderEntity o = orderRepository.findById(buy.getId()).orElseThrow();
        assertThat(o.getState()).isEqualTo("FILLED");
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("0.0016");
        assertThat(o.getAppliedExecutedFunds()).as("체결대금").isEqualByComparingTo("19980");
        assertThat(o.getAppliedPaidFee()).as("거래소 보고 수수료").isEqualByComparingTo("9.99");
        assertThat(o.getFeeSource()).isEqualTo(OrderExecutionEngine.FEE_SOURCE_REPORTED);
        assertThat(o.getSettlementVersion()).isEqualTo(1L);
        PositionEntity p = entryPosition(buy);
        assertThat(p.getStatus()).isEqualTo("OPEN");
        assertThat(p.getSize()).as("BTC 수량").isEqualByComparingTo("0.0016");
        assertThat(p.getInvestedKrw()).as("투입금 = 체결대금 + 수수료").isEqualByComparingTo("19989.99");
        assertThat(available(s)).as("200 + (20,000 − 19,980 − 9.99)").isEqualByComparingTo("210.01");
        assertThat(engineActive(buy)).as("종결 — 활성 목록에서 빠진다").isFalse();
        verify(upbitOrderClient, times(1)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("통합 ② 부분 체결 뒤 잔량 취소 — 0.0008 BTC·10,000·수수료 5 반영, 미사용 9,995 복원(고아 정리 반복에도 한 번)")
    void sma20BuyPartialFillThenCancelSettles() {
        LiveTradingSessionEntity s = noTimeStopSession();
        OrderEntity buy = submittedSma20Buy(s);
        settle(buy, buyReport(buy.getExchangeOrderId(), "cancel", "0.0008", "10000", "5"));
        liveTradingService.reconcileOrphanBuyPositions();
        liveTradingService.reconcileOrphanBuyPositions();
        System.out.println("[SMA20 통합 ② 부분 체결 뒤 취소] " + settled(s, buy));

        OrderEntity o = orderRepository.findById(buy.getId()).orElseThrow();
        assertThat(o.getState()).as("체결분이 있으면 CANCELLED 가 아니다").isEqualTo("FILLED");
        assertThat(o.getReportedState()).as("거래소 cancel 보존").isEqualTo("cancel");
        assertThat(o.getQuantity()).as("요청액 보존").isEqualByComparingTo("20000");
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("0.0008");
        assertThat(o.getAppliedExecutedFunds()).isEqualByComparingTo("10000");
        assertThat(o.getAppliedPaidFee()).isEqualByComparingTo("5");
        PositionEntity p = entryPosition(buy);
        assertThat(p.getStatus()).isEqualTo("OPEN");
        assertThat(p.getSize()).isEqualByComparingTo("0.0008");
        assertThat(p.getInvestedKrw()).isEqualByComparingTo("10005");
        assertThat(available(s)).as("200 + (20,000 − 10,000 − 5)").isEqualByComparingTo("10195");
        assertThat(engineActive(buy)).isFalse();
    }

    @Test
    @DisplayName("통합 ③ 같은 응답 반복·수수료 정정 — 반복은 변화 없음, 수수료 9.00→9.99 는 차액 0.99 만 한 번")
    void sma20BuyRepeatAndFeeCorrectionAppliesDifferenceOnce() {
        LiveTradingSessionEntity s = noTimeStopSession();
        OrderEntity buy = submittedSma20Buy(s);
        String uuid = buy.getExchangeOrderId();

        settle(buy, buyReport(uuid, "done", "0.0016", "19980", "9.00"));
        String first = settled(s, buy);
        assertThat(available(s)).as("200 + (20,000 − 19,980 − 9.00)").isEqualByComparingTo("211.00");

        settle(buy, buyReport(uuid, "done", "0.0016", "19980", "9.00"));
        settle(buy, buyReport(uuid, "done", "0.0016", "19980", "9.00"));
        assertThat(settled(s, buy)).as("같은 응답 반복 — 주문·포지션·잔액 변화 없음%n  1회차: %s", first).isEqualTo(first);

        settle(buy, buyReport(uuid, "done", "0.0016", "19980", "9.99"));
        String corrected = settled(s, buy);
        System.out.println("[SMA20 통합 ③ 수수료 정정]\n  1회차: " + first + "\n  정정후: " + corrected);
        OrderEntity o = orderRepository.findById(buy.getId()).orElseThrow();
        assertThat(o.getAppliedPaidFee()).isEqualByComparingTo("9.99");
        assertThat(o.getSettlementVersion()).as("반영 회차만 — 1회차·정정 두 번").isEqualTo(2L);
        assertThat(entryPosition(buy).getSize()).as("수량 불변").isEqualByComparingTo("0.0016");
        assertThat(entryPosition(buy).getInvestedKrw()).isEqualByComparingTo("19989.99");
        assertThat(available(s)).as("차액 0.99 만 차감").isEqualByComparingTo("210.01");

        settle(buy, buyReport(uuid, "done", "0.0016", "19980", "9.99"));
        assertThat(settled(s, buy)).as("정정 응답 반복 — 다시 빼지 않는다").isEqualTo(corrected);
        verify(upbitOrderClient, times(1)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("통합 ④ 체결대금만 정정 — 수량 0.0016 그대로, 19,980→19,990 이면 평균단가·투입금·잔액이 함께 바뀌고 반복은 변화 없음")
    void sma20BuyFundsOnlyCorrectionUpdatesPositionAndBalance() {
        // CLOSED 포지션에 온 정정이 재개방하지 않는 것은 LateFillCorrectionTest 가 본다 — 여기서는 열린 포지션만.
        LiveTradingSessionEntity s = noTimeStopSession();
        OrderEntity buy = submittedSma20Buy(s);
        String uuid = buy.getExchangeOrderId();

        settle(buy, buyReport(uuid, "done", "0.0016", "19980", "9.99"));
        String first = settled(s, buy);
        assertThat(entryPosition(buy).getAvgPrice()).as("19,980 / 0.0016").isEqualByComparingTo("12487500");
        assertThat(available(s)).isEqualByComparingTo("210.01");

        settle(buy, buyReport(uuid, "done", "0.0016", "19990", "9.99"));
        String corrected = settled(s, buy);
        System.out.println("[SMA20 통합 ④ 체결대금 정정]\n  1회차: " + first + "\n  정정후: " + corrected);
        OrderEntity o = orderRepository.findById(buy.getId()).orElseThrow();
        assertThat(o.getAppliedExecutedFunds()).isEqualByComparingTo("19990");
        assertThat(o.getAppliedPaidFee()).as("수수료 그대로").isEqualByComparingTo("9.99");
        assertThat(o.getSettlementVersion()).as("1회차·정정 두 번").isEqualTo(2L);
        PositionEntity p = entryPosition(buy);
        assertThat(p.getSize()).as("수량 불변").isEqualByComparingTo("0.0016");
        assertThat(p.getAvgPrice()).as("19,990 / 0.0016").isEqualByComparingTo("12493750");
        assertThat(p.getInvestedKrw()).as("19,990 + 9.99").isEqualByComparingTo("19999.99");
        assertThat(available(s)).as("차액 10 만 차감 — 200 + (20,000 − 19,990 − 9.99)").isEqualByComparingTo("200.01");

        settle(buy, buyReport(uuid, "done", "0.0016", "19990", "9.99"));
        assertThat(settled(s, buy)).as("정정 응답 반복 — 다시 빼지 않는다").isEqualTo(corrected);
        verify(upbitOrderClient, times(1)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    private boolean engineActive(OrderEntity o) {
        return orderExecutionEngine.getActiveOrders().stream().anyMatch(a -> a.getId().equals(o.getId()));
    }

    // ── 매수 체결 → 청산 → 매도 체결 → 포지션 종료 (문서 §5-9, 2026-10-30 리뷰 지시) ──────────────────

    /** 시장가 매도(ord_type=market) 조회 응답. */
    private static com.cryptoautotrader.exchange.upbit.dto.OrderResponse sellReport(
            String uuid, String volume, String funds, String fee) {
        com.cryptoautotrader.exchange.upbit.dto.OrderResponse r = new com.cryptoautotrader.exchange.upbit.dto.OrderResponse();
        r.setUuid(uuid);
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("done");
        r.setMarket(COIN);
        r.setVolume(new BigDecimal(volume));
        r.setExecutedVolume(new BigDecimal(volume));
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPaidFee(new BigDecimal(fee));
        return r;
    }

    /** 매수 전량 체결(0.0016 BTC · 19,980 · 9.99)까지 — 통합 ①과 같은 출발점(잔액 210.01). */
    private OrderEntity filledSma20Buy(LiveTradingSessionEntity s) {
        OrderEntity buy = submittedSma20Buy(s);
        settle(buy, buyReport(buy.getExchangeOrderId(), "done", "0.0016", "19980", "9.99"));
        assertThat(entryPosition(buy).getSize()).isEqualByComparingTo("0.0016");
        assertThat(available(s)).isEqualByComparingTo("210.01");
        assertHoldingsMatch(s, "매수 체결 뒤");
        return buy;
    }

    /**
     * 매도는 별도 스레드에서 만들어진다 — 행과 접수 기록(SUBMITTED)을 기다린 뒤, 거래소 매도 호출이 1번뿐인지 본다.
     * 매도 대기가 실패하면 단계별 근거(포지션 상태·청산 사유·주문 목록·실행기 상태)를 메시지에 싣는다.
     */
    private OrderEntity assertSellSubmittedOnce(LiveTradingSessionEntity s, PositionEntity p) {
        awaitOrders(s, "SELL", 1);
        awaitDrained();
        List<OrderEntity> sells = orders(s, "SELL");
        PositionEntity cur = reload(p);
        var pool = orderExecutor.getThreadPoolExecutor();
        String trace = String.format("포지션 %s/%s · 주문 %s · 실행기 대기 %d·활성 %d·완료 %d/%d",
                cur.getStatus(), cur.getExitReason(),
                orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", s.getId()).stream()
                        .map(o -> o.getSide() + ":" + o.getState()).toList(),
                pool.getQueue().size(), pool.getActiveCount(), pool.getCompletedTaskCount(), pool.getTaskCount());
        assertThat(sells).as("SELL 주문 1건 — " + trace).hasSize(1);
        assertThat(sells.get(0).getState()).as("SELL 접수 기록 — " + trace).isEqualTo("SUBMITTED");
        assertThat(sells.get(0).getQuantity()).as("매도 수량 = 보유 수량").isEqualByComparingTo("0.0016");
        verify(upbitOrderClient, times(1)).createOrder(eq(COIN), eq("ask"), any(), any(), anyString(), anyString());
        return sells.get(0);
    }

    /** 대역 거래소 보유량 = 열린 포지션 수량 합 = 주문 체결 수량(매수 − 매도) 합. */
    private void assertHoldingsMatch(LiveTradingSessionEntity s, String when) {
        BigDecimal exchange = exchangeFills.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal positions = positionRepository.findBySessionKindAndSessionId("LIVE", s.getId()).stream()
                .filter(p -> !"CLOSED".equals(p.getStatus()))
                .map(PositionEntity::getSize).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal orders = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", s.getId()).stream()
                .filter(o -> o.getFilledQuantity() != null && o.getFilledQuantity().signum() > 0)
                .map(o -> "SELL".equals(o.getSide()) ? o.getFilledQuantity().negate() : o.getFilledQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.println("[SMA20 보유량 " + when + "] 거래소 " + exchange + " · 포지션 " + positions + " · 주문 " + orders);
        assertThat(positions).as(when + " — 열린 포지션 수량 = 대역 거래소 보유량").isEqualByComparingTo(exchange);
        assertThat(orders).as(when + " — 주문 체결 수량 합 = 대역 거래소 보유량").isEqualByComparingTo(exchange);
    }

    private List<OrderEntity> activeOrders(LiveTradingSessionEntity s) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", s.getId()).stream()
                .filter(o -> com.cryptoautotrader.api.util.TradingConstants.ACTIVE_ORDER_STATES.contains(o.getState())
                        || engineActive(o))
                .toList();
    }

    @Test
    @DisplayName("통합 ⑤ 매수 체결 → 손절 → 매도 체결 — 포지션 CLOSED, 잔액 210.01 + 18,880 − 9.44, 보유량 0 일치, 반복 응답은 한 번만")
    void sma20BuyThenStopLossSellClosesAndSettles() {
        LiveTradingSessionEntity s = noTimeStopSession();
        OrderEntity buy = filledSma20Buy(s);
        PositionEntity p = entryPosition(buy);
        System.out.println("[SMA20 통합 ⑤ 진입] avg=" + p.getAvgPrice() + " 손절가=" + p.getStopLossPrice()
                + " 익절가=" + p.getTakeProfitPrice());

        // 같은 마감 봉 위치에 가격만 내린 자료 — 마지막 종가 11,800,000(평균 12,487,500 대비 −5.5%, 손절 5% 아래).
        // 신호 평가는 이미 본 봉이라 건너뛰고, 손절 감시는 매 회차 돈다.
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", COIN);
        insertCache(series(i -> 9_305_000L + 5_000L * i));
        evaluateOnly(s);
        assertThat(reload(p).getStatus()).isEqualTo("CLOSING");
        assertThat(reload(p).getExitReason()).isEqualTo(ExitReason.STOP_LOSS);
        OrderEntity sell = assertSellSubmittedOnce(s, p);

        settle(sell, sellReport(sell.getExchangeOrderId(), "0.0016", "18880", "9.44"));
        liveTradingService.reconcileClosingPositions();
        String closed = settled(s, buy) + " sell=" + orderRepository.findById(sell.getId()).orElseThrow().getState();
        System.out.println("[SMA20 통합 ⑤ 청산] " + closed + " realized=" + reload(p).getRealizedPnl());

        OrderEntity so = orderRepository.findById(sell.getId()).orElseThrow();
        assertThat(so.getState()).isEqualTo("FILLED");
        assertThat(so.getFilledQuantity()).isEqualByComparingTo("0.0016");
        assertThat(so.getAppliedExecutedFunds()).isEqualByComparingTo("18880");
        assertThat(so.getAppliedPaidFee()).isEqualByComparingTo("9.44");
        PositionEntity c = reload(p);
        assertThat(c.getStatus()).isEqualTo("CLOSED");
        assertThat(c.getExitReason()).isEqualTo(ExitReason.STOP_LOSS);
        assertThat(c.getClosedAt()).isNotNull();
        // 🔴 이 REAL LIVE 경로에서 확인한 사실이다(엔진 공통 규칙이 아니다 — PAPER 는 매수 수수료를 평균단가에 넣고,
        //    백테스트는 진입 수수료를 손익에서 뺀다). 여기서 realized_pnl = 매도 순대금 − 수량 × 평균단가 이고 평균단가는
        //    체결금액 ÷ 수량이라 **매수 수수료 9.99 가 빠져 있다.** 실제 순손실은 잔액 기준 −1,119.43 이다.
        //    position_fee(매수 + 매도)를 realized_pnl 에서 다시 빼면 매도 수수료가 두 번 빠진다(−1,128.87, 틀림).
        assertThat(c.getRealizedPnl()).as("18,880 − 9.44 − 0.0016 × 12,487,500 (매수 수수료 미포함)").isEqualByComparingTo("-1109.44");
        assertThat(c.getPositionFee()).as("매수 9.99 + 매도 9.44").isEqualByComparingTo("19.43");
        assertThat(available(s)).as("210.01 + 18,880 − 9.44").isEqualByComparingTo("19080.57");
        assertThat(available(s).subtract(new BigDecimal("20200"))).as("실제 순손실(잔액 기준) = 실현손익 − 매수 수수료")
                .isEqualByComparingTo("-1119.43")
                .isEqualByComparingTo(c.getRealizedPnl().subtract(new BigDecimal("9.99")));
        assertThat(activeOrders(s)).as("활성 주문 0").isEmpty();
        assertHoldingsMatch(s, "청산 뒤");

        // 같은 매도 응답이 다시 와도 대금을 두 번 넣지 않는다.
        settle(sell, sellReport(sell.getExchangeOrderId(), "0.0016", "18880", "9.44"));
        liveTradingService.reconcileClosingPositions();
        assertThat(settled(s, buy) + " sell=" + orderRepository.findById(sell.getId()).orElseThrow().getState())
                .as("매도 응답 반복 — 변화 없음").isEqualTo(closed);
        assertThat(liveRepo.findById(s.getId()).orElseThrow().getStatus()).as("손절은 세션을 멈추지 않는다").isEqualTo("RUNNING");
        verify(upbitOrderClient, times(2)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("통합 ⑥ 매수 체결 → 세션 정지 → 청산 매도 체결 — STOPPED·활성 주문 0·정리 대기 0·보유량 0 일치")
    void sma20BuyThenStopLiquidatesAndCleansUp() {
        LiveTradingSessionEntity s = noTimeStopSession();
        OrderEntity buy = filledSma20Buy(s);
        PositionEntity p = entryPosition(buy);

        liveTradingService.stopSession(s.getId());
        assertThat(liveRepo.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("STOPPED");
        assertThat(reload(p).getStatus()).isEqualTo("CLOSING");
        assertThat(reload(p).getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
        OrderEntity sell = assertSellSubmittedOnce(s, p);
        liveTradingService.cleanupStoppedSessionPositions();
        assertThat(cleanupRepository.countPending("LIVE", s.getId()))
                .as("🔴 매도를 제출했다는 사실로 정리 완료가 아니다").isEqualTo(1L);
        verify(upbitOrderClient, times(2)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());

        // 정지 시점 가격(마지막 종가 12,495,000)으로 체결 — 0.0016 × 12,495,000 = 19,992, 수수료 9.99.
        settle(sell, sellReport(sell.getExchangeOrderId(), "0.0016", "19992", "9.99"));
        liveTradingService.reconcileClosingPositions();
        liveTradingService.cleanupStoppedSessionPositions();
        String done = settled(s, buy);
        System.out.println("[SMA20 통합 ⑥ 정지 청산] " + done + " realized=" + reload(p).getRealizedPnl()
                + " 정리 대기=" + cleanupRepository.countPending("LIVE", s.getId()));

        LiveTradingSessionEntity fin = liveRepo.findById(s.getId()).orElseThrow();
        assertThat(fin.getStatus()).as("청산 확정이 정지 상태를 되돌리지 않는다").isEqualTo("STOPPED");
        PositionEntity c = reload(p);
        assertThat(c.getStatus()).isEqualTo("CLOSED");
        assertThat(c.getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
        // ⑤와 같은 REAL LIVE 경로 규칙 — realized_pnl 2.01 은 매수 수수료 미포함, 실제 순손익은 잔액 기준 −7.98.
        assertThat(c.getRealizedPnl()).as("19,992 − 9.99 − 0.0016 × 12,487,500 (매수 수수료 미포함)").isEqualByComparingTo("2.01");
        assertThat(c.getPositionFee()).as("매수 9.99 + 매도 9.99").isEqualByComparingTo("19.98");
        assertThat(fin.getAvailableKrw()).as("210.01 + 19,992 − 9.99").isEqualByComparingTo("20192.02");
        assertThat(fin.getAvailableKrw().subtract(new BigDecimal("20200"))).as("실제 순손익(잔액 기준)").isEqualByComparingTo("-7.98");
        assertThat(activeOrders(s)).as("활성 주문 0").isEmpty();
        assertThat(orderRepository.countUnresolvedSubmissions("LIVE", s.getId())).as("미해결 제출 0").isZero();
        assertThat(cleanupRepository.countPending("LIVE", s.getId())).as("정리 대기 0").isZero();
        assertHoldingsMatch(s, "정지 청산 뒤");

        // 반복 응답·정리 작업 반복 — 대금 한 번, 매도 추가 없음.
        settle(sell, sellReport(sell.getExchangeOrderId(), "0.0016", "19992", "9.99"));
        liveTradingService.reconcileClosingPositions();
        liveTradingService.cleanupStoppedSessionPositions();
        assertThat(settled(s, buy)).as("반복 — 변화 없음").isEqualTo(done);
        verify(upbitOrderClient, times(2)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    // ── 주문 상태 불명(UNKNOWN) — 확인 전에는 같은 세션이 중복 주문을 내지 않는다 (문서 §6-2) ──────

    @Test
    @DisplayName("UNKNOWN 매수가 남은 세션 — 다음 마감의 BUY 신호에도 새 매수를 내지 않는다")
    void unknownBuyBlocksNewBuy() {
        insertCache(uptrend());
        LiveTradingSessionEntity s = noTimeStopSession();
        // 실제 매수 경로처럼 size=0 포지션 + 응답을 잃은 매수 주문
        PositionEntity p = position(s, lastClose(uptrend()), Instant.now().minus(10, ChronoUnit.MINUTES), BigDecimal.ZERO, "OPEN");
        unknownOrder(s, p, "BUY", new BigDecimal("20000"));
        evaluate(s);
        assertThat(orders(s, "BUY")).as("UNKNOWN 1건 그대로 — 새 매수 없음").hasSize(1);
        assertThat(orders(s, "SELL")).isEmpty();
        assertThat(reload(p).getStatus()).as("확인 전에는 닫지도 복원하지도 않는다").isEqualTo("OPEN");
    }

    @Test
    @DisplayName("UNKNOWN 매도가 남은 CLOSING 포지션 — 손절 구간·SELL 신호여도 다시 팔지 않고 새로 사지도 않는다")
    void unknownSellIsNotResubmitted() {
        List<Candle> cs = downtrend();
        insertCache(cs);
        LiveTradingSessionEntity s = noTimeStopSession();
        BigDecimal avg = lastClose(cs).divide(new BigDecimal("0.90"), 0, java.math.RoundingMode.HALF_UP);
        PositionEntity p = position(s, avg, Instant.now().minus(72, ChronoUnit.HOURS), new BigDecimal("0.001"), "CLOSING");
        unknownOrder(s, p, "SELL", new BigDecimal("0.001"));
        evaluate(s);
        assertThat(orders(s, "SELL")).as("UNKNOWN 1건 그대로 — 재매도 없음").hasSize(1);
        assertThat(orders(s, "BUY")).isEmpty();
        assertThat(reload(p).getStatus()).isEqualTo("CLOSING");
    }

    /**
     * 🔴 정지는 "신규 주문만 막는 기능"이 아니다 — 매수 취소 요청과 장부 수량 청산을 함께 시작한다({@code stopSession}).
     * 여기서는 불명 주문이 남은 채 정지해도 <b>그 주문을 다시 내지 않는지</b>만 본다.
     */
    @Test
    @DisplayName("정지 — UNKNOWN 매수는 식별자 취소 요청만, 매도·자금 복원 없음, 포지션은 정리 대기로 열어 둔다")
    void stopWithUnknownBuyDoesNotDuplicate() {
        insertCache(uptrend());
        LiveTradingSessionEntity s = noTimeStopSession();
        PositionEntity p = position(s, lastClose(uptrend()), Instant.now().minus(10, ChronoUnit.MINUTES), BigDecimal.ZERO, "OPEN");
        OrderEntity buy = unknownOrder(s, p, "BUY", new BigDecimal("20000"));
        liveTradingService.stopSession(s.getId());
        org.mockito.Mockito.verify(upbitOrderClient).cancelOrderByIdentifier(buy.getClientOrderId());
        assertThat(liveRepo.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("STOPPED");
        assertThat(orders(s, "BUY")).as("새 매수 없음").hasSize(1);
        assertThat(orders(s, "SELL")).as("수량 0 — 매도 없음").isEmpty();
        assertThat(orderRepository.findById(buy.getId()).orElseThrow().getState()).as("확인 전 종결 안 함").isEqualTo("UNKNOWN");
        assertThat(liveRepo.findById(s.getId()).orElseThrow().getAvailableKrw()).as("복원 없음").isEqualByComparingTo("20200");
        assertThat(reload(p).getStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("정지 — UNKNOWN 매도가 남은 CLOSING 포지션은 다시 팔지 않는다(진행 중 매도는 취소도 하지 않는다)")
    void stopWithUnknownSellDoesNotDuplicate() {
        List<Candle> cs = downtrend();
        insertCache(cs);
        LiveTradingSessionEntity s = noTimeStopSession();
        PositionEntity p = position(s, lastClose(cs), Instant.now().minus(72, ChronoUnit.HOURS), new BigDecimal("0.001"), "CLOSING");
        OrderEntity sell = unknownOrder(s, p, "SELL", new BigDecimal("0.001"));
        liveTradingService.stopSession(s.getId());
        org.mockito.Mockito.verify(upbitOrderClient, org.mockito.Mockito.never()).cancelOrderByIdentifier(sell.getClientOrderId());
        assertThat(liveRepo.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("STOPPED");
        assertThat(orders(s, "SELL")).as("UNKNOWN 1건 그대로 — 정지가 새 매도를 내지 않는다").hasSize(1);
        assertThat(reload(p).getStatus()).isEqualTo("CLOSING");
    }

    // ── 연결 구간 보완 — 상태 불명·늦은 체결·재시작 후 정산 (문서 §5-9 v1.23, 2026-10-30 리뷰 지시) ──────────────
    //
    // 위 UNKNOWN 시험은 손으로 만든 불명 행이 "막는지"만 본다. 일반 경로(ClaimAndUnknownTrackingTest·
    // CancelConfirmAndLateFillCleanupTest)는 다른 전략의 손으로 만든 주문으로 본다. 여기서는 SMA20 회차가 낸 실제 주문이
    // 불명·늦은 체결·재시작을 지나 정산·청산까지 이어지는지 본다. 재시작은 프로세스 메모리를 비우고 시작 복구 본문을 돈다.

    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    /** 이 방향(bid·ask)의 주문은 거래소가 받았지만 응답을 잃는다(시간 초과). 다른 방향은 정상 접수. */
    private void stubLostResponse(String lostSide) {
        org.mockito.Mockito.doAnswer(inv -> {
            if (lostSide.equals(inv.getArgument(1))) {
                throw new RuntimeException(new java.net.http.HttpTimeoutException("request timed out"));
            }
            return new com.cryptoautotrader.exchange.upbit.UpbitOrderClient.ExchangeResult(
                    accepted(inv.getArgument(1), inv.getArgument(4)), "{}");
        }).when(upbitOrderClient).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    /**
     * 프로세스 재시작 — 메모리에만 있는 상태(이미 평가한 마감 봉·세션 전략 인스턴스·열린/닫힌 회차)를 비우고
     * 시작 복구 본문({@code reconcileInternal})을 트랜잭션 안에서 돈다. {@code reconcileOnStartup} 은 끝에 웹소켓 구독을
     * 하므로(외부 연결) 본문만 부른다.
     */
    private void restart() {
        awaitDrained();
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        ((java.util.Map<?, ?>) ReflectionTestUtils.getField(target, "lastEvaluatedClosedCandle")).clear();
        ((java.util.Map<?, ?>) ReflectionTestUtils.getField(target, "sessionStatefulStrategies")).clear();
        Object r = AopTestUtils.getUltimateTargetObject(rounds);
        synchronized (r) {
            ((java.util.Map<?, ?>) ReflectionTestUtils.getField(r, "open")).clear();
            ((java.util.Map<?, ?>) ReflectionTestUtils.getField(r, "closed")).clear();
        }
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(st -> ReflectionTestUtils.invokeMethod(target, "reconcileInternal"));
    }

    /** 손절 구간 자료로 바꾼다 — 마지막 종가 11,800,000(평균 12,487,500 대비 −5.5%). 통합 ⑤와 같다. */
    private void lossCandles() {
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", COIN);
        insertCache(series(i -> 9_305_000L + 5_000L * i));
    }

    private OrderEntity reloadOrder(OrderEntity o) {
        return orderRepository.findById(o.getId()).orElseThrow();
    }

    @Test
    @DisplayName("연결 ① 매수 응답 유실 → UNKNOWN(예약 유지) → 재시작·같은 봉 재평가에도 새 매수 없음 → 식별자 조회로 체결 정산 → 손절 매도 1회 → CLOSED")
    void sma20LostBuyResponseResolvesAcrossRestartThenExits() {
        LiveTradingSessionEntity s = noTimeStopSession();
        stubLostResponse("bid");
        insertCache(uptrend());
        evaluate(s);
        awaitOrders(s, "BUY", 1);
        awaitDrained();
        assertThat(orders(s, "BUY")).as("BUY 1건 — " + lastTrace).hasSize(1);
        OrderEntity buy = orders(s, "BUY").get(0);
        assertThat(buy.getState()).as("시간 초과는 거절이 아니다").isEqualTo("UNKNOWN");
        assertThat(buy.getClientOrderId()).as("추적 단서 — 식별자").isNotNull();
        assertThat(buy.getExchangeOrderId()).isNull();
        assertThat(available(s)).as("예약 유지 — 쓰였을 수 있는 돈을 되돌리지 않는다").isEqualByComparingTo("200");
        assertThat(entryPosition(buy).getStatus()).isEqualTo("OPEN");
        assertThat(entryPosition(buy).getSize()).isEqualByComparingTo("0");
        assertThat(orderRepository.countUnresolvedSubmissions("LIVE", s.getId())).isEqualTo(1L);

        restart();
        evaluate(s);   // 메모리가 비어 같은 마감 봉을 다시 평가한다
        System.out.println("[SMA20 연결 ① 재시작 뒤 같은 봉] " + lastTrace);
        assertThat(orders(s, "BUY")).as("재시작 뒤 같은 봉 — 새 매수 없음: " + lastTrace).hasSize(1);
        assertThat(reloadOrder(buy).getState()).as("시작 복구는 불명을 종결하지 않는다").isEqualTo("UNKNOWN");
        assertThat(available(s)).isEqualByComparingTo("200");

        String uuid = "sma20-found-" + UUID.randomUUID();
        com.cryptoautotrader.exchange.upbit.dto.OrderResponse report = buyReport(uuid, "done", "0.0016", "19980", "9.99");
        doReturn(report).when(upbitOrderClient).getOrderByIdentifier(buy.getClientOrderId());
        doReturn(report).when(upbitOrderClient).getOrder(uuid);
        exchangeFills.put(uuid, new BigDecimal("0.0016"));
        orderExecutionEngine.pollActiveOrders();
        System.out.println("[SMA20 연결 ① 식별자 해소] " + settled(s, buy));

        OrderEntity o = reloadOrder(buy);
        assertThat(o.getState()).isEqualTo("FILLED");
        assertThat(o.getExchangeOrderId()).as("식별자 조회가 찾은 주문번호").isEqualTo(uuid);
        assertThat(o.getClientOrderId()).as("같은 식별자 유지").isEqualTo(buy.getClientOrderId());
        assertThat(o.getAppliedExecutedFunds()).isEqualByComparingTo("19980");
        assertThat(o.getAppliedPaidFee()).isEqualByComparingTo("9.99");
        assertThat(entryPosition(buy).getSize()).isEqualByComparingTo("0.0016");
        assertThat(entryPosition(buy).getInvestedKrw()).isEqualByComparingTo("19989.99");
        assertThat(available(s)).as("통합 ①과 같다 — 200 + 10.01").isEqualByComparingTo("210.01");
        assertThat(orderRepository.countUnresolvedSubmissions("LIVE", s.getId())).isZero();
        assertHoldingsMatch(s, "불명 해소 뒤");

        lossCandles();
        evaluateOnly(s);
        PositionEntity p = entryPosition(buy);
        assertThat(reload(p).getStatus()).isEqualTo("CLOSING");
        assertThat(reload(p).getExitReason()).isEqualTo(ExitReason.STOP_LOSS);
        OrderEntity sell = assertSellSubmittedOnce(s, p);
        settle(sell, sellReport(sell.getExchangeOrderId(), "0.0016", "18880", "9.44"));
        liveTradingService.reconcileClosingPositions();
        System.out.println("[SMA20 연결 ① 청산] " + settled(s, buy) + " realized=" + reload(p).getRealizedPnl());
        assertThat(reload(p).getStatus()).isEqualTo("CLOSED");
        assertThat(reload(p).getRealizedPnl()).isEqualByComparingTo("-1109.44");
        assertThat(available(s)).as("통합 ⑤와 같다").isEqualByComparingTo("19080.57");
        assertThat(activeOrders(s)).isEmpty();
        assertHoldingsMatch(s, "청산 뒤");
        verify(upbitOrderClient, times(2)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("연결 ② 손절 매도 응답 유실 → UNKNOWN → 반복 평가·청산 확정·재시작에도 다시 팔지 않음(CLOSING 유지) → 식별자 조회로 체결 → CLOSED·대금 한 번")
    void sma20LostSellResponseIsNotResubmittedAcrossRestart() {
        LiveTradingSessionEntity s = noTimeStopSession();
        OrderEntity buy = filledSma20Buy(s);
        PositionEntity p = entryPosition(buy);
        stubLostResponse("ask");
        lossCandles();
        evaluateOnly(s);
        awaitOrders(s, "SELL", 1);
        awaitDrained();
        assertThat(orders(s, "SELL")).hasSize(1);
        OrderEntity sell = orders(s, "SELL").get(0);
        assertThat(sell.getState()).isEqualTo("UNKNOWN");
        assertThat(sell.getClientOrderId()).isNotNull();
        assertThat(reload(p).getStatus()).isEqualTo("CLOSING");

        for (int i = 0; i < 2; i++) {
            evaluateOnly(s);
            liveTradingService.reconcileClosingPositions();
            awaitDrained();
        }
        restart();
        assertThat(reload(p).getStatus()).as("🔴 시작 복구가 불명 매도의 포지션을 OPEN 으로 되돌리지 않는다").isEqualTo("CLOSING");
        evaluateOnly(s);
        liveTradingService.reconcileClosingPositions();
        awaitDrained();
        System.out.println("[SMA20 연결 ② 재시작 뒤] pos=" + reload(p).getStatus() + " 매도="
                + orders(s, "SELL").stream().map(OrderEntity::getState).toList() + " available=" + available(s));
        assertThat(orders(s, "SELL")).as("재매도 없음").hasSize(1);
        assertThat(orders(s, "BUY")).as("새 매수 없음").hasSize(1);
        assertThat(available(s)).as("매도 대금은 체결 확인 전에 넣지 않는다").isEqualByComparingTo("210.01");
        verify(upbitOrderClient, times(1)).createOrder(eq(COIN), eq("ask"), any(), any(), anyString(), anyString());

        String uuid = "sma20-found-" + UUID.randomUUID();
        com.cryptoautotrader.exchange.upbit.dto.OrderResponse report = sellReport(uuid, "0.0016", "18880", "9.44");
        doReturn(report).when(upbitOrderClient).getOrderByIdentifier(sell.getClientOrderId());
        doReturn(report).when(upbitOrderClient).getOrder(uuid);
        exchangeFills.put(uuid, new BigDecimal("-0.0016"));
        orderExecutionEngine.pollActiveOrders();
        liveTradingService.reconcileClosingPositions();
        String closed = settled(s, buy) + " sell=" + reloadOrder(sell).getState();
        System.out.println("[SMA20 연결 ② 식별자 해소·청산] " + closed + " realized=" + reload(p).getRealizedPnl());

        assertThat(reloadOrder(sell).getState()).isEqualTo("FILLED");
        assertThat(reloadOrder(sell).getExchangeOrderId()).isEqualTo(uuid);
        assertThat(reload(p).getStatus()).isEqualTo("CLOSED");
        assertThat(reload(p).getExitReason()).isEqualTo(ExitReason.STOP_LOSS);
        assertThat(reload(p).getRealizedPnl()).isEqualByComparingTo("-1109.44");
        assertThat(available(s)).as("210.01 + 18,880 − 9.44").isEqualByComparingTo("19080.57");
        assertThat(activeOrders(s)).isEmpty();
        assertHoldingsMatch(s, "불명 매도 해소 뒤");

        orderExecutionEngine.pollActiveOrders();
        liveTradingService.reconcileClosingPositions();
        restart();
        assertThat(settled(s, buy) + " sell=" + reloadOrder(sell).getState()).as("반복·재시작 — 변화 없음").isEqualTo(closed);
        verify(upbitOrderClient, times(2)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("연결 ③ 늦은 체결 — 매수 5분 경과 취소 요청, 그사이 체결(체결 후 취소 보고) → 취소로 닫지 않고 체결 정산, 미사용만 복원, 고아 정리 무복원")
    void sma20TimeoutCancelThenLateFillSettles() {
        LiveTradingSessionEntity s = noTimeStopSession();
        OrderEntity buy = submittedSma20Buy(s);
        String uuid = buy.getExchangeOrderId();
        OrderEntity cur = reloadOrder(buy);
        cur.setSubmittedAt(Instant.now().minus(6, ChronoUnit.MINUTES));
        orderRepository.save(cur);
        // 타임아웃 직전 조회(대기) → 취소 전 조회(대기) → 취소 → 확인 조회(체결 후 취소) — CancelConfirmAndLateFillCleanupTest A4 와 같은 순서
        doReturn(buyReport(uuid, "wait", "0", "0", "0"), buyReport(uuid, "wait", "0", "0", "0"),
                buyReport(uuid, "cancel", "0.0016", "19980", "9.99")).when(upbitOrderClient).getOrder(uuid);
        doReturn(buyReport(uuid, "wait", "0", "0", "0")).when(upbitOrderClient).cancelOrder(uuid);
        exchangeFills.put(uuid, new BigDecimal("0.0016"));

        orderExecutionEngine.pollActiveOrders();
        orderExecutionEngine.pollActiveOrders();
        liveTradingService.reconcileOrphanBuyPositions();
        liveTradingService.reconcileOrphanBuyPositions();
        System.out.println("[SMA20 연결 ③ 늦은 체결] " + settled(s, buy));

        verify(upbitOrderClient, times(1)).cancelOrder(uuid);
        OrderEntity o = reloadOrder(buy);
        assertThat(o.getState()).as("체결분이 있으면 CANCELLED 가 아니다").isEqualTo("FILLED");
        assertThat(o.getReportedState()).isEqualTo("cancel");
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("0.0016");
        assertThat(entryPosition(buy).getStatus()).isEqualTo("OPEN");
        assertThat(entryPosition(buy).getSize()).isEqualByComparingTo("0.0016");
        assertThat(available(s)).as("미사용 10.01 만 복원 — 200 + 10.01").isEqualByComparingTo("210.01");
        assertHoldingsMatch(s, "늦은 체결 뒤");

        evaluateOnly(s);
        assertThat(orders(s, "BUY")).as("체결된 포지션이 있으니 새 매수 없음").hasSize(1);
        verify(upbitOrderClient, times(1)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("연결 ④ 늦은 체결 — 매수 접수 뒤 정지(취소 요청), 그 뒤 체결 → 정리 대기, 청산 매도 1회(반복에도), CLOSED 뒤에만 정리 완료")
    void sma20LateFillAfterStopIsLiquidatedOnce() {
        LiveTradingSessionEntity s = noTimeStopSession();
        OrderEntity buy = submittedSma20Buy(s);
        String uuid = buy.getExchangeOrderId();
        doReturn(buyReport(uuid, "wait", "0", "0", "0")).when(upbitOrderClient).getOrder(uuid);
        doReturn(buyReport(uuid, "wait", "0", "0", "0")).when(upbitOrderClient).cancelOrder(uuid);

        liveTradingService.stopSession(s.getId());
        PositionEntity p = entryPosition(buy);
        assertThat(liveRepo.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("STOPPED");
        assertThat(reload(p).getStatus()).as("진행 중 매수의 size=0 포지션은 열어 둔다").isEqualTo("OPEN");
        assertThat(cleanupRepository.countPending("LIVE", s.getId())).as("정지가 정리 대기로 등록한다").isEqualTo(1L);
        assertThat(available(s)).as("체결 여부 확인 전 — 복원 없음").isEqualByComparingTo("200");

        doReturn(buyReport(uuid, "done", "0.0016", "19980", "9.99")).when(upbitOrderClient).getOrder(uuid);
        exchangeFills.put(uuid, new BigDecimal("0.0016"));
        orderExecutionEngine.pollActiveOrders();
        System.out.println("[SMA20 연결 ④ 정지 뒤 늦은 체결] " + settled(s, buy));
        assertThat(reload(p).getSize()).isEqualByComparingTo("0.0016");
        assertThat(available(s)).isEqualByComparingTo("210.01");
        assertHoldingsMatch(s, "정지 뒤 늦은 체결");

        for (int i = 0; i < 3; i++) {
            liveTradingService.cleanupStoppedSessionPositions();
            awaitDrained();
        }
        OrderEntity sell = assertSellSubmittedOnce(s, p);
        assertThat(reload(p).getStatus()).isEqualTo("CLOSING");
        assertThat(reload(p).getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
        assertThat(cleanupRepository.countPending("LIVE", s.getId())).as("🔴 매도 제출로 완료가 아니다").isEqualTo(1L);

        settle(sell, sellReport(sell.getExchangeOrderId(), "0.0016", "19992", "9.99"));
        liveTradingService.reconcileClosingPositions();
        liveTradingService.cleanupStoppedSessionPositions();
        System.out.println("[SMA20 연결 ④ 정리 청산] " + settled(s, buy) + " 정리 대기=" + cleanupRepository.countPending("LIVE", s.getId()));
        assertThat(reload(p).getStatus()).isEqualTo("CLOSED");
        assertThat(liveRepo.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("STOPPED");
        assertThat(available(s)).as("통합 ⑥과 같다 — 210.01 + 19,992 − 9.99").isEqualByComparingTo("20192.02");
        assertThat(cleanupRepository.countPending("LIVE", s.getId())).isZero();
        assertThat(orderRepository.countUnresolvedSubmissions("LIVE", s.getId())).isZero();
        assertThat(activeOrders(s)).isEmpty();
        assertHoldingsMatch(s, "정지 정리 뒤");
        verify(upbitOrderClient, times(2)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("연결 ⑤ 재시작 — 매수 접수(SUBMITTED) 중 재시작: 시작 복구가 주문을 건드리지 않고, 같은 봉 재평가에도 새 매수 없음, 이어서 체결 정산")
    void sma20RestartWhileBuySubmittedThenSettles() {
        LiveTradingSessionEntity s = noTimeStopSession();
        OrderEntity buy = submittedSma20Buy(s);

        restart();
        assertThat(reloadOrder(buy).getState()).as("거래소 주문번호가 있는 접수 주문은 시작 복구 대상이 아니다").isEqualTo("SUBMITTED");
        evaluate(s);
        System.out.println("[SMA20 연결 ⑤ 재시작 뒤 같은 봉] " + lastTrace);
        assertThat(orders(s, "BUY")).as("새 매수 없음 — " + lastTrace).hasSize(1);
        assertThat(available(s)).isEqualByComparingTo("200");

        settle(buy, buyReport(buy.getExchangeOrderId(), "done", "0.0016", "19980", "9.99"));
        System.out.println("[SMA20 연결 ⑤ 재시작 뒤 정산] " + settled(s, buy));
        assertThat(reloadOrder(buy).getState()).isEqualTo("FILLED");
        assertThat(entryPosition(buy).getSize()).isEqualByComparingTo("0.0016");
        assertThat(available(s)).isEqualByComparingTo("210.01");
        assertHoldingsMatch(s, "재시작 뒤 정산");
        verify(upbitOrderClient, times(1)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("연결 ⑥ 재시작 — 손절 매도 체결 기록 뒤 포지션 반영 전 재시작: 시작 복구가 CLOSED 확정·대금 한 번, 반복·재시작에 변화 없음")
    void sma20RestartAfterSellFillFinalizesOnce() {
        LiveTradingSessionEntity s = noTimeStopSession();
        OrderEntity buy = filledSma20Buy(s);
        PositionEntity p = entryPosition(buy);
        lossCandles();
        evaluateOnly(s);
        OrderEntity sell = assertSellSubmittedOnce(s, p);
        settle(sell, sellReport(sell.getExchangeOrderId(), "0.0016", "18880", "9.44"));
        System.out.println("[SMA20 연결 ⑥ 재시작 전] " + settled(s, buy) + " sell=" + reloadOrder(sell).getState());
        assertThat(reloadOrder(sell).getState()).isEqualTo("FILLED");
        assertThat(reload(p).getStatus()).as("매도 체결 기록만 — 포지션 반영 전").isEqualTo("CLOSING");

        restart();
        String closed = settled(s, buy) + " sell=" + reloadOrder(sell).getState();
        System.out.println("[SMA20 연결 ⑥ 시작 복구 뒤] " + closed + " realized=" + reload(p).getRealizedPnl());
        assertThat(reload(p).getStatus()).isEqualTo("CLOSED");
        assertThat(reload(p).getRealizedPnl()).isEqualByComparingTo("-1109.44");
        assertThat(available(s)).as("통합 ⑤와 같다").isEqualByComparingTo("19080.57");
        assertThat(activeOrders(s)).isEmpty();
        assertHoldingsMatch(s, "시작 복구 뒤");

        liveTradingService.reconcileClosingPositions();
        restart();
        orderExecutionEngine.pollActiveOrders();
        assertThat(settled(s, buy) + " sell=" + reloadOrder(sell).getState()).as("반복·재시작 — 대금 한 번").isEqualTo(closed);
        verify(upbitOrderClient, times(2)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("연결 ⑦ 재시작 — 빈 메모리의 회차도 마감(종가 + 5분) 뒤 접수를 거절한다: 같은 마감 봉의 두 번째 진입은 재시작으로 생기지 않는다")
    void freshRoundsRejectLateSubmissionAfterRestart() {
        // 첫 승인은 마감 시각 이후에만 일어난다(processDue). 재시작한 프로세스가 같은 봉을 다시 평가해 접수하려 할 때는
        // 이미 마감이 지났고, 그 판정은 메모리(closed)가 아니라 시각으로 한다 — 빈 인스턴스로 확인한다.
        EntryPriorityRounds fresh = new EntryPriorityRounds(limits);
        fresh.setForcedForTest(true);
        EntryPriorityRounds.RoundKey key = new EntryPriorityRounds.RoundKey("H1", lastOpen);
        Instant deadline = EntryPriorityRounds.deadline(key);
        assertThat(deadline).isEqualTo(lastOpen.plus(65, ChronoUnit.MINUTES));
        String late = fresh.submit(new EntryPriorityRounds.Candidate("LIVE", -1L, COIN, key, () -> "호출되면 안 된다"),
                deadline.plusSeconds(1));
        assertThat(late).as("빈 메모리여도 마감 뒤 도착").contains("회차 마감 뒤 도착");
        assertThat(fresh.processDue(deadline.plusSeconds(2))).as("승인할 후보 없음").isEmpty();
    }
}
