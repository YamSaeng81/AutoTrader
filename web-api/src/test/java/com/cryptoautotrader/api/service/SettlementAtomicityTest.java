package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
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
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산의 원자성 — <b>중간에서 실패하면 전부 롤백되고, 재처리에서 정확히 한 번 반영된다</b> (2026-10-12).
 *
 * <h3>확인하는 네 가지</h3>
 * <ol>
 *   <li>주문의 조건부 UPDATE 성공 뒤 <b>잔액 변경 전</b>에 예외 → 전부 이전 상태</li>
 *   <li>주문의 조건부 UPDATE 성공 뒤 <b>잔액 변경 후</b>에 예외 → 전부 이전 상태</li>
 *   <li>같은 응답 재처리 → <b>예상 절대금액</b>이 반영</li>
 *   <li>추가 반복 처리 → <b>변화 없음</b></li>
 * </ol>
 *
 * <p>🔴 <b>"반복해도 같다" 는 "옳다" 가 아니다.</b> 그래서 절대금액을 확인한다 — 2026-10-12 에 잔액
 * 조정 방향을 반대로 잡았을 때, 반복 동일성만 보던 시험은 그것을 잡지 못했다.</p>
 *
 * <h3>🔴 원자성의 현재 범위는 주문·잔액까지다</h3>
 *
 * <p>포지션 수수료와 평균단가는 <b>아직 정산 경로에 연결되지 않았다</b>(포지션 수수료는 자체 계산값이
 * 남고 평균단가는 요청액 폴백을 쓴다). 그러므로 이 시험은 <b>"주문·잔액·포지션 정산이 원자적이다" 를
 * 증명하지 않는다</b> — 주문·잔액까지다. 포지션은 <b>변하지 않아야 한다</b>는 것만 확인한다.</p>
 */
@DisplayName("정산 원자성 — 중간 실패와 재처리 (주문·잔액 범위)")
class SettlementAtomicityTest extends IntegrationTestBase {

    @Autowired
    private OrderExecutionEngine orderExecutionEngine;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @MockBean
    private UpbitOrderClient upbitOrderClient;

    @MockBean
    private UpbitRestClient upbitRestClient;

    /** 잔액 변경 지점 — 여기 전·후에 예외를 주입한다. */
    @SpyBean
    private SessionBalanceUpdater balanceUpdater;

    /**
     * 🔴 이 시험은 쓰지 않는다 — {@code DynamicFillAccountingTest} 와 <b>같은 시험 컨텍스트</b>를 공유하려고 둔다
     * (2026-10-13). 목·스파이 조합이 다르면 컨텍스트가 하나 더 생기고, 시험 JVM 힙(512MB)이 캐시된 컨텍스트로
     * 가득 차 전체 실행이 멈췄다(실측: 힙 523/524MB, GC 로 CPU 18분).
     */
    @SpyBean
    private DynamicSessionBalanceUpdater sharedContextDynamicUpdater;

