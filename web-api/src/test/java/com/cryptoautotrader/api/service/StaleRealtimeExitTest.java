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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>N02 — 낡은 실시간 시세가 보호 매도로 이어지는지</b> (2026-11-01 총괄 지시, NOVEMBER_LIVE_UPGRADE N02).
 *
 * <p>유효 시세의 기존 정의는 {@link MarkPriceBook#freshPriceFor}: 관측(체결) 시각이 {@code position-limit.mark-max-age-seconds}(120초)
 * 보다 오래면 현재가가 아니다. 공유 풀 평가·포지션별 한도·캔들 없는 회차 청산({@code CandleGapExitTest}·{@code PoolLossLimitTest})은 이
 * 정의로 낡은 값을 거절한다. 운영 {@code cad3939} 의 LIVE·DYNAMIC 실시간 처리기는 이벤트의 관측 시각을 보지 않는다.</p>
 *
 * <p>진입가 300·손절가 285 보유에 121초 전 체결가 280 → 매도 0·OPEN 유지·보호 점검 시각/가격 이력 갱신 없음. 같은 가격의 신선한 이벤트 →
 * 보호 매도 1회. 그 뒤 신선한 이벤트가 한 번 더 와도 매도는 1회({@link StopVersusInFlightExitRaceTest}·{@link DoubleSellGuardTest} 와 같은 기대).</p>
 */
@DisplayName("N02 낡은 실시간 시세 — 관측 시각이 유효기간을 넘은 가격은 보호 매도를 내지 않는다")
class StaleRealtimeExitTest extends IntegrationTestBase {

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

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final AtomicInteger sellSeq = new AtomicInteger();

    private record Fx(String kind, Long sessionId, Long positionId, String coin) { }

    @BeforeEach
    void exchange() {
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        report("uuid-n02-" + sellSeq.incrementAndGet() + "-" + System.nanoTime(), inv.getArgument(0)), "{}"));
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
        String coin = "KRW-STL" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
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

    /** 생산 진입점(프록시 — {@code @Async}·{@code @Transactional})으로 보내고 처리가 끝날 때까지 기다린다. */
    private void event(Fx f, Instant observedAt) {
        RealtimePriceEvent e = new RealtimePriceEvent(f.coin(), new BigDecimal("280"), observedAt, RealtimePriceEvent.WS_TRADE);
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

    /** LIVE 급등락 판정용 가격 이력 — DYNAMIC 에는 없다. */
    @SuppressWarnings("unchecked")
    private Object liveHistory(Fx f) {
        return ((Map<String, Object>) ReflectionTestUtils.getField(target("LIVE"), "priceHistory")).get(f.coin());
    }

    /** 코인별 점검 간격(5초) 기록을 지워 바로 다음 이벤트를 받게 한다 — 중복 매도는 간격이 아니라 포지션 상태로 막혀야 한다. */
    @SuppressWarnings("unchecked")
    private void clearThrottle(Fx f) {
        String field = "DYNAMIC".equals(f.kind()) ? "rtCheckLastMs" : "rtStopLossLastCheckMs";
        ((Map<String, Long>) ReflectionTestUtils.getField(target(f.kind()), field)).remove(f.coin());
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
        // 🔴 (총괄 v1.164 §3-2, 2026-11-13, 시험만) 옛 조건(대기열 비었음·active 0·taskCount == completed)은 놀고 있던 작업자가
        //    작업을 꺼낸 뒤 실행 표시 전에도 참이다 — DrainGapGate(꺼낸 뒤 머묾)로 이 클래스의 단언이 작업 전에 읽히는 것을 고정 재현했다.
        //    이 호출 전에 넣은 작업이 모두 끝났음을 실행기 장벽(확정 신호)으로 기다린다. 시간 초과는 실패다. 기대값·단언은 그대로다.
        com.cryptoautotrader.api.support.ExecutorBarrier.awaitPriorTasks(executor.getThreadPoolExecutor(), 20_000);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("121초 전 280 → 매도 0·OPEN·점검 시각/이력 갱신 없음 → 신선한 280 → 보호 매도 1회 → 다시 신선한 280 → 매도 그대로 1회")
    void staleEventDoesNotSellFreshEventSellsOnce(String kind) {
        assertThat(markPriceBook.maxAgeSeconds()).as("기존 유효기간 정의 — 새 한도를 만들지 않는다").isEqualTo(120);
        Fx f = holding(kind);
        assertThat(ReflectionTestUtils.getField(target(kind), "markPriceBook")).as("처리기가 같은 시세 장부(정의)를 쓴다").isSameAs(markPriceBook);

        event(f, Instant.now().minusSeconds(121));
        assertThat(exchangeSells(f)).as("🔴 cad3939: 121초 전 체결가로 보호 매도를 낸다 (%s)", kind).isZero();
        assertThat(positionStatus(f)).isEqualTo("OPEN");
        assertThat(lastSlCheck(f)).as("낡은 가격으로 보호 점검 시각을 최신처럼 갱신하지 않는다").isNull();
        if ("LIVE".equals(kind)) {
            assertThat(liveHistory(f)).as("낡은 가격을 지금 시각의 급등락 이력으로 넣지 않는다").isNull();
        }

        event(f, Instant.now());
        assertThat(exchangeSells(f)).as("같은 가격의 신선한 이벤트는 보호 매도 1회").isEqualTo(1);
        assertThat(positionStatus(f)).as("접수만 됐다 — CLOSING").isEqualTo("CLOSING");
        assertThat(lastSlCheck(f)).isNotNull();

        clearThrottle(f);
        event(f, Instant.now());
        assertThat(exchangeSells(f)).as("이미 청산 중 — 중복 매도 없음").isEqualTo(1);
    }
}
