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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원자 회계 경로를 <b>우회하던 두 경로</b>의 회귀 시험 (2026-10-13).
 *
 * <p>코드 읽기로 찾아 고친 두 경로가 실제로 원자 경로를 지나고, 반복 처리에서 중복이 없는지 확인한다.</p>
 * <ul>
 *   <li>① <b>5분 타임아웃 직전 체결 확인</b> — {@code pollActiveOrders} 의 타임아웃 분기가 거래소를 다시
 *       조회해 체결을 보면, 종전에는 {@code syncOrderState}(종전 경로)로 반영했다.</li>
 *   <li>② <b>취소 실패 후 체결 확인</b> — {@code cancelOrder} 가 취소에 실패하고 재조회로 체결을 보면,
 *       종전에는 같은 종전 경로로 반영했다.</li>
 * </ul>
 *
 * <p>종전 경로가 반영하면 원금 차이 복원과 원자 경로 정산의 미사용 복원이 겹칠 수 있다. 기대값은 원자 경로
 * 하나의 결과 — 요청 3,000 / 체결 2,940 / 수수료 1.47 → 미사용 58.53 이 <b>한 번</b>(7,058.53), 수량 9.8,
 * 정산 버전 1 — 이고, 이후 반복 조회에서 값이 변하지 않아야 한다.</p>
 */
