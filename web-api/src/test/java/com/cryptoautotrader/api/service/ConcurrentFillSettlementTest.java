package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시험 10 — <b>같은 주문을 두 작업자가 동시에 반영해도 정산은 한 번</b> (2026-10-12).
 *
 * <p>두 경로가 같은 체결 응답을 동시에 들고 원자 회계 경로에 들어가게 한다. 거래소 조회 지점에서
 * 장벽으로 <b>두 작업자를 맞춰 세운 뒤</b> 동시에 놓는다 — 겹침이 실제로 일어났는지는 장벽 통과
 * 횟수로 확인한다(겹치지 않았으면 시험이 무의미하다).</p>
 *
 * <ul>
 *   <li>① 폴러 둘 — 같은 {@code SUBMITTED} 주문을 동시에 조회</li>
 *   <li>② 폴러와 복구 — 복구가 불명 주문을 식별자로 찾아 {@code SUBMITTED} 로 올리고 조회하는 동안,
 *       폴러가 그 주문을 같은 응답으로 조회</li>
 * </ul>
 *
 * <p>LIVE·DYNAMIC 양쪽에서 같은 기대값을 단정한다 — 요청 3,000 / 체결 2,940 / 수수료 1.47 →
 * 미사용 58.53 이 <b>정확히 한 번</b>, 포지션 수량 9.8 이 <b>정확히 한 번</b>.</p>
 *
 * <p>🔴 스케줄된 실제 폴러(5초)도 돌고 있다 — 세 번째 작업자로 끼어들 수 있으며, 그래도 불변식은
 * 같다. 장벽은 이 시험의 두 스레드에만 건다.</p>
 */
@DisplayName("시험 10 — 같은 주문의 동시 반영에서 정산은 한 번")
class ConcurrentFillSettlementTest extends IntegrationTestBase {

    private static final int ROUNDS = 20;
    private static final String WORKER_PREFIX = "t10-";

    @Autowired private OrderExecutionEngine engine;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();

    /** uuid → 그 uuid 를 조회하는 시험 작업자들을 맞춰 세우는 장벽. */
    private final ConcurrentHashMap<String, CyclicBarrier> barriers = new ConcurrentHashMap<>();
    private final AtomicInteger overlapped = new AtomicInteger();

    @AfterEach
    void cleanUp() {
        for (Long id : liveSessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.deleteById(id);
        }
        for (Long id : dynamicSessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.deleteById(id);
        }
        liveSessions.clear();
        dynamicSessions.clear();
    }

    private record Fixture(String kind, Long sessionId, Long positionId, Long orderId, String uuid, String identifier) { }

