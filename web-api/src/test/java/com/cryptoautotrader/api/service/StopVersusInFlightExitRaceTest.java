package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionCleanupEntity;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE_READINESS #5 — <b>정지와 진행 중인 청산(WS 손절·평가 틱)이 같은 포지션을 다툴 때 그 포지션의 추적이 끊기지 않는다</b> (2026-10-29).
 *
 * <h3>재현한 결함</h3>
 *
 * <p>정지({@code closeSessionPositions} / {@code closeOpenPositions})는 ① 이미 CLOSING 인 포지션을 정리 대기로 등록하고
 * ② OPEN 포지션을 읽어 ③ {@code markClosingIfOpen} 으로 CLOSING 전환에 성공한 것만 등록·매도했다. ②와 ③ 사이(또는 ①과 ② 사이)에
 * WS 손절이나 평가 틱이 같은 포지션을 먼저 CLOSING 으로 바꾸면 정지는 ③에서 0 을 받고 <b>등록 없이 건너뛰었다.</b>
 * 그 매도가 체결 없이 끝나면(거래소 거절) 청산 확정이 포지션을 OPEN 으로 되돌리는데, 정지된 세션에는 평가 루프도 WS 감시도 없고
 * 정리 작업은 등록된 포지션만 본다 — <b>정지된 세션에 주인 없는 실자금 포지션</b>이 남는다.</p>
 *
 * <h3>확인하는 것</h3>
 * <ul>
 *   <li>매도 제출 중복 없음 — 경합 동안 거래소 매도는 1회</li>
 *   <li>정지 후 기존 주문 추적 유지 — 정지가 끝나면 그 포지션은 정리 대기에 있다</li>
 *   <li>그 매도가 거절되면 정리 작업이 정지의 청산 정책으로 <b>한 번만</b> 다시 판다(반복 실행에도)</li>
 * </ul>
 *
 * <p>대역은 거래소 경계뿐이다. 경합은 두 방식으로 만든다: (가) 정지의 {@code markClosingIfOpen(FORCED_STOP)} 호출 직전에 WS 손절을
 * 끝까지 실행·커밋시키는 결정적 끼어들기, (나) WS 손절과 정지를 장벽으로 함께 출발시키는 실제 동시 실행(여러 회).</p>
 *
 * <p>🔴 증명하지 않는 것: 평가 틱의 매도도 같은 {@code executeSessionSell}/{@code executeSell} → {@code markClosingIfOpen} 을 거치므로
 * 같은 창을 갖지만, 틱 자체(캔들·전략 판정)를 돌리지는 않았다. 수정은 트리거와 무관하게 정지 쪽에서 등록을 보장한다.</p>
 */
@DisplayName("#5 정지 ∥ 진행 중 청산 — 매도 1회, 정지 후에도 포지션 추적 유지")
class StopVersusInFlightExitRaceTest extends IntegrationTestBase {

    private static final BigDecimal SIZE = new BigDecimal("9.80000000");
    private static final AtomicInteger COIN_SEQ = new AtomicInteger();

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;
    @Autowired @Qualifier("marketDataExecutor") private ThreadPoolTaskExecutor marketDataExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    /** 정지 트랜잭션 안에서 반드시 불리는 지점 — 결정적 끼어들기를 여기에 심는다. */
    @SpyBean private PositionStateTransition positionStateTransition;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();
    private final AtomicInteger sellSeq = new AtomicInteger();

    private record Fx(String kind, Long sessionId, Long positionId, String coin) { }

