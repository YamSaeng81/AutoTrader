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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.158 결정 3 — CLOSING 커밋 뒤 비동기 매도 의도가 만들어지기 전에 청산 확인이 "SELL 주문 없음" 만 보고 OPEN 으로 되돌리는 경합
 * (로컬 실제 PostgreSQL, 84fb03d6 기점, 2026-11-12).
 *
 * <p>주문 실행기 작업자를 래치로 붙잡아 커밋 뒤 제출을 대기열에 둔 상태에서 청산 확인을 부른다. 계약: <b>CLOSING 과 식별 가능한 SELL 의도가
 * 함께 확정되므로</b> 그 시점에도 CLOSING 이 유지되고 의도 1행이 보인다. 풀어 준 뒤 거래소 호출 1회·실제 폴러 체결·청산 확정으로 CLOSED,
 * 입금 1회·매도 1건. 실행기 거절은 의도 철회(외부 호출 0) 뒤 기존 롤백, 응답 불명은 CLOSING 유지·재매도 0.</p>
 *
 * <p>거래소 응답만 대역이다. 가상 체결·현금 보정은 만들지 않는다 — 체결은 {@code checkOrderStatus} 폴러가 대역 응답을 읽어 반영한다.
 * 시간만 기다렸다 OPEN 으로 바꾸는 유예는 쓰지 않는다.</p>
 *
 * <pre>
 * docker exec v152-pg psql -U trader -d postgres -c "CREATE DATABASE cr_x"
 * CR_PG_URL=jdbc:postgresql://127.0.0.1:15437/cr_x ./gradlew :web-api:test --rerun --tests '*ClosingIntentAtomicityPgTest'
 * </pre>
 */
