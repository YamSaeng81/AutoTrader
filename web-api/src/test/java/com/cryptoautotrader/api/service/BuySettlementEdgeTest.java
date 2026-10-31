package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.OrderSettlementDiscrepancyEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.OrderSettlementDiscrepancyRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매수 정산의 남은 시험 — {@code UNKNOWN_ORDER_STATE_DESIGN.md} §7 의 12 · 22 · 22-b (선행 조건 5-i, 2026-10-15).
 *
 * <p>각 시험은 주문 행만 보지 않고 <b>세션 잔액·포지션</b>까지 본다. 시작 상태: 세션 10,000 에서 시장가 매수 3,000 을 예약해
 * 잔액 7,000, 포지션 수량 0, 주문 {@code SUBMITTED}.</p>
 */
@DisplayName("매수 정산 — 부분 체결 재폴링 · 체결량 감소 · 수수료 감소")
class BuySettlementEdgeTest extends IntegrationTestBase {

    @Autowired private OrderSettlementService settlement;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderSettlementDiscrepancyRepository discrepancyRepository;

    @Autowired private OrderExecutionEngine orderExecutionEngine;

    @MockBean private UpbitOrderClient upbitOrderClient;
    /** {@code checkOrderStatus} 는 이것이 null 이면 아무 일도 하지 않고 돌아간다. */
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitRestClient upbitRestClient;

