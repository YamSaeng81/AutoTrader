package com.cryptoautotrader.api.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>N19 — WS 이상 때의 REST 전역 폴백({@code pollRestTickerFallback}) 항목별 입력 방어</b> (2026-11-05 총괄 v1.120 §4).
 *
 * <p>7a94472 의 폴백은 응답 항목마다 가격 검사 없이 이벤트를 내보내고(0·음수도 발행 — N16 처리기가 버려 매도는 없다),
 * {@code trade_price} 가 숫자가 아닌 항목 하나가 {@code new BigDecimal} 예외로 배치 전체의 {@code catch} 로 넘어가
 * <b>그 뒤의 다른 정상 코인 발행까지 0</b>이 된다.</p>
 *
 * <p>계약: 0·음수·숫자 형식 이상 항목은 그 항목만 건너뛰고(발행 0), 앞뒤 정상 코인은 각각 한 번 발행한다. 누락·null 은 기존대로
 * 조용히 건너뛴다. 외부 조회 실패의 기존 ERROR, 이벤트 발행기 등 내부 실행 예외의 기존 배치 실패 경로는 바꾸지 않는다(입력 이상으로
 * 숨기지 않는다). 폴백 주기·WS 불량 기준·120초 정의는 그대로다.</p>
 *
 * <p>WS 불량 판정과 구독 목록만 대역(SpyBean)으로 고정하고, 발행된 정상 가격은 실제 LIVE/DYNAMIC 처리기로 이어져 보호 매도·점검
 * 기록까지 간다(준비 자료는 {@link N17RestInvalidPriceTest} 와 같다: 진입 300·손절가 285·수량 9.8). 거래소 주문·시세·텔레그램은 대역이다.</p>
 */
@RecordApplicationEvents
@Import(N19RestFallbackItemDefenseTest.BoomListener.class)
@DisplayName("N19 REST 전역 폴백 — 0·음수·형식 이상 항목만 건너뛰고 앞뒤 정상 코인은 발행·보호 매도 유지, 내부 예외는 숨기지 않음")
class N19RestFallbackItemDefenseTest extends IntegrationTestBase {

    private static final BigDecimal SIZE = new BigDecimal("9.80000000");
    private static final AtomicInteger COIN_SEQ = new AtomicInteger();
    /** 이 코인의 이벤트를 받으면 동기 처리기가 예외를 던진다 — 발행기 내부 실행 예외 대역. */
    static final String BOOM = "KRW-N19BOOM";

    /** 동기 처리기 대역 — {@link #BOOM} 이벤트에서만 예외를 던진다. */
    public static class Boom {
        @EventListener
        public void on(RealtimePriceEvent e) {
            if (BOOM.equals(e.getCoinCode())) throw new IllegalStateException("시험 대역 처리기 내부 예외");
        }
    }

    @TestConfiguration
    static class BoomListener {
        @Bean
        Boom n19BoomListener() {
            return new Boom();
        }
    }

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired private MarkPriceBook markPriceBook;
    @Autowired private ApplicationEvents events;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;
    @Autowired @Qualifier("marketDataExecutor") private ThreadPoolTaskExecutor marketDataExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private TelegramNotificationService telegramService;
    @SpyBean private ExchangeHealthMonitor exchangeHealthMonitor;
    @SpyBean private WsSubscriptionManager wsSubscriptionManager;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final AtomicInteger sellSeq = new AtomicInteger();
    private ListAppender<ILoggingEvent> logs;
    private Logger liveLogger;

    private record Fx(String kind, Long sessionId, Long positionId, String coin) { }

