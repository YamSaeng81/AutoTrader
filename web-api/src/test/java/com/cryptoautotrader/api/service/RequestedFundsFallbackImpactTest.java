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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청액 폴백이 어디까지 전달되는가 — <b>평균단가 · 손절 · 익절 · 실현손익</b> (2026-10-12).
 *
 * <h3>경로 확인 결과</h3>
 *
 * <p>시장가 매수에서 {@code executedFunds} 가 없으면 {@code OrderExecutionEngine} 은
 * {@code quantity}(요청 KRW)로 폴백해 평균단가를 구한다. 그 값이 다음으로 전달된다:</p>
 *
 * <table>
 *   <tr><th>대상</th><th>전달 여부</th><th>근거</th></tr>
 *   <tr><td><b>평균단가</b></td><td>🔴 직접</td>
 *       <td>{@code avgFillPrice = fundsUsed / filledQty}</td></tr>
 *   <tr><td><b>손익률</b></td><td>🔴 직접</td>
 *       <td>{@code pnlPct = (현재가 − avgPrice) / avgPrice} — 평균단가가 과대면 손익률이 과소</td></tr>
 *   <tr><td><b>손절</b></td><td>🟡 <b>폴백 경로에서만</b></td>
 *       <td>{@code stopLossPrice != null} 이면 가격 비교라 영향 없다. <b>null 이면</b>
 *           {@code pnlPct <= −한도} 로 판단하므로 <b>조기 손절</b>이 될 수 있다</td></tr>
 *   <tr><td><b>익절</b></td><td>🟢 영향 없음</td>
 *       <td>{@code takeProfitPrice} 는 <b>진입 시점에 신호가 기준</b>으로 설정되고 평균단가를 쓰지
 *           않는다. 트레일링만 나중에 갱신한다</td></tr>
 *   <tr><td><b>실현손익</b></td><td>🔴 직접</td>
 *       <td>{@code realizedPnl = 매도대금 − 수수료 − (수량 × avgPrice)} — 평균단가가 과대면
 *           <b>실현손익이 과소</b>하게 기록된다</td></tr>
 * </table>
 *
 * <p>🔴 <b>즉 손절·익절가 자체는 평균단가에서 오지 않는다.</b> 그래서 평균단가를 고쳐도 기존
 * 손절·익절가가 자동으로 바뀌지는 않는다 — 그 둘을 <b>별도 규칙</b>으로 다뤄야 한다는 판단과
 * 일치한다. 영향은 ① 손절 <b>폴백</b> 경로 ② 손익률 표시 ③ 실현손익이다.</p>
 */
@DisplayName("요청액 폴백의 전달 범위 — 평균단가·손절·익절·실현손익")
class RequestedFundsFallbackImpactTest extends IntegrationTestBase {

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
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id)
                    .forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id)
                    .forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
    }

    private record Fixture(Long sessionId, Long positionId, Long orderId) { }

    /** 🔴 {@code stopLossPrice} 를 두지 않는다 — 손절 폴백 경로를 보기 위해서다. */
    private Fixture marketBuyWithoutStopLossPrice() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("7000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .stopLossPct(new BigDecimal("5.00"))
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
                .exchangeOrderId("uuid-fallback")
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());

        return new Fixture(session.getId(), pos.getId(), order.getId());
    }

    /** 요청 3,000 중 2,940 사용, 9.8 개 체결, 수수료 1.47 → 실제 평균단가는 300. */
    private OrderResponse done2940() {
        OrderResponse r = new OrderResponse();
        r.setUuid("uuid-fallback");
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

    @Test
    @DisplayName("수정 후 — 평균단가 300, 손익률 0%, 손절 폴백이 올바른 평균단가로 판단한다")
    void propagationAfterFix() {
        Fixture f = marketBuyWithoutStopLossPrice();
        Mockito.doReturn(done2940()).when(upbitOrderClient).getOrder("uuid-fallback");

        orderExecutionEngine.checkOrderStatus(f.orderId());

        PositionEntity pos = positionRepository.findById(f.positionId()).orElseThrow();
        assertThat(pos.getAvgPrice())
                .as("🟢 실제 사용액 기준 — 2940 / 9.8 = 300. 종전에는 요청액 폴백으로 306.12 였다")
                .isEqualByComparingTo(new BigDecimal("300.00000000"));

        // 손익률: 현재가 300 이면 실제로는 0% 인데, 과대 평균단가 때문에 손실로 보인다.
        BigDecimal currentPrice = new BigDecimal("300.00000000");
        BigDecimal pnlPct = currentPrice.subtract(pos.getAvgPrice())
                .divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        assertThat(pnlPct)
                .as("🟢 현재가 300 에서 손익률이 0% 다 — 🔴 **손절 폴백은 수정된 평균단가로 판단한다.** "
                        + "종전에는 −2% 로 보여 조기 손절을 유발할 수 있었다")
                .isEqualByComparingTo(BigDecimal.ZERO.setScale(6));

        assertThat(pos.getTakeProfitPrice())
                .as("🟢 익절가는 평균단가에서 오지 않는다 — 진입 시점 설정이며 이 경로의 영향을 받지 않는다")
                .isNull();
        assertThat(pos.getStopLossPrice())
                .as("🟢 손절가도 평균단가에서 오지 않는다. 🔴 다만 null 이면 손익률 기반 폴백이 쓰인다")
                .isNull();
    }

    /**
     * 🔴 <b>미해결 — 배포 차단 항목. 이 시험은 통과하지 않는다.</b>
     *
     * <p>요구사항: 실제 사용액 2,940원·수량 9.8 이면 <b>평균단가 300원</b>, <b>수수료는 거래소 보고값
     * 1.47원</b>으로 반영돼야 한다. 보고값이 없으면 <b>추정임을 명시</b>하고 확정값처럼 취급하지
     * 않는다.</p>
     *
     * <p>🔴 <b>평균단가를 고치면서 기존 손절·익절가를 자동으로 바꾸지 않는다.</b> 최초 체결 시 기준가
     * 설정과, 늦은 회계 정정 시 기존 기준가 처리는 <b>별도 규칙</b>이다 — 평균단가가 바뀌었다고
     * 손절선을 움직이면 사람이 모르는 사이에 청산 조건이 달라진다.</p>
     */
    @Test
    @DisplayName("평균단가 300, 수수료는 거래소 보고값 1.47")
    void avgPriceAndFeeFromExchangeReport() {
        Fixture f = marketBuyWithoutStopLossPrice();
        Mockito.doReturn(done2940()).when(upbitOrderClient).getOrder("uuid-fallback");

        orderExecutionEngine.checkOrderStatus(f.orderId());

        PositionEntity pos = positionRepository.findById(f.positionId()).orElseThrow();
        assertThat(pos.getAvgPrice())
                .as("실제 사용액 기준 평균단가 — 2940 / 9.8 = 300")
                .isEqualByComparingTo(new BigDecimal("300.00000000"));
        assertThat(pos.getPositionFee())
                .as("수수료는 거래소 보고값 1.47 이어야 한다 — 자체 계산값 1.50 이 아니다")
                .isEqualByComparingTo(new BigDecimal("1.47"));
    }
}
