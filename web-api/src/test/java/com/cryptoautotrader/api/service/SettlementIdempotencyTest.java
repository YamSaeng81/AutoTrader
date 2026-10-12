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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산 멱등성 — 설계안 §5.1 의 시험 17·18·19 (2026-10-07).
 *
 * <p>근거 문서: [`UNKNOWN_ORDER_STATE_DESIGN.md`](../../../../../../../docs/UNKNOWN_ORDER_STATE_DESIGN.md)
 * §5.1(정산 버전 가드 + 최신성 규칙).</p>
 *
 * <h3>세 가지를 본다</h3>
 * <ol>
 *   <li><b>17 — 같은 응답 반복</b>: 추가 정산 0</li>
 *   <li><b>18 — 오래된 응답의 뒤늦은 도착</b>: 상태·수량 역행 0</li>
 *   <li><b>19 — 수량 불변 · 수수료/체결금액 변경</b>: 정확히 한 번 반영</li>
 * </ol>
 *
 * <p>🔴 <b>주문 행만 보지 않는다.</b> 세 시험 모두 <b>세션 잔액</b>과 <b>포지션 수량</b>까지 확인한다 —
 * 정산의 결과가 그 둘에 남기 때문이다.</p>
 *
 * <p>🔴 <b>기존 코드가 실패해야 한다고 가정하지 않는다.</b> 돌려서 실제 결과를 보고, 통과하면 왜
 * 통과하는지 적는다. 아래 각 시험의 주석에 측정 결과를 남긴다.</p>
 */
@DisplayName("정산 멱등성 — 반복·역행·수수료 변경 (설계안 §5.1)")
class SettlementIdempotencyTest extends IntegrationTestBase {

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

    /** `checkOrderStatus` 는 이것이 null 이면 아무 일도 하지 않고 돌아간다. */
    @MockBean
    private UpbitRestClient upbitRestClient;

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

