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
 * 회귀 사례 — <b>요청 금액보다 실제 사용액이 작은 시장가 매수</b> (2026-10-12).
 *
 * <h3>왜 별도 사례로 고정하는가</h3>
 *
 * <p>V81 작업 중에 찾았다: {@code order.setExecutedFunds(...)} 는 <b>"부분 체결 후 취소" 분기에서만</b>
 * 호출된다. 정상 전량 체결({@code state=done})에서는 저장되지 않아 두 가지가 따라온다 —</p>
 *
 * <ol>
 *   <li><b>평균단가</b>: 시장가 매수는 {@code executedFunds} 가 없으면 {@code quantity}(요청 KRW)로
 *       폴백한다. 실제 사용액 ≤ 요청액 이므로 평균단가가 <b>과대 계산</b>된다. 평균단가는 손절·익절
 *       기준가라 과대 계산되면 청산 판단이 어긋난다.</li>
 *   <li><b>미사용 KRW</b>: 복원 조건이 {@code executedFunds != null && executedFunds < quantity} 인데
 *       {@code null} 이라 <b>성립하지 않는다.</b> 세션 잔액에서 차감된 요청액과 실제 사용액의 차액이
 *       복원되지 않는다.</li>
 * </ol>
 *
 * <p>🔴 <b>기존 컬럼을 단순히 채우는 수정으로 끝내지 않는다.</b> {@code executed_funds} 를 정상 체결에서
 * 채우면 위 두 금액 경로가 <b>조용히 바뀐다</b> — 평균단가와 KRW 복원은 돈이 움직이는 자리다. 그래서
 * V81 은 원본을 <b>새 컬럼</b>({@code reported_*})에 보존하고 기존 경로는 건드리지 않았다. 이 시험은
 * <b>그 경로를 언제 바꿀지 판단하기 위한 근거</b>로 현재 동작을 고정한다.</p>
 */
@DisplayName("요청액보다 실제 사용액이 작은 시장가 매수 (회귀 사례)")
class MarketBuyUnusedFundsTest extends IntegrationTestBase {

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