    private final List<Long> createdSessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            orderRepository.findBySessionIdAndStateIn(id,
                    List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED", "FILLED", "CANCELLED", "FAILED"))
                    .forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id)
                    .forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
    }

    private record Fixture(Long sessionId, Long positionId, Long orderId) { }

    /**
     * 요청 3,000원 시장가 매수.
     *
     * <p>🔴 <b>전제를 시험에 고정한다</b>: 세션 잔액에서 <b>정확히 3,000원</b>이 선차감돼 있고
     * (10,000 → 7,000) <b>별도의 수수료 차감은 없다.</b> 거래소가 그 3,000원 안에서 수수료를 뺀다.
     * 이 전제가 깨지면 미사용액 계산(요청액 − 체결금액 − 수수료)도 달라진다.</p>
     */
    private Fixture marketBuy3000() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("7000.00"))     // 10,000 − 3,000, 수수료 별도 차감 없음
                .totalAssetKrw(new BigDecimal("10000.00"))
                .status("RUNNING").startedAt(Instant.now()).build());
        createdSessions.add(session.getId());

        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(BigDecimal.ZERO)
                .investedKrw(new BigDecimal("3000.00000000"))
                .status("OPEN").sessionKind("LIVE").sessionId(session.getId())
                .openedAt(Instant.now()).build());

        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000.00000000"))
                .state("SUBMITTED")
                .sessionKind("LIVE").sessionId(session.getId()).positionId(pos.getId())
                .exchangeOrderId("uuid-atomic")
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());

        return new Fixture(session.getId(), pos.getId(), order.getId());
    }

    /** 전량 체결 — 2,940원 사용, 수수료 1.47. 미사용 58.53. */
    private OrderResponse done2940() {
        OrderResponse r = new OrderResponse();
        r.setUuid("uuid-atomic");
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

    private void advancePastBackoff(Long orderId) {
        OrderEntity o = orderRepository.findById(orderId).orElseThrow();
        o.setSettlementNextCheckAt(Instant.now().minusSeconds(1));
        orderRepository.save(o);
    }

    /**
     * 🔴 <b>정산 범위만</b> 담는다 — 주문의 반영값·버전·확정 표시와 세션 잔액.
     *
     * <p>포지션을 넣지 않는 이유: 같은 {@code checkOrderStatus} 호출 안에서 <b>체결 반영</b>(상태
     * 동기화)이 <b>별도 트랜잭션</b>으로 먼저 돌아 포지션 수량·평균단가·수수료를 바꾼다. 그것은
     * 정산 실패와 무관하게 커밋되므로, 포지션을 스냅샷에 넣으면 <b>정산의 롤백을 확인하는 대신 체결
     * 반영의 결과를 비교</b>하게 된다(처음에 그렇게 써서 시험이 잘못 실패했다).</p>
     */
    /**
     * 🔴 <b>포지션까지 범위에 넣는다</b> (2026-10-12, ⓑ 결정 이후).
     *
     * <p>종전에는 포지션을 뺐다 — 체결 반영이 별도 트랜잭션으로 먼저 커밋돼 비교가 성립하지 않았기
     * 때문이다. <b>그 자체가 중간 상태의 증거였다.</b> 이제 주문·잔액·포지션이 한 트랜잭션이므로
     * 세 대상이 <b>함께</b> 롤백되는지 확인한다.</p>
     */
    private String settlementSnapshot(Fixture f) {
        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        PositionEntity p = positionRepository.findById(f.positionId()).orElseThrow();
        LiveTradingSessionEntity s = sessionRepository.findById(f.sessionId()).orElseThrow();
        return String.format("order{state=%s filledQty=%s appFunds=%s appFee=%s src=%s ver=%s} "
                        + "pos{status=%s size=%s avg=%s fee=%s} session{availableKrw=%s}",
                o.getState(), o.getFilledQuantity(),
                o.getAppliedExecutedFunds(), o.getAppliedPaidFee(), o.getFeeSource(),
                o.getSettlementVersion(),
                p.getStatus(), p.getSize(), p.getAvgPrice(), p.getPositionFee(),
                s.getAvailableKrw());
    }

    /**
     * @param failBeforeBalance true 면 잔액 변경 **전**에, false 면 **후**에 예외를 던진다.
     */
    private void runFailThenRecover(boolean failBeforeBalance) {
        Fixture f = marketBuy3000();
        Mockito.doReturn(done2940()).when(upbitOrderClient).getOrder("uuid-atomic");

        String before = settlementSnapshot(f);
        AtomicInteger calls = new AtomicInteger();

        Mockito.doAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                if (failBeforeBalance) {
                    throw new IllegalStateException("의도적 실패 — 잔액 변경 전");
                }
                Object result = inv.callRealMethod();          // 잔액을 바꾼 뒤
                throw new IllegalStateException("의도적 실패 — 잔액 변경 후");
            }
            return inv.callRealMethod();
        }).when(balanceUpdater).applyWithin(Mockito.anyLong(), Mockito.any());

        // 1회차 — 실패한다. 🔴 호출부가 예외를 삼키므로(정산 실패는 로그만) 여기서 터지지 않는다.
        orderExecutionEngine.checkOrderStatus(f.orderId());

        assertThat(calls.get())
                .as("잔액 변경 지점을 지나야 재현이 성립한다 — 0 이면 시험이 무의미하다")
                .isGreaterThan(0);
        assertThat(settlementSnapshot(f))
                .as("🔴 실패했으면 주문의 반영값·버전·확정 표시와 잔액이 **모두 이전 상태**여야 한다%n"
                        + "  이전: %s%n  실패후: %s", before, settlementSnapshot(f))
                .isEqualTo(before);

        // 2회차 — 같은 응답으로 재처리. 예상 절대금액이 반영돼야 한다.
        advancePastBackoff(f.orderId());
        orderExecutionEngine.checkOrderStatus(f.orderId());

        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        assertThat(o.getAppliedExecutedFunds()).isEqualByComparingTo(new BigDecimal("2940.00000000"));
        assertThat(o.getAppliedPaidFee()).isEqualByComparingTo(new BigDecimal("1.47000000"));
        assertThat(o.getSettlementVersion()).as("반영이 일어난 회차만 버전이 오른다").isEqualTo(1L);
        assertThat(o.getFillAccountingPending())
                .as("🔴 회계를 반영했으면 **대기 표시가 해제**돼야 한다 — 남아 있으면 신규 매수가 "
                        + "영구히 막힌다")
                .isFalse();
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("미사용 58.53 이 한 번 복원돼 7058.53 이어야 한다 "
                        + "(요청 3000 − 체결 2940 − 수수료 1.47)")
                .isEqualByComparingTo(new BigDecimal("7058.53"));

        String afterRecover = settlementSnapshot(f);

        // 3회차·4회차 — 추가 반복에서 변화가 없어야 한다.
        for (int i = 0; i < 2; i++) {
            advancePastBackoff(f.orderId());
            orderExecutionEngine.checkOrderStatus(f.orderId());
        }
        assertThat(settlementSnapshot(f))
                .as("추가 반복에서 값이 달라지면 중복 반영이다%n  복구후: %s%n  반복후: %s",
                        afterRecover, settlementSnapshot(f))
                .isEqualTo(afterRecover);

        // 🟢 포지션도 같은 트랜잭션에서 반영된다 — 거래소 보고값을 쓴다.
        PositionEntity pos = positionRepository.findById(f.positionId()).orElseThrow();
        assertThat(pos.getPositionFee())
                .as("수수료는 거래소 보고값 1.47 이어야 한다 — 자체 계산값 1.50 이 아니다")
                .isEqualByComparingTo(new BigDecimal("1.47000000"));
        assertThat(pos.getAvgPrice())
                .as("평균단가는 실제 사용액 기준 300 이어야 한다 — 요청액 폴백 306.12 가 아니다")
                .isEqualByComparingTo(new BigDecimal("300.00000000"));
        assertThat(pos.getSize())
                .as("체결 수량이 포지션에 반영돼야 한다")
                .isEqualByComparingTo(new BigDecimal("9.80000000"));
    }

    @Test
    @DisplayName("잔액 변경 **전** 실패 → 전부 롤백, 재처리에서 한 번만 반영")
    void failBeforeBalanceChange() {
        runFailThenRecover(true);
    }

    @Test
    @DisplayName("잔액 변경 **후** 실패 → 전부 롤백, 재처리에서 한 번만 반영")
    void failAfterBalanceChange() {
        runFailThenRecover(false);
    }
}