    @BeforeEach
    void exchange() {
        // 거래소는 매도를 접수하고(대기), 조회에는 "대기 중" 으로 답한다 — 체결·거절은 시험이 정한다.
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.eq("ask"),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new UpbitOrderClient.ExchangeResult(
                        report("uuid-svr-" + sellSeq.incrementAndGet() + "-" + System.nanoTime(), inv.getArgument(0), "wait"), "{}"));
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString()))
                .thenAnswer(inv -> report(inv.getArgument(0), null, "wait"));
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

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private static OrderResponse report(String uuid, String market, String state) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("ask");
        r.setOrdType("market");
        r.setState(state);
        r.setMarket(market);
        r.setVolume(SIZE);
        r.setExecutedVolume(BigDecimal.ZERO);
        return r;
    }

    /** 실행 중 실자금 세션 + 수량 9.8·평단 300·손절가 290 인 OPEN 포지션. 코인은 호출마다 달라 WS 점검 간격(코인별)에 걸리지 않는다. */
    private Fx running(String kind) {
        String coin = "KRW-SVR" + kind.charAt(0) + COIN_SEQ.incrementAndGet();
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
                .stopLossPrice(new BigDecimal("290"))
                .status("OPEN").sessionKind(kind).sessionId(sid)
                .openedAt(Instant.now()).build());
        if ("DYNAMIC".equals(kind)) {
            DynamicSessionEntity s = dynamicRepo.findById(sid).orElseThrow();
            s.setCurrentPositionId(pos.getId());
            dynamicRepo.saveAndFlush(s);
        }
        return new Fx(kind, sid, pos.getId(), coin);
    }

    /** WS 손절 — 손절가 290 아래 280. 생산 진입점(프록시, {@code @Async}·{@code @Transactional})으로 부른다. */
    private void wsStopLoss(Fx f) {
        RealtimePriceEvent e = new RealtimePriceEvent(f.coin(), new BigDecimal("280"), Instant.now(), RealtimePriceEvent.WS_TRADE);
        if ("DYNAMIC".equals(f.kind())) dynamicTradingService.onRealtimePriceEvent(e);
        else liveTradingService.onRealtimePriceEvent(e);
    }

    private void stop(Fx f) {
        if ("DYNAMIC".equals(f.kind())) dynamicTradingService.stopSession(f.sessionId());
        else liveTradingService.stopSession(f.sessionId());
    }

    private String sessionStatus(Fx f) {
        return "DYNAMIC".equals(f.kind())
                ? dynamicRepo.findById(f.sessionId()).orElseThrow().getStatus()
                : liveRepo.findById(f.sessionId()).orElseThrow().getStatus();
    }

    private void closingReconcile(String kind) {
        if ("DYNAMIC".equals(kind)) dynamicTradingService.reconcileDynamicClosingPositions();
        else liveTradingService.reconcileClosingPositions();
    }

    private void cleanupWorker(String kind) {
        if ("DYNAMIC".equals(kind)) dynamicTradingService.cleanupStoppedSessionPositions();
        else liveTradingService.cleanupStoppedSessionPositions();
    }

    private List<OrderEntity> sells(Fx f) {
        return orderRepository.findByPositionIdOrderByCreatedAtDesc(f.positionId()).stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide())).toList();
    }

    private int exchangeSells(Fx f) {
        return (int) Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName()) && f.coin().equals(i.getArgument(0)))
                .count();
    }

    /** 다른 트랜잭션이 커밋한 포지션 상태 — 정지 트랜잭션의 영속성 컨텍스트를 거치지 않는다. */
    private String committedStatus(Long positionId) {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t.execute(st -> jdbc.queryForObject("SELECT status FROM position WHERE id = ?", String.class, positionId));
    }

    private void drain(ThreadPoolTaskExecutor executor) {
        var pool = executor.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0
                    && pool.getTaskCount() == pool.getCompletedTaskCount()) return;
            sleep(50);
        }
        throw new AssertionError(executor.getThreadNamePrefix() + " 실행기가 비워지지 않았다");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 이 포지션의 매도 {@code n} 건이 모두 거래소 주문번호를 받을 때까지 기다린다. */
    private List<OrderEntity> awaitSells(Fx f, int n) {
        long deadline = System.currentTimeMillis() + 20_000;
        List<OrderEntity> s = sells(f);
        while (System.currentTimeMillis() < deadline) {
            s = sells(f);
            if (s.size() == n && s.stream().allMatch(o -> o.getExchangeOrderId() != null)) {
                drain(orderExecutor);
                return sells(f);
            }
            sleep(50);
        }
        throw new AssertionError("매도 " + n + "건이 접수 상태가 되지 않았다: "
                + s.stream().map(o -> o.getState() + "/" + o.getExchangeOrderId()).toList());
    }

    /**
     * 정지가 끝난 뒤의 공통 확인 — 매도 1회·정리 대기 등록, 그리고 그 매도가 거절됐을 때 정리 작업이 한 번만 다시 판다.
     * @param label 출력용
     */
    private void assertTrackedAfterStop(Fx f, String label) {
        drain(marketDataExecutor);
        List<OrderEntity> first = awaitSells(f, 1);
        System.out.println("[" + label + "] 세션=" + sessionStatus(f) + " 포지션=" + committedStatus(f.positionId())
                + " 매도=" + first.stream().map(o -> o.getState() + "/" + o.getSignalReason()).toList()
                + " 정리대기=" + cleanupRepository.findById(f.positionId()).isPresent());
        assertThat(sessionStatus(f)).isEqualTo("STOPPED");
        assertThat(exchangeSells(f)).as("🔴 경합 동안 거래소 매도는 한 번").isEqualTo(1);
        assertThat(committedStatus(f.positionId())).isEqualTo("CLOSING");
        assertThat(cleanupRepository.findById(f.positionId()))
                .as("🔴 정지가 끝나면 이 포지션은 정리 대기에 있다 — 누가 먼저 CLOSING 으로 바꿨든").isPresent();

        // 앞선 매도가 거래소에서 거절됐다(체결 없음의 근거) → 청산 확정이 OPEN 으로 되돌린다.
        OrderEntity rejected = first.get(0);
        rejected.setState("FAILED");
        rejected.setFailureCode(OrderExecutionEngine.EXCHANGE_REJECTED);
        orderRepository.save(rejected);
        closingReconcile(f.kind());
        assertThat(committedStatus(f.positionId())).as("거절된 매도 — OPEN 롤백").isEqualTo("OPEN");

        for (int i = 0; i < 3; i++) {
            cleanupWorker(f.kind());
            drain(orderExecutor);
        }
        List<OrderEntity> after = awaitSells(f, 2);
        PositionCleanupEntity c = cleanupRepository.findById(f.positionId()).orElseThrow();
        System.out.println("[" + label + " 거절 후] 포지션=" + committedStatus(f.positionId()) + " 매도=" + after.size()
                + " 정리 메모=" + c.getLastNote());
        assertThat(exchangeSells(f)).as("🔴 정리 작업이 한 번만 다시 판다 — 반복 실행에도").isEqualTo(2);
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
        assertThat(c.getDoneAt()).as("CLOSED 확인 전에는 완료가 아니다").isNull();
    }

    // ── 시험 ──────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("🔴 (가) 정지가 OPEN 으로 읽은 뒤 WS 손절이 먼저 CLOSING·매도 — 정지 후에도 정리 대기, 거절되면 정리 작업이 한 번 다시 판다")
    void wsSellWinsAfterStopReadOpen(String kind) {
        Fx f = running(kind);
        AtomicInteger interleaved = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            if (interleaved.getAndIncrement() == 0) {
                // 정지는 이 포지션을 OPEN 으로 읽었다. 그 사이 WS 손절이 끝까지 돌아 커밋한다.
                wsStopLoss(f);
                long deadline = System.currentTimeMillis() + 20_000;
                while (!"CLOSING".equals(committedStatus(f.positionId()))) {
                    if (System.currentTimeMillis() > deadline) throw new AssertionError("WS 손절이 CLOSING 을 커밋하지 않았다");
                    sleep(20);
                }
                drain(marketDataExecutor);
            }
            return inv.callRealMethod();
        }).when(positionStateTransition).markClosingIfOpen(Mockito.eq(f.positionId()), Mockito.any(), Mockito.eq(ExitReason.FORCED_STOP));

        stop(f);

        assertThat(interleaved.get()).as("끼어들기가 실제로 일어났다").isEqualTo(1);
        assertThat(sells(f)).hasSize(1);
        assertThat(sells(f).get(0).getSignalReason()).as("매도는 WS 손절이 냈다").contains("실시간 손절");
        assertTrackedAfterStop(f, "가 " + kind);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("🔴 (나) WS 손절과 정지를 장벽으로 함께 출발 × 6회 — 매번 매도 1회, 정리 대기, 거절 후 다시 파는 것도 1회")
    void wsAndStopRaceForReal(String kind) throws Exception {
        int wsWins = 0;
        for (int round = 0; round < 6; round++) {
            Fx f = running(kind);
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            Thread ws = new Thread(() -> {
                try {
                    barrier.await();
                    wsStopLoss(f);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            Thread stopper = new Thread(() -> {
                try {
                    barrier.await();
                    stop(f);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            ws.start();
            stopper.start();
            ws.join();
            stopper.join();
            assertThat(errors).as("정지·WS 모두 예외 없이").isEmpty();
            drain(marketDataExecutor);
            List<OrderEntity> s = awaitSells(f, 1);
            if (s.get(0).getSignalReason() != null && s.get(0).getSignalReason().contains("실시간 손절")) wsWins++;
            assertTrackedAfterStop(f, "나 " + kind + " #" + round);
        }
        System.out.println("[나 " + kind + "] WS 손절이 먼저 매도한 회차 " + wsWins + "/6");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("🔴 페이퍼 세션 정지 — 닫히지 않은 페이퍼 포지션은 자기 종류(DYN_PAPER)로 등록돼 실자금 정리 작업(DYNAMIC)에 오르지 않는다")
    void paperPositionIsNotHandedToRealCleanup() {
        Long sid = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                .totalAssetKrw(new BigDecimal("10000"))
                .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("POSITION_MONITORING").tradingMode("PAPER")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build()).getId();
        dynamicSessions.add(sid);
        // 페이퍼 매도가 진행 중인 것처럼 CLOSING 인 포지션 — 정지는 매도를 내지 않고 등록만 한다.
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-SVRP").side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(SIZE).investedKrw(new BigDecimal("2940"))
                .status("CLOSING").closingAt(Instant.now()).sessionKind("DYN_PAPER").sessionId(sid)
                .openedAt(Instant.now()).build());

        dynamicTradingService.stopSession(sid);

        PositionCleanupEntity c = cleanupRepository.findById(pos.getId()).orElseThrow();
        assertThat(c.getSessionKind()).as("자기 종류로 등록").isEqualTo("DYN_PAPER");
        assertThat(cleanupRepository.findPending("DYNAMIC")).as("실자금 정리 작업의 대상이 아니다")
                .noneMatch(p -> p.getPositionId().equals(pos.getId()));
        // DYN_PAPER 는 wipe 대상 종류가 아니므로 직접 치운다
        cleanupRepository.deleteById(pos.getId());
        positionRepository.deleteById(pos.getId());
    }
}
