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
 * 늦게 도착한 체결 정정 — <b>CLOSED 포지션 재개방 금지</b>와 <b>기준가 보존</b> (2026-10-12).
 *
 * <p>거래소 체결과 로컬 커밋 사이에는 간극이 있으므로, 포지션이 이미 청산된 <b>뒤에</b> 더 큰
 * 체결 보고가 도착할 수 있다. 그때 두 가지를 지켜야 한다:</p>
 *
 * <ul>
 *   <li>🔴 <b>수량을 되살리지 않는다.</b> 청산된 포지션이 다시 열리면 평가 루프가 그것을 실제
 *       보유분으로 보고 매매하려 한다 — 거래소에 없는 수량을 팔려 드는 상태가 된다.</li>
 *   <li>🔴 <b>손절·익절 가격을 건드리지 않는다.</b> 그 값은 진입 시점 신호가로 정해졌고 평균단가에서
 *       오지 않는다. 회계 정정이 기준가를 움직이면 사람이 모르는 사이에 청산 조건이 달라진다 —
 *       최초 설정과 늦은 정정은 별도 규칙이다.</li>
 * </ul>
 *
 * <p>손익·수수료는 조정한다. 이미 끝난 거래의 <b>기록</b>이므로 재개방과 무관하다.</p>
 */
@DisplayName("늦은 체결 정정 — CLOSED 재개방 금지·기준가 보존")
class LateFillCorrectionTest extends IntegrationTestBase {

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
     * 1차 체결(9.8 개 / 2,940원 / 수수료 1.47)이 이미 반영된 주문과, 그 뒤 <b>청산까지 끝난</b>
     * 포지션. 손절·익절가가 설정돼 있다 — 정정이 그 값을 건드리는지 보기 위해서다.
     */
    private Fixture alreadyAppliedThenClosed() {
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
                .investedKrw(new BigDecimal("2940.00000000"))
                .positionFee(new BigDecimal("1.47000000"))
                .stopLossPrice(new BigDecimal("285.00000000"))
                .takeProfitPrice(new BigDecimal("330.00000000"))
                .realizedPnl(new BigDecimal("120.00000000"))
                .status("CLOSED").sessionKind("LIVE").sessionId(session.getId())
                .openedAt(Instant.now()).closedAt(Instant.now()).build());

        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3100.00000000"))
                .state("FILLED")
                .filledQuantity(new BigDecimal("9.80000000"))
                .appliedExecutedFunds(new BigDecimal("2940.00000000"))
                .appliedPaidFee(new BigDecimal("1.47000000"))
                .feeSource(OrderExecutionEngine.FEE_SOURCE_REPORTED)
                .settlementVersion(1L)
                .sessionKind("LIVE").sessionId(session.getId()).positionId(pos.getId())
                .exchangeOrderId("uuid-late")
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());

        return new Fixture(session.getId(), pos.getId(), order.getId());
    }

    /** 뒤늦은 보고 — 실제로는 10.0 개 / 3,000원 / 수수료 1.50 이었다. */
    private OrderResponse lateLargerReport() {
        OrderResponse r = new OrderResponse();
        r.setUuid("uuid-late");
        r.setSide("bid");
        r.setOrdType("price");
        r.setState("done");
        r.setMarket("KRW-DOGE");
        r.setVolume(new BigDecimal("3100.00000000"));
        r.setExecutedVolume(new BigDecimal("10.00000000"));
        r.setExecutedFunds(new BigDecimal("3000.00000000"));
        r.setPaidFee(new BigDecimal("1.50000000"));
        return r;
    }

    @Test
    @DisplayName("CLOSED 포지션에는 손익·수수료만 반영하고 수량을 되살리지 않는다")
    void closedPositionIsNotReopened() {
        Fixture f = alreadyAppliedThenClosed();
        Mockito.doReturn(lateLargerReport()).when(upbitOrderClient).getOrder("uuid-late");

        orderExecutionEngine.checkOrderStatus(f.orderId());

        PositionEntity pos = positionRepository.findById(f.positionId()).orElseThrow();
        assertThat(pos.getStatus())
                .as("🔴 재개방하지 않는다 — OPEN 으로 되돌아가면 평가 루프가 없는 수량을 매매하려 한다")
                .isEqualTo("CLOSED");
        assertThat(pos.getSize())
                .as("🔴 수량을 되살리지 않는다")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(pos.getPositionFee())
                .as("수수료는 보고값 1.50 으로 조정된다 — 끝난 거래의 기록이므로 재개방과 무관하다")
                .isEqualByComparingTo(new BigDecimal("1.50000000"));

        assertThat(pos.getStopLossPrice())
                .as("🔴 손절가는 진입 시점 값 그대로여야 한다 — 회계 정정이 청산 조건을 움직여서는 안 된다")
                .isEqualByComparingTo(new BigDecimal("285.00000000"));
        assertThat(pos.getTakeProfitPrice())
                .as("🔴 익절가도 그대로여야 한다")
                .isEqualByComparingTo(new BigDecimal("330.00000000"));

        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        assertThat(o.getAppliedExecutedFunds())
                .as("주문 반영값은 보고값까지 올라간다")
                .isEqualByComparingTo(new BigDecimal("3000.00000000"));
        assertThat(o.getFilledQuantity())
                .as("주문의 체결 수량은 보고값을 따른다 — 포지션 수량과 달라지는 것이 정상이다")
                .isEqualByComparingTo(new BigDecimal("10.00000000"));
        assertThat(o.getFillAccountingPending())
                .as("반영을 마쳤으면 대기 표시가 해제된다")
                .isFalse();

        // 잔액: 1차에서 3100 − 2940 − 1.47 = 158.53 을 복원했다. 정정 후 복원액은
        // 3100 − 3000 − 1.50 = 98.50 이므로 차분 −60.03 이 반영돼야 한다.
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("복원액 차분만 반영된다 (7000 − 60.03)")
                .isEqualByComparingTo(new BigDecimal("6939.97"));
    }
}
