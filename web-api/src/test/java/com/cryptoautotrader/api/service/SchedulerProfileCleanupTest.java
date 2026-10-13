package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 스케줄러가 <b>실제로</b> 정리 대기를 가져가는가 (2026-10-13).
 *
 * <p>시험 기반은 스케줄러를 끈다. 다른 시험은 정리 작업을 직접 부르므로, 운영처럼 {@code @Scheduled} 로
 * 돌 때 대기 행을 가져가는지는 그 시험들이 보이지 않는다. 이 클래스만 {@code scheduler} 프로필로 켠다.</p>
 *
 * <p>🔴 {@code @DirtiesContext} — 이 컨텍스트의 스케줄러는 공유 DB 를 바꾼다. 캐시에 남으면 뒤에 도는 다른
 * 시험 클래스의 데이터를 건드리므로 클래스가 끝나면 닫는다.</p>
 */
@ActiveProfiles("scheduler")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("scheduler 프로필 — 정리 작업이 대기 행을 가져간다")
class SchedulerProfileCleanupTest extends IntegrationTestBase {

    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired private PositionCleanupRegistrar cleanupRegistrar;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();

    @BeforeEach
    void exchange() {
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString())).thenAnswer(inv -> {
            OrderResponse r = new OrderResponse();
            r.setUuid(inv.getArgument(0));
            r.setState("wait");
            return r;
        });
        Mockito.when(upbitOrderClient.createOrder(Mockito.any(), Mockito.eq("ask"), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            OrderResponse r = new OrderResponse();
            r.setUuid("uuid-sched-sell-" + System.nanoTime());
            r.setState("wait");
            r.setSide("ask");
            return new UpbitOrderClient.ExchangeResult(r, "{}");
        });
    }

    @AfterEach
    void cleanUp() {
        for (Long id : liveSessions) {
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(p -> cleanupRepository.deleteById(p.getId()));
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.deleteById(id);
        }
        for (Long id : dynamicSessions) {
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(p -> cleanupRepository.deleteById(p.getId()));
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.deleteById(id);
        }
        liveSessions.clear();
        dynamicSessions.clear();
    }

    private Long stoppedSession(String kind) {
        if ("DYNAMIC".equals(kind)) {
            Long id = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                    .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                    .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                    .totalAssetKrw(new BigDecimal("10000"))
                    .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                    .status("STOPPED").scanState("POSITION_MONITORING").tradingMode("REAL")
                    .maxCandidateSize(30).targetWatchSize(10)
                    .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                    .watchlistRefreshMin(60).build()).getId();
            dynamicSessions.add(id);
            return id;
        }
        Long id = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                .totalAssetKrw(new BigDecimal("10000"))
                .status("STOPPED").startedAt(Instant.now()).build()).getId();
        liveSessions.add(id);
        return id;
    }

    private static boolean await(BooleanSupplier cond, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(250);
        }
        return cond.getAsBoolean();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIVE", "DYNAMIC"})
    @DisplayName("정지 세션의 보유 포지션 — 예약된 정리 작업이 청산 매도를 한 번 낸다")
    void scheduledWorkerPicksUpPendingCleanup(String kind) throws Exception {
        Long sid = stoppedSession(kind);
        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(new BigDecimal("9.8")).investedKrw(new BigDecimal("3000"))
                .status("OPEN").sessionKind(kind).sessionId(sid)
                .openedAt(Instant.now()).build());
        cleanupRegistrar.register(pos.getId(), kind, sid, PositionCleanupEntity.REASON_LATE_FILL);

        boolean picked = await(() -> orderRepository.findByPositionIdOrderByCreatedAtDesc(pos.getId()).stream()
                .anyMatch(o -> "SELL".equalsIgnoreCase(o.getSide()) && o.getExchangeOrderId() != null), 60_000);
        PositionCleanupEntity c = cleanupRepository.findById(pos.getId()).orElseThrow();
        System.out.println("[스케줄러 " + kind + "] 가져감=" + picked + " 시도=" + c.getAttempts()
                + " 메모=" + c.getLastNote() + " 마지막시도=" + c.getLastAttemptAt()
                + " 포지션=" + positionRepository.findById(pos.getId()).orElseThrow().getStatus()
                + " 대기행(" + kind + ")=" + cleanupRepository.findPending(kind).size());
        assertThat(picked).as("scheduler 프로필에서 @Scheduled 정리 작업이 60초 안에 대기 행을 가져가야 한다").isTrue();

        // 한 주기(10초) 이상 더 지켜도 두 번째 매도가 나가지 않는다.
        Thread.sleep(12_000);
        long sells = orderRepository.findByPositionIdOrderByCreatedAtDesc(pos.getId()).stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide())).count();
        System.out.println("[스케줄러 " + kind + "] 12초 뒤 매도=" + sells
                + " 메모=" + cleanupRepository.findById(pos.getId()).orElseThrow().getLastNote());
        assertThat(sells).isEqualTo(1);
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(Mockito.any(), Mockito.eq("ask"),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        assertThat(cleanupRepository.findById(pos.getId()).orElseThrow().getDoneAt())
                .as("CLOSED 확인 전에는 완료가 아니다").isNull();
    }
}
