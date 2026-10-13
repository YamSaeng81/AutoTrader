package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DYNAMIC 정지 경합 — <b>정지 후 신규 claim 0회, 이미 claim 된 주문은 추적이 이어진다</b> (2026-10-12).
 *
 * <p>종전에는 정지·회계 미반영·미해결 제출 가드가 LIVE 에만 있었다. 예약은 공통화됐는데 그 앞뒤의
 * 승인 조건이 갈려 있었다. 또 DYNAMIC 정지는 size=0 포지션을 무조건 닫아, 철회된 매수의 예약이
 * 복원되지 않고(고아 정리는 열린 포지션만 본다) 늦게 온 체결이 반영될 곳이 없었다.</p>
 *
 * <p>🔴 보장 범위: 정지는 <b>신규 승인·신규 claim</b> 을 막는다. 이미 claim 된 주문이 거래소로 나가는
 * 것은 막지 못한다 — 그 주문의 체결은 열린 포지션에 반영되지만, <b>정지된 세션에서 그 포지션을
 * 누가 청산하는가(늦은 체결 정리)는 이 시험의 범위가 아니다.</b></p>
 */
@DisplayName("DYNAMIC 정지 경합 — 신규 claim 차단과 추적 지속")
class DynamicStopRaceTest extends IntegrationTestBase {

