package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionCleanupRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.ExecutorBarrier;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.155 §4-2 — 실시간 처리기의 점검 간격(평상시 5초·급등락 1초)이 보호 가격을 처음 넘은 유효 시세를 버리는가 (2026-11-12).
 *
 * <p>직접 처리기 경로다 — 실제 {@code LiveTradingService}/{@code DynamicTradingService} 의 {@code onRealtimePriceEvent}(실제
 * {@code marketDataExecutor} 위 @Async·@Transactional)를 한 시세씩 부르고 실행기 장벽으로 끝을 기다린다. F 전달 경계는 지나지 않는다.
 * 실제 세션·포지션(진입 300·손절가 285)과 실제 주문 실행기를 쓰고 거래소 주문 클라이언트만 대역이다.</p>
 *
 * <p>시간은 기다리지 않는다(sleep 없음) — 처리기가 마지막 점검 시각을 담는 지도({@code rtStopLossLastCheckMs}·{@code spikeCheckLastMs}·
 * {@code rtCheckLastMs})에 "지금 − 경과"를 넣어 경과를 만든다. "간격 안"은 5초 경로 4초·1초 경로 0.5초 경과(처리 지연 여유를 둔다),
 * "경계"는 정확히 간격만큼 경과다. 처리기가 벽시계를 직접 읽으므로 1ms 직전은 결정적으로 만들 수 없다 — 이 후보는 시계 접점을 더하지 않는다.</p>
 *
 * <p>점검 여부는 세션의 SL 점검 시각({@code lastSlCheckAt})에 표지(EPOCH)를 넣고 바뀌었는지로 본다. 기점 b5cd1d0 에서는 "간격 안 첫 통과"
 * 사례가 실패해야 하고(버려짐), 경계·반복·비가격·낡은 시세 사례는 수정 전후 같아야 한다.</p>
 */
@DisplayName("v1.155 §4-2 실시간 점검 간격 — 보호 가격 첫 통과 시세 (LIVE/DYNAMIC 직접 처리기)")
class RealtimeThrottleCrossingTest extends IntegrationTestBase {

    private static final BigDecimal SIZE = new BigDecimal("9.80000000");
    private static final AtomicInteger COIN_SEQ = new AtomicInteger();

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;
    @Autowired @Qualifier("marketDataExecutor") private ThreadPoolTaskExecutor marketDataExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final AtomicInteger sellSeq = new AtomicInteger();

    private record Fx(String kind, Long sessionId, Long positionId, String coin) { }

