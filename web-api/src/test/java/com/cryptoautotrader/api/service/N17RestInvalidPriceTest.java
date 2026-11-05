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
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * <b>N17 — SL 감시기 REST 강제 갱신의 가격 유효성과 결과·문구 연결</b> (2026-11-05 총괄 v1.118 지시 1).
 *
 * <p>1103f48 의 {@code forceRefreshPrice}(LIVE·DYNAMIC)는 REST {@code trade_price} 가 0·음수여도 체결 시각이 신선하면 이벤트를 발행하고
 * {@code PUBLISHED} 로 분류했다. 처리기는 N16 규칙({@link MarkPriceBook#isPrice})으로 그 값을 버리므로 매도는 없지만, LIVE 경보는
 * "SL 점검 이벤트를 발행했습니다"라고 하고 DYNAMIC 은 연속 5회 뒤 "실시간 이벤트 경로가 죽었을 수 있습니다"라고 원인을 잘못 짚는다.</p>
 *
 * <p>계약: 0·음수는 {@code INVALID_PRICE} 로 구분하고 이벤트를 내보내지 않는다. 누락·null·형식 이상·빈 응답·조회 예외는 기존대로
 * {@code FAILED}(발행 0). 정상 양수의 {@code PUBLISHED}, 낡은 양수의 {@code STALE}(발행은 기존 동작)과 120초 정의는 그대로다.
 * 감시기의 60초 주기·3분 미점검 기준·DYNAMIC 연속 횟수/초기화 숫자는 바꾸지 않는다.</p>
 *
 * <p>결과값 비교는 이름 문자열로 한다 — 1103f48 에서도 컴파일되어 실패 먼저 기록을 남기기 위해서다. 준비 자료는
 * {@link SlWatchdogFreshnessTest} 와 같다(진입 300·손절가 285·수량 9.8). 거래소 주문·시세·텔레그램은 대역이다.</p>
 */
@RecordApplicationEvents
@DisplayName("N17 SL 감시기 REST 강제 갱신 — 0·음수는 INVALID_PRICE·발행 0·가격 이상 문구, 정상 값 뒤 회복")
class N17RestInvalidPriceTest extends IntegrationTestBase {

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
    @Autowired private ApplicationEvents events;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;
    @Autowired @Qualifier("marketDataExecutor") private ThreadPoolTaskExecutor marketDataExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private TelegramNotificationService telegramService;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final AtomicInteger sellSeq = new AtomicInteger();

    private record Fx(String kind, Long sessionId, Long positionId, String coin) { }

    @BeforeEach
    void exchange() {
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        report("uuid-n17-" + sellSeq.incrementAndGet() + "-" + System.nanoTime(), inv.getArgument(0)), "{}"));
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

    /** 실행 중 실자금 세션 + 수량 9.8·진입 300·손절가 285 OPEN 포지션({@link SlWatchdogFreshnessTest} 와 같다). */
    private Fx holding(String kind) {
        String coin = "KRW-NSV" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
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

    @SuppressWarnings("unchecked")
    private Object liveHistory(Fx f) {
        return ((Map<String, Object>) ReflectionTestUtils.getField(target("LIVE"), "priceHistory")).get(f.coin());
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

    private void restReturns(String coin, String price, Instant tradeAt) throws Exception {
        Mockito.when(upbitRestClient.getTicker(coin)).thenReturn(List.of(
                Map.of("market", coin, "trade_price", price, "trade_timestamp", tradeAt.toEpochMilli())));
    }

    /** 이 코인에 대해 시험 스레드에서 발행된 실시간 가격 이벤트 수. */
    private long published(String coin) {
        return events.stream(RealtimePriceEvent.class).filter(e -> coin.equals(e.getCoinCode())).count();
    }

    private ForceRefreshResult refresh(String kind, String coin) {
        return "DYNAMIC".equals(kind)
                ? ((DynamicTradingService) target(kind)).forceRefreshPrice(coin)
                : ((LiveTradingService) target(kind)).forceRefreshPrice(coin);
    }

    private void watchdog(Fx f) {
        if ("DYNAMIC".equals(f.kind())) dynamicTradingService.warnStaleSlCheck();
        else liveTradingService.warnStaleSlCheck();
        drain(marketDataExecutor);
        drain(orderExecutor);
    }

    private List<String> alerts(Fx f) {
        String tag = "DYNAMIC".equals(f.kind()) ? "[동적#" + f.sessionId() + "]" : "세션 " + f.sessionId() + " (";
        return Mockito.mockingDetails(telegramService).getInvocations().stream()
                .filter(i -> "sendCustomNotification".equals(i.getMethod().getName()))
                .map(i -> (String) i.getArgument(0))
                .filter(m -> m.contains(tag))
                .toList();
    }

    // ── ① 조회 결과 분류(강제 갱신 메서드 단독) ───────────────────────────────────────────────

    @ParameterizedTest(name = "{0} trade_price={1} 체결 {2}초 전")
    @CsvSource({
            "LIVE,0,1", "LIVE,-1,1", "LIVE,0.00000000,1", "LIVE,0,121",
            "DYNAMIC,0,1", "DYNAMIC,-1,1", "DYNAMIC,0.00000000,1", "DYNAMIC,0,121"})
    @DisplayName("① 0·음수(신선·낡음 모두) → INVALID_PRICE, 이벤트 0 — 1103f48 은 신선하면 PUBLISHED·1, 낡으면 STALE·1")
    void nonPositiveRestPriceIsInvalidAndNotPublished(String kind, String price, long ageSeconds) throws Exception {
        String coin = "KRW-NSR" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
        restReturns(coin, price, Instant.now().minusSeconds(ageSeconds));

        ForceRefreshResult r = refresh(kind, coin);

        assertThat(r.outcome().name()).isEqualTo("INVALID_PRICE");
        assertThat(published(coin)).as("가격 검사 뒤에만 발행한다").isZero();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "LIVE,fresh_positive,PUBLISHED,1", "LIVE,stale_positive,STALE,1",
            "LIVE,missing_price,FAILED,0", "LIVE,null_price,FAILED,0", "LIVE,malformed_price,FAILED,0",
            "LIVE,empty_response,FAILED,0", "LIVE,client_exception,FAILED,0",
            "DYNAMIC,fresh_positive,PUBLISHED,1", "DYNAMIC,stale_positive,STALE,1",
            "DYNAMIC,missing_price,FAILED,0", "DYNAMIC,null_price,FAILED,0", "DYNAMIC,malformed_price,FAILED,0",
            "DYNAMIC,empty_response,FAILED,0", "DYNAMIC,client_exception,FAILED,0"})
    @DisplayName("② 기존 분류 유지 — 정상 양수 PUBLISHED·1, 낡은 양수 STALE·1(기존 발행), 누락·null·형식 이상·빈 응답·조회 예외 FAILED·0")
    void existingOutcomesUnchanged(String kind, String input, String expected, long expectedEvents) throws Exception {
        String coin = "KRW-NSE" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
        long now = Instant.now().toEpochMilli();
        switch (input) {
            case "fresh_positive" -> restReturns(coin, "299", Instant.now().minusSeconds(1));
            case "stale_positive" -> restReturns(coin, "299", Instant.now().minusSeconds(121));
            case "missing_price" -> Mockito.when(upbitRestClient.getTicker(coin)).thenReturn(List.of(
                    Map.of("market", coin, "trade_timestamp", now)));
            case "null_price" -> {
                Map<String, Object> t = new HashMap<>();
                t.put("market", coin);
                t.put("trade_price", null);
                t.put("trade_timestamp", now);
                Mockito.when(upbitRestClient.getTicker(coin)).thenReturn(List.of(t));
            }
            case "malformed_price" -> restReturns(coin, "abc", Instant.now().minusSeconds(1));
            case "empty_response" -> Mockito.when(upbitRestClient.getTicker(coin)).thenReturn(List.of());
            case "client_exception" -> Mockito.when(upbitRestClient.getTicker(coin)).thenThrow(new RuntimeException("시험 대역 조회 실패"));
            default -> throw new IllegalArgumentException(input);
        }

        ForceRefreshResult r = refresh(kind, coin);

        assertThat(r.outcome().name()).isEqualTo(expected);
        assertThat(published(coin)).isEqualTo(expectedEvents);
    }

    // ── ③ 감시기 경로: 매도·점검 이력 오염 없음, 가격 이상 문구, 다음 주기 유지, 정상 값 뒤 회복 ──────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("③ 신선한 REST 0 → -5 두 주기: 매도 0·OPEN·점검 시각/이력/장부 그대로, 주기마다 가격 이상 경보(발행·재개·이벤트 경로·손절 정상 문구 없음) → 신선한 280 → 보호 매도 1회·점검 기록")
    void invalidRestPriceKeepsWatchingAndRecoversOnValidPrice(String kind) throws Exception {
        assertThat(markPriceBook.maxAgeSeconds()).as("유효기간을 바꾸지 않는다").isEqualTo(120);
        Fx f = holding(kind);
        Instant old = Instant.now().minus(10, ChronoUnit.MINUTES);
        setLastSlCheck(f, old);

        restReturns(f.coin(), "0", Instant.now().minusSeconds(1));
        watchdog(f);
        restReturns(f.coin(), "-5", Instant.now().minusSeconds(1));
        watchdog(f);

        assertThat(exchangeSells(f)).as("0·음수로 보호 매도를 내지 않는다").isZero();
        assertThat(positionStatus(f)).isEqualTo("OPEN");
        assertThat(lastSlCheck(f)).as("점검 시각을 남기지 않는다").isEqualTo(old);
        if ("LIVE".equals(kind)) assertThat(liveHistory(f)).as("급등락 이력에 넣지 않는다").isNull();
        assertThat(markPriceBook.freshPrice(f.coin(), Instant.now())).as("시세 장부에 적지 않는다").isNull();
        assertThat(published(f.coin())).as("감시기도 0·음수 이벤트를 내보내지 않는다").isZero();

        List<String> alerts = alerts(f);
        assertThat(alerts).as("두 주기 모두 사람에게 알린다 — 감시는 다음 주기에도 이어진다").hasSize(2);
        assertThat(alerts.get(0)).contains("가격으로 볼 수 없는 값").contains("trade_price 0");
        assertThat(alerts.get(1)).contains("가격으로 볼 수 없는 값").contains("trade_price -5");
        assertThat(alerts).allSatisfy(m -> assertThat(m)
                .doesNotContain("발행")
                .doesNotContain("재개")
                .doesNotContain("이벤트 경로")
                .doesNotContain("손절 자체가 멈춘 것은 아닙니다"));

        restReturns(f.coin(), "280", Instant.now().minusSeconds(1));
        watchdog(f);

        assertThat(lastSlCheck(f)).as("정상 값은 처리기가 실제 점검 시각을 남긴다").isAfter(old);
        assertThat(exchangeSells(f)).as("손절가 285 아래 신선한 280 → 보호 매도 1회").isEqualTo(1);
    }
}