    private static final BigDecimal CAPITAL = new BigDecimal("10000.00");
    private static final BigDecimal BUY = new BigDecimal("3000");

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private OrderExecutionEngine engine;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> sessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : sessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.deleteById(id);
        }
        sessions.clear();
    }

    private Long runningSession() {
        Long id = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(CAPITAL).availableKrw(CAPITAL).totalAssetKrw(CAPITAL)
                .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build()).getId();
        sessions.add(id);
        return id;
    }

    private record Intent(Long orderId, Long positionId, OrderRequest request) { }

    /** 틱과 같은 경계 — 한 트랜잭션에서 슬롯·예약·의도를 쓰고 커밋한다. 제출은 하지 않는다. */
    private Intent reserve(Long sessionId) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(st -> {
            PositionEntity pos = positionRepository.save(PositionEntity.builder()
                    .coinPair("KRW-DOGE").side("BUY")
                    .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                    .size(BigDecimal.ZERO).investedKrw(BUY)
                    .status("OPEN").sessionKind("DYNAMIC").sessionId(sessionId)
                    .openedAt(Instant.now()).build());
            OrderRequest req = new OrderRequest();
            req.setCoinPair("KRW-DOGE");
            req.setSide("BUY");
            req.setOrderType("MARKET");
            req.setQuantity(BUY);
            req.setReason("DYNAMIC 정지 경합 시험");
            req.setSignalPrice(new BigDecimal("300"));
            req.setSessionId(sessionId);
            req.setSessionKind("DYNAMIC");
            req.setPositionId(pos.getId());
            return new Intent(engine.reserveAndPrepareIntent(req), pos.getId(), req);
        });
    }

    private String rejectCode(Long sessionId) {
        try {
            reserve(sessionId);
            return null;
        } catch (OrderExecutionEngine.ReservationRejected r) {
            return r.getCode();
        }
    }

    private BigDecimal available(Long id) {
        return dynamicRepo.findById(id).orElseThrow().getAvailableKrw();
    }

    private void awaitDrained() {
        var pool = orderExecutor.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("orderExecutor 가 비워지지 않았다");
    }

    private static OrderResponse exchange(String uuid, String state) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("bid");
        r.setOrdType("price");
        r.setState(state);
        r.setMarket("KRW-DOGE");
        r.setVolume(new BigDecimal("3000"));
        if ("done".equals(state)) {
            r.setExecutedVolume(new BigDecimal("9.80000000"));
            r.setExecutedFunds(new BigDecimal("2940.00000000"));
            r.setPaidFee(new BigDecimal("1.47000000"));
        }
        return r;
    }

    @Test
    @DisplayName("① 의도 커밋 → 정지 → claim: claim 이 정지를 보고 철회, 제출 0회, 예약은 고아 정리가 한 번 복원")
    void stopBeforeClaimWithdraws() {
        Long id = runningSession();
        Intent intent = reserve(id);
        assertThat(available(id)).isEqualByComparingTo("7000");

        dynamicTradingService.stopSession(id);
        assertThat(positionRepository.findById(intent.positionId()).orElseThrow().getStatus())
                .as("🔴 정지가 size=0 을 닫으면 고아 정리가 보지 못해 예약 3,000 이 영영 복원되지 않는다")
                .isEqualTo("OPEN");

        engine.submitPreparedOrder(intent.orderId(), intent.request());
        awaitDrained();

        OrderEntity o = orderRepository.findById(intent.orderId()).orElseThrow();
        System.out.println("[DYNAMIC 정지→claim] state=" + o.getState() + " code=" + o.getFailureCode());
        assertThat(o.getState()).isEqualTo("FAILED");
        assertThat(o.getFailureCode()).isEqualTo(OrderExecutionEngine.WITHDRAWN_STOPPED);
        Mockito.verify(upbitOrderClient, Mockito.never()).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        dynamicTradingService.reconcileDynamicOrphanBuyPositions();
        dynamicTradingService.reconcileDynamicOrphanBuyPositions();
        assertThat(available(id))
                .as("철회된 매수의 예약 3,000 이 정확히 한 번 복원돼야 한다")
                .isEqualByComparingTo(CAPITAL);
        assertThat(positionRepository.findById(intent.positionId()).orElseThrow().getStatus()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("② 정지 후 신규 매수 — 승인되지 않고 슬롯·차감·의도가 남지 않는다")
    void stopBlocksNewEntry() {
        Long id = runningSession();
        dynamicTradingService.stopSession(id);

        String code = rejectCode(id);
        System.out.println("[DYNAMIC 정지 후 신규 매수] 거절 코드=" + code);
        assertThat(code).as("정지된 세션에 신규 매수가 승인되면 청산할 주체가 없는 포지션이 생긴다").isNotNull();
        assertThat(available(id)).isEqualByComparingTo(CAPITAL);
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id)).isEmpty();
        assertThat(positionRepository.findBySessionKindAndSessionId("DYNAMIC", id)).isEmpty();
    }

    @Test
    @DisplayName("③ claim·제출 → 정지: 주문은 추적되고 늦은 체결이 열린 포지션에 원자 경로로 한 번 반영된다")
    void claimedOrderKeepsBeingTrackedAfterStop() {
        Long id = runningSession();
        Intent intent = reserve(id);
        String uuid = "uuid-dyn-stop-" + System.nanoTime();
        Mockito.doReturn(new UpbitOrderClient.ExchangeResult(exchange(uuid, "wait"), "{}"))
                .when(upbitOrderClient).createOrder(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any());

        engine.submitPreparedOrder(intent.orderId(), intent.request());
        awaitDrained();
        assertThat(orderRepository.findById(intent.orderId()).orElseThrow().getState()).isEqualTo("SUBMITTED");

        dynamicTradingService.stopSession(id);
        assertThat(orderRepository.findById(intent.orderId()).orElseThrow().getState())
                .as("정지가 제출된 주문을 종결로 바꾸면 추적 대상에서 빠진다").isEqualTo("SUBMITTED");
        assertThat(positionRepository.findById(intent.positionId()).orElseThrow().getStatus())
                .as("🔴 진행 중인 매수의 포지션을 닫으면 체결이 반영될 곳이 없다(재개방 금지)")
                .isEqualTo("OPEN");

        // 정지 뒤 체결 — 폴러가 조회한다.
        Mockito.doReturn(exchange(uuid, "done")).when(upbitOrderClient).getOrder(uuid);
        engine.checkOrderStatus(intent.orderId());
        dynamicTradingService.reconcileDynamicOrphanBuyPositions();

        OrderEntity o = orderRepository.findById(intent.orderId()).orElseThrow();
        PositionEntity p = positionRepository.findById(intent.positionId()).orElseThrow();
        System.out.println("[DYNAMIC claim→정지→체결] order=" + o.getState() + " pos=" + p.getStatus()
                + "/" + p.getSize() + " available=" + available(id));
        assertThat(o.getState()).isEqualTo("FILLED");
        assertThat(p.getSize()).isEqualByComparingTo("9.8");
        assertThat(p.getStatus()).as("체결된 포지션 — 청산은 늦은 체결 정리(미구현)의 몫").isEqualTo("OPEN");
        assertThat(available(id))
                .as("7,000 + 미사용 58.53 (수수료 포함) — 고아 정리가 체결분을 복원하면 안 된다")
                .isEqualByComparingTo("7058.53");
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("⑤ 확정된 미제출 철회가 있는 size=0 — 정지가 예약을 한 번 복원하고 닫는다, 고아 정리와 겹쳐도 한 번")
    void confirmedWithdrawalIsRestoredAndClosedAtStop() {
        Long id = runningSession();
        Intent intent = reserve(id);
        // 실행기가 작업을 받지 못한 경우 — 그 의도를 claim 할 작업이 없음이 확정된다.
        assertThat(engine.withdrawUndispatchedIntent(intent.orderId(), "시험 — 디스패치 실패")).isTrue();
        assertThat(available(id)).isEqualByComparingTo("7000");

        dynamicTradingService.stopSession(id);
        System.out.println("[DYNAMIC 철회 확정 → 정지] pos=" + positionRepository.findById(intent.positionId())
                .orElseThrow().getStatus() + " available=" + available(id));
        assertThat(positionRepository.findById(intent.positionId()).orElseThrow().getStatus())
                .as("🔴 확정된 미제출 철회까지 열어 두면 정리가 고아 정리 주기에 기댄다").isEqualTo("CLOSED");
        assertThat(available(id)).as("예약 3,000 이 정지에서 한 번 복원").isEqualByComparingTo(CAPITAL);

        dynamicTradingService.reconcileDynamicOrphanBuyPositions();
        assertThat(available(id)).as("고아 정리가 같은 예약을 또 복원하면 없던 돈이 생긴다").isEqualByComparingTo(CAPITAL);
        Mockito.verify(upbitOrderClient, Mockito.never()).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("⑥ 응답 불명(UNKNOWN) 매수의 size=0 — 정지가 닫지도 복원하지도 않는다")
    void unknownBuyIsKeptAtStop() {
        Long id = runningSession();
        Intent intent = reserve(id);
        OrderEntity o = orderRepository.findById(intent.orderId()).orElseThrow();
        o.setState("UNKNOWN");
        o.setUnknownReason("시험 — 제출 응답 유실");
        orderRepository.save(o);

        dynamicTradingService.stopSession(id);
        dynamicTradingService.reconcileDynamicOrphanBuyPositions();
        assertThat(positionRepository.findById(intent.positionId()).orElseThrow().getStatus())
                .as("접수됐을 수 있는 주문의 슬롯을 풀면 안 된다").isEqualTo("OPEN");
        assertThat(available(id)).as("접수됐을 수 있는 예약을 복원하면 쓰인 돈을 되돌린다").isEqualByComparingTo("7000");
    }

    @Test
    @DisplayName("④ 회계 미반영 체결·미해결 제출이 있으면 DYNAMIC 신규 매수도 막힌다")
    void pendingAccountingAndUnresolvedBlockEntry() {
        for (String blocker : List.of("PENDING_ACCOUNTING", "UNKNOWN")) {
            Long id = runningSession();
            orderRepository.save(OrderEntity.builder()
                    .coinPair("KRW-XRP").side("BUY").orderType("MARKET")
                    .quantity(new BigDecimal("1000"))
                    .state("PENDING_ACCOUNTING".equals(blocker) ? "SUBMITTED" : "UNKNOWN")
                    .fillAccountingPending("PENDING_ACCOUNTING".equals(blocker))
                    .sessionKind("DYNAMIC").sessionId(id)
                    .clientOrderId("cat-guard-" + System.nanoTime())
                    .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                    .createdAt(Instant.now()).build());

            String code = rejectCode(id);
            System.out.println("[DYNAMIC 가드 " + blocker + "] 거절 코드=" + code);
            assertThat(code).as("%s 가 있는데 신규 매수가 승인됐다", blocker).isEqualTo("INTENT_REJECTED");
            assertThat(available(id)).as("거절이면 예약도 남지 않는다").isEqualByComparingTo(CAPITAL);
            assertThat(positionRepository.findBySessionKindAndSessionId("DYNAMIC", id)).isEmpty();
        }
    }
}
