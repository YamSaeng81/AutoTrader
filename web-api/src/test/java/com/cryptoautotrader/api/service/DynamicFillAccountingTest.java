package com.cryptoautotrader.api.service;

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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DYNAMIC 시장가 매수의 체결 회계가 <b>LIVE 와 같은 원자 경로</b>를 지나는가 (2026-10-12).
 *
 * <p>종전 DYNAMIC 경로({@code handleBuyFill})는 원금 차이(요청 − 체결금액)를 <b>수수료 없이</b> 복원했고,
 * 포지션·잔액을 다른 트랜잭션에 썼다. 요청 3,000 / 체결 2,940 / 수수료 1.47 에서 종전 복원은 60,
 * 맞는 값은 58.53 이다.</p>
 *
 * <ul>
 *   <li>수수료 포함 미사용액 복원 — 전량 체결·체결 후 취소 모두 7,058.53</li>
 *   <li>잔액 변경 전·후 실패 → 주문·잔액·포지션이 <b>함께</b> 롤백</li>
 *   <li>재처리 → 한 번만 반영, 추가 반복 → 변화 없음</li>
 * </ul>
 */
@DisplayName("DYNAMIC 체결 회계 — 원자 경로 통합")
class DynamicFillAccountingTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine engine;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private DynamicSessionRepository dynamicRepo;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    /** 잔액 변경 지점 — 여기 전·후에 예외를 주입한다. */
    @SpyBean private DynamicSessionBalanceUpdater balanceUpdater;

    /** 쓰지 않는다 — {@code SettlementAtomicityTest} 와 같은 컨텍스트를 공유하려고 둔다(힙 한계, 그 클래스 주석 참조). */
    @SpyBean private SessionBalanceUpdater sharedContextLiveUpdater;

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

    private record Fixture(Long sessionId, Long positionId, Long orderId, String uuid) { }

    /** 10,000 에서 3,000 을 선차감한 DYNAMIC 시장가 매수(제출됨). 별도 수수료 차감은 없다. */
    private Fixture marketBuy3000() {
        String uuid = "uuid-dyn-acct-" + System.nanoTime();
        Long sid = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("7000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                // 틱이 평가하지 않게 정지 상태로 둔다 — 체결 회계는 세션 상태와 무관하게 돈다.
                .status("STOPPED").scanState("POSITION_MONITORING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build()).getId();
        sessions.add(sid);
        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300.00000000")).avgPrice(new BigDecimal("300.00000000"))
                .size(BigDecimal.ZERO).investedKrw(new BigDecimal("3000.00000000"))
                .status("OPEN").sessionKind("DYNAMIC").sessionId(sid)
                .openedAt(Instant.now()).build());
        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000.00000000"))
                .reservedKrw(new BigDecimal("3000.00000000"))
                .state("SUBMITTED")
                .sessionKind("DYNAMIC").sessionId(sid).positionId(pos.getId())
                .exchangeOrderId(uuid)
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L).settlementFinal(Boolean.FALSE).settlementAttempts(0)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());
        return new Fixture(sid, pos.getId(), order.getId(), uuid);
    }

    private static OrderResponse response(String uuid, String state) {
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

    private void advancePastBackoff(Long orderId) {
        OrderEntity o = orderRepository.findById(orderId).orElseThrow();
        o.setSettlementNextCheckAt(Instant.now().minusSeconds(1));
        orderRepository.save(o);
    }

    private String snapshot(Fixture f) {
        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        PositionEntity p = positionRepository.findById(f.positionId()).orElseThrow();
        DynamicSessionEntity s = dynamicRepo.findById(f.sessionId()).orElseThrow();
        return String.format("order{state=%s filled=%s appFunds=%s appFee=%s src=%s ver=%s} "
                        + "pos{status=%s size=%s avg=%s fee=%s} session{availableKrw=%s}",
                o.getState(), o.getFilledQuantity(), o.getAppliedExecutedFunds(), o.getAppliedPaidFee(),
                o.getFeeSource(), o.getSettlementVersion(),
                p.getStatus(), p.getSize(), p.getAvgPrice(), p.getPositionFee(), s.getAvailableKrw());
    }

    private void assertAppliedOnce(Fixture f) {
        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        PositionEntity p = positionRepository.findById(f.positionId()).orElseThrow();
        assertThat(dynamicRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("🔴 미사용 = 요청 3000 − 체결 2940 − 수수료 1.47 = 58.53 → 7,058.53. "
                        + "7,060 이면 종전 경로(수수료 미반영)다. %s", snapshot(f))
                .isEqualByComparingTo(new BigDecimal("7058.53"));
        assertThat(o.getState()).isEqualTo("FILLED");
        assertThat(o.getSettlementVersion()).isEqualTo(1L);
        assertThat(o.getFillAccountingPending()).isFalse();
        assertThat(p.getSize()).isEqualByComparingTo(new BigDecimal("9.80000000"));
        assertThat(p.getAvgPrice()).as("평균단가 = 실제 사용액 ÷ 수량").isEqualByComparingTo(new BigDecimal("300"));
        assertThat(p.getPositionFee()).as("수수료는 거래소 보고값").isEqualByComparingTo(new BigDecimal("1.47"));
    }

    private void fillThenRepeat(String exchangeState) {
        Fixture f = marketBuy3000();
        Mockito.doReturn(response(f.uuid(), exchangeState)).when(upbitOrderClient).getOrder(f.uuid());

        engine.checkOrderStatus(f.orderId());
        System.out.println("[DYNAMIC 체결 회계 " + exchangeState + "] " + snapshot(f));
        assertAppliedOnce(f);

        String after = snapshot(f);
        for (int i = 0; i < 3; i++) {
            advancePastBackoff(f.orderId());
            engine.checkOrderStatus(f.orderId());
        }
        assertThat(snapshot(f)).as("반복 조회에서 값이 바뀌면 중복 반영이다").isEqualTo(after);
    }

    @Test
    @DisplayName("전량 체결 — 수수료 포함 미사용액 58.53 이 한 번 복원된다")
    void fullFillRestoresUnusedNetOfFee() {
        fillThenRepeat("done");
    }

    @Test
    @DisplayName("체결 후 취소 — 같은 경로, 같은 금액")
    void cancelWithFillRestoresUnusedNetOfFee() {
        fillThenRepeat("cancel");
    }

    private void runFailThenRecover(boolean failBeforeBalance) {
        Fixture f = marketBuy3000();
        Mockito.doReturn(response(f.uuid(), "done")).when(upbitOrderClient).getOrder(f.uuid());
        String before = snapshot(f);
        AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                if (failBeforeBalance) {
                    throw new IllegalStateException("의도적 실패 — 잔액 변경 전");
                }
                inv.callRealMethod();
                throw new IllegalStateException("의도적 실패 — 잔액 변경 후");
            }
            return inv.callRealMethod();
        }).when(balanceUpdater).applyWithin(Mockito.eq(f.sessionId()), Mockito.any());

        engine.checkOrderStatus(f.orderId());
        assertThat(calls.get()).as("잔액 변경 지점을 지나야 재현이 성립한다").isGreaterThan(0);
        String afterFail = snapshot(f);
        System.out.println("[DYNAMIC 회계 실패 " + (failBeforeBalance ? "전" : "후") + "] 이전=" + before
                + " 실패후=" + afterFail);
        assertThat(afterFail)
                .as("🔴 실패하면 주문 반영값·버전, 잔액, 포지션이 **모두 이전 상태**여야 한다%n  이전: %s%n  실패후: %s",
                        before, afterFail)
                .isEqualTo(before);
        assertThat(orderRepository.findById(f.orderId()).orElseThrow().getFillAccountingPending())
                .as("관측과 대기 표시는 남아 재처리 근거가 된다 — 그동안 신규 매수가 막힌다")
                .isTrue();

        advancePastBackoff(f.orderId());
        engine.checkOrderStatus(f.orderId());
        assertAppliedOnce(f);

        String recovered = snapshot(f);
        for (int i = 0; i < 2; i++) {
            advancePastBackoff(f.orderId());
            engine.checkOrderStatus(f.orderId());
        }
        assertThat(snapshot(f)).as("재처리 뒤 반복에서 중복 반영이 없어야 한다").isEqualTo(recovered);
    }

    @Test
    @DisplayName("잔액 변경 **전** 실패 → 함께 롤백, 재처리에서 한 번")
    void failBeforeBalanceChange() {
        runFailThenRecover(true);
    }

    @Test
    @DisplayName("잔액 변경 **후** 실패 → 함께 롤백, 재처리에서 한 번")
    void failAfterBalanceChange() {
        runFailThenRecover(false);
    }
}