    private Fixture submittedBuy() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("7000.00"))
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
                .coinPair("KRW-DOGE").side("BUY").orderType("LIMIT")
                .quantity(new BigDecimal("3000.00000000"))
                .state("SUBMITTED")
                .sessionKind("LIVE").sessionId(session.getId()).positionId(pos.getId())
                .exchangeOrderId("uuid-settle")
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());

        return new Fixture(session.getId(), pos.getId(), order.getId());
    }

    /** 전량 체결 응답 — 체결가 산출 값까지 채운다(없으면 체결 처리에서 NPE 가 난다). */
    private OrderResponse filled(String funds, String fee) {
        OrderResponse r = new OrderResponse();
        r.setUuid("uuid-settle");
        r.setSide("bid");
        r.setOrdType("limit");
        r.setState("done");
        r.setMarket("KRW-DOGE");
        r.setVolume(new BigDecimal("10.00000000"));
        r.setExecutedVolume(new BigDecimal("10.00000000"));
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPrice(new BigDecimal("300.00000000"));
        r.setPaidFee(new BigDecimal(fee));
        return r;
    }

    /**
     * 백오프 시간이 지난 것처럼 만든다.
     *
     * <p>🔴 정산은 반영할 때마다 {@code settlement_next_check_at} 을 미래로 미룬다(백오프) — 5초
     * 폴러를 그대로 때리지 않기 위한 생산 동작이다. 이 시험은 <b>백오프 타이밍이 아니라 차분 반영</b>을
     * 보므로, 시각을 되돌려 "시간이 지났다" 를 표현한다. 실제 시간을 기다리지 않는다.</p>
     */
    private void advancePastBackoff(Long orderId) {
        OrderEntity o = orderRepository.findById(orderId).orElseThrow();
        o.setSettlementNextCheckAt(Instant.now().minusSeconds(1));
        orderRepository.save(o);
    }

    private String snapshot(Fixture f) {
        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        PositionEntity p = positionRepository.findById(f.positionId()).orElseThrow();
        LiveTradingSessionEntity s = sessionRepository.findById(f.sessionId()).orElseThrow();
        return String.format("order{state=%s filledQty=%s price=%s execFunds=%s "
                        + "repFunds=%s repFee=%s appFunds=%s appFee=%s src=%s ver=%s} "
                        + "pos{status=%s size=%s avg=%s fee=%s} session{availableKrw=%s totalAsset=%s}",
                o.getState(), o.getFilledQuantity(), o.getPrice(), o.getExecutedFunds(),
                o.getReportedExecutedFunds(), o.getReportedPaidFee(),
                o.getAppliedExecutedFunds(), o.getAppliedPaidFee(),
                o.getFeeSource(), o.getSettlementVersion(),
                p.getStatus(), p.getSize(), p.getAvgPrice(), p.getPositionFee(),
                s.getAvailableKrw(), s.getTotalAssetKrw());
    }

    // ── 원본 보존 (V81 의 첫 단계) ────────────────────────────────────────

    @Test
    @DisplayName("원본 보존 — 정상 전량 체결에서도 거래소 보고 체결금액·수수료가 저장된다")
    void reportedValuesArePersistedOnNormalFill() {
        Fixture f = submittedBuy();
        Mockito.doReturn(filled("2950.00000000", "1.47500000"))
                .when(upbitOrderClient).getOrder("uuid-settle");

        orderExecutionEngine.checkOrderStatus(f.orderId());

        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        assertThat(o.getReportedExecutedFunds())
                .as("거래소가 보고한 체결금액이 보존돼야 한다 — 종전에는 정상 체결에서 저장되지 않았다%n  %s",
                        snapshot(f))
                .isEqualByComparingTo(new BigDecimal("2950.00000000"));
        assertThat(o.getReportedPaidFee())
                .as("거래소가 보고한 수수료가 보존돼야 한다 — 종전에는 저장할 컬럼조차 없었다")
                .isEqualByComparingTo(new BigDecimal("1.47500000"));

        // 🔴 2026-10-12: 회계가 연결됐으므로 반영값도 채워진다(이 시험은 1단계 기준으로 쓰였다).
        assertThat(o.getAppliedPaidFee())
                .as("차분 정산이 연결된 뒤에는 반영값이 거래소 보고값으로 채워진다")
                .isEqualByComparingTo(new BigDecimal("1.47500000"));
        assertThat(o.getFeeSource())
                .as("보고값이 모두 있으면 출처가 REPORTED 로 올라간다")
                .isEqualTo(OrderExecutionEngine.FEE_SOURCE_REPORTED);
        assertThat(o.getSettlementFinal())
                .as("🔴 반영한 회차에서는 확정하지 않는다 — done 이후에도 상세가 채워질 수 있다")
                .isFalse();
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getPositionFee())
                .as("포지션 수수료는 아직 자체 계산값(3000 × 0.0005 = 1.50)이다 — 포지션 쪽 전환은 미착수")
                .isEqualByComparingTo(new BigDecimal("1.50"));
    }

    // ── 17 ────────────────────────────────────────────────────────────────

    /**
     * 🟢 <b>통과 (2026-10-07). 통과하는 이유를 적는다</b> — 설계가 의도한 가드가 있어서가 아니다.
     * {@code syncOrderState} 가 {@code currentState == newState} 이면 <b>조기 반환</b>하고,
     * {@code checkOrderStatus} 가 종결 주문({@code !ACTIVE_STATES})이면 아예 조회하지 않는다.
     * 즉 <b>"같은 상태면 아무것도 하지 않는다" 는 성질이 중복을 막고 있다.</b>
     * 🔴 그 성질이 시험 19 에서는 <b>유효한 갱신까지 막는다</b> — 같은 메커니즘의 양면이다.
     */
    @Test
    @DisplayName("17 — 같은 응답을 반복 반영해도 추가 정산이 없다")
    void repeatedIdenticalResponseAppliesOnce() {
        Fixture f = submittedBuy();
        Mockito.doReturn(filled("3000.00000000", "1.50000000"))
                .when(upbitOrderClient).getOrder("uuid-settle");

        orderExecutionEngine.checkOrderStatus(f.orderId());
        String afterFirst = snapshot(f);

        advancePastBackoff(f.orderId());
        orderExecutionEngine.checkOrderStatus(f.orderId());
        advancePastBackoff(f.orderId());
        orderExecutionEngine.checkOrderStatus(f.orderId());
        String afterRepeat = snapshot(f);

        assertThat(afterRepeat)
                .as("같은 응답을 세 번 반영했는데 주문·포지션·잔액이 달라지면 중복 정산이다%n"
                        + "  1회차: %s%n  반복후: %s", afterFirst, afterRepeat)
                .isEqualTo(afterFirst);
    }

    // ── 18 ────────────────────────────────────────────────────────────────

    /**
     * 🟢 <b>통과 (2026-10-07). 예상과 달랐으므로 이유를 적는다.</b> 설계안을 쓸 때는 실패할 것으로
     * 봤다 — {@code syncOrderState} 는 {@code FILLED → SUBMITTED} 를 "변경" 으로 보고 진행하기
     * 때문이다. 실제로는 그 앞에서 막힌다: {@code checkOrderStatus} 가
     * {@code !ACTIVE_STATES.contains(order.getState())} 이면 <b>조회조차 하지 않고 돌아간다.</b>
     * 🔴 다만 이것은 "최신성 규칙이 있다" 는 뜻이 아니라 <b>종결 주문을 더 보지 않는다</b> 는 뜻이다 —
     * 그래서 종결 후의 <b>정산 정정도 반영할 수 없다</b>(시험 19 와 같은 뿌리).
     * 🔴 또한 이 시험은 {@code checkOrderStatus} 경로만 덮는다. {@code syncOrderState} 를 직접 부르는
     * 다른 경로(예: {@code cancelOrder} 의 체결 확인 분기)에는 그 가드가 없다.
     */
    @Test
    @DisplayName("18 — 종결 뒤 도착한 활성 응답이 상태·수량을 되돌리지 않는다")
    void staleActiveResponseMustNotRegress() {
        Fixture f = submittedBuy();
        Mockito.doReturn(filled("3000.00000000", "1.50000000"))
                .when(upbitOrderClient).getOrder("uuid-settle");
        orderExecutionEngine.checkOrderStatus(f.orderId());
        String afterFilled = snapshot(f);

        // 뒤늦게 도착한 오래된 응답 — 아직 대기 중이고 체결량도 0 이라고 말한다
        OrderResponse stale = new OrderResponse();
        stale.setUuid("uuid-settle");
        stale.setSide("bid");
        stale.setOrdType("limit");
        stale.setState("wait");
        stale.setMarket("KRW-DOGE");
        stale.setVolume(new BigDecimal("10.00000000"));
        stale.setExecutedVolume(BigDecimal.ZERO);
        Mockito.doReturn(stale).when(upbitOrderClient).getOrder("uuid-settle");

        // 🔴 백오프를 지나게 한다 — 그러지 않으면 조회 자체가 일어나지 않아 "역행하지 않았다" 가
        //    아무것도 증명하지 않는다.
        advancePastBackoff(f.orderId());
        orderExecutionEngine.checkOrderStatus(f.orderId());

        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        assertThat(o.getState())
                .as("종결된 주문이 활성으로 되돌아가면 폴러가 다시 쫓고 정산이 꼬인다%n  체결후: %s%n  역행후: %s",
                        afterFilled, snapshot(f))
                .isEqualTo("FILLED");
        assertThat(o.getFilledQuantity())
                .as("누적 체결량이 줄어들면 안 된다")
                .isEqualByComparingTo(new BigDecimal("10.00000000"));
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getSize())
                .as("포지션 수량이 줄어들면 안 된다")
                .isEqualByComparingTo(new BigDecimal("10.00000000"));
    }

    // ── 확정 조건 회귀 ────────────────────────────────────────────────────

    /**
     * 🔴 회귀 — <b>첫 응답 → 동일 응답 → 늦은 수수료 증가</b>에서 마지막 갱신을 놓치지 않는다.
     *
     * <p>이 순서가 확정 조건을 두 번 틀리게 만든 자리다. ① "종결 상태 + 값이 다 있음" 으로 확정하면
     * 1회차에서 끝나 2회차를 놓친다. ② "종결 상태 + 차분 없음이 한 번 확인됨" 으로 확정하면
     * <b>2회차(동일 응답)에서 확정</b>되어 3회차의 수수료 증가를 놓친다 — <b>같은 값이 한 번 더
     * 왔다는 사실은 이후 변경이 없다는 증거가 아니다.</b></p>
     *
     * <p>🔴 그래서 자동 확정을 두지 않는다. <b>백오프와 확정은 별개</b>이며, 차분이 없다는 이유로
     * 재조회를 영구히 멈추지 않는다.</p>
     */
    @Test
    @DisplayName("확정 회귀 — 동일 응답을 거쳐도 늦게 도착한 수수료 증가가 반영된다")
    void lateFeeIncreaseAfterIdenticalResponseIsStillApplied() {
        Fixture f = submittedBuy();

        // 1회차: 체결 확인 (수수료 1.00)
        Mockito.doReturn(filled("3000.00000000", "1.00000000"))
                .when(upbitOrderClient).getOrder("uuid-settle");
        orderExecutionEngine.checkOrderStatus(f.orderId());
        OrderEntity afterFirst = orderRepository.findById(f.orderId()).orElseThrow();
        assertThat(afterFirst.getAppliedPaidFee()).isEqualByComparingTo(new BigDecimal("1.00000000"));
        assertThat(afterFirst.getSettlementFinal())
                .as("🔴 반영한 회차에서 확정하면 뒤따르는 정정을 놓친다")
                .isFalse();

        // 2회차: **같은 응답** — 차분이 없다. 여기서 확정해 버리면 3회차를 놓친다.
        advancePastBackoff(f.orderId());
        orderExecutionEngine.checkOrderStatus(f.orderId());
        assertThat(orderRepository.findById(f.orderId()).orElseThrow().getSettlementFinal())
                .as("🔴 '같은 값이 한 번 더 왔다' 는 이후 변경이 없다는 증거가 아니다 — 확정하면 안 된다")
                .isFalse();

        // 3회차: 늦게 도착한 수수료 증가 (1.00 → 1.50)
        Mockito.doReturn(filled("3000.00000000", "1.50000000"))
                .when(upbitOrderClient).getOrder("uuid-settle");
        advancePastBackoff(f.orderId());
        orderExecutionEngine.checkOrderStatus(f.orderId());

        OrderEntity afterLate = orderRepository.findById(f.orderId()).orElseThrow();
        assertThat(afterLate.getAppliedPaidFee())
                .as("동일 응답을 거쳐도 늦게 온 수수료 증가는 반영돼야 한다")
                .isEqualByComparingTo(new BigDecimal("1.50000000"));
        assertThat(afterLate.getSettlementVersion())
                .as("반영이 일어난 회차만 버전이 오른다 — 1회차·3회차 두 번")
                .isEqualTo(2L);
    }

    // ── 19 ────────────────────────────────────────────────────────────────

    /**
     * 🔴 <b>실패 (2026-10-07) — 배포 차단 항목. 보존한다.</b>
     *
     * <p>측정 결과: 1회차와 2회차가 완전히 같다 —
     * {@code order{state=FILLED filledQty=10 price=300 execFunds=null} pos{size=10 fee=1.50}
     * session{availableKrw=7000}}. 즉 <b>체결금액·수수료가 늘어난 응답이 전혀 반영되지 않는다.</b></p>
     *
     * <p>원인은 두 겹이다:</p>
     * <ol>
     *   <li><b>조기 반환이 두 번</b> — {@code checkOrderStatus} 는 종결 주문을 조회하지 않고,
     *       {@code syncOrderState} 는 같은 상태면 반환한다. 시험 17·18 을 통과시키는 바로 그
     *       성질이 여기서는 유효한 갱신을 막는다.</li>
     *   <li>🔴 <b>저장할 자리가 없다</b> — 응답의 {@code executedFunds} 는 주문 행에 필드가 있는데도
     *       <b>채우지 않는다</b>({@code execFunds=null}). 수수료는 주문 엔티티에 필드가 없고,
     *       포지션의 {@code position_fee} 에 남는 값은 <b>거래소가 보고한 {@code paidFee}(1.00)가
     *       아니라 우리가 계산한 값(1.50 = 3000 × 0.0005)</b>이다.</li>
     * </ol>
     *
     * <p>🔴 <b>두 번째가 설계에 영향을 준다.</b> 설계안 §5.1 은 "이미 반영한 누적 체결량·금액·수수료"
     * 를 보존해 차분을 반영하라고 했는데, <b>지금은 누적 금액·수수료를 거래소 보고값으로 보존하지
     * 않는다.</b> 그러므로 정산 버전 가드만 추가해서는 이 시험이 통과하지 않는다 — <b>보존할 필드와
     * 거래소 보고값을 쓰는 경로가 함께 필요하다.</b></p>
     */
    @Test
    @DisplayName("19 — 수량은 같고 체결금액·수수료만 늘어난 응답이 정확히 한 번 반영된다")
    void feeOnlyIncreaseAppliedExactlyOnce() {
        Fixture f = submittedBuy();
        // 1회차: trades 미정산 — 체결금액이 작게 보고된다
        Mockito.doReturn(filled("2900.00000000", "1.00000000"))
                .when(upbitOrderClient).getOrder("uuid-settle");
        orderExecutionEngine.checkOrderStatus(f.orderId());
        String afterPartialSettle = snapshot(f);

        // 2회차: 정산이 끝나 체결금액·수수료가 채워진다 (수량은 그대로)
        Mockito.doReturn(filled("3000.00000000", "1.50000000"))
                .when(upbitOrderClient).getOrder("uuid-settle");
        advancePastBackoff(f.orderId());
        orderExecutionEngine.checkOrderStatus(f.orderId());
        String afterFullSettle = snapshot(f);

        // 3회차: 같은 응답을 또 받아도 더 반영되지 않아야 한다
        advancePastBackoff(f.orderId());
        orderExecutionEngine.checkOrderStatus(f.orderId());
        String afterRepeat = snapshot(f);

        assertThat(afterFullSettle)
                .as("수량이 같아도 체결금액·수수료 갱신은 반영돼야 한다%n  1회차: %s%n  2회차: %s",
                        afterPartialSettle, afterFullSettle)
                .isNotEqualTo(afterPartialSettle);
        assertThat(afterRepeat)
                .as("반영은 한 번이어야 한다 — 3회차에서 또 달라지면 중복이다%n  2회차: %s%n  3회차: %s",
                        afterFullSettle, afterRepeat)
                .isEqualTo(afterFullSettle);
    }
}
