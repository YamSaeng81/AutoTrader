package com.cryptoautotrader.api.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionCleanupRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>O1·O3 — 강제 갱신 로그 고정 문구 · 실시간 손절 문구 중립화</b> (총괄 v1.134 §2, 1899b49 기점).
 *
 * <p>O1: LIVE·DYNAMIC {@code forceRefreshPrice} 의 INVALID_PRICE WARN 은 REST market 문자열·가격 숫자 원문을, catch ERROR 는
 * {@code e.getMessage()} 를 출력 인자로 넘겼다. 이 메서드 안의 두 줄만 고정 사유로 바꾼다 — 인자는 0/음수 같은 유한한 분류뿐이고
 * Throwable 을 넘기지 않는다. 결과(INVALID_PRICE·FAILED·STALE·PUBLISHED)·이벤트 발행 수·로그 수준·건수는 그대로다.
 * 승인된 관측 정규식 {@code \[(Dynamic\]\[)?§9\] REST 강제 갱신 시세가 가격이 아님 . 발행하지 않음 \(} 앞부분과 경계를 유지한다.</p>
 *
 * <p>O3: REST 폴백 시세로 발동한 손절도 같은 처리기를 지나 {@code 실시간 손절(WS)} 로 찍혔다. 새 로그·새 매도 사유를
 * {@code 실시간 손절} 로 중립화한다. 조건·가격·ExitReason.STOP_LOSS·매도 수·텔레그램 조건은 그대로다. 과거 저장 사유
 * {@code 실시간 손절(WS)} 의 분류 호환은 유지한다(대조군).</p>
 *
 * <p>준비 자료는 {@link N17RestInvalidPriceTest}·{@link StaleRealtimeExitTest} 와 같다(진입 300·손절가 285·수량 9.8).</p>
 */
@RecordApplicationEvents
@DisplayName("O1·O3 로그 출력 계약 — 강제 갱신 WARN/ERROR 원문 없음·수준/건수/결과 유지, 실시간 손절 문구 중립(REST·WS 모두)")
class O1O3LogContractTest extends IntegrationTestBase {

    private static final BigDecimal SIZE = new BigDecimal("9.80000000");
    private static final AtomicInteger COIN_SEQ = new AtomicInteger();
    /** 승인된 관측 정규식(LIMITED_LIVE_TEST_PLAN 4곳) — 그대로 세어져야 한다. */
    private static final Pattern APPROVED_N17 = Pattern.compile("\\[(Dynamic\\]\\[)?§9\\] REST 강제 갱신 시세가 가격이 아님 . 발행하지 않음 \\(");
    /** 출력 인자로 허용하는 유한한 분류. */
    private static final Set<String> FINITE_ARGS = Set.of("0", "음수");
    private static final String MARK = "🔴 가짜 표지";

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired private ApplicationEvents events;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;
    @Autowired @Qualifier("marketDataExecutor") private ThreadPoolTaskExecutor marketDataExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private TelegramNotificationService telegramService;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final AtomicInteger sellSeq = new AtomicInteger();
    private ListAppender<ILoggingEvent> logs;
    private Logger liveLogger;
    private Logger dynamicLogger;

    private record Fx(String kind, Long sessionId, Long positionId, String coin) { }

    @BeforeEach
    void setUp() {
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        report("uuid-o1o3-" + sellSeq.incrementAndGet() + "-" + System.nanoTime(), inv.getArgument(0)), "{}"));
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString()))
                .thenAnswer(inv -> report(inv.getArgument(0), null));
        liveLogger = (Logger) LoggerFactory.getLogger(LiveTradingService.class);
        dynamicLogger = (Logger) LoggerFactory.getLogger(DynamicTradingService.class);
        logs = new ListAppender<>();
        logs.start();
        liveLogger.addAppender(logs);
        dynamicLogger.addAppender(logs);
    }

    @AfterEach
    void cleanUp() {
        drain(marketDataExecutor);
        drain(orderExecutor);
        liveLogger.detachAppender(logs);
        dynamicLogger.detachAppender(logs);
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

    private Fx holding(String kind) {
        String coin = "KRW-OLC" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
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
                .stopLossPrice(new BigDecimal("285"))
                .status("OPEN").sessionKind(kind).sessionId(sid)
                .openedAt(Instant.now()).build());
        if ("DYNAMIC".equals(kind)) {
            DynamicSessionEntity s = dynamicRepo.findById(sid).orElseThrow();
            s.setCurrentPositionId(pos.getId());
            dynamicRepo.saveAndFlush(s);
        }
        return new Fx(kind, sid, pos.getId(), coin);
    }

    private Object target(String kind) {
        return AopTestUtils.getTargetObject("DYNAMIC".equals(kind) ? dynamicTradingService : liveTradingService);
    }

    private ForceRefreshResult refresh(String kind, String coin) {
        return "DYNAMIC".equals(kind)
                ? ((DynamicTradingService) target(kind)).forceRefreshPrice(coin)
                : ((LiveTradingService) target(kind)).forceRefreshPrice(coin);
    }

    private long published(String coin, String market) {
        return events.stream(RealtimePriceEvent.class)
                .filter(e -> coin.equals(e.getCoinCode()) || market.equals(e.getCoinCode())).count();
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

    /** 이 시험 스레드에서 두 서비스 로거가 남긴 WARN 이상 줄. */
    private List<ILoggingEvent> myWarnOrAbove() {
        String me = Thread.currentThread().getName();
        return logs.list.stream()
                .filter(e -> me.equals(e.getThreadName()))
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .toList();
    }

    /** 원문이 어떤 경로(메시지·인자·Throwable)로도 로그에 들어가지 않았는가. */
    private static void assertNoRaw(ILoggingEvent e, String... raws) {
        String msg = e.getFormattedMessage();
        assertThat(e.getThrowableProxy()).as("Throwable 을 로그에 넘기지 않는다").isNull();
        assertThat(msg).as("줄바꿈 없음").doesNotContain("\n").doesNotContain("\r");
        assertThat(msg).doesNotContain(MARK).doesNotContain("api.upbit.com").doesNotContain("token");
        for (String raw : raws) assertThat(msg).as("원문 %s 없음", raw).doesNotContain(raw);
        Object[] args = e.getArgumentArray();
        if (args != null) {
            assertThat(Arrays.stream(args).map(String::valueOf).toList()).as("인자는 유한한 분류뿐").allMatch(FINITE_ARGS::contains);
        }
    }

    // ── O1 ① 0·음수 + 줄바꿈/가짜 표지 market → INVALID_PRICE·발행 0·WARN 1(원문 없음, 승인 정규식 유지) ─────────

    @ParameterizedTest(name = "{0} trade_price={1}")
    @CsvSource({"LIVE,-5.125,음수", "LIVE,0.00000000,0", "DYNAMIC,-5.125,음수", "DYNAMIC,0.00000000,0"})
    @DisplayName("O1① 0·음수 + 가짜 표지·줄바꿈 market → INVALID_PRICE·이벤트 0·WARN 1·ERROR 0, 원문(market·숫자) 없음, 분류만, 승인 정규식으로 셈")
    void invalidPriceWarnHasNoRaw(String kind, String price, String category) throws Exception {
        String coin = "KRW-OIP" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
        String market = "KRW-OMK" + COIN_SEQ.incrementAndGet() + "\n" + MARK + " ERROR [§9] 정상";
        Mockito.when(upbitRestClient.getTicker(coin)).thenReturn(List.of(
                Map.of("market", market, "trade_price", price, "trade_timestamp", Instant.now().minusSeconds(1).toEpochMilli())));

        ForceRefreshResult r = refresh(kind, coin);

        assertThat(r.outcome().name()).isEqualTo("INVALID_PRICE");
        assertThat(published(coin, market)).isZero();
        List<ILoggingEvent> lines = myWarnOrAbove();
        assertThat(lines).as("WARN 1줄 · ERROR 0 — 수준·건수 유지").hasSize(1);
        ILoggingEvent w = lines.get(0);
        assertThat(w.getLevel()).isEqualTo(Level.WARN);
        assertThat(APPROVED_N17.matcher(w.getFormattedMessage()).find()).as("승인 관측 정규식이 그대로 센다").isTrue();
        assertThat(w.getFormattedMessage().startsWith("[Dynamic]")).isEqualTo("DYNAMIC".equals(kind));
        assertThat(w.getFormattedMessage()).contains("분류=" + category);
        assertNoRaw(w, "KRW-OMK", price, "5.125", "market=", "trade_price=");
    }

    // ── O1 ② 형식 이상 가격·조회 예외(가짜 표지·줄바꿈·URL) → FAILED·발행 0·ERROR 1(수준 유지, 원문·Throwable 없음) ──

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"LIVE,malformed", "LIVE,exception", "DYNAMIC,malformed", "DYNAMIC,exception"})
    @DisplayName("O1② 형식 이상 가격·예외 메시지의 가짜 표지·줄바꿈·URL → FAILED·이벤트 0·ERROR 1(WARN 으로 낮추지 않음)·원문/예외 메시지/Throwable 없음")
    void failureErrorHasNoRaw(String kind, String input) throws Exception {
        String coin = "KRW-OFE" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
        String hostile = "조회 실패\n" + MARK + " https://api.upbit.com/v1/ticker?markets=" + coin + "&token=abc";
        if ("malformed".equals(input)) {
            Mockito.when(upbitRestClient.getTicker(coin)).thenReturn(List.of(
                    Map.of("market", coin, "trade_price", "1\n" + MARK, "trade_timestamp", Instant.now().toEpochMilli())));
        } else {
            Mockito.when(upbitRestClient.getTicker(coin)).thenThrow(new IllegalStateException(hostile));
        }

        ForceRefreshResult r = refresh(kind, coin);

        assertThat(r.outcome().name()).isEqualTo("FAILED");
        assertThat(published(coin, coin)).isZero();
        List<ILoggingEvent> lines = myWarnOrAbove();
        assertThat(lines).as("ERROR 1줄 — 수준·건수 유지").hasSize(1);
        ILoggingEvent e = lines.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.ERROR);
        assertThat(e.getFormattedMessage()).contains("강제 복구 실패");
        assertThat(e.getFormattedMessage().startsWith("[Dynamic]")).isEqualTo("DYNAMIC".equals(kind));
        assertNoRaw(e, "조회 실패", "Character", "neither", "IllegalStateException", "NumberFormatException", coin);
    }

    // ── O1 ③ 대조군: 정상·낡은 양수 → PUBLISHED·STALE, 이벤트 1, 이 메서드의 WARN/ERROR 0 ───────────────────

    @ParameterizedTest(name = "{0} {1}초 전")
    @CsvSource({"LIVE,1,PUBLISHED", "LIVE,121,STALE", "DYNAMIC,1,PUBLISHED", "DYNAMIC,121,STALE"})
    @DisplayName("O1③ 대조군 — 정상 양수 PUBLISHED·1, 낡은 양수 STALE·1, 이 호출의 WARN/ERROR 0")
    void validPricesUnchanged(String kind, long ageSeconds, String expected) throws Exception {
        String coin = "KRW-OVP" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
        Mockito.when(upbitRestClient.getTicker(coin)).thenReturn(List.of(
                Map.of("market", coin, "trade_price", "299", "trade_timestamp", Instant.now().minusSeconds(ageSeconds).toEpochMilli())));

        ForceRefreshResult r = refresh(kind, coin);

        assertThat(r.outcome().name()).isEqualTo(expected);
        assertThat(published(coin, coin)).isEqualTo(1);
        assertThat(myWarnOrAbove()).isEmpty();
    }

    // ── O3 실제 이벤트 처리기: REST·WS 신선한 280 → 손절 매도 1회, 새 사유·로그 '실시간 손절'(WS 없음), 텔레그램 1 ──

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"LIVE,REST_TRADE", "LIVE,WS_TRADE", "DYNAMIC,REST_TRADE", "DYNAMIC,WS_TRADE"})
    @DisplayName("O3 REST·WS 이벤트 손절 — 매도 1회·STOP_LOSS 분류·새 사유와 WARN 은 '실시간 손절'로 시작하고 '(WS)' 없음·손절 알림 1")
    void realtimeStopLossTextIsNeutral(String kind, String source) {
        Fx f = holding(kind);
        RealtimePriceEvent ev = new RealtimePriceEvent(f.coin(), new BigDecimal("280"), Instant.now(), source);
        if ("DYNAMIC".equals(kind)) dynamicTradingService.onRealtimePriceEvent(ev);
        else liveTradingService.onRealtimePriceEvent(ev);
        drain(marketDataExecutor);
        // 🔴 (총괄 v1.156 §124-4, 2026-11-12) 매도는 @Async orderExecutor 작업이다. 옛 대기 조건(큐 비었음·active 0·taskCount ==
        //    completed)은 놀고 있는 작업자가 작업을 꺼낸 뒤 실행 표시 전이면 참이다 — 전체 실행 12:54 LIVE REST_TRADE 매도 0 은 이
        //    경계였다(같은 SHA 고정 경합 진단: 꺼낸 채 붙잡으면 옛 조건 참·매도 0·주문 행 0, 풀면 매도 1). 작업 결과(거래소 호출)를
        //    기다린다 — 오지 않으면 20초 뒤 실패. 기대값(1회)은 아래 그대로 센다.
        Mockito.verify(upbitOrderClient, Mockito.timeout(20_000).atLeastOnce()).createOrder(Mockito.eq(f.coin()), Mockito.eq("ask"),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        drain(orderExecutor);

        long sells = Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName()) && f.coin().equals(i.getArgument(0))).count();
        assertThat(sells).as("손절가 285 아래 280 → 보호 매도 1회").isEqualTo(1);
        List<OrderEntity> sellOrders = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, f.sessionId()).stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide())).toList();
        assertThat(sellOrders).hasSize(1);
        String reason = sellOrders.get(0).getSignalReason();
        assertThat(reason).as("새 사유 — 기대값 변경: '실시간 손절(WS) — …' → '실시간 손절 — …'").startsWith("실시간 손절 — ").doesNotContain("(WS)").doesNotContain("WS");
        Mockito.verify(telegramService, Mockito.times(1)).notifyStopLoss(Mockito.eq(f.coin()), Mockito.anyDouble(), Mockito.eq(f.sessionId()));

        List<String> slLines = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("실시간 손절") && m.contains(f.coin())).toList();
        assertThat(slLines).as("손절 WARN 1줄").hasSize(1);
        assertThat(slLines.get(0)).doesNotContain("(WS)").doesNotContain("WS");
    }

    // ── O3 분류 호환: 새 사유와 과거 저장 사유 모두 STOP_LOSS ──────────────────────────────────────────

    @Test
    @DisplayName("O3 분류 — 새 사유 '실시간 손절 — …' 과 과거 '실시간 손절(WS) — …' 모두 STOP_LOSS(과거 조회 호환 유지)")
    void classifierKeepsOldAndNewStopLossReasons() {
        for (String reason : List.of("실시간 손절 — 손익률 -6.67%", "실시간 손절 — pnl -6.67%",
                "실시간 손절(WS) — 손익률 -6.67%", "실시간 손절(WS) — pnl -6.67%")) {
            OrderEntity sell = OrderEntity.builder().side("SELL").state("FILLED").signalReason(reason).createdAt(Instant.now()).build();
            assertThat(ExitReasonClassifier.classify(new PositionEntity(), List.of(sell)))
                    .as(reason).isEqualTo(ExitReasonClassifier.Category.STOP_LOSS);
        }
    }
}