    @BeforeEach
    void setUp() {
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        report("uuid-n19-" + sellSeq.incrementAndGet() + "-" + System.nanoTime(), inv.getArgument(0)), "{}"));
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString()))
                .thenAnswer(inv -> report(inv.getArgument(0), null));
        // WS 불량(폴백 발동)만 고정한다 — 기준 초(30)는 원문 그대로 넘어온다
        Mockito.doReturn(true).when(exchangeHealthMonitor).isWsUnhealthy(Mockito.anyLong());
        liveLogger = (Logger) LoggerFactory.getLogger(LiveTradingService.class);
        logs = new ListAppender<>();
        logs.start();
        liveLogger.addAppender(logs);
    }

    @AfterEach
    void cleanUp() {
        liveLogger.detachAppender(logs);
        drain(marketDataExecutor);
        drain(orderExecutor);
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

    /** 실행 중 실자금 세션 + 수량 9.8·진입 300·손절가 285 OPEN 포지션({@link N17RestInvalidPriceTest} 와 같다). */
    private Fx holding(String kind) {
        String coin = "KRW-N19H" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
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

    @SuppressWarnings("unchecked")
    private Instant lastSlCheck(Fx f) {
        return ((Map<Long, Instant>) ReflectionTestUtils.getField(target(f.kind()), "lastSlCheckAt")).get(f.sessionId());
    }

    @SuppressWarnings("unchecked")
    private void setLastSlCheck(Fx f, Instant at) {
        ((Map<Long, Instant>) ReflectionTestUtils.getField(target(f.kind()), "lastSlCheckAt")).put(f.sessionId(), at);
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

    /** 응답 항목 하나. kind: 가격 문자열 그대로, 또는 "missing"(키 없음)·"null"(값 null). */
    private static Map<String, Object> item(String coin, String price) {
        Map<String, Object> t = new HashMap<>();
        t.put("market", coin);
        if (!"missing".equals(price)) t.put("trade_price", "null".equals(price) ? null : price);
        t.put("trade_timestamp", Instant.now().minusSeconds(1).toEpochMilli());
        return t;
    }

    /** 폴백 1주기 — 구독 목록과 REST 응답을 고정하고 원문 메서드를 부른 뒤 비동기 처리기를 비운다. */
    private void fallbackCycle(List<String> coins, List<Map<String, Object>> response) throws Exception {
        Mockito.doReturn(coins).when(wsSubscriptionManager).getSubscribedCoins();
        Mockito.when(upbitRestClient.getTicker(String.join(",", coins))).thenReturn(response);
        liveTradingService.pollRestTickerFallback();
        drain(marketDataExecutor);
        drain(orderExecutor);
    }

    private long published(String coin) {
        return events.stream(RealtimePriceEvent.class).filter(e -> coin.equals(e.getCoinCode())).count();
    }

    private List<String> logLines(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ── ① 항목별 분류 — 이상 항목의 위치(맨 앞·가운데)와 종류별 ──────────────────────────────────

    @ParameterizedTest(name = "이상 {0} 위치 {1}")
    @CsvSource({
            "0,first", "0,middle", "-1,first", "-1,middle", "0.00000000,middle",
            "abc,first", "abc,middle", "'',middle", "1e,middle",
            "missing,first", "missing,middle", "null,first", "null,middle"})
    @DisplayName("① 이상 항목만 발행 0, 앞뒤 정상 코인은 각각 1 — 7a94472 는 0·음수를 발행하고 형식 이상 뒤 코인을 0으로 만든다")
    void badItemIsSkippedAndOthersPublishedOnce(String bad, String where) throws Exception {
        int n = COIN_SEQ.incrementAndGet();
        String a = "KRW-N19A" + n, x = "KRW-N19X" + n, b = "KRW-N19B" + n;
        List<Map<String, Object>> response = "first".equals(where)
                ? List.of(item(x, bad), item(a, "299"), item(b, "301"))
                : List.of(item(a, "299"), item(x, bad), item(b, "301"));

        fallbackCycle(List.of(a, b, x), response);

        assertThat(published(x)).as("이상 항목은 발행하지 않는다").isZero();
        assertThat(published(a)).as("정상 코인 A 는 한 번").isEqualTo(1);
        assertThat(published(b)).as("이상 항목 뒤 정상 코인 B 도 한 번").isEqualTo(1);
        assertThat(logLines(Level.ERROR)).as("입력 이상은 배치 실패 ERROR 가 아니다").isEmpty();
        assertThat(String.join("\n", logLines(Level.WARN))).as("WARN 에 원문 값·예외 문구를 싣지 않는다")
                .doesNotContain("abc").doesNotContain("Character").doesNotContain("NumberFormat");
    }

    @Test
    @DisplayName("② 정상 2개 대조군 — 둘 다 한 번씩 발행, WARN·ERROR 없음")
    void twoValidItemsPublishedOnceEach() throws Exception {
        int n = COIN_SEQ.incrementAndGet();
        String a = "KRW-N19A" + n, b = "KRW-N19B" + n;

        fallbackCycle(List.of(a, b), List.of(item(a, "299"), item(b, "301")));

        assertThat(published(a)).isEqualTo(1);
        assertThat(published(b)).isEqualTo(1);
        assertThat(logLines(Level.ERROR)).isEmpty();
        assertThat(logLines(Level.WARN).stream().filter(m -> m.contains("건너뜀"))).isEmpty();
    }

    // ── ③ 기존 실패 경로 유지 — 외부 조회 실패 ERROR, 내부 실행 예외는 입력 이상으로 숨기지 않는다 ─────────

    @Test
    @DisplayName("③-1 외부 조회 예외 — 발행 0, 기존 ERROR 한 줄 그대로")
    void clientExceptionKeepsExistingError() throws Exception {
        int n = COIN_SEQ.incrementAndGet();
        String a = "KRW-N19A" + n;
        Mockito.doReturn(List.of(a)).when(wsSubscriptionManager).getSubscribedCoins();
        Mockito.when(upbitRestClient.getTicker(a)).thenThrow(new RuntimeException("시험 대역 조회 실패"));

        liveTradingService.pollRestTickerFallback();

        assertThat(published(a)).isZero();
        assertThat(logLines(Level.ERROR)).containsExactly("[§9] REST ticker fallback 실패: 시험 대역 조회 실패");
    }

    @Test
    @DisplayName("③-2 발행기 내부 실행 예외 — 입력 이상 WARN 으로 삼키지 않고 기존 배치 실패 ERROR 경로(뒤 항목 미처리)")
    void internalPublishExceptionIsNotTreatedAsInputError() throws Exception {
        int n = COIN_SEQ.incrementAndGet();
        String a = "KRW-N19A" + n, b = "KRW-N19B" + n;

        fallbackCycle(List.of(a, BOOM, b), List.of(item(a, "299"), item(BOOM, "300"), item(b, "301")));

        assertThat(published(a)).isEqualTo(1);
        assertThat(logLines(Level.ERROR)).as("기존 배치 실패 ERROR").hasSize(1)
                .allSatisfy(m -> assertThat(m).startsWith("[§9] REST ticker fallback 실패"));
        assertThat(published(b)).as("내부 예외 뒤 항목은 기존처럼 처리되지 않는다 — 입력 이상으로 숨겨 계속하지 않는다").isZero();
        assertThat(logLines(Level.WARN).stream().filter(m -> m.contains("건너뜀"))).as("입력 이상 WARN 이 아니다").isEmpty();
    }

    // ── ④ 실제 LIVE/DYNAMIC 소비자 연결 — 보호 매도·점검 유지, 비정상 값은 매도·점검·장부 0, 다음 주기 회복 ──────

    @Test
    @DisplayName("④ 혼합 응답(LIVE 정상 280 · 형식 이상 · DYNAMIC 정상 280 · LIVE 0) → 정상 둘 보호 매도 1회씩·점검 기록, 0 코인은 매도·점검·장부 0 → 다음 주기 280 이면 매도 1회")
    void mixedResponseKeepsProtectionForValidCoinsAndRecovers() throws Exception {
        assertThat(markPriceBook.maxAgeSeconds()).as("유효기간을 바꾸지 않는다").isEqualTo(120);
        Fx live = holding("LIVE");
        Fx dyn = holding("DYNAMIC");
        Fx zero = holding("LIVE");
        String junk = "KRW-N19J" + COIN_SEQ.incrementAndGet();
        Instant old = Instant.now().minus(10, ChronoUnit.MINUTES);
        setLastSlCheck(live, old);
        setLastSlCheck(dyn, old);
        setLastSlCheck(zero, old);
        List<String> coins = List.of(live.coin(), junk, dyn.coin(), zero.coin());

        fallbackCycle(coins, List.of(item(live.coin(), "280"), item(junk, "NOT_PRICE"), item(dyn.coin(), "280"), item(zero.coin(), "0")));

        assertThat(exchangeSells(live)).as("LIVE 정상 280 < 손절가 285 → 보호 매도 1회").isEqualTo(1);
        assertThat(exchangeSells(dyn)).as("형식 이상 뒤의 DYNAMIC 정상 280 → 보호 매도 1회").isEqualTo(1);
        assertThat(lastSlCheck(live)).isAfter(old);
        assertThat(lastSlCheck(dyn)).isAfter(old);
        assertThat(exchangeSells(zero)).as("0 으로 매도하지 않는다").isZero();
        assertThat(positionStatus(zero)).isEqualTo("OPEN");
        assertThat(lastSlCheck(zero)).as("0 은 점검 시각을 남기지 않는다").isEqualTo(old);
        assertThat(markPriceBook.freshPrice(zero.coin(), Instant.now())).as("0 은 시세 장부에 적지 않는다").isNull();
        assertThat(published(zero.coin())).isZero();
        assertThat(published(junk)).isZero();

        fallbackCycle(List.of(zero.coin()), List.of(item(zero.coin(), "280")));

        assertThat(published(zero.coin())).as("다음 주기 정상 값은 발행").isEqualTo(1);
        assertThat(lastSlCheck(zero)).as("다음 주기 정상 값은 점검을 남긴다").isAfter(old);
        assertThat(exchangeSells(zero)).as("다음 주기 280 → 보호 매도 1회").isEqualTo(1);
    }
}
