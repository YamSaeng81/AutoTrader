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
class ThrottleReviewWitnessTest extends IntegrationTestBase {

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
        String coin = "KRW-T" + COIN_SEQ.incrementAndGet() + "X" + (System.nanoTime() % 100000);
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
                    .totalAssetKrw(new BigDecimal("10000"))
                    .status("RUNNING").startedAt(Instant.now()).build()).getId();
            liveSessions.add(sid);
        }
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair(coin).side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(SIZE).investedKrw(new BigDecimal("2940")).entryAllocationKrw(new BigDecimal("3000"))
                .stopLossPrice(new BigDecimal("285")).takeProfitPrice(tp)
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

    @Test
    void relativeStopEarlyCheckConsumesActualCrossing() {
        Fx f = holding("DYNAMIC", null);
        PositionEntity p = positionRepository.findById(f.positionId()).orElseThrow();
        p.setStopLossPrice(null); positionRepository.saveAndFlush(p);
        boolean initial = tick(f, "286");
        elapsed(f, DYN, 500);
        boolean near = tick(f, "285.0001");
        int sellsNear = sells(f);
        elapsed(f, DYN, 500);
        boolean crossed = tick(f, "284");
        trace("REVIEW-relative", f, "initial="+initial, "near="+near, "nearSells="+sellsNear, "crossed="+crossed,
              "floor="+field(f, "rtProtectFloor").get(f.coin()));
        assertThat(initial).isTrue(); assertThat(near).isTrue(); assertThat(sellsNear).isZero();
        assertThat(crossed).as("observation: actual crossing gets skipped after approximate early wakeup").isFalse();
        assertThat(sells(f)).isZero(); assertThat(status(f)).isEqualTo("OPEN");
    }

    @Test
    void absoluteStopControlDoesNotConsumeActualCrossing() {
        Fx f = holding("DYNAMIC", null);
        boolean initial = tick(f, "286");
        elapsed(f, DYN, 500);
        boolean near = tick(f, "285.0001");
        elapsed(f, DYN, 500);
        boolean crossed = tick(f, "284");
        trace("REVIEW-absolute", f, "initial="+initial, "near="+near, "crossed="+crossed);
        assertThat(initial).isTrue(); assertThat(near).isFalse(); assertThat(crossed).isTrue();
        assertThat(sells(f)).isEqualTo(1); assertThat(status(f)).isEqualTo("CLOSING");
    }

}
