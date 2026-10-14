package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선행 조건 5번 5-b — <b>LIVE 비상 정지 세 경로가 동시 쓰기를 견디는가</b> (2026-10-14).
 *
 * <p>일반 정지는 2026-10-07 에 "앞서 읽은 세션 엔티티를 그대로 save" 하던 형태를 상태 전환만 재조회·재시도
 * ({@code balanceUpdater})로 바꿨다. 같은 형태가 <b>비상 정지(단건)·전체 비상 정지·서킷 브레이커 발동</b>에 남아 있었다
 * (DYNAMIC 은 고쳐져 있다). 충돌은 {@link LiveStopUnderConcurrentWriteTest} 와 같은 방식으로 심는다 — 정지 트랜잭션 안에서
 * 반드시 불리는 지점에서 같은 세션 행을 독립 트랜잭션으로 고쳐 버전을 올린다(평가 루프·체결 반영이 끼어든 것과 같다).</p>
 *
 * <p>확인하는 것: ① 최종 상태 {@code EMERGENCY_STOPPED} ② 열린 포지션 없음 ③ 거래소 매도 정확히 1회 ④ 그 매도가 체결되면
 * 포지션 {@code CLOSED}·대금이 한 번 반영된다(정지·청산의 최종 상태). 서킷 브레이커는 발동 기록(횟수·사유)도 남아야 한다.</p>
 *
 * <p>🔴 증명하지 않는 것: 주입한 충돌 한 지점에 대한 검증이며 모든 동시 실행 조합의 증명이 아니다.</p>
 */
@DisplayName("LIVE 비상 정지 — 동시 쓰기 중에도 정지·청산이 끝난다 (선행 조건 5-b)")
class LiveEmergencyStopUnderConcurrentWriteTest extends IntegrationTestBase {

    @SpyBean private LiveTradingService liveTradingService;
    @SpyBean private PositionStateTransition positionStateTransition;
    @SpyBean private RiskManagementService riskManagementService;
    @MockBean private UpbitOrderClient upbitOrderClient;

    @Autowired private LiveTradingSessionRepository sessionRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderExecutionEngine engine;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;