@TestPropertySource(properties = {
        "spring.datasource.url=${CR_PG_URL}",
        "spring.datasource.username=${CR_PG_USER:trader}",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never"
})
@EnabledIfEnvironmentVariable(named = "CR_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("v1.158 결정 3 — CLOSING 과 매도 의도 원자성 (로컬 PostgreSQL)")
class ClosingIntentAtomicityPgTest extends IntegrationTestBase {

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private OrderExecutionEngine orderEngine;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private PositionCleanupRegistrar cleanupRegistrar;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient ws;

    private CountDownLatch release;
    /** 붙잡은 작업이 모두 끝났는지 — 대기열을 가득 채운 경우 장벽 작업을 넣기 전에 비워져야 한다. */
    private CountDownLatch blockersDone = new CountDownLatch(0);

    private void out(String s) { System.out.println("[CR158] " + s); }

    @BeforeEach
    void exchange() {
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString())).thenAnswer(inv -> {
            OrderResponse r = new OrderResponse(); r.setUuid(inv.getArgument(0)); r.setState("wait"); return r; });
        Mockito.when(upbitOrderClient.createOrder(Mockito.any(), Mockito.eq("ask"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> { OrderResponse r = new OrderResponse(); r.setUuid("uuid-cr158-" + System.nanoTime());
                    r.setState("wait"); r.setSide("ask"); return new UpbitOrderClient.ExchangeResult(r, "{}"); });
    }

    @AfterEach
    void releaseWorkers() throws InterruptedException {
        if (release != null) release.countDown();
        blockersDone.await(20, TimeUnit.SECONDS);
        ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
    }

    /** 실행기 작업자를 모두 붙잡는다. fillQueue 면 최대 작업자·대기열까지 채워 다음 접수가 거절되게 한다. */
    private void holdWorkers(boolean fillQueue) throws Exception {
        ThreadPoolExecutor pool = orderExecutor.getThreadPoolExecutor();
        ExecutorBarrier.awaitPriorTasks(pool, 15_000);
        release = new CountDownLatch(1);
        final CountDownLatch r = release;
        // 🔴 살아 있는 작업자 전부(앞 시험이 남긴 비코어 작업자 포함)를 붙잡는다 — 코어 수만 붙잡으면 쉬는 비코어 작업자가 제출을 가져간다
        //    (수정 전 1회차 ② 가 pool=4 active=2 로 창을 만들지 못했다).
        pool.prestartAllCoreThreads();
        int workers = Math.max(pool.getCorePoolSize(), pool.getPoolSize());
        // 붙잡을 작업 수를 미리 정해 모두 끝났음을 센다 — 대기열을 가득 채운 ④ 에서 장벽 작업이 거절되지 않게 한다.
        int total = workers + (fillQueue ? pool.getQueue().remainingCapacity() + (pool.getMaximumPoolSize() - workers) : 0);
        blockersDone = new CountDownLatch(total);
        final CountDownLatch done = blockersDone;
        Runnable blocker = () -> {
            try { r.await(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); } };
        CountDownLatch running = new CountDownLatch(workers);
        for (int i = 0; i < workers; i++) pool.submit(() -> { running.countDown(); blocker.run(); });
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
        if (fillQueue) {
            int cap = pool.getQueue().remainingCapacity();
            for (int i = 0; i < cap; i++) pool.submit(blocker);
            int extra = pool.getMaximumPoolSize() - pool.getPoolSize();
            for (int i = 0; i < extra; i++) pool.submit(blocker);
            assertThat(pool.getQueue().remainingCapacity()).isZero();
            assertThat(pool.getPoolSize()).isEqualTo(pool.getMaximumPoolSize());
        }
        assertThat(pool.getActiveCount()).as("모든 작업자가 붙잡혔다").isEqualTo(pool.getPoolSize());
        out("작업자 붙잡음 pool=" + pool.getPoolSize() + " active=" + pool.getActiveCount() + " queue=" + pool.getQueue().size());
    }

    private void releaseAndDrain() throws InterruptedException {
        release.countDown();
        // 대기열을 가득 채운 경우(④) 장벽 작업이 거절되지 않도록 붙잡은 작업이 모두 끝난 뒤 장벽을 넣는다
        assertThat(blockersDone.await(20, TimeUnit.SECONDS)).isTrue();
        ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
    }

    private long exchangeCalls() {
        return Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName())).count();
    }

    private List<OrderEntity> sells(Long posId) {
        return orderRepository.findByPositionIdOrderByCreatedAtDesc(posId).stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide())).toList();
    }

    private String state(Long posId) {
        PositionEntity p = positionRepository.findById(posId).orElseThrow();
        StringBuilder b = new StringBuilder("포지션=" + p.getStatus() + " 매도=" + sells(posId).size());
        for (OrderEntity o : sells(posId)) b.append(" [#").append(o.getId()).append(' ').append(o.getState())
                .append(" 실패=").append(o.getFailureCode()).append(" 반영=").append(o.getSellAppliedAt() != null).append(']');
        return b.append(" 거래소createOrder=").append(exchangeCalls()).toString();
    }

    private Long liveSession(String coin, String status) {
        return liveRepo.save(LiveTradingSessionEntity.builder().sessionType("REAL").strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair(coin).timeframe("M5").initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                .totalAssetKrw(new BigDecimal("10000")).stopLossPct(new BigDecimal("5.0"))
                .status(status).startedAt(Instant.now()).build()).getId();
    }

    private PositionEntity openPos(String kind, Long sid, String coin) {
        return positionRepository.save(PositionEntity.builder().coinPair(coin).side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300")).size(new BigDecimal("9.8"))
                .investedKrw(new BigDecimal("2940")).entryAllocationKrw(new BigDecimal("3000"))
                .positionFee(BigDecimal.ZERO).realizedPnl(BigDecimal.ZERO)
                .status("OPEN").sessionKind(kind).sessionId(sid).openedAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());
    }

    /** 실제 폴러가 대역 응답(done)을 읽어 FILLED 로 반영한다. */
    private void fillViaPoller(OrderEntity sell, String coin) {
        OrderResponse fill = new OrderResponse(); fill.setUuid(sell.getExchangeOrderId());
        fill.setState("done"); fill.setSide("ask"); fill.setOrdType("market"); fill.setMarket(coin);
        fill.setVolume(new BigDecimal("9.8")); fill.setExecutedVolume(new BigDecimal("9.8"));
        fill.setExecutedFunds(new BigDecimal("2744")); fill.setPaidFee(new BigDecimal("1.372"));
        Mockito.doReturn(fill).when(upbitOrderClient).getOrder(sell.getExchangeOrderId());
        orderEngine.checkOrderStatus(sell.getId());
        assertThat(orderRepository.findById(sell.getId()).orElseThrow().getState()).isEqualTo("FILLED");
    }

    private static String coin(String p) { return "KRW-" + p + (System.nanoTime() % 100000); }

    // ── ① 정지 후 정리 작업의 창 ──────────────────────────────────────────────
    @Test
    @DisplayName("① LIVE 정리 작업: 의도 생성 전 청산 확인이 와도 CLOSING·의도 유지 → 매도1·입금1")
    void cleanupWindow() throws Exception {
        String c = coin("CRA");
        Long sid = liveSession(c, "STOPPED");
        PositionEntity pos = openPos("LIVE", sid, c);
        cleanupRegistrar.register(pos.getId(), "LIVE", sid, PositionCleanupEntity.REASON_LATE_FILL);
        holdWorkers(false);

        liveTradingService.cleanupStoppedSessionPositions();
        out("① 정리 직후: " + state(pos.getId()));
        liveTradingService.reconcileClosingPositions();
        out("① 청산 확인(실행기 대기 중): " + state(pos.getId()));
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus())
                .as("주문 부재로 OPEN 되돌림 금지 — 의도는 CLOSING 과 함께 확정").isEqualTo("CLOSING");
        assertThat(sells(pos.getId())).as("CLOSING 과 함께 확정된 매도 의도").hasSize(1);
        assertThat(exchangeCalls()).isZero();

        releaseAndDrain();
        out("① 실행기 해제 뒤: " + state(pos.getId()));
        assertThat(exchangeCalls()).isEqualTo(1);
        OrderEntity sell = sells(pos.getId()).get(0);
        assertThat(sell.getState()).isEqualTo("SUBMITTED");

        BigDecimal before = liveRepo.findById(sid).orElseThrow().getAvailableKrw();
        fillViaPoller(sell, c);
        liveTradingService.reconcileClosingPositions();
        BigDecimal settled = liveRepo.findById(sid).orElseThrow().getAvailableKrw();
        out("① 체결·청산 확인 뒤: " + state(pos.getId()) + " 가용 " + before + "→" + settled);
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("CLOSED");
        assertThat(settled).isGreaterThan(before);

        liveTradingService.reconcileClosingPositions();
        liveTradingService.cleanupStoppedSessionPositions();
        ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
        assertThat(liveRepo.findById(sid).orElseThrow().getAvailableKrw()).as("입금 1회").isEqualByComparingTo(settled);
        assertThat(sells(pos.getId())).as("중복 매도 0").hasSize(1);
        assertThat(exchangeCalls()).isEqualTo(1);
    }

    // ── ② LIVE 실시간 손절의 창 ───────────────────────────────────────────────
    @Test
    @DisplayName("② LIVE 실시간 손절: 의도 생성 전 청산 확인이 와도 CLOSING·의도 유지 → 매도1·입금1")
    void liveRealtimeStopWindow() throws Exception {
        String c = coin("CRB");
        Long sid = liveSession(c, "RUNNING");
        PositionEntity pos = openPos("LIVE", sid, c);
        holdWorkers(false);

        liveTradingService.onRealtimePriceEvent(new RealtimePriceEvent(c, new BigDecimal("280")));
        out("② 실시간 손절 직후: " + state(pos.getId()));
        liveTradingService.reconcileClosingPositions();
        out("② 청산 확인(실행기 대기 중): " + state(pos.getId()));
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("CLOSING");
        assertThat(sells(pos.getId())).hasSize(1);

        releaseAndDrain();
        assertThat(exchangeCalls()).isEqualTo(1);
        OrderEntity sell = sells(pos.getId()).get(0);
        BigDecimal before = liveRepo.findById(sid).orElseThrow().getAvailableKrw();
        fillViaPoller(sell, c);
        liveTradingService.reconcileClosingPositions();
        BigDecimal settled = liveRepo.findById(sid).orElseThrow().getAvailableKrw();
        out("② 체결·청산 확인 뒤: " + state(pos.getId()) + " 가용 " + before + "→" + settled);
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("CLOSED");
        assertThat(settled).isGreaterThan(before);
        liveTradingService.reconcileClosingPositions();
        assertThat(liveRepo.findById(sid).orElseThrow().getAvailableKrw()).isEqualByComparingTo(settled);
        assertThat(sells(pos.getId())).hasSize(1);
    }

    // ── ③ DYNAMIC REAL 손절 매도의 창 ─────────────────────────────────────────
    @Test
    @DisplayName("③ DYNAMIC REAL 매도: 의도 생성 전 청산 확인이 와도 CLOSING·의도 유지 → 매도1")
    void dynamicSellWindow() throws Exception {
        String c = coin("CRC");
        DynamicSessionEntity s = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("10000.00")).availableKrw(new BigDecimal("7000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("POSITION_MONITORING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60).build());
        PositionEntity pos = openPos("DYNAMIC", s.getId(), c);
        holdWorkers(false);

        boolean sold = dynamicTradingService.executeSell(s, pos, new BigDecimal("280"), "손절(시험)", ExitReason.STOP_LOSS);
        assertThat(sold).isTrue();
        dynamicTradingService.reconcileDynamicClosingPositions();
        out("③ 청산 확인(실행기 대기 중): " + state(pos.getId()));
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("CLOSING");
        assertThat(sells(pos.getId())).hasSize(1);

        releaseAndDrain();
        assertThat(exchangeCalls()).isEqualTo(1);
        OrderEntity sell = sells(pos.getId()).get(0);
        BigDecimal before = dynamicRepo.findById(s.getId()).orElseThrow().getAvailableKrw();
        fillViaPoller(sell, c);
        dynamicTradingService.reconcileDynamicClosingPositions();
        BigDecimal settled = dynamicRepo.findById(s.getId()).orElseThrow().getAvailableKrw();
        out("③ 체결·청산 확인 뒤: " + state(pos.getId()) + " 가용 " + before + "→" + settled);
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("CLOSED");
        assertThat(settled).isGreaterThan(before);
        dynamicTradingService.reconcileDynamicClosingPositions();
        assertThat(dynamicRepo.findById(s.getId()).orElseThrow().getAvailableKrw()).isEqualByComparingTo(settled);
        assertThat(sells(pos.getId())).hasSize(1);
    }

    // ── ④ 실행기 거절 ─────────────────────────────────────────────────────────
    @Test
    @DisplayName("④ 실행기 거절: 의도 철회(외부 호출 0) → 청산 확인이 근거(철회 FAILED)로 OPEN 복귀")
    void executorRejection() throws Exception {
        String c = coin("CRD");
        Long sid = liveSession(c, "STOPPED");
        PositionEntity pos = openPos("LIVE", sid, c);
        cleanupRegistrar.register(pos.getId(), "LIVE", sid, PositionCleanupEntity.REASON_LATE_FILL);
        holdWorkers(true);

        try {
            liveTradingService.cleanupStoppedSessionPositions();
        } catch (RuntimeException e) {
            out("④ 정리 호출 예외: " + e.getClass().getSimpleName());
        }
        out("④ 거절 직후: " + state(pos.getId()));
        List<OrderEntity> ss = sells(pos.getId());
        assertThat(ss).as("철회된 의도가 근거로 남는다").hasSize(1);
        assertThat(ss.get(0).getState()).isEqualTo("FAILED");
        assertThat(ss.get(0).getFailureCode()).isEqualTo(OrderExecutionEngine.WITHDRAWN_DISPATCH_LOST);
        liveTradingService.reconcileClosingPositions();
        out("④ 청산 확인 뒤: " + state(pos.getId()));
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
        releaseAndDrain();
        assertThat(exchangeCalls()).as("거절된 제출은 외부로 나가지 않는다").isZero();
    }

    // ── ⑤ 의도 확정 뒤 응답 불명 ──────────────────────────────────────────────
    @Test
    @DisplayName("⑤ 응답 불명: CLOSING 유지·재매도 0")
    void unknownAfterIntent() throws Exception {
        String c = coin("CRE");
        Long sid = liveSession(c, "STOPPED");
        PositionEntity pos = openPos("LIVE", sid, c);
        cleanupRegistrar.register(pos.getId(), "LIVE", sid, PositionCleanupEntity.REASON_LATE_FILL);
        Mockito.when(upbitOrderClient.createOrder(Mockito.any(), Mockito.eq("ask"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new RuntimeException("read timed out (시험 대역)"));
        holdWorkers(false);
        liveTradingService.cleanupStoppedSessionPositions();
        liveTradingService.reconcileClosingPositions();
        out("⑤ 청산 확인(실행기 대기 중): " + state(pos.getId()));
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("CLOSING");
        releaseAndDrain();
        out("⑤ 제출 뒤: " + state(pos.getId()));
        assertThat(sells(pos.getId())).hasSize(1);
        assertThat(sells(pos.getId()).get(0).getState()).isEqualTo("UNKNOWN");
        liveTradingService.reconcileClosingPositions();
        liveTradingService.cleanupStoppedSessionPositions();
        ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
        out("⑤ 재확인 뒤: " + state(pos.getId()));
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("CLOSING");
        assertThat(sells(pos.getId())).as("응답 불명 뒤 재매도 0").hasSize(1);
        assertThat(exchangeCalls()).isEqualTo(1);
    }
}
