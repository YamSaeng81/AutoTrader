package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>거래소 DOWN → 복구 자동 재시작</b> (2026-10-19 운영 관측).
 *
 * <p>운영 2026-10-19 업비트 서버 점검(02:00경~06:55 KST) 때 "거래소 DOWN" 으로 LIVE 202 가 비상 정지됐고, 복구 시
 * 재시작이 <code>LIVE 세션 시작 #202 는 호출자의 트랜잭션 안에서만 할 수 있다</code> 로 실패했다. 원인은
 * {@code onExchangeRecovered} 가 같은 클래스의 {@code startSession} 을 직접 불러 트랜잭션 프록시를 거치지 않은 것이다
 * (fc0c039 의 잠금 검사가 그것을 거절했다 — V80 에서는 검사가 없어 잠금 없이 통과했다).</p>
 *
 * <p>확인하는 것: ① 등록된 정상 세션은 복구 뒤 RUNNING ② 재시작은 수동 시작과 <b>같은 검사</b>를 거친다 — 공유 풀
 * 미등록이면 거절되고 상태는 EMERGENCY_STOPPED 로 남는다(STOPPED 로 바꿔 두고 실패하지 않는다) ③ 정지 뒤 사람이 손댄
 * 세션(수동 정지)은 재시작하지 않는다 ④ 보유 중 DOWN 이면 비상 정지·청산 매도 1회.</p>
 */
@DisplayName("거래소 DOWN → 복구 자동 재시작")
class ExchangeRecoveryRestartTest extends IntegrationTestBase {

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private LiveTradingSessionRepository sessionRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private ExchangeHealthMonitor healthMonitor;
    @Autowired @Qualifier("marketDataExecutor") private ThreadPoolTaskExecutor marketDataExecutor;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;

    /** 시작 경로의 잔고 동기화가 거래소를 부르지 않게 막는다 — 스냅숏은 시험 기반이 채운다. */
    @MockBean private PortfolioSyncService portfolioSyncService;
    @MockBean private UpbitOrderClient upbitOrderClient;

    private BigDecimal savedTotal;
    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void save() {
        savedTotal = limits.getTotalCapKrw();
        // 다른 시험이 남긴 RUNNING 세션이 비상 정지·재시작 대상에 섞이지 않게 한다.
        sessionRepository.findByStatus("RUNNING").forEach(s -> {
            s.setStatus("STOPPED");
            sessionRepository.save(s);
        });
    }

    @AfterEach
    void cleanUp() {
        limits.setTotalCapKrw(savedTotal);
        healthMonitor.healthCheckUriForTest(null);
        healthMonitor.updateStatus("UP");
        created.forEach(id -> {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            sessionRepository.findById(id).ifPresent(sessionRepository::delete);
        });
        created.clear();
    }