    private final List<Long> createdSessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
    }

    // ── 픽스처 ──────────────────────────────────────────────────────

    private Long runningSessionWithOpenPosition() {
        LiveTradingSessionEntity s = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000.00")).availableKrw(new BigDecimal("2000.00"))
                .totalAssetKrw(new BigDecimal("10000.00")).mddPeakCapital(new BigDecimal("10000.00"))
                .status("RUNNING").maxHoldHours(24).startedAt(Instant.now()).build());
        positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(new BigDecimal("26")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionKind("LIVE").sessionId(s.getId()).openedAt(Instant.now()).build());
        createdSessions.add(s.getId());
        return s.getId();
    }

    private void acceptSells() {
        OrderResponse r = new OrderResponse();
        r.setUuid("uuid-emergency-sell-" + System.nanoTime());
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("wait");
        r.setMarket("KRW-DOGE");
        r.setVolume(new BigDecimal("26"));
        r.setExecutedVolume(BigDecimal.ZERO);
        Mockito.doReturn(new UpbitOrderClient.ExchangeResult(r, "{}")).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    /** 같은 세션 행을 독립 트랜잭션에서 고쳐 버전을 올린다 — 평가 루프·체결 반영이 끼어든 것과 같다. */
    private void bumpSessionVersion(Long sessionId) {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.executeWithoutResult(st -> {
            LiveTradingSessionEntity fresh = sessionRepository.findById(sessionId).orElseThrow();
            fresh.setTotalAssetKrw(fresh.getTotalAssetKrw().add(BigDecimal.ONE));
            sessionRepository.saveAndFlush(fresh);
        });
    }

    /** 청산 지점(정지 트랜잭션 안)에서 한 번 충돌을 심는다. */
    private AtomicInteger injectAtClosing(Long sessionId) {
        AtomicInteger injected = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            if (injected.getAndIncrement() == 0) bumpSessionVersion(sessionId);
            return inv.callRealMethod();
        }).when(positionStateTransition).markClosingIfOpen(Mockito.anyLong(), Mockito.any(), Mockito.any(ExitReason.class));
        return injected;
    }

    private void awaitDrained() {
        var pool = orderExecutor.getThreadPoolExecutor();
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
        throw new AssertionError("orderExecutor 가 20초 안에 비워지지 않았다");
    }

    private Throwable run(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private List<OrderEntity> sells(Long sessionId) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", sessionId).stream()
                .filter(o -> "SELL".equals(o.getSide())).toList();
    }

    /**
     * 정지 직후 상태 + 매도 체결 뒤 최종 상태를 확인한다.
     *
     * @param error 정지 호출이 던진 예외(없으면 null) — 실패 메시지에 싣는다
     */
    private void assertStoppedAndLiquidated(Long sessionId, Throwable error) {
        awaitDrained();
        LiveTradingSessionEntity after = sessionRepository.findById(sessionId).orElseThrow();
        // 측정 기록 — 실패하는 경우에도 무엇이 남았는지 남긴다(거래소 호출 수·매도 행·포지션 상태).
        long calls = Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName())).count();
        System.out.println("[비상 정지 측정] 세션=" + after.getStatus() + " 거래소 매도 호출=" + calls
                + " 매도 행=" + sells(sessionId).stream().map(o -> o.getState() + "/" + o.getExchangeOrderId()).toList()
                + " 포지션=" + positionRepository.findBySessionKindAndSessionId("LIVE", sessionId).stream()
                        .map(p -> p.getStatus() + "/" + p.getSize()).toList()
                + " 잔액=" + after.getAvailableKrw() + " 예외=" + (error == null ? "없음" : error.getClass().getSimpleName()));
        String why = error == null ? "예외 없음" : error.getClass().getSimpleName() + ": " + error.getMessage();
        assertThat(after.getStatus()).as("비상 정지가 커밋돼야 한다 (%s)", why).isEqualTo("EMERGENCY_STOPPED");
        assertThat(after.getStoppedAt()).isNotNull();
        assertThat(positionRepository.findBySessionKindAndSessionIdAndStatus("LIVE", sessionId, "OPEN"))
                .as("OPEN 포지션이 남으면 정지됐는데 청산되지 않은 것이다").isEmpty();
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(
                Mockito.eq("KRW-DOGE"), Mockito.eq("ask"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        // 매도 체결 — 26 × 300 = 7,800, 보고 수수료 3.12 → 2,000 + 7,796.88 = 9,796.88 (한 번)
        List<OrderEntity> s = sells(sessionId);
        assertThat(s).hasSize(1);
        OrderEntity sell = s.get(0);
        OrderResponse done = new OrderResponse();
        done.setUuid(sell.getExchangeOrderId());
        done.setSide("ask");
        done.setOrdType("market");
        done.setState("done");
        done.setMarket("KRW-DOGE");
        done.setVolume(new BigDecimal("26"));
        done.setExecutedVolume(new BigDecimal("26"));
        done.setExecutedFunds(new BigDecimal("7800"));
        done.setPaidFee(new BigDecimal("3.12"));
        Mockito.doReturn(done).when(upbitOrderClient).getOrder(sell.getExchangeOrderId());
        engine.checkOrderStatus(sell.getId());
        liveTradingService.reconcileClosingPositions();
        engine.checkOrderStatus(sell.getId());       // 반복 처리에도 한 번만
        liveTradingService.reconcileClosingPositions();

        LiveTradingSessionEntity fin = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(positionRepository.findBySessionKindAndSessionId("LIVE", sessionId))
                .allSatisfy(p -> assertThat(p.getStatus()).as("청산 확정").isEqualTo("CLOSED"));
        assertThat(fin.getAvailableKrw()).as("대금 7,800 − 수수료 3.12 가 한 번").isEqualByComparingTo("9796.88");
        assertThat(fin.getStatus()).as("청산 확정이 정지 상태를 되돌리지 않는다").isEqualTo("EMERGENCY_STOPPED");
    }

    // ── 시험 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("비상 정지(단건) — 청산 중 같은 세션 행에 쓰기가 들어와도 EMERGENCY_STOPPED·매도 1회·체결 후 CLOSED")
    void singleEmergencyStop() {
        Long id = runningSessionWithOpenPosition();
        acceptSells();
        AtomicInteger injected = injectAtClosing(id);
        Throwable error = run(() -> liveTradingService.emergencyStopSession(id));
        assertThat(injected.get()).as("충돌을 실제로 심었다").isPositive();
        assertStoppedAndLiquidated(id, error);
    }

    @Test
    @DisplayName("전체 비상 정지 — 청산 중 같은 세션 행에 쓰기가 들어와도 EMERGENCY_STOPPED·매도 1회·체결 후 CLOSED")
    void emergencyStopAll() {
        Long id = runningSessionWithOpenPosition();
        acceptSells();
        AtomicInteger injected = injectAtClosing(id);
        Throwable error = run(() -> liveTradingService.emergencyStopAll(false));
        assertThat(injected.get()).as("충돌을 실제로 심었다").isPositive();
        assertStoppedAndLiquidated(id, error);
    }

    @Test
    @DisplayName("서킷 브레이커 발동 — 판정과 기록 사이에 같은 세션 행에 쓰기가 들어와도 발동 기록·EMERGENCY_STOPPED·매도 1회·체결 후 CLOSED")
    void circuitBreakerTrip() {
        Long id = runningSessionWithOpenPosition();
        acceptSells();
        AtomicInteger injected = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            if (injected.getAndIncrement() == 0) bumpSessionVersion(id);
            return CircuitBreakerResult.triggered("시험 — MDD 초과");
        }).when(riskManagementService).checkCircuitBreaker(Mockito.any(LiveTradingSessionEntity.class));

        // 생산 경로와 같다 — 스케줄러가 틱 시작에 읽어 둔 세션 엔티티로 평가한다(트랜잭션 없음).
        LiveTradingSessionEntity tickSnapshot = sessionRepository.findById(id).orElseThrow();
        // 프록시가 아니라 실제 객체에서 부른다 — 생산에서도 스케줄러가 같은 객체 안에서 부르므로 트랜잭션 프록시를 지나지 않는다.
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(liveTradingService);
        Throwable error = run(() -> ReflectionTestUtils.invokeMethod(target, "evaluateAndExecuteSession", tickSnapshot));
        assertThat(injected.get()).as("충돌을 실제로 심었다 (%s)", error).isPositive();

        LiveTradingSessionEntity after = sessionRepository.findById(id).orElseThrow();
        assertThat(after.getCircuitBreakerTripCount()).as("발동 횟수 기록 (%s)", error).isEqualTo(1);
        assertThat(after.getCircuitBreakerReason()).isEqualTo("시험 — MDD 초과");
        assertThat(after.getCircuitBreakerTriggeredAt()).isNotNull();
        assertStoppedAndLiquidated(id, error);
    }
}
