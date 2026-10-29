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
        // 시험마다 몇 초씩 옮겨 회차 키가 겹치지 않게 한다 — 처리된 회차는 늦은 접수를 받지 않는다.
        lastOpen = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)
                .minusSeconds(ROUND_SEQ.incrementAndGet());
        useCloseHour(lastOpen.atZone(ZoneOffset.UTC).getHour());
    }

    private static final java.util.concurrent.atomic.AtomicInteger ROUND_SEQ = new java.util.concurrent.atomic.AtomicInteger();

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

    /** 평가 회차 + 회차 승인 — 운영 한 번의 마감 처리와 같다. */
    private void evaluate(LiveTradingSessionEntity s) {
        evaluateOnly(s);
        approveRound();
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
        assertThat(found).as(side + " 주문 1건").hasSize(1);
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
}