    private final List<Long> sessions = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long id : sessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(o -> {
                discrepancyRepository.findByOrderIdOrderByDetectedAtDesc(o.getId()).forEach(discrepancyRepository::delete);
                orderRepository.delete(o);
            });
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
    }

    private record Fixture(Long sessionId, Long positionId, Long orderId) {}

    private Fixture submittedBuy() {
        Long sid = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair("KRW-BSE").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                .totalAssetKrw(new BigDecimal("10000")).status("RUNNING").startedAt(Instant.now()).build()).getId();
        sessions.add(sid);
        PositionEntity p = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-BSE").side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(BigDecimal.ZERO).investedKrw(new BigDecimal("3000")).entryAllocationKrw(new BigDecimal("3000"))
                .status("OPEN").sessionKind("LIVE").sessionId(sid).openedAt(Instant.now()).build());
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-BSE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000")).reservedKrw(new BigDecimal("3000")).state("SUBMITTED")
                .sessionKind("LIVE").sessionId(sid).positionId(p.getId())
                .exchangeOrderId("bse-" + UUID.randomUUID()).clientOrderId("bse-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());
        return new Fixture(sid, p.getId(), o.getId());
    }

    private static OrderResponse response(String state, String volume, String funds, String fee) {
        OrderResponse r = new OrderResponse();
        r.setUuid("bse");
        r.setSide("bid");
        r.setOrdType("price");
        r.setState(state);
        r.setMarket("KRW-BSE");
        r.setPrice(new BigDecimal("3000"));
        r.setExecutedVolume(new BigDecimal(volume));
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPaidFee(fee == null ? null : new BigDecimal(fee));
        return r;
    }

    private BigDecimal available(Fixture f) {
        return liveRepo.findById(f.sessionId()).orElseThrow().getAvailableKrw();
    }

    private PositionEntity position(Fixture f) {
        return positionRepository.findById(f.positionId()).orElseThrow();
    }

    private OrderEntity order(Fixture f) {
        return orderRepository.findById(f.orderId()).orElseThrow();
    }

    private List<OrderSettlementDiscrepancyEntity> discrepancies(Fixture f) {
        return discrepancyRepository.findByOrderIdOrderByDetectedAtDesc(f.orderId());
    }

    /** 폴러가 부르는 경로 그대로 — 거래소 조회 응답을 정하고 {@code checkOrderStatus} 를 부른다. */
    private void poll(Fixture f, OrderResponse r) throws Exception {
        r.setUuid(order(f).getExchangeOrderId());
        Mockito.doReturn(r).when(upbitOrderClient).getOrder(order(f).getExchangeOrderId());
        orderExecutionEngine.checkOrderStatus(f.orderId());
    }

    @Test
    @DisplayName("12 — 부분 체결이 늘어나는 동안 같은 응답을 반복해 폴링해도 차분만 반영된다(누적이 두 번 더해지지 않는다)")
    void partialFillIncreasingWithRepolls() throws Exception {
        Fixture f = submittedBuy();

        // ① 5 개 / 1,500 / 0.75 — 두 번. 활성 주문의 부분 체결은 상태 동기화(변화 없음) + 정산 경로로 간다.
        poll(f, response("wait", "5", "1500", "0.75"));
        BigDecimal a1 = available(f);
        poll(f, response("wait", "5", "1500", "0.75"));
        assertThat(order(f).getState()).as("아직 활성").isEqualTo("SUBMITTED");
        assertThat(available(f)).as("같은 응답 반복 — 차분 0").isEqualByComparingTo(a1);
        assertThat(order(f).getAppliedExecutedFunds()).isEqualByComparingTo("1500");
        // 🔴 관측: 활성 주문인데 미체결분(3,000 − 1,500 − 0.75 = 1,499.25)이 세션 잔액에 먼저 복원된다(정산 경로의 미사용 공식).
        //    공유 풀·계좌 여력은 이 주문의 예약 3,000 을 계속 세므로(활성) 실자금 과대 승인은 아니다 — 종결에서 바로잡힌다.
        assertThat(a1).isEqualByComparingTo("8499.25");

        // ② 7 개 / 2,100 / 1.05 — 두 번
        poll(f, response("wait", "7", "2100", "1.05"));
        BigDecimal a2 = available(f);
        poll(f, response("wait", "7", "2100", "1.05"));
        assertThat(available(f)).isEqualByComparingTo(a2).isEqualByComparingTo("7898.95");   // 7,000 + (3,000 − 2,100 − 1.05)
        assertThat(order(f).getAppliedExecutedFunds()).as("누적값, 1,500 + 2,100 이 아니다").isEqualByComparingTo("2100");

        // ③ 전량 9.8 개 / 2,940 / 1.47 (done) — 세 번. 종결 응답은 원자 경로(주문·잔액·포지션 한 트랜잭션)로 간다.
        poll(f, response("done", "9.8", "2940", "1.47"));
        poll(f, response("done", "9.8", "2940", "1.47"));
        orderExecutionEngine.checkOrderStatus(f.orderId());   // 종결 뒤에는 조회조차 하지 않는다(18)
        assertThat(order(f).getState()).isEqualTo("FILLED");
        assertThat(position(f).getSize()).isEqualByComparingTo("9.8");
        assertThat(position(f).getAvgPrice()).isEqualByComparingTo("300");
        assertThat(position(f).getInvestedKrw()).isEqualByComparingTo("2941.47");
        assertThat(order(f).getFilledQuantity()).isEqualByComparingTo("9.8");
        assertThat(order(f).getAppliedExecutedFunds()).isEqualByComparingTo("2940");
        assertThat(order(f).getAppliedPaidFee()).isEqualByComparingTo("1.47");
        // 미사용 = 3,000 − 2,940 − 1.47 = 58.53 — 중간 복원이 모두 되돌려지고 한 번만 남는다
        assertThat(available(f)).isEqualByComparingTo("7058.53");
        assertThat(discrepancies(f)).isEmpty();
    }

    @Test
    @DisplayName("22 — 활성 주문에 누적 체결이 줄어든 응답이 오면 반영하지 않고 불일치로 남긴다(수량·잔액 역행 0)")
    void volumeDecreaseOnActiveOrderIsRejected() throws Exception {
        Fixture f = submittedBuy();
        poll(f, response("wait", "7", "2100", "1.05"));
        BigDecimal availBefore = available(f);
        assertThat(order(f).getState()).as("아직 활성").isEqualTo("SUBMITTED");

        // 오래된 응답 — 5 개 / 1,500 / 0.75
        poll(f, response("wait", "5", "1500", "0.75"));
        assertThat(order(f).getState()).isEqualTo("SUBMITTED");
        assertThat(order(f).getAppliedExecutedFunds()).as("반영값 역행 0").isEqualByComparingTo("2100");
        assertThat(order(f).getAppliedPaidFee()).isEqualByComparingTo("1.05");
        assertThat(available(f)).as("잔액 역행 0").isEqualByComparingTo(availBefore);
        assertThat(position(f).getSize()).as("포지션 수량 역행 0").isEqualByComparingTo("0");
        List<OrderSettlementDiscrepancyEntity> d = discrepancies(f);
        assertThat(d).as("불일치로 남는다").hasSize(1);
        assertThat(d.get(0).getKind()).isIn(OrderSettlementDiscrepancyEntity.KIND_VOLUME_DECREASE,
                OrderSettlementDiscrepancyEntity.KIND_FUNDS_DECREASE);

        // 정상 응답이 이어지면 그대로 진행한다 — 감소 응답이 주문을 막아 두지 않는다
        poll(f, response("done", "9.8", "2940", "1.47"));
        assertThat(order(f).getState()).isEqualTo("FILLED");
        assertThat(position(f).getSize()).isEqualByComparingTo("9.8");
        assertThat(available(f)).isEqualByComparingTo("7058.53");
    }

    @Test
    @DisplayName("22-b — 매수: 수량 같은데 보고 수수료가 줄어든 응답은 자동 반영하지 않고, 폐기하지도 않고 불일치로 보존한다")
    void buyFeeDecreaseIsPreservedAsDiscrepancy() {
        Fixture f = submittedBuy();
        settlement.observeAndApplyBuyFill(f.orderId(), response("done", "9.8", "2940", "1.47"));
        assertThat(order(f).getFeeSource()).isEqualTo(OrderExecutionEngine.FEE_SOURCE_REPORTED);
        assertThat(available(f)).isEqualByComparingTo("7058.53");

        // 같은 수량·금액, 수수료 1.47 → 1.20
        OrderSettlementService.Outcome out = settlement.settle(f.orderId(), response("done", "9.8", "2940", "1.20"));
        assertThat(out).isEqualTo(OrderSettlementService.Outcome.DISCREPANCY);
        assertThat(order(f).getAppliedPaidFee()).as("자동 반영 0").isEqualByComparingTo("1.47");
        assertThat(available(f)).as("잔액 불변(감소분 0.27 을 돌려주지 않는다)").isEqualByComparingTo("7058.53");
        assertThat(position(f).getInvestedKrw()).isEqualByComparingTo("2941.47");
        List<OrderSettlementDiscrepancyEntity> d = discrepancies(f);
        assertThat(d).as("폐기되지 않고 이전 값·수신 값이 함께 남는다").hasSize(1);
        assertThat(d.get(0).getKind()).isEqualTo(OrderSettlementDiscrepancyEntity.KIND_FEE_DECREASE);
        assertThat(d.get(0).getPreviousValue()).isEqualByComparingTo("1.47");
        assertThat(d.get(0).getReceivedValue()).isEqualByComparingTo("1.20");
        assertThat(d.get(0).getResolvedAt()).as("사람이 확인하기 전까지 미해결").isNull();

        // 폴링 경로(관측 → 회계)로 같은 감소가 와도 같다
        settlement.observeAndApplyBuyFill(f.orderId(), response("done", "9.8", "2940", "1.20"));
        assertThat(order(f).getAppliedPaidFee()).isEqualByComparingTo("1.47");
        assertThat(available(f)).isEqualByComparingTo("7058.53");
        assertThat(discrepancies(f)).hasSize(2).allSatisfy(x ->
                assertThat(x.getKind()).isEqualTo(OrderSettlementDiscrepancyEntity.KIND_FEE_DECREASE));
    }

    /**
     * 22-c — 반올림 누적 기록 (리뷰 2026-10-31 "0.005원 차이"). 세션 잔액 열은 DECIMAL(20,2), 주문 반영값은 8자리다.
     * 같은 주문의 수수료가 0.001원씩 늘어나는 정정을 10번 반영하고, 회차마다 세 값을 따로 본다.
     *
     * <ul>
     *   <li>원시 장부 = 예약 뒤 잔액 7,000 + (요청 3,000 − 반영 대금 − 반영 수수료) — 주문 행의 반영값으로 다시 계산한 값</li>
     *   <li>저장 잔액 = 세션 행 {@code available_krw}</li>
     *   <li>저장 규칙 예측 = 직전 저장 잔액 + 이번 정정 Δ 를 둘째 자리에서 반올림(HALF_UP)한 값 — 이 시험이 따로 계산한다</li>
     * </ul>
     *
     * <p>🔴 기록이다 — 허용 오차를 두지 않고, 차이가 어떻게 쌓이는지 그대로 단언한다. 이 시험이 통과한다는 것은 "누적된다" 를
     * 확인했다는 뜻이지 그 차이를 받아들였다는 뜻이 아니다(처리는 리뷰 판단, LIMITED_LIVE_TEST_PLAN §5-9 10).</p>
     */
    @Test
    @DisplayName("22-c — 매수: 같은 주문의 수수료를 0.001원씩 10번 정정하면 원시 장부·저장 잔액·차이가 회차마다 어떻게 되는지 기록한다")
    void repeatedSubCentFeeCorrectionsAccumulateRoundingGap() {
        Fixture f = submittedBuy();
        settlement.observeAndApplyBuyFill(f.orderId(), response("done", "9.8", "2940", "1.47"));
        assertThat(order(f).getFeeSource()).isEqualTo(OrderExecutionEngine.FEE_SOURCE_REPORTED);
        assertThat(available(f)).isEqualByComparingTo("7058.53");

        BigDecimal step = new BigDecimal("0.001");
        BigDecimal fee = new BigDecimal("1.47");
        BigDecimal predicted = available(f);
        StringBuilder trace = new StringBuilder("회차 | 반영 수수료 | 원시 장부 | 저장 잔액 | 저장 규칙 예측 | 차이(저장 − 원시)\n");
        for (int i = 1; i <= 10; i++) {
            fee = fee.add(step);
            OrderSettlementService.Outcome out = settlement.settle(f.orderId(), response("done", "9.8", "2940", fee.toPlainString()));
            assertThat(out).as("회차 %d — 수수료 증가 정정은 반영 대상", i).isEqualTo(OrderSettlementService.Outcome.APPLIED);

            OrderEntity o = order(f);
            assertThat(o.getAppliedPaidFee()).as("회차 %d — 주문 반영 수수료", i).isEqualByComparingTo(fee);
            BigDecimal raw = new BigDecimal("7000").add(new BigDecimal("3000"))
                    .subtract(o.getAppliedExecutedFunds()).subtract(o.getAppliedPaidFee());
            predicted = predicted.subtract(step).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal stored = available(f);
            trace.append(String.format("%d | %s | %s | %s | %s | %s%n", i, fee.toPlainString(), raw.toPlainString(),
                    stored.toPlainString(), predicted.toPlainString(), stored.subtract(raw).toPlainString()));
            assertThat(raw).as("회차 %d — 원시 장부", i).isEqualByComparingTo(new BigDecimal("7058.53").subtract(step.multiply(BigDecimal.valueOf(i))));
            assertThat(stored).as("회차 %d — 저장 잔액은 저장 규칙 예측과 같다", i).isEqualByComparingTo(predicted);
        }
        System.out.println("[22-c 반올림 누적]\n" + trace);

        // 10회 뒤: 원시 장부 7,058.52 · 저장 잔액 7,058.53(매 회차 7,058.529 → 7,058.53 으로 정정분이 사라진다) · 차이 0.01
        BigDecimal raw = new BigDecimal("7058.52");
        assertThat(available(f)).as("저장 잔액").isEqualByComparingTo("7058.53");
        assertThat(available(f).subtract(raw)).as("차이 — 회차마다 0.001 씩 쌓인다").isEqualByComparingTo("0.01");
        assertThat(discrepancies(f)).as("증가 정정은 불일치가 아니다").isEmpty();
    }
}