    @BeforeEach
    void exchange() {
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        report("uuid-thr-" + sellSeq.incrementAndGet() + "-" + System.nanoTime(), inv.getArgument(0)), "{}"));
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString())).thenAnswer(inv -> report(inv.getArgument(0), null));
    }

    @AfterEach
    void cleanUp() {
        barrier();
        for (Long id : liveSessions) wipe("LIVE", id, () -> liveRepo.deleteById(id));
        for (Long id : dynamicSessions) wipe("DYNAMIC", id, () -> dynamicRepo.deleteById(id));
        liveSessions.clear();
        dynamicSessions.clear();
    }

    private void wipe(String kind, Long sid, Runnable deleteSession) {
        positionRepository.findBySessionKindAndSessionId(kind, sid).forEach(p -> cleanupRepository.deleteById(p.getId()));
        orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, sid).forEach(orderRepository::delete);
        positionRepository.findBySessionKindAndSessionId(kind, sid).forEach(positionRepository::delete);
        deleteSession.run();
    }

    private static OrderResponse report(String uuid, String market) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("wait");
        r.setMarket(market);
        r.setVolume(SIZE);
        r.setExecutedVolume(BigDecimal.ZERO);
        return r;
    }

    /** 실행 중 실자금 세션 + 수량 9.8·진입 300·손절가 285(익절가 tp, null 이면 없음) OPEN 포지션. */
    private Fx holding(String kind, BigDecimal tp) {
        return holding(kind, tp, new BigDecimal("285"), newCoin());
    }

    private static String newCoin() {
        return "KRW-T" + COIN_SEQ.incrementAndGet() + "X" + (System.nanoTime() % 100000);
    }

    /**
     * 실행 중 실자금 세션(손절 5%) + 수량 9.8·진입/평균 300 OPEN 포지션. 절대 손절가 absStop 이 null 이면 세션 백분율 손절(5%)만 판정한다
     * (총괄 v1.157 §125 반례). coin 을 같게 주면 같은 코인의 여러 세션·보호선을 만든다.
     */
    private Fx holding(String kind, BigDecimal tp, BigDecimal absStop, String coin) {
        Long sid;
        if ("DYNAMIC".equals(kind)) {
            sid = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                    .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                    .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                    .totalAssetKrw(new BigDecimal("10000"))
                    .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                    .status("RUNNING").scanState("POSITION_MONITORING").tradingMode("REAL")
                    .currentCoinPair(coin)
                    .maxCandidateSize(30).targetWatchSize(10)
                    .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                    .watchlistRefreshMin(60)
                    .build()).getId();
            dynamicSessions.add(sid);
        } else {
            sid = liveRepo.saveAndFlush(LiveTradingSessionEntity.builder()
                    .sessionType("REAL").strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                    .coinPair(coin).timeframe("M5")
                    .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                    .totalAssetKrw(new BigDecimal("10000")).stopLossPct(new BigDecimal("5.0"))
                    .status("RUNNING").startedAt(Instant.now()).build()).getId();
            liveSessions.add(sid);
        }
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair(coin).side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(SIZE).investedKrw(new BigDecimal("2940")).entryAllocationKrw(new BigDecimal("3000"))
                .stopLossPrice(absStop).takeProfitPrice(tp)
                .status("OPEN").sessionKind(kind).sessionId(sid)
                .openedAt(Instant.now()).build());
        if ("DYNAMIC".equals(kind)) {
            DynamicSessionEntity s = dynamicRepo.findById(sid).orElseThrow();
            s.setCurrentPositionId(pos.getId());
            dynamicRepo.saveAndFlush(s);
        }
        return new Fx(kind, sid, pos.getId(), coin);
    }

    private Object target(Fx f) {
        return AopTestUtils.getTargetObject("LIVE".equals(f.kind()) ? liveTradingService : dynamicTradingService);
    }

    @SuppressWarnings("unchecked")
    private <V> Map<Object, V> field(Fx f, String name) {
        return (Map<Object, V>) ReflectionTestUtils.getField(target(f), name);
    }

    private void barrier() {
        ExecutorBarrier.awaitPriorTasks(marketDataExecutor.getThreadPoolExecutor(), 15_000);
        ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 15_000);
    }

    /** 한 시세를 그 엔진 처리기에 직접(실제 프록시 — @Async·@Transactional) 넣고 끝을 기다린다. 반환: 이 시세가 점검을 일으켰는가. */
    private boolean tick(Fx f, String price, Instant observedAt) {
        Map<Object, Instant> slCheck = field(f, "lastSlCheckAt");
        slCheck.put(f.sessionId(), Instant.EPOCH);
        RealtimePriceEvent e = new RealtimePriceEvent(f.coin(), price == null ? null : new BigDecimal(price), observedAt,
                RealtimePriceEvent.WS_TRADE);
        if ("LIVE".equals(f.kind())) liveTradingService.onRealtimePriceEvent(e);
        else dynamicTradingService.onRealtimePriceEvent(e);
        barrier();
        return !Instant.EPOCH.equals(slCheck.get(f.sessionId()));
    }

    private boolean tick(Fx f, String price) {
        return tick(f, price, Instant.now());
    }

    /** 마지막 점검을 "지금 − elapsedMs" 로 둔다(시간 대기 대신). */
    private void elapsed(Fx f, String map, long elapsedMs) {
        Map<Object, Long> m = field(f, map);
        m.put(f.coin(), System.currentTimeMillis() - elapsedMs);
    }

    private int sells(Fx f) {
        return (int) Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName()) && f.coin().equals(i.getArgument(0)))
                .count();
    }

    private String status(Fx f) {
        return positionRepository.findById(f.positionId()).orElseThrow().getStatus();
    }

    private void trace(String name, Fx f, Object... cols) {
        StringBuilder b = new StringBuilder("[THR155] ").append(name).append(" | ").append(f.kind());
        for (Object c : cols) b.append(" | ").append(c);
        b.append(" | 상태=").append(status(f)).append(" 매도=").append(sells(f));
        System.out.println(b);
    }

    private static final String LIVE_NORMAL = "rtStopLossLastCheckMs";
    private static final String LIVE_SPIKE = "spikeCheckLastMs";
    private static final String DYN = "rtCheckLastMs";

    // ── LIVE ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L1 평상시 경로 — 286 점검 뒤 5초 간격 안(4초)의 284(손절가 285 첫 통과): 점검·손절 1")
    void liveNormalCrossingInsideInterval() {
        Fx f = holding("LIVE", null);
        boolean c1 = tick(f, "286");
        elapsed(f, LIVE_NORMAL, 4_000);
        boolean c2 = tick(f, "284");
        trace("L1", f, "286 점검=" + c1, "284 점검=" + c2);
        assertThat(c1).isTrue();
        assertThat(c2).as("간격 안이지만 보호선 첫 통과 — 점검").isTrue();
        assertThat(sells(f)).isEqualTo(1);
        assertThat(status(f)).isEqualTo("CLOSING");
    }

    @Test
    @DisplayName("L2 평상시 경로 경계 — 286 점검 뒤 정확히 5초 경과의 284: 수정 전후 모두 점검·손절 1")
    void liveNormalAtBoundary() {
        Fx f = holding("LIVE", null);
        tick(f, "286");
        elapsed(f, LIVE_NORMAL, 5_000);
        boolean c2 = tick(f, "284");
        trace("L2", f, "284 점검=" + c2);
        assertThat(c2).isTrue();
        assertThat(sells(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("L3 급등락 경로 — 310·290(급락 점검) 뒤 1초 간격 안(0.5초)의 280(첫 통과): 점검·손절 1")
    void liveSpikeCrossingInsideInterval() {
        Fx f = holding("LIVE", null);
        tick(f, "310");
        boolean c2 = tick(f, "290");
        elapsed(f, LIVE_SPIKE, 500);
        boolean c3 = tick(f, "280");
        trace("L3", f, "290 점검=" + c2, "280 점검=" + c3);
        assertThat(c2).as("310→290 급락(−6.45%/30s) — 첫 급락 점검").isTrue();
        assertThat(c3).as("1초 간격 안이지만 보호선 첫 통과 — 점검").isTrue();
        assertThat(sells(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("L4 급등락 경로 경계 — 290 급락 점검 뒤 정확히 1초 경과의 280: 수정 전후 모두 점검·손절 1")
    void liveSpikeAtBoundary() {
        Fx f = holding("LIVE", null);
        tick(f, "310");
        tick(f, "290");
        elapsed(f, LIVE_SPIKE, 1_000);
        boolean c3 = tick(f, "280");
        trace("L4", f, "280 점검=" + c3);
        assertThat(c3).isTrue();
        assertThat(sells(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("L5 단순 반복 — 손절가 위 290 세 번(간격 안): 첫 번만 점검, 매도 0 — 수정 전후 같다")
    void liveRepeatAboveStopThrottled() {
        Fx f = holding("LIVE", null);
        boolean c1 = tick(f, "290");
        boolean c2 = tick(f, "290");
        boolean c3 = tick(f, "290");
        trace("L5", f, "점검=" + c1 + "," + c2 + "," + c3);
        assertThat(List.of(c1, c2, c3)).containsExactly(true, false, false);
        assertThat(sells(f)).isZero();
    }

    @Test
    @DisplayName("L6 첫 통과 뒤 계속 아래 — 284(첫 통과) 뒤 283·282 는 간격 안이면 점검하지 않는다(유계), 매도 1(중복 없음)")
    void liveBelowAfterCrossingBounded() {
        Fx f = holding("LIVE", null);
        tick(f, "286");
        elapsed(f, LIVE_NORMAL, 4_000);
        boolean c2 = tick(f, "284");
        boolean c3 = tick(f, "283");
        boolean c4 = tick(f, "282");
        trace("L6", f, "점검=" + c2 + "," + c3 + "," + c4);
        assertThat(c3).isFalse();
        assertThat(c4).isFalse();
        assertThat(sells(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("L7 비가격·낡은 시세 — 286 뒤 간격 안의 0·낡은(10분 전) 280 은 점검·매도·통과 기억 없음, 다음 유효 284 는 첫 통과로 점검")
    void liveInvalidAndStaleIgnored() {
        Fx f = holding("LIVE", null);
        tick(f, "286");
        elapsed(f, LIVE_NORMAL, 4_000);
        boolean zero = tick(f, "0");
        boolean stale = tick(f, "280", Instant.now().minus(10, ChronoUnit.MINUTES));
        int sellsAfterInvalid = sells(f);
        boolean valid = tick(f, "284");
        trace("L7", f, "0 점검=" + zero, "낡은 280 점검=" + stale, "무효 뒤 매도=" + sellsAfterInvalid, "284 점검=" + valid);
        assertThat(zero).isFalse();
        assertThat(stale).isFalse();
        assertThat(sellsAfterInvalid).isZero();
        assertThat(valid).as("직전 유효 시세 286 → 284 첫 통과").isTrue();
        assertThat(sells(f)).isEqualTo(1);
    }

    // ── DYNAMIC ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D1 DYNAMIC 손절 — 286 점검 뒤 5초 간격 안(4초)의 284(첫 통과): 점검·손절 1")
    void dynamicStopCrossingInsideInterval() {
        Fx f = holding("DYNAMIC", null);
        tick(f, "286");
        elapsed(f, DYN, 4_000);
        boolean c2 = tick(f, "284");
        trace("D1", f, "284 점검=" + c2);
        assertThat(c2).isTrue();
        assertThat(sells(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("D2 DYNAMIC 익절 — 익절가 330, 329 점검 뒤 5초 간격 안(4초)의 331(첫 통과): 점검·익절 1")
    void dynamicTakeProfitCrossingInsideInterval() {
        Fx f = holding("DYNAMIC", new BigDecimal("330"));
        tick(f, "329");
        elapsed(f, DYN, 4_000);
        boolean c2 = tick(f, "331");
        trace("D2", f, "331 점검=" + c2);
        assertThat(c2).isTrue();
        assertThat(sells(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("D3 DYNAMIC 경계 — 286 점검 뒤 정확히 5초 경과의 284: 수정 전후 모두 점검·손절 1")
    void dynamicAtBoundary() {
        Fx f = holding("DYNAMIC", null);
        tick(f, "286");
        elapsed(f, DYN, 5_000);
        boolean c2 = tick(f, "284");
        trace("D3", f, "284 점검=" + c2);
        assertThat(c2).isTrue();
        assertThat(sells(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("D4 DYNAMIC 단순 반복 — 손절가 위 290 세 번(간격 안): 첫 번만 점검, 매도 0")
    void dynamicRepeatAboveStopThrottled() {
        Fx f = holding("DYNAMIC", null);
        boolean c1 = tick(f, "290");
        boolean c2 = tick(f, "290");
        boolean c3 = tick(f, "290");
        trace("D4", f, "점검=" + c1 + "," + c2 + "," + c3);
        assertThat(List.of(c1, c2, c3)).containsExactly(true, false, false);
        assertThat(sells(f)).isZero();
    }

    // ── 총괄 v1.157 §125 — 판정에 이르지 못한 조기 점검이 실제 첫 통과를 소비하는가 ─────────────────────────────
    //
    // 평균 300·손절 5%·절대 손절가 없음이면 실제 판정은 손익률(6자리 HALF_UP 반올림 뒤 ×100) ≤ −5 다.
    //   285.00025 → −0.049999(…167 반올림) → −4.9999 : 미발동
    //   285.0001  → −0.050000(…667 반올림) → −5.0000 : 발동
    //   284       → −5.3333                          : 발동
    // 모든 시세는 직전 점검 뒤 0.5초 경과(간격 안)로 둔다. LIVE 는 평상시·급등락 두 간격 지도를 모두 0.5초로 둔다 — 급락 경로가
    // 우연히 점검을 불러 결과를 가리지 않게 한다(이 시험은 첫 통과 규칙만 본다).

    private static final long WITHIN_MS = 500;

    private void within(Fx f) {
        if ("LIVE".equals(f.kind())) {
            elapsed(f, LIVE_NORMAL, WITHIN_MS);
            elapsed(f, LIVE_SPIKE, WITHIN_MS);
        } else {
            elapsed(f, DYN, WITHIN_MS);
        }
    }

    /** 같은 코인 여러 세션 — 모두에 점검 표지를 넣고 한 시세를 첫 세션 엔진에 넣는다. 반환: 세션별 점검 여부. */
    private List<Boolean> tickAll(String price, Fx... fs) {
        Map<Object, Instant> slCheck = field(fs[0], "lastSlCheckAt");
        for (Fx f : fs) slCheck.put(f.sessionId(), Instant.EPOCH);
        RealtimePriceEvent e = new RealtimePriceEvent(fs[0].coin(), new BigDecimal(price), Instant.now(), RealtimePriceEvent.WS_TRADE);
        if ("LIVE".equals(fs[0].kind())) liveTradingService.onRealtimePriceEvent(e);
        else dynamicTradingService.onRealtimePriceEvent(e);
        barrier();
        List<Boolean> out = new ArrayList<>();
        for (Fx f : fs) out.add(!Instant.EPOCH.equals(slCheck.get(f.sessionId())));
        return out;
    }

    private void percentStopCrossingAfterNearMiss(String kind, String name) {
        Fx f = holding(kind, null, null, newCoin());
        boolean initial = tick(f, "286");
        within(f);
        boolean near = tick(f, "285.00025");
        int sellsNear = sells(f);
        within(f);
        boolean crossed = tick(f, "284");
        int sellsCrossed = sells(f);
        within(f);
        boolean again = tick(f, "283");
        trace(name, f, "286 점검=" + initial, "285.00025 점검=" + near, "그때 매도=" + sellsNear,
                "284 점검=" + crossed, "그때 매도=" + sellsCrossed, "283 점검=" + again);
        assertThat(initial).isTrue();
        assertThat(near).as("285.00025 는 실제 판정(−4.9999%)상 미발동 — 판정 규칙과 같은 기준이면 조기 점검하지 않는다").isFalse();
        assertThat(sellsNear).isZero();
        assertThat(crossed).as("284 는 실제 판정상 처음 발동하는 유효 시세 — 간격 안이어도 점검").isTrue();
        assertThat(sellsCrossed).isEqualTo(1);
        assertThat(again).as("청산 뒤 계속 아래 — 보호선 없음, 간격 안이면 점검 없음").isFalse();
        assertThat(sells(f)).as("한 번 청산 뒤 중복 매도 없음").isEqualTo(1);
        assertThat(status(f)).isEqualTo("CLOSING");
    }

    @Test
    @DisplayName("P1 DYNAMIC 백분율 손절(절대가 없음) — 286→285.00025→284(간격 안): 중간 미발동 시세는 점검하지 않고 284 점검·손절 1, 뒤 283 중복 0")
    void dynamicPercentStopNearMissDoesNotConsumeCrossing() {
        percentStopCrossingAfterNearMiss("DYNAMIC", "P1");
    }

    @Test
    @DisplayName("P2 LIVE 백분율 손절(절대가 없음) — 286→285.00025→284(간격 안): 중간 미발동 시세는 점검하지 않고 284 점검·손절 1, 뒤 283 중복 0")
    void livePercentStopNearMissDoesNotConsumeCrossing() {
        percentStopCrossingAfterNearMiss("LIVE", "P2");
    }

    private void absoluteStopControl(String kind, String name) {
        Fx f = holding(kind, null, new BigDecimal("285"), newCoin());
        tick(f, "286");
        within(f);
        boolean near = tick(f, "285.00025");
        within(f);
        boolean crossed = tick(f, "284");
        trace(name, f, "285.00025 점검=" + near, "284 점검=" + crossed);
        assertThat(near).isFalse();
        assertThat(crossed).isTrue();
        assertThat(sells(f)).isEqualTo(1);
        assertThat(status(f)).isEqualTo("CLOSING");
    }

    @Test
    @DisplayName("P3 DYNAMIC 절대 손절가 285 대조 — 같은 입력: 중간 점검 없음, 284 점검·손절 1")
    void dynamicAbsoluteStopControl() {
        absoluteStopControl("DYNAMIC", "P3");
    }

    @Test
    @DisplayName("P4 LIVE 절대 손절가 285 대조 — 같은 입력: 중간 점검 없음, 284 점검·손절 1")
    void liveAbsoluteStopControl() {
        absoluteStopControl("LIVE", "P4");
    }

    private void twoLinesSameCoin(String kind, String name) {
        String coin = newCoin();
        Fx pct = holding(kind, null, null, coin);                    // 백분율 5% (실제 발동 285.0001 이하)
        Fx abs = holding(kind, null, new BigDecimal("280"), coin);   // 절대 280
        List<Boolean> c0 = tickAll("286", pct, abs);
        within(pct);
        List<Boolean> c1 = tickAll("285.00025", pct, abs);
        within(pct);
        List<Boolean> c2 = tickAll("284", pct, abs);
        int sellsAfter284 = sells(pct);
        within(pct);
        List<Boolean> c3 = tickAll("279", pct, abs);
        String pctStatus = status(pct);
        trace(name, abs, "286=" + c0, "285.00025=" + c1, "284=" + c2, "284뒤 매도=" + sellsAfter284, "279=" + c3,
                "백분율 포지션=" + pctStatus);
        assertThat(c1).as("어느 보호선도 실제 발동하지 않는 시세 — 점검 없음").containsExactly(false, false);
        assertThat(c2.get(0)).as("백분율 보호선 첫 발동 — 점검").isTrue();
        assertThat(sellsAfter284).isEqualTo(1);
        assertThat(c3.get(1)).as("남은 절대 280 보호선 첫 발동 — 간격 안이어도 점검").isTrue();
        assertThat(sells(pct)).as("코인 매도 = 두 포지션 각 1").isEqualTo(2);
        assertThat(pctStatus).isEqualTo("CLOSING");
        assertThat(status(abs)).isEqualTo("CLOSING");
    }

    @Test
    @DisplayName("P5 DYNAMIC 같은 코인 두 보호선(백분율 5%·절대 280) — 285.00025 점검 없음, 284 백분율 손절, 279 절대 손절: 매도 2")
    void dynamicTwoLinesSameCoin() {
        twoLinesSameCoin("DYNAMIC", "P5");
    }

    @Test
    @DisplayName("P6 LIVE 같은 코인 두 보호선(백분율 5%·절대 280) — 285.00025 점검 없음, 284 백분율 손절, 279 절대 손절: 매도 2")
    void liveTwoLinesSameCoin() {
        twoLinesSameCoin("LIVE", "P6");
    }

    private void percentInvalidAndStale(String kind, String name) {
        Fx f = holding(kind, null, null, newCoin());
        tick(f, "286");
        within(f);
        boolean zero = tick(f, "0");
        boolean stale = tick(f, "280", Instant.now().minus(10, ChronoUnit.MINUTES));
        within(f);
        boolean near = tick(f, "285.00025");
        int sellsBefore = sells(f);
        within(f);
        boolean crossed = tick(f, "284");
        trace(name, f, "0=" + zero, "낡은280=" + stale, "285.00025=" + near, "앞 매도=" + sellsBefore, "284=" + crossed);
        assertThat(zero).isFalse();
        assertThat(stale).isFalse();
        assertThat(near).isFalse();
        assertThat(sellsBefore).isZero();
        assertThat(crossed).isTrue();
        assertThat(sells(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("P7 DYNAMIC 백분율 — 0·낡은 280·285.00025 뒤 284: 앞 셋 점검·매도 없음, 284 점검·손절 1")
    void dynamicPercentInvalidAndStale() {
        percentInvalidAndStale("DYNAMIC", "P7");
    }

    @Test
    @DisplayName("P8 LIVE 백분율 — 0·낡은 280·285.00025 뒤 284: 앞 셋 점검·매도 없음, 284 점검·손절 1")
    void livePercentInvalidAndStale() {
        percentInvalidAndStale("LIVE", "P8");
    }

    private void roundingBoundaryTriggers(String kind, String name) {
        Fx f = holding(kind, null, null, newCoin());
        tick(f, "286");
        within(f);
        boolean edge = tick(f, "285.0001");
        trace(name, f, "285.0001 점검=" + edge);
        assertThat(edge).as("285.0001 은 반올림 뒤 −5.0000% — 실제 판정 발동, 간격 안이어도 점검").isTrue();
        assertThat(sells(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("P9 DYNAMIC 반올림 경계 — 286 뒤 간격 안 285.0001(반올림 −5.0000%): 점검·손절 1")
    void dynamicRoundingBoundary() {
        roundingBoundaryTriggers("DYNAMIC", "P9");
    }

    @Test
    @DisplayName("P10 LIVE 반올림 경계 — 286 뒤 간격 안 285.0001(반올림 −5.0000%): 점검·손절 1")
    void liveRoundingBoundary() {
        roundingBoundaryTriggers("LIVE", "P10");
    }
}
