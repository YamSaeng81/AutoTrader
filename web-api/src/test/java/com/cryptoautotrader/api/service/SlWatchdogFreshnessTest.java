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
import org.junit.jupiter.params.provider.ValueSource;
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
 * <b>N02 보완 — SL 미점검 감시기가 조회·발행 결과와 실제 점검을 구분하는지</b> (2026-11-01 총괄 지시 3).
 *
 * <p>cad3939 의 감시기({@code warnStaleSlCheck})는 REST 시세 이벤트를 <b>발행만 하면</b> "SL 감시를 재개했습니다"라고 알렸다(LIVE). 처리기는
 * 비동기이고 N02 뒤에는 낡은 시세를 거절하므로 발행은 점검이 아니다. 낡은 REST 응답 → 매도·점검 시각 갱신·"재개" 문구 없음,
 * 신선한 응답 → 처리기가 실제로 점검 시각을 남기고 다음 주기는 조용하다. 경보를 없애거나 유효기간(120초)을 늘리지 않는다.</p>
 *
 * <p>아래는 {@link StaleRealtimeExitTest} 와 같은 준비 자료다(진입 300·손절가 285·수량 9.8).</p>
 *
 * <!-- 원래 N02 설명 -->
 * <b>N02 — 낡은 실시간 시세가 보호 매도로 이어지는지</b> (2026-11-01 총괄 지시, NOVEMBER_LIVE_UPGRADE N02).
 *
 * <p>유효 시세의 기존 정의는 {@link MarkPriceBook#freshPriceFor}: 관측(체결) 시각이 {@code position-limit.mark-max-age-seconds}(120초)
 * 보다 오래면 현재가가 아니다. 공유 풀 평가·포지션별 한도·캔들 없는 회차 청산({@code CandleGapExitTest}·{@code PoolLossLimitTest})은 이
 * 정의로 낡은 값을 거절한다. 운영 {@code cad3939} 의 LIVE·DYNAMIC 실시간 처리기는 이벤트의 관측 시각을 보지 않는다.</p>
 *
 * <p>진입가 300·손절가 285 보유에 121초 전 체결가 280 → 매도 0·OPEN 유지·보호 점검 시각/가격 이력 갱신 없음. 같은 가격의 신선한 이벤트 →
 * 보호 매도 1회. 그 뒤 신선한 이벤트가 한 번 더 와도 매도는 1회({@link StopVersusInFlightExitRaceTest}·{@link DoubleSellGuardTest} 와 같은 기대).</p>
 */
@DisplayName("N02 보완 SL 감시기 — 낡은 REST 응답은 점검·매도·재개 문구 없음, 신선한 응답은 실제 점검 기록")
class SlWatchdogFreshnessTest extends IntegrationTestBase {

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
                        report("uuid-n02w-" + sellSeq.incrementAndGet() + "-" + System.nanoTime(), inv.getArgument(0)), "{}"));
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

    /** 실행 중 실자금 세션 + 수량 9.8·진입 300·손절가 285 OPEN 포지션. 코인은 호출마다 달라 코인별 점검 간격에 걸리지 않는다. */
    private Fx holding(String kind) {
        String coin = "KRW-WDG" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
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

    @SuppressWarnings("unchecked")
    private void setLastSlCheck(Fx f, Instant at) {
        ((Map<Long, Instant>) ReflectionTestUtils.getField(target(f.kind()), "lastSlCheckAt")).put(f.sessionId(), at);
    }

    /** 거래소 REST 시세 — 체결 시각을 싣는다(운영 응답과 같다). */
    private void restReturns(Fx f, String price, Instant tradeAt) throws Exception {
        Mockito.when(upbitRestClient.getTicker(f.coin())).thenReturn(List.of(
                Map.of("market", f.coin(), "trade_price", price, "trade_timestamp", tradeAt.toEpochMilli())));
    }

    /** 감시기 한 주기 — 발행된 이벤트의 비동기 처리까지 기다린다. */
    private void watchdog(Fx f) {
        if ("DYNAMIC".equals(f.kind())) dynamicTradingService.warnStaleSlCheck();
        else liveTradingService.warnStaleSlCheck();
        drain(marketDataExecutor);
        drain(orderExecutor);
    }

    /** 이 세션에 관한 알림 문구 — LIVE 는 "세션 N (", DYNAMIC 은 "[동적#N]". */
    private List<String> alerts(Fx f) {
        String tag = "DYNAMIC".equals(f.kind()) ? "[동적#" + f.sessionId() + "]" : "세션 " + f.sessionId() + " (";
        return Mockito.mockingDetails(telegramService).getInvocations().stream()
                .filter(i -> "sendCustomNotification".equals(i.getMethod().getName()))
                .map(i -> (String) i.getArgument(0))
                .filter(m -> m.contains(tag))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("낡은 REST 응답(121초 전 280, 손절가 285 아래) — 매도 0·OPEN·점검 시각 그대로·\"재개\" 문구 없음, 경보는 낡음을 알린다")
    void staleRestResponseNeitherChecksNorClaimsResumption(String kind) throws Exception {
        assertThat(markPriceBook.maxAgeSeconds()).as("유효기간을 늘리지 않는다").isEqualTo(120);
        Fx f = holding(kind);
        Instant old = Instant.now().minus(10, ChronoUnit.MINUTES);
        setLastSlCheck(f, old);
        restReturns(f, "280", Instant.now().minusSeconds(121));

        int cycles = "DYNAMIC".equals(kind) ? 5 : 1;   // DYNAMIC 은 연속 5회부터 사람을 부른다(기존 규칙 그대로)
        for (int i = 0; i < cycles; i++) watchdog(f);

        assertThat(exchangeSells(f)).as("낡은 REST 가격으로 보호 매도를 내지 않는다").isZero();
        assertThat(positionStatus(f)).isEqualTo("OPEN");
        assertThat(lastSlCheck(f)).as("발행했다고 점검 시각을 남기지 않는다").isEqualTo(old);
        List<String> alerts = alerts(f);
        assertThat(alerts).as("경보는 없애지 않는다").isNotEmpty();
        assertThat(alerts).allSatisfy(m -> {
            assertThat(m).as("🔴 cad3939: \"SL 감시를 재개했습니다\"").doesNotContain("재개");
            assertThat(m).contains("체결 121초 전").contains("기준 120초");
            assertThat(m).doesNotContain("이벤트 경로가 죽었을");
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("신선한 REST 응답(1초 전 299, 손절가 위) — 처리기가 실제 점검 시각을 남기고 매도 0, 다음 주기는 미점검이 아니라 조용하다")
    void freshRestResponseLeavesActualCheckRecord(String kind) throws Exception {
        Fx f = holding(kind);
        Instant old = Instant.now().minus(10, ChronoUnit.MINUTES);
        setLastSlCheck(f, old);
        restReturns(f, "299", Instant.now().minusSeconds(1));

        watchdog(f);
        Instant checked = lastSlCheck(f);
        assertThat(checked).as("실제 점검 기록 — 처리기가 남긴 시각").isAfter(old);
        assertThat(exchangeSells(f)).isZero();
        assertThat(positionStatus(f)).isEqualTo("OPEN");
        List<String> first = alerts(f);
        if ("LIVE".equals(kind)) {
            assertThat(first).hasSize(1);
            assertThat(first.get(0)).contains("발행").contains("다음 주기에 확인").doesNotContain("재개했습니다");
        } else {
            assertThat(first).as("DYNAMIC 은 자가복구가 되는 동안 조용하다(기존 규칙)").isEmpty();
        }

        watchdog(f);
        assertThat(alerts(f)).as("점검이 기록됐으므로 다음 주기에 미점검 경보가 다시 나가지 않는다").isEqualTo(first);
    }
}