@DisplayName("원자 회계 우회 경로 회귀 — 타임아웃 직전 체결·취소 실패 후 체결")
class AtomicPathBypassTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine engine;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> liveSessions = new ArrayList<>();
    private final List<Long> dynamicSessions = new ArrayList<>();

    @BeforeEach
    void defaultExchange() {
        // 공유 DB 에 남은 다른 활성 주문은 "대기 중" 으로 답한다 — 폴러 전체를 돌리는 시험이 그 주문 때문에
        // 깨지거나 그 주문을 바꾸지 않게 한다.
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString())).thenAnswer(inv -> {
            OrderResponse r = new OrderResponse();
            r.setUuid(inv.getArgument(0));
            r.setState("wait");
            return r;
        });
    }

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

    private record Fixture(String kind, Long sessionId, Long positionId, Long orderId, String uuid) { }

    /** 10,000 에서 3,000 을 예약한 제출된 시장가 매수. {@code submittedAgo} 만큼 전에 제출됐다. */
    private Fixture submittedBuy(String kind, Duration submittedAgo) {
        return submittedBuy(kind, submittedAgo, "STOPPED");
    }

    /**
     * 🔴 세션 상태를 받는다 (2026-10-13). 정지된 세션의 매수는 폴러가 타임아웃 분기보다 먼저 취소를 요청하므로,
     * 타임아웃 분기를 시험하려면 세션이 실행 중이어야 한다. 시험 기반의 스케줄러는 꺼져 있다.
     */
    private Fixture submittedBuy(String kind, Duration submittedAgo, String sessionStatus) {
        String uuid = "uuid-bypass-" + kind + "-" + System.nanoTime();
        Long sid;
        if ("DYNAMIC".equals(kind)) {
            sid = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                    .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                    .initialCapital(new BigDecimal("10000.00")).availableKrw(new BigDecimal("7000.00"))
                    .totalAssetKrw(new BigDecimal("10000.00"))
                    .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                    .status(sessionStatus).scanState("POSITION_MONITORING").tradingMode("REAL")
                    .maxCandidateSize(30).targetWatchSize(10)
                    .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                    .watchlistRefreshMin(60)
                    .build()).getId();
            dynamicSessions.add(sid);
        } else {
            sid = liveRepo.save(LiveTradingSessionEntity.builder()
                    .sessionType("REAL").strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                    .coinPair("KRW-DOGE").timeframe("M5")
                    .initialCapital(new BigDecimal("10000.00")).availableKrw(new BigDecimal("7000.00"))
                    .totalAssetKrw(new BigDecimal("10000.00"))
                    .status(sessionStatus).startedAt(Instant.now()).build()).getId();
            liveSessions.add(sid);
        }
        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300.00000000")).avgPrice(new BigDecimal("300.00000000"))
                .size(BigDecimal.ZERO).investedKrw(new BigDecimal("3000.00000000"))
                .status("OPEN").sessionKind(kind).sessionId(sid)
                .openedAt(Instant.now()).build());
        Instant submitted = Instant.now().minus(submittedAgo);
        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000.00000000"))
                .reservedKrw(new BigDecimal("3000.00000000"))
                .state("SUBMITTED")
                .sessionKind(kind).sessionId(sid).positionId(pos.getId())
                .clientOrderId("cat-bypass-" + System.nanoTime())
                .exchangeOrderId(uuid)
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L).settlementFinal(Boolean.FALSE).settlementAttempts(0)
                .createdAt(submitted).submittedAt(submitted).build());
        return new Fixture(kind, sid, pos.getId(), order.getId(), uuid);
    }

    private static OrderResponse filled(String uuid, String state) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("bid");
        r.setOrdType("price");
        r.setState(state);
        r.setMarket("KRW-DOGE");
        r.setVolume(new BigDecimal("3000.00000000"));
        r.setExecutedVolume(new BigDecimal("9.80000000"));
        r.setExecutedFunds(new BigDecimal("2940.00000000"));
        r.setPaidFee(new BigDecimal("1.47000000"));
        return r;
    }

    private BigDecimal available(Fixture f) {
        return "DYNAMIC".equals(f.kind())
                ? dynamicRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw()
                : liveRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw();
    }

    private String snapshot(Fixture f) {
        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        PositionEntity p = positionRepository.findById(f.positionId()).orElseThrow();
        return String.format("order{state=%s filled=%s appFunds=%s appFee=%s ver=%s pending=%s} pos{size=%s fee=%s} available=%s",
                o.getState(), o.getFilledQuantity(), o.getAppliedExecutedFunds(), o.getAppliedPaidFee(),
                o.getSettlementVersion(), o.getFillAccountingPending(), p.getSize(), p.getPositionFee(), available(f));
    }

    private void advancePastBackoff(Long orderId) {
        OrderEntity o = orderRepository.findById(orderId).orElseThrow();
        o.setSettlementNextCheckAt(Instant.now().minusSeconds(1));
        orderRepository.save(o);
    }

    /** 원자 경로 한 번의 결과와 같고, 반복 조회에서 변하지 않는가. */
    private void assertOnceAndStable(Fixture f, String label) {
        String first = snapshot(f);
        System.out.println("[우회 경로 " + label + "] " + first);
        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        assertThat(available(f))
                .as("🔴 %s — 미사용 58.53 한 번 → 7,058.53. 7,060 이면 종전 경로(수수료 미반영), 7,118.53 이면 이중 복원. %s",
                        label, first)
                .isEqualByComparingTo("7058.53");
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getSize()).isEqualByComparingTo("9.8");
        assertThat(o.getState()).isEqualTo("FILLED");
        assertThat(o.getSettlementVersion()).as("원자 경로가 반영했으면 정산 버전 1 — 0 이면 종전 경로가 반영했다").isEqualTo(1L);
        assertThat(o.getFillAccountingPending()).isFalse();

        for (int i = 0; i < 3; i++) {
            advancePastBackoff(f.orderId());
            engine.checkOrderStatus(f.orderId());
            engine.pollActiveOrders();
        }
        assertThat(snapshot(f)).as("%s — 반복 처리에서 값이 바뀌면 중복 반영이다", label).isEqualTo(first);
    }

    private void timeoutPath(String kind, String exchangeState) {
        Fixture f = submittedBuy(kind, Duration.ofMinutes(6), "RUNNING");   // ORDER_TIMEOUT(5분) 경과
        Mockito.doReturn(filled(f.uuid(), exchangeState)).when(upbitOrderClient).getOrder(f.uuid());

        engine.pollActiveOrders();

        Mockito.verify(upbitOrderClient, Mockito.never()).cancelOrder(f.uuid());
        assertOnceAndStable(f, kind + " 타임아웃 직전 " + exchangeState);
    }

    private void cancelFailurePath(String kind) {
        Fixture f = submittedBuy(kind, Duration.ofSeconds(10));
        // 🔴 취소 전 조회는 "대기 중" 이어야 취소가 나간다 (2026-10-13 — 취소는 먼저 묻고, 이미 종결이면 보내지
        //    않는다). 취소가 실패한 뒤의 확인 조회부터 체결이 보인다.
        OrderResponse waiting = new OrderResponse();
        waiting.setUuid(f.uuid());
        waiting.setState("wait");
        Mockito.doReturn(waiting, filled(f.uuid(), "done")).when(upbitOrderClient).getOrder(f.uuid());
        Mockito.doThrow(new RuntimeException("400 order_not_found — 이미 체결"))
                .when(upbitOrderClient).cancelOrder(f.uuid());

        engine.cancelOrder(f.orderId());
        Mockito.verify(upbitOrderClient).cancelOrder(f.uuid());
        assertThat(orderRepository.findById(f.orderId()).orElseThrow().getState())
                .as("취소 실패 + 체결 확인 — 이 경로에서는 반영하지 않고 원자 경로에 맡긴다")
                .isEqualTo("SUBMITTED");

        engine.checkOrderStatus(f.orderId());
        assertOnceAndStable(f, kind + " 취소 실패 후 체결");
    }

    @Test
    @DisplayName("① LIVE — 타임아웃 직전 전량 체결")
    void liveTimeoutDone() {
        timeoutPath("LIVE", "done");
    }

    @Test
    @DisplayName("① LIVE — 타임아웃 직전 체결 후 취소")
    void liveTimeoutCancelWithFill() {
        timeoutPath("LIVE", "cancel");
    }

    @Test
    @DisplayName("① DYNAMIC — 타임아웃 직전 전량 체결")
    void dynamicTimeoutDone() {
        timeoutPath("DYNAMIC", "done");
    }

    @Test
    @DisplayName("① DYNAMIC — 타임아웃 직전 체결 후 취소")
    void dynamicTimeoutCancelWithFill() {
        timeoutPath("DYNAMIC", "cancel");
    }

    @Test
    @DisplayName("② LIVE — 취소 실패 후 체결 확인")
    void liveCancelFailure() {
        cancelFailurePath("LIVE");
    }

    @Test
    @DisplayName("② DYNAMIC — 취소 실패 후 체결 확인")
    void dynamicCancelFailure() {
        cancelFailurePath("DYNAMIC");
    }
}
