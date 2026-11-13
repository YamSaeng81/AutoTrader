package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionCleanupRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.cryptoautotrader.exchange.upbit.dto.TickerData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N16 재현 (2026-11-05, 총괄 낮 작업 지시 §2) — 비정상 가격·관측 시각이 손절과 시세 장부에 미치는 영향.
 *
 * <p>틀은 N02 {@link StaleRealtimeExitTest} 와 같다 — 진입 300·손절가 285·수량 9.8 의 실자금 보유, 생산 진입점(프록시·{@code @Async})으로
 * 이벤트를 보내고 실행기를 비운다. 거래소 주문 클라이언트만 대역이다. 120초 경계·낡은 이벤트 거절·신선한 가격의 손절 1회는
 * {@code StaleRealtimeExitTest} 가 이미 보므로 여기서 다시 보지 않는다.</p>
 *
 * <p>기존 입력 계약: {@link MarkPriceBook#observe} 는 가격 {@code null}·0·음수와 관측 시각 {@code null} 을 거절한다. N02 주석은 실시간
 * 처리기가 "시세 장부와 같은 정의" 를 쓴다고 적는다.</p>
 */
@DisplayName("N16 재현 — 비정상 가격·관측 시각")
class N16InvalidPriceReproTest extends IntegrationTestBase {

    private static final BigDecimal SIZE = new BigDecimal("9.80000000");
    private static final AtomicInteger COIN_SEQ = new AtomicInteger();

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired private MarkPriceBook markPriceBook;
    @Autowired private HeldMarkRefresher heldMarkRefresher;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Autowired private PositionLimitGuard positionLimitGuard;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;
    @Autowired @Qualifier("marketDataExecutor") private ThreadPoolTaskExecutor marketDataExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final List<String> coins = new ArrayList<>();
    private final AtomicInteger sellSeq = new AtomicInteger();

    private record Fx(String kind, Long sessionId, Long positionId, String coin) { }

    @BeforeEach
    void exchange() {
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        report("uuid-n16-" + sellSeq.incrementAndGet() + "-" + System.nanoTime(), inv.getArgument(0)), "{}"));
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString()))
                .thenAnswer(inv -> report(inv.getArgument(0), null));
    }

    @AfterEach
    void cleanUp() {
        drain(marketDataExecutor);
        drain(orderExecutor);
        for (Long id : liveSessions) wipe("LIVE", id, () -> liveRepo.deleteById(id));
        for (Long id : dynamicSessions) wipe("DYNAMIC", id, () -> dynamicRepo.deleteById(id));
        liveSessions.clear();
        dynamicSessions.clear();
        coins.forEach(markPriceBook::remove);
        coins.clear();
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

    private Fx holding(String kind) {
        String coin = "KRW-N16" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
        coins.add(coin);
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
                .stopLossPrice(new BigDecimal("285")).takeProfitPrice(new BigDecimal("330"))
                .status("OPEN").sessionKind(kind).sessionId(sid)
                .openedAt(Instant.now()).build());
        if ("DYNAMIC".equals(kind)) {
            DynamicSessionEntity s = dynamicRepo.findById(sid).orElseThrow();
            s.setCurrentPositionId(pos.getId());
            dynamicRepo.saveAndFlush(s);
        }
        return new Fx(kind, sid, pos.getId(), coin);
    }

    private void send(Fx f, BigDecimal price, Instant observedAt) {
        RealtimePriceEvent e = new RealtimePriceEvent(f.coin(), price, observedAt, RealtimePriceEvent.WS_TRADE);
        if ("DYNAMIC".equals(f.kind())) dynamicTradingService.onRealtimePriceEvent(e);
        else liveTradingService.onRealtimePriceEvent(e);
        drain(marketDataExecutor);
        drain(orderExecutor);
    }

    private Object target(String kind) {
        return AopTestUtils.getTargetObject("DYNAMIC".equals(kind) ? dynamicTradingService : liveTradingService);
    }

    @SuppressWarnings("unchecked")
    private Instant lastSlCheck(Fx f) {
        return ((Map<Long, Instant>) ReflectionTestUtils.getField(target(f.kind()), "lastSlCheckAt")).get(f.sessionId());
    }

    @SuppressWarnings("unchecked")
    private Object liveHistory(Fx f) {
        return ((Map<String, Object>) ReflectionTestUtils.getField(target("LIVE"), "priceHistory")).get(f.coin());
    }

    @SuppressWarnings("unchecked")
    private void clearThrottle(Fx f) {
        String field = "DYNAMIC".equals(f.kind()) ? "rtCheckLastMs" : "rtStopLossLastCheckMs";
        ((Map<String, Long>) ReflectionTestUtils.getField(target(f.kind()), field)).remove(f.coin());
    }

    /**
     * 보호 매도가 있어야 하는 단언 전용 (v1.159, 2026-11-13). 매도 의도는 커밋 뒤 orderExecutor 작업이 거래소에 낸다. {@link #drain} 의
     * 판정(대기열 비어 있음·활성 0·작업 수=완료 수)은 작업자가 막 꺼낸 작업을 잠그기 전에 참이 될 수 있어(4a4a04a3 전체 회귀에서 재현:
     * drain 직후 active=1·INTENT_READY, 21ms 뒤 SUBMITTED), 거래소 호출을 최대 15초 기다린 뒤 실행기를 다시 비우고 그 수를 돌려준다.
     * 기다려도 오지 않으면 그때의 수(0)를 돌려줘 단언이 실패한다. 0회 단언에는 쓰지 않는다.
     */
    private int awaitSells(Fx f, int expected) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (exchangeSells(f) < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        drain(orderExecutor);
        return exchangeSells(f);
    }

    private int exchangeSells(Fx f) {
        return (int) Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName()) && f.coin().equals(i.getArgument(0)))
                .count();
    }

    private String positionStatus(Fx f) {
        return positionRepository.findById(f.positionId()).orElseThrow().getStatus();
    }

    private static void drain(ThreadPoolTaskExecutor executor) {
        var pool = executor.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0
                    && pool.getTaskCount() == pool.getCompletedTaskCount()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("실행기가 20초 안에 비워지지 않았다");
    }

    /**
     * ① 가격 0·음수 — 장부는 거절하는 값. 처리기도 같은 정의라면 매도·점검 기록·이력이 없어야 하고, 뒤이은 신선한 정상 가격(280,
     * 손절가 285 아래)에서 보호 매도 1회가 나야 한다.
     */
    @ParameterizedTest(name = "{0} 가격 {1}")
    @CsvSource({"LIVE,0", "LIVE,-1", "DYNAMIC,0", "DYNAMIC,-1"})
    @DisplayName("① 신선한 시각의 가격 0·음수 → 매도 0·OPEN·점검 시각/이력 없음 → 신선한 280 → 보호 매도 1회")
    void nonPositivePriceIsNotAPrice(String kind, String price) {
        Fx f = holding(kind);
        assertThat(markPriceBook.observe(f.coin(), new BigDecimal(price), Instant.now(), Instant.now(), MarkPriceBook.WS_TRADE))
                .as("시세 장부의 기존 계약 — 0·음수는 관측이 아니다").isFalse();

        send(f, new BigDecimal(price), Instant.now());
        System.out.println("[N16 ①] " + kind + " 가격 " + price + " → 거래소 매도 " + exchangeSells(f) + " · 포지션 " + positionStatus(f)
                + " · 점검 시각 " + lastSlCheck(f) + ("LIVE".equals(kind) ? " · 이력 " + liveHistory(f) : "")
                + " · 매도 주문 " + orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, f.sessionId()).stream()
                .filter(o -> "SELL".equals(o.getSide())).map(o -> o.getState() + "@" + o.getSignalPrice()).toList());
        assertThat(exchangeSells(f)).as("가격 %s 로 보호 매도를 내지 않는다 (%s)", price, kind).isZero();
        assertThat(positionStatus(f)).isEqualTo("OPEN");
        assertThat(lastSlCheck(f)).as("가격이 아닌 값으로 보호 점검 시각을 남기지 않는다").isNull();
        if ("LIVE".equals(kind)) assertThat(liveHistory(f)).as("급등락 이력에 넣지 않는다").isNull();

        clearThrottle(f);
        send(f, new BigDecimal("280"), Instant.now());
        assertThat(awaitSells(f, 1)).as("신선한 정상 가격의 보호 매도 1회").isEqualTo(1);
        assertThat(positionStatus(f)).isEqualTo("CLOSING");
    }

    /**
     * ② 가격 {@code null} — 운영 발행 지점은 내지 않는다(웹소켓은 0 으로 메우고 REST 는 건너뛴다). 시험 생성자 경로만. 처리 뒤 정상 가격의
     * 보호 매도가 막히지 않아야 한다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("② 가격 null → 매도 0·점검 시각 없음 → 신선한 280 → 보호 매도 1회")
    void nullPriceDoesNotBlockNextCheck(String kind) {
        Fx f = holding(kind);
        send(f, null, Instant.now());
        Instant afterNull = lastSlCheck(f);
        clearThrottle(f);
        send(f, new BigDecimal("280"), Instant.now());
        int sells = awaitSells(f, 1);
        System.out.println("[N16 ②] " + kind + " null 뒤 점검 시각 " + afterNull + " · 다음 280 → 거래소 매도 " + sells
                + " · 포지션 " + positionStatus(f));
        assertThat(afterNull).as("null 로 보호 점검 시각을 남기지 않는다").isNull();
        assertThat(sells).as("null 다음 신선한 정상 가격의 보호 매도 1회").isEqualTo(1);
    }

    /**
     * ③ 생산 경로 — 웹소켓 ticker 메시지에 체결가({@code tp})가 없을 때. 운영 파서({@code getDecimalSafe})는 0 을 넣는다. 그 틱을 운영
     * 리스너({@code onWsTick})에 그대로 넘겨 처리기까지 보낸다. 외부 접속 없음(메시지를 직접 넣는다).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE"})
    @DisplayName("③ 웹소켓 ticker 에 tp 가 없으면 파서가 0 을 넣는다 → 운영 리스너 경로로 처리기까지")
    void wsTickerWithoutTradePrice(String kind) {
        Fx f = holding(kind);
        UpbitWebSocketClient ws = new UpbitWebSocketClient();
        List<TickerData> got = new ArrayList<>();
        ws.addTickerListener(got::add);
        long nowMs = System.currentTimeMillis();
        String msg = "{\"ty\":\"ticker\",\"cd\":\"" + f.coin() + "\",\"tms\":" + nowMs + ",\"ttms\":" + nowMs + "}";
        ReflectionTestUtils.invokeMethod(ws, "processMessage", (Object) msg.getBytes(StandardCharsets.UTF_8));
        assertThat(got).hasSize(1);
        System.out.println("[N16 ③] tp 없는 ticker → 파서 체결가 " + got.get(0).getTradePrice() + " · 체결 시각 " + got.get(0).getTradeTimestamp());

        ReflectionTestUtils.invokeMethod(target("LIVE"), "onWsTick", got.get(0));
        // 🔴 (G1 후속, 총괄 v1.165 §3-6, 2026-11-13, 시험만) onWsTick 은 이벤트를 내고 처리기는 디스패처가 marketDataExecutor 에서 돌린다. 아래
        //    단언(매도 0·OPEN·장부 없음)은 모두 음성이라 처리기가 돌기 전에도 참이다 — DrainGapGate 500ms 에서 옛 대기 뒤 단언이 통과한 시점에
        //    디스패처는 pendingEvents=1·processed 그대로였다(g1 근거 n16-orig-gate500-probe). 앞선 작업 완료를 실행기 장벽으로 기다린다.
        com.cryptoautotrader.api.support.ExecutorBarrier.awaitPriorTasks(marketDataExecutor.getThreadPoolExecutor(), 20_000);
        com.cryptoautotrader.api.support.ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
        System.out.println("[N16 ③] 처리 뒤 거래소 매도 " + exchangeSells(f) + " · 포지션 " + positionStatus(f)
                + " · 장부 " + markPriceBook.get(f.coin()));
        assertThat(got.get(0).getTradePrice()).as("운영 파서: 없는 tp → 0").isEqualByComparingTo("0");
        assertThat(exchangeSells(f)).as("체결가 없는 틱으로 보호 매도를 내지 않는다").isZero();
        assertThat(positionStatus(f)).isEqualTo("OPEN");
        assertThat(markPriceBook.get(f.coin())).as("장부는 이미 거절한다").isNull();
    }

    /**
     * ④ 미래 관측 시각 — 총괄 판정(4a4c225 검토): 장부가 정렬·최신성에 쓰는 시각은 {@code min(원래 관측 시각, 서버 수신 시각)}.
     * 3385f70 까지는 이 자리의 시험({@code futureObservationFreezesBook})이 "미래 100 이 정상 90 을 막고 23시간 뒤에도 신선하다"는
     * 현재 동작을 그대로 단언했다(그 원문은 근거 n16-invalid-price-fe01207 의 before/after XML). 새 계약은 그 결함을 고친 상태를 단언한다.
     */
    @Test
    @DisplayName("④a 체결 — 미래 100 뒤 정상 90 이 덮고, 장부 시각은 수신 시각이며 원래 관측 시각은 따로 남는다")
    void futureTradeIsCappedAndRecovers() {
        Fx f = holding("LIVE");
        Instant now = Instant.now();
        Instant future = now.plus(1, ChronoUnit.DAYS);
        boolean first = markPriceBook.observe(f.coin(), new BigDecimal("100"), future, now, MarkPriceBook.WS_TRADE);
        MarkPriceBook.Mark m1 = markPriceBook.get(f.coin());
        boolean second = markPriceBook.observe(f.coin(), new BigDecimal("90"), now.plusSeconds(1), now.plusSeconds(1), MarkPriceBook.WS_TRADE);
        BigDecimal fresh = markPriceBook.freshPrice(f.coin(), now.plusSeconds(2));
        System.out.println("[N16 ④a] 미래 100 기록 " + first + " · 장부 시각 " + m1.at() + " (수신 " + now + ", 원래 관측 "
                + observedAtOf(m1) + ") · 정상 90 기록 " + second + " · 2초 뒤 신선한 가격 " + fresh);
        assertThat(first).isTrue();
        assertThat(m1.at()).as("장부 시각 = min(관측, 수신)").isEqualTo(now);
        assertThat(observedAtOf(m1)).as("원래 관측 시각은 바꾸지 않고 따로 둔다").isEqualTo(future);
        assertThat(m1.receivedAt()).isEqualTo(now);
        assertThat(second).as("정상 후속 관측이 덮는다").isTrue();
        assertThat(fresh).isEqualByComparingTo("90");
    }

    /** 원래 관측 시각 — 수정 전 기록에는 이 필드가 없으므로 반사로 읽는다(없으면 null). */
    private static Object observedAtOf(MarkPriceBook.Mark m) {
        try {
            return MarkPriceBook.Mark.class.getMethod("observedAt").invoke(m);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Test
    @DisplayName("④b 체결 — 미래 값 뒤 후속 관측이 없으면 기존 120초 기준으로 낡고(120.999초 허용·121초 거절), 23시간 뒤 풀·포지션 평가는 낡음, 보강이 다시 조회한다")
    void futureTradeExpiresWithoutFollowUp() {
        Fx f = holding("LIVE");
        Instant now = Instant.now();
        markPriceBook.observe(f.coin(), new BigDecimal("100"), now.plus(1, ChronoUnit.DAYS), now, MarkPriceBook.WS_TRADE);
        BigDecimal at120999 = markPriceBook.freshPrice(f.coin(), now.plusMillis(120_999));
        BigDecimal at121 = markPriceBook.freshPrice(f.coin(), now.plusSeconds(121));
        Instant later = now.plus(23, ChronoUnit.HOURS);
        BigDecimal at23h = markPriceBook.freshPrice(f.coin(), later);
        PoolLossGuard.PriceMark pm = poolLossGuard.markFor(f.coin(), SIZE, new BigDecimal("300"), null);
        String unjudged = positionLimitGuard.unjudgedCode(0, new BigDecimal("2940"), pm, later);
        Mockito.clearInvocations(upbitRestClient);
        HeldMarkRefresher.Result r = heldMarkRefresher.refreshNow(later);
        int tickerCalls = (int) Mockito.mockingDetails(upbitRestClient).getInvocations().stream()
                .filter(i -> "getTicker".equals(i.getMethod().getName()) && String.valueOf((Object) i.getArgument(0)).contains(f.coin())).count();
        System.out.println("[N16 ④b] 120.999초 " + at120999 + " · 121초 " + at121 + " · 23시간 " + at23h + " · 풀 평가 시각 " + pm.at()
                + " · 포지션 판정 " + unjudged + " · 23시간 뒤 보강 대상 " + r.targets() + " · 시세 조회 " + tickerCalls + "회");
        assertThat(at120999).isEqualByComparingTo("100");
        assertThat(at121).isNull();
        assertThat(at23h).isNull();
        assertThat(pm.at()).as("풀 평가가 보는 시각도 보정 시각").isEqualTo(now);
        assertThat(unjudged).isEqualTo(PositionLimitGuard.MARK_STALE);
        assertThat(r.targets()).contains(f.coin());
        assertThat(tickerCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("④c 체결 — 과거 관측은 수신 시각으로 올리지 않는다(낡은 체결가 재수신은 낡은 채), 더 이른 정상 관측의 역순 보호와 같은 유효 시각의 기존 동작은 그대로")
    void pastAndOrderingUnchanged() {
        Fx f = holding("LIVE");
        Instant now = Instant.now();
        boolean old = markPriceBook.observe(f.coin(), new BigDecimal("100"), now.minusSeconds(600), now, MarkPriceBook.REST_TRADE);
        BigDecimal oldFresh = markPriceBook.freshPrice(f.coin(), now);
        Instant oldAt = markPriceBook.get(f.coin()).at();
        boolean normal = markPriceBook.observe(f.coin(), new BigDecimal("280"), now, now, MarkPriceBook.WS_TRADE);
        boolean earlier = markPriceBook.observe(f.coin(), new BigDecimal("1"), now.minusSeconds(5), now.plusSeconds(1), MarkPriceBook.REST_TRADE);
        boolean same = markPriceBook.observe(f.coin(), new BigDecimal("281"), now, now.plusSeconds(2), MarkPriceBook.WS_TRADE);
        BigDecimal current = markPriceBook.freshPrice(f.coin(), now.plusSeconds(3));
        System.out.println("[N16 ④c] 10분 전 체결 기록 " + old + " · 장부 시각 " + oldAt + " · 신선 " + oldFresh + " · 정상 280 " + normal
                + " · 더 이른 1 " + earlier + " · 같은 유효 시각 281 " + same + " · 현재 " + current);
        assertThat(old).isTrue();
        assertThat(oldAt).isEqualTo(now.minusSeconds(600));
        assertThat(oldFresh).isNull();
        assertThat(normal).isTrue();
        assertThat(earlier).isFalse();
        assertThat(same).as("같은 유효 시각이면 기존처럼 나중 값").isTrue();
        assertThat(current).isEqualByComparingTo("281");
    }

    @Test
    @DisplayName("④d 호가 — 미래 호가 시각도 수신 시각으로 제한되어 후속 없으면 120초 기준으로 낡고, 정상 호가가 덮는다")
    void futureBookIsCappedAndRecovers() {
        Fx f = holding("LIVE");
        Instant now = Instant.now();
        List<BigDecimal[]> bidsA = new ArrayList<>();
        bidsA.add(new BigDecimal[]{new BigDecimal("100"), new BigDecimal("100")});
        List<BigDecimal[]> bidsB = new ArrayList<>();
        bidsB.add(new BigDecimal[]{new BigDecimal("90"), new BigDecimal("100")});
        boolean first = markPriceBook.observeBook(f.coin(), bidsA, now.plus(1, ChronoUnit.DAYS), now);
        Instant bookAt = markPriceBook.get(f.coin()).at();
        BigDecimal expired = markPriceBook.freshPriceFor(f.coin(), now.plusSeconds(121), SIZE);
        boolean second = markPriceBook.observeBook(f.coin(), bidsB, now.plusSeconds(1), now.plusSeconds(1));
        BigDecimal fresh = markPriceBook.freshPriceFor(f.coin(), now.plusSeconds(2), SIZE);
        System.out.println("[N16 ④d] 미래 호가 기록 " + first + " · 장부 시각 " + bookAt + " · 121초 뒤 " + expired
                + " · 정상 호가 기록 " + second + " · 2초 뒤 " + fresh);
        assertThat(first).isTrue();
        assertThat(bookAt).isEqualTo(now);
        assertThat(expired).isNull();
        assertThat(second).isTrue();
        assertThat(fresh).isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("④e 생산 이벤트 경로 — 미래 관측 시각 이벤트(가격 정상)는 장부에 서버 수신 시각으로 적히고, 수신 시각 없는 직접 기록은 한 번 읽은 서버 시각으로 제한된다")
    void eventPathAndMissingReceivedAt() {
        Fx f = holding("LIVE");
        Instant before = Instant.now();
        // 운영 발행 지점처럼 애플리케이션 이벤트로 낸다 — 장부(onPrice)·LIVE·DYNAMIC 처리기가 모두 받는다
        eventPublisher.publishEvent(new RealtimePriceEvent(f.coin(), new BigDecimal("290"), before.plus(1, ChronoUnit.DAYS),
                RealtimePriceEvent.WS_TRADE));
        drain(marketDataExecutor);
        drain(orderExecutor);
        Instant after = Instant.now();
        MarkPriceBook.Mark m = markPriceBook.get(f.coin());
        Fx g = holding("LIVE");
        Instant before2 = Instant.now();
        boolean direct = markPriceBook.observe(g.coin(), new BigDecimal("100"), before2.plus(1, ChronoUnit.DAYS), null, MarkPriceBook.REST_TRADE);
        Instant after2 = Instant.now();
        MarkPriceBook.Mark n = markPriceBook.get(g.coin());
        System.out.println("[N16 ④e] 이벤트 장부 " + (m == null ? null : m.price() + "@" + m.at() + " 수신 " + m.receivedAt())
                + " · 매도 " + exchangeSells(f) + " · 수신 없는 기록 " + direct + " " + (n == null ? null : n.at() + " 수신 " + n.receivedAt()));
        assertThat(m).isNotNull();
        assertThat(m.at()).isBetween(before, after);
        assertThat(m.at()).isEqualTo(m.receivedAt());
        assertThat(exchangeSells(f)).isZero();
        assertThat(direct).isTrue();
        assertThat(n.at()).isBetween(before2, after2);
        assertThat(n.receivedAt()).isEqualTo(n.at());
    }

    /** ⑤ 관측 시각 EPOCH·null — 기존 계약대로 장부는 앞선 신선한 값을 지키고, 처리기는 EPOCH 를 낡은 값으로 버린다(N02 정의). */
    @Test
    @DisplayName("⑤ 관측 시각 EPOCH·null — 장부는 신선한 값을 지키고(EPOCH 는 더 이르다, null 은 거절) EPOCH 이벤트는 처리기가 낡은 값으로 버린다")
    void epochAndNullObservedAt() {
        Fx f = holding("LIVE");
        Instant now = Instant.now();
        assertThat(markPriceBook.observe(f.coin(), new BigDecimal("280"), now, now, MarkPriceBook.WS_TRADE)).isTrue();
        assertThat(markPriceBook.observe(f.coin(), new BigDecimal("1"), Instant.EPOCH, now, MarkPriceBook.REST_TRADE)).isFalse();
        assertThat(markPriceBook.observe(f.coin(), new BigDecimal("1"), null, now, MarkPriceBook.REST_TRADE)).isFalse();
        assertThat(markPriceBook.freshPrice(f.coin(), now)).isEqualByComparingTo("280");
        send(f, new BigDecimal("1"), Instant.EPOCH);
        System.out.println("[N16 ⑤] EPOCH 1 이벤트 → 거래소 매도 " + exchangeSells(f) + " · 점검 시각 " + lastSlCheck(f));
        assertThat(exchangeSells(f)).isZero();
        assertThat(lastSlCheck(f)).isNull();
    }
}