    private Long runningSession(String coin) {
        LiveTradingSessionEntity s = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT")
                .coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("10000"))
                .totalAssetKrw(new BigDecimal("10000")).mddPeakCapital(new BigDecimal("10000"))
                .status("RUNNING").maxHoldHours(24).startedAt(Instant.now()).build());
        created.add(s.getId());
        return s.getId();
    }

    private String status(Long id) {
        return sessionRepository.findById(id).orElseThrow().getStatus();
    }

    private void down() {
        liveTradingService.onExchangeDown(new ExchangeDownEvent(this, "시험 — 연속 실패"));
        awaitDrained(orderExecutor);
    }

    /** 복구 이벤트는 비동기(@Async marketDataExecutor)다 — 끝날 때까지 기다린다. */
    private void recover() {
        liveTradingService.onExchangeRecovered(new ExchangeRecoveredEvent(this, "DOWN"));
        awaitDrained(marketDataExecutor);
    }

    private static void awaitDrained(ThreadPoolTaskExecutor ex) {
        var pool = ex.getThreadPoolExecutor();
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

    @Test
    @DisplayName("① 등록된 정상 세션 — DOWN 에 비상 정지, 복구에 RUNNING")
    void registeredSessionRestarts() {
        Long id = runningSession("KRW-DOGE");
        down();
        assertThat(status(id)).isEqualTo("EMERGENCY_STOPPED");

        recover();
        assertThat(status(id)).as("🔴 운영 2026-10-19: 자기 호출로 트랜잭션 없이 시작해 거절됐다").isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("② 공유 풀 미등록 — 재시작도 자본 검사에서 거절되고, 상태는 EMERGENCY_STOPPED 로 남는다")
    void unregisteredPoolRejectsRestartAndKeepsEmergencyStopped() {
        Long id = runningSession("KRW-DOGE");
        down();
        limits.setTotalCapKrw(null);   // 운영 LIVE 202 와 같은 상태 — 등록 풀 없음

        recover();
        assertThat(status(id))
                .as("🔴 재시작은 수동 시작의 검사를 우회하지 않는다. 종전 흐름은 STOPPED 로 먼저 저장하고 실패해 정지 원인을 지웠다")
                .isEqualTo("EMERGENCY_STOPPED");
    }

    @Test
    @DisplayName("③ 비상 정지 뒤 사람이 정지한 세션 — 복구가 다시 켜지 않는다")
    void manuallyStoppedSessionIsNotRestarted() {
        Long id = runningSession("KRW-DOGE");
        down();
        LiveTradingSessionEntity s = sessionRepository.findById(id).orElseThrow();
        s.setStatus("STOPPED");   // 운영자의 수동 정지
        sessionRepository.save(s);

        recover();
        assertThat(status(id)).isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("⑤ 실제 경로 — HTTP 실패 3회 → DOWN(비상 정지) → 느린 응답 DEGRADED(재시작 안 함) → 빠른 응답 UP → 재시작")
    void realHealthCheckPathThroughDegraded() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> mode = new java.util.concurrent.atomic.AtomicReference<>("fail");
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/market/all", ex -> {
            int code = "fail".equals(mode.get()) ? 503 : 200;
            if ("slow".equals(mode.get())) {
                try {
                    Thread.sleep(3_300);   // DEGRADED 임계 3초 초과
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "[]".getBytes();
            ex.sendResponseHeaders(code, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            healthMonitor.healthCheckUriForTest(java.net.URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/market/all"));
            healthMonitor.updateStatus("UP");
            Long id = runningSession("KRW-DOGE");

            healthMonitor.checkHealth();
            healthMonitor.checkHealth();
            assertThat(healthMonitor.getStatus()).isEqualTo("DEGRADED");
            assertThat(status(id)).as("실패 2회는 아직 DOWN 이 아니다").isEqualTo("RUNNING");
            healthMonitor.checkHealth();
            awaitDrained(orderExecutor);
            assertThat(healthMonitor.getStatus()).isEqualTo("DOWN");
            assertThat(status(id)).as("DOWN 이벤트 → 비상 정지").isEqualTo("EMERGENCY_STOPPED");

            mode.set("slow");
            healthMonitor.checkHealth();
            awaitDrained(marketDataExecutor);
            assertThat(healthMonitor.getStatus()).isEqualTo("DEGRADED");
            assertThat(status(id)).as("느린 응답은 복구가 아니다").isEqualTo("EMERGENCY_STOPPED");

            mode.set("ok");
            healthMonitor.checkHealth();
            awaitDrained(marketDataExecutor);
            assertThat(healthMonitor.getStatus()).isEqualTo("UP");
            assertThat(status(id)).as("🔴 종전에는 DEGRADED 를 거치면 복구 이벤트가 나지 않아 재시작되지 않았다").isEqualTo("RUNNING");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("④ 보유 중 DOWN — 비상 정지가 커밋되고 청산 매도는 정확히 1회")
    void downWithOpenPositionStopsAndSellsOnce() {
        OrderResponse r = new OrderResponse();
        r.setUuid("uuid-down-sell-" + System.nanoTime());
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("wait");
        r.setMarket("KRW-XRP");
        r.setVolume(new BigDecimal("26"));
        r.setExecutedVolume(BigDecimal.ZERO);
        Mockito.doReturn(new UpbitOrderClient.ExchangeResult(r, "{}")).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        Long id = runningSession("KRW-XRP");
        positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-XRP").side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(new BigDecimal("26")).investedKrw(new BigDecimal("7800"))
                .status("OPEN").sessionKind("LIVE").sessionId(id).openedAt(Instant.now()).build());

        down();
        assertThat(status(id)).isEqualTo("EMERGENCY_STOPPED");
        assertThat(positionRepository.findBySessionKindAndSessionIdAndStatus("LIVE", id, "OPEN")).isEmpty();
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(
                Mockito.eq("KRW-XRP"), Mockito.eq("ask"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }
}
