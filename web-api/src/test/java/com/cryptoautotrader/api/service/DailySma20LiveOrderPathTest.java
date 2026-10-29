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
import static org.mockito.Mockito.doReturn;

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

    /** 시험 세션 설정(문서 §5-1): 초기 20,200 · 투자 비율 0.995 · 1회 상한 20,000 · 손절 하한 5% · 시간 청산 24h. */
    private LiveTradingSessionEntity session(BigDecimal availableKrw, BigDecimal maxInvestment) {
        LiveTradingSessionEntity s = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType(DailySmaTrendStrategy.NAME).coinPair(COIN).timeframe("H1")
                .initialCapital(new BigDecimal("20200")).availableKrw(availableKrw).totalAssetKrw(availableKrw)
                .investRatio(new BigDecimal("0.995")).maxInvestment(maxInvestment)
                .stopLossPct(new BigDecimal("5.00")).maxHoldHours(24)
                .status("RUNNING").startedAt(Instant.now().minus(30, ChronoUnit.DAYS)).build());
        liveIds.add(s.getId());
        return s;
    }

    private LiveTradingSessionEntity testSession() {
        return session(new BigDecimal("20200"), new BigDecimal("20000"));
    }

    private PositionEntity openPosition(LiveTradingSessionEntity s, BigDecimal avgPrice, Instant openedAt) {
        BigDecimal size = new BigDecimal("0.001");
        return positionRepository.save(PositionEntity.builder()
                .coinPair(COIN).side("BUY").entryPrice(avgPrice).avgPrice(avgPrice).size(size)
                .investedKrw(avgPrice.multiply(size)).entryAllocationKrw(avgPrice.multiply(size))
                .status("OPEN").sessionId(s.getId()).sessionKind("LIVE").openedAt(openedAt).build());
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
        List<OrderEntity> buys = orders(s, "BUY");
        System.out.println("[SMA20 진입] " + lastLog(s).getSignal() + " / " + lastLog(s).getReason());
        assertThat(buys).hasSize(1);
        assertThat(buys.get(0).getQuantity()).isEqualByComparingTo("20000");
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
        assertThat(awaitOrders(s, "SELL", 1)).hasSize(1);
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
        assertThat(awaitOrders(s, "SELL", 1)).hasSize(1);
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
        assertThat(orders(s, "BUY")).hasSize(1);
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

        assertThat(orders(capped, "BUY")).hasSize(1);
        assertThat(orders(capped, "BUY").get(0).getQuantity()).isEqualByComparingTo("20000");

        assertThat(orders(uncapped, "BUY")).as("24,875 > 포지션당 20,000 → 예약 거절").isEmpty();
        assertThat(lastLog(uncapped).getBlockedReason()).contains("PER_POSITION_CAP");
    }
}