    /** 요청 3,000원 시장가 매수. 세션 잔액에서 3,000원이 이미 차감돼 있다. */
    private Fixture marketBuyRequesting3000() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("7000.00"))     // 10,000 − 3,000(차감 완료)
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
                .coinPair("KRW-DOGE").side("BUY")
                .orderType("MARKET")                          // 🔴 시장가 — quantity 가 KRW 금액이다
                .quantity(new BigDecimal("3000.00000000"))
                .state("SUBMITTED")
                .sessionKind("LIVE").sessionId(session.getId()).positionId(pos.getId())
                .exchangeOrderId("uuid-unused")
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .createdAt(Instant.now()).submittedAt(Instant.now()).build());

        return new Fixture(session.getId(), pos.getId(), order.getId());
    }

    /** 전량 체결이지만 실제로는 2,940원만 썼다 — 9.8 개 × 300원. 남은 60원은 쓰이지 않았다. */
    private OrderResponse doneUsing2940() {
        OrderResponse r = new OrderResponse();
        r.setUuid("uuid-unused");
        r.setSide("bid");
        r.setOrdType("price");                                // 시장가 매수(금액 지정)
        r.setState("done");
        r.setMarket("KRW-DOGE");
        r.setVolume(new BigDecimal("3000.00000000"));
        r.setExecutedVolume(new BigDecimal("9.80000000"));
        r.setExecutedFunds(new BigDecimal("2940.00000000"));  // 🔴 요청 3,000 > 사용 2,940
        r.setPaidFee(new BigDecimal("1.47000000"));
        return r;
    }

    @Test
    @DisplayName("거래소 보고 원본은 보존된다 — 요청액과 사용액의 차이가 기록에 남는다")
    void reportedFundsArePreservedSoTheGapIsVisible() {
        Fixture f = marketBuyRequesting3000();
        Mockito.doReturn(doneUsing2940()).when(upbitOrderClient).getOrder("uuid-unused");

        orderExecutionEngine.checkOrderStatus(f.orderId());

        OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
        assertThat(o.getReportedExecutedFunds())
                .as("실제 사용액이 보존돼야 요청액과의 차이를 나중에 판단할 수 있다")
                .isEqualByComparingTo(new BigDecimal("2940.00000000"));
        assertThat(o.getQuantity())
                .as("요청액은 그대로 남는다")
                .isEqualByComparingTo(new BigDecimal("3000.00000000"));
        assertThat(o.getFeeSource())
                .as("새 회계 경로에 참여한 주문이므로 NULL(전환 이전 데이터)이 아니어야 한다. "
                        + "생성 시 PENDING 이었고 정산 반영 후 REPORTED 로 올라간다")
                .isEqualTo(OrderExecutionEngine.FEE_SOURCE_REPORTED);
    }

    @Test
    @DisplayName("미사용 금액이 복원된다 — 원금 차이 60원이 아니라 58.53원이다")
    void unusedFundsRestoredNetOfFee() {
        Fixture f = marketBuyRequesting3000();
        Mockito.doReturn(doneUsing2940()).when(upbitOrderClient).getOrder("uuid-unused");

        orderExecutionEngine.checkOrderStatus(f.orderId());

        // 🔴 요청 3,000 은 제출 시점에 이미 전액 차감됐다. 거래소가 그 안에서 수수료를 빼므로
        //    미사용액은 **요청액 − 체결금액 − 수수료** = 3000 − 2940 − 1.47 = 58.53 이다.
        //    원금 차이(3000 − 2940 = 60)를 복원하면 수수료 1.47 만큼 과다 복원된다.
        BigDecimal afterFirst =
                sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw();
        assertThat(afterFirst)
                .as("7000 + 58.53 = 7058.53 이어야 한다. 7060 이면 수수료를 복원해 버린 것이다")
                .isEqualByComparingTo(new BigDecimal("7058.53"));

        // 반복 응답 — 추가 복원이 없어야 한다
        for (int i = 0; i < 3; i++) {
            OrderEntity o = orderRepository.findById(f.orderId()).orElseThrow();
            o.setSettlementNextCheckAt(Instant.now().minusSeconds(1));
            orderRepository.save(o);
            orderExecutionEngine.checkOrderStatus(f.orderId());
        }
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("반복 응답에 추가 복원이 일어나면 쓰이지 않은 돈이 생긴다")
                .isEqualByComparingTo(afterFirst);
    }

    /**
     * 요청액보다 실제 사용액이 작은 매수에서 ① <b>평균단가가 실제 사용액 기준</b>이고
     * ② <b>미사용 금액이 복원</b>되며 ③ <b>반복 응답에도 추가 복원이 없어야</b> 한다.
     *
     * <p>🔴 <b>복원액은 원금 차이 60원이 아니다.</b> 요청액 3,000 에서 체결금액 2,940 과 수수료
     * 1.47 이 모두 빠져나갔으므로 돌려받는 금액은 {@code 3000 − 2940 − 1.47 = 58.53} 이다.
     * 시장가 매수는 수수료를 요청액 <b>안에서</b> 떼기 때문이다 — 60원으로 보면 1.47원을 두 번
     * 세는 셈이다.</p>
     */
    @Test
    @DisplayName("평균단가는 실제 사용액 기준이고 미사용 금액은 한 번만 복원된다")
    void avgPriceUsesActualFundsAndUnusedKrwRestoredOnce() {
        Fixture f = marketBuyRequesting3000();
        Mockito.doReturn(doneUsing2940()).when(upbitOrderClient).getOrder("uuid-unused");

        orderExecutionEngine.checkOrderStatus(f.orderId());

        PositionEntity pos = positionRepository.findById(f.positionId()).orElseThrow();
        assertThat(pos.getAvgPrice())
                .as("평균단가는 실제 사용액 기준이어야 한다 (2940 / 9.8 = 300). "
                        + "요청액 기준이면 306.12 로 과대 계산돼 손절·익절 기준가가 어긋난다")
                .isEqualByComparingTo(new BigDecimal("300.00000000"));

        BigDecimal afterFirst = sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw();
        assertThat(afterFirst)
                .as("미사용액은 수수료를 제외한 58.53원이다 (7000 + 3000 − 2940 − 1.47)")
                .isEqualByComparingTo(new BigDecimal("7058.53"));

        // 반복 응답 — 추가 복원이 없어야 한다
        orderExecutionEngine.checkOrderStatus(f.orderId());
        orderExecutionEngine.checkOrderStatus(f.orderId());

        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("반복 응답에 추가 복원이 일어나면 쓰이지 않은 돈이 생긴다")
                .isEqualByComparingTo(afterFirst);
    }
}