    /** 세션 10,000 에서 3,000 을 예약(선차감)한 시장가 매수. */
    private Fixture fixture(String kind, String state, int round) {
        String uuid = "uuid-t10-" + kind + "-" + state + "-" + round + "-" + System.nanoTime();
        String identifier = "cat-t10-" + System.nanoTime();
        Long sessionId;
        if ("DYNAMIC".equals(kind)) {
            sessionId = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                    .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                    .initialCapital(new BigDecimal("10000.00"))
                    .availableKrw(new BigDecimal("7000.00"))
                    .totalAssetKrw(new BigDecimal("10000.00"))
                    .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                    // 정지 상태로 둔다 — 틱이 이 세션을 평가하지 않게 한다(정산은 세션 상태와 무관하다).
                    .status("STOPPED").scanState("POSITION_MONITORING").tradingMode("REAL")
                    .maxCandidateSize(30).targetWatchSize(10)
                    .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                    .watchlistRefreshMin(60)
                    .build()).getId();
            dynamicSessions.add(sessionId);
        } else {
            sessionId = liveRepo.save(LiveTradingSessionEntity.builder()
                    .sessionType("REAL").strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                    .coinPair("KRW-DOGE").timeframe("M5")
                    .initialCapital(new BigDecimal("10000.00"))
                    .availableKrw(new BigDecimal("7000.00"))
                    .totalAssetKrw(new BigDecimal("10000.00"))
                    .status("STOPPED").startedAt(Instant.now()).build()).getId();
            liveSessions.add(sessionId);
        }
        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300.00000000")).avgPrice(new BigDecimal("300.00000000"))
                .size(BigDecimal.ZERO).investedKrw(new BigDecimal("3000.00000000"))
                .status("OPEN").sessionKind(kind).sessionId(sessionId)
                .openedAt(Instant.now()).build());
        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000.00000000"))
                .reservedKrw(new BigDecimal("3000.00000000"))
                .state(state)
                .sessionKind(kind).sessionId(sessionId).positionId(pos.getId())
                .clientOrderId(identifier)
                .exchangeOrderId("SUBMITTED".equals(state) ? uuid : null)
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L).settlementFinal(Boolean.FALSE).settlementAttempts(0)
                .createdAt(Instant.now())
                .submittedAt("SUBMITTED".equals(state) ? Instant.now() : null)
                .build());
        return new Fixture(kind, sessionId, pos.getId(), order.getId(), uuid, identifier);
    }

    private static OrderResponse done2940(String uuid) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("bid");
        r.setOrdType("price");
        r.setState("done");
        r.setMarket("KRW-DOGE");
        r.setVolume(new BigDecimal("3000.00000000"));
        r.setExecutedVolume(new BigDecimal("9.80000000"));
        r.setExecutedFunds(new BigDecimal("2940.00000000"));
        r.setPaidFee(new BigDecimal("1.47000000"));
        return r;
    }

    /** 조회 응답 — 시험 작업자는 장벽에서 상대를 기다렸다가 동시에 돌려받는다. */
    private void stubExchange(Fixture f) {
        barriers.put(f.uuid(), new CyclicBarrier(2));
        Mockito.doAnswer(inv -> {
            if (Thread.currentThread().getName().startsWith(WORKER_PREFIX)) {
                try {
                    barriers.get(f.uuid()).await(10, TimeUnit.SECONDS);
                    overlapped.incrementAndGet();
                } catch (Exception notOverlapped) {
                    // 겹치지 못했다 — 응답은 돌려주고, 시험이 겹침 횟수로 무의미함을 잡는다.
                }
            }
            return done2940(f.uuid());
        }).when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doReturn(done2940(f.uuid())).when(upbitOrderClient).getOrderByIdentifier(f.identifier());
    }

    private String snapshot(Fixture f) {
        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        PositionEntity p = positionRepository.findById(f.positionId()).orElseThrow();
        BigDecimal available = "DYNAMIC".equals(f.kind())
                ? dynamicRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw()
                : liveRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw();
        return String.format("order{state=%s filled=%s appFunds=%s appFee=%s ver=%s pending=%s} "
                        + "pos{size=%s fee=%s} available=%s",
                o.getState(), o.getFilledQuantity(), o.getAppliedExecutedFunds(), o.getAppliedPaidFee(),
                o.getSettlementVersion(), o.getFillAccountingPending(), p.getSize(), p.getPositionFee(),
                available);
    }

    private void assertSettledOnce(Fixture f, String label) {
        String snap = snapshot(f);
        System.out.println("[시험 10 " + label + "] " + snap);
        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        PositionEntity p = positionRepository.findById(f.positionId()).orElseThrow();
        BigDecimal available = "DYNAMIC".equals(f.kind())
                ? dynamicRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw()
                : liveRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw();
        assertThat(available)
                .as("🔴 %s — 미사용 58.53 이 정확히 한 번 복원돼 7,058.53 이어야 한다. 7,117.06 이면 두 번이다. %s",
                        label, snap)
                .isEqualByComparingTo(new BigDecimal("7058.53"));
        assertThat(p.getSize())
                .as("🔴 %s — 체결 수량 9.8 이 한 번만 반영돼야 한다. %s", label, snap)
                .isEqualByComparingTo(new BigDecimal("9.80000000"));
        assertThat(o.getSettlementVersion()).as("%s — 반영은 한 회차 %s", label, snap).isEqualTo(1L);
        assertThat(o.getAppliedExecutedFunds()).isEqualByComparingTo(new BigDecimal("2940.00000000"));
        assertThat(o.getAppliedPaidFee()).isEqualByComparingTo(new BigDecimal("1.47000000"));
        assertThat(o.getState()).isEqualTo("FILLED");
        assertThat(o.getFillAccountingPending()).as("대기 표시가 남으면 신규 매수가 영구히 막힌다").isFalse();
    }

    private void runPair(Runnable a, Runnable b, List<Throwable> errors) throws InterruptedException {
        Thread ta = new Thread(() -> {
            try { a.run(); } catch (Throwable e) { errors.add(e); }
        }, WORKER_PREFIX + "a");
        Thread tb = new Thread(() -> {
            try { b.run(); } catch (Throwable e) { errors.add(e); }
        }, WORKER_PREFIX + "b");
        ta.start();
        tb.start();
        ta.join(30_000);
        tb.join(30_000);
    }

    private void twoPollers(String kind) throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Fixture f = fixture(kind, "SUBMITTED", round);
            stubExchange(f);
            int before = overlapped.get();
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            runPair(() -> engine.checkOrderStatus(f.orderId()),
                    () -> engine.checkOrderStatus(f.orderId()), errors);
            assertThat(overlapped.get() - before)
                    .as("두 작업자가 조회 지점에서 실제로 겹쳐야 시험이 성립한다").isEqualTo(2);
            assertThat(errors).as("호출부로 새어 나온 예외").isEmpty();
            assertSettledOnce(f, kind + " 폴러×2 #" + round);
        }
    }

    private void pollerAndRecovery(String kind) throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Fixture f = fixture(kind, "UNKNOWN", round);
            stubExchange(f);
            int before = overlapped.get();
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            runPair(
                    // 복구 — 식별자로 찾아 SUBMITTED 로 올리고(커밋) 조회한다
                    () -> engine.resolveUnresolvedSubmission(f.orderId()),
                    // 폴러 — 복구가 uuid 를 커밋하는 즉시 같은 주문을 조회한다
                    () -> {
                        long deadline = System.currentTimeMillis() + 10_000;
                        while (System.currentTimeMillis() < deadline) {
                            OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
                            if (o.getExchangeOrderId() != null) break;
                            Thread.onSpinWait();
                        }
                        engine.checkOrderStatus(f.orderId());
                    }, errors);
            assertThat(overlapped.get() - before)
                    .as("복구와 폴러가 조회 지점에서 실제로 겹쳐야 시험이 성립한다").isEqualTo(2);
            assertThat(errors).as("호출부로 새어 나온 예외").isEmpty();
            assertSettledOnce(f, kind + " 폴러+복구 #" + round);
        }
    }

    @Test
    @DisplayName("① LIVE — 폴러 둘이 같은 체결을 동시에 반영해도 한 번")
    void liveTwoPollers() throws Exception {
        twoPollers("LIVE");
    }

    @Test
    @DisplayName("① DYNAMIC — 폴러 둘이 같은 체결을 동시에 반영해도 한 번")
    void dynamicTwoPollers() throws Exception {
        twoPollers("DYNAMIC");
    }

    @Test
    @DisplayName("② LIVE — 폴러와 복구가 같은 주문을 동시에 처리해도 한 번")
    void livePollerAndRecovery() throws Exception {
        pollerAndRecovery("LIVE");
    }

    @Test
    @DisplayName("② DYNAMIC — 폴러와 복구가 같은 주문을 동시에 처리해도 한 번")
    void dynamicPollerAndRecovery() throws Exception {
        pollerAndRecovery("DYNAMIC");
    }
}
