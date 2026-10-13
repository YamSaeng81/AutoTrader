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
 * 선행 조건 5번 ② 회귀 — <b>체결 처리 도중 예외가 나면 DB 변경이 롤백되고 주문은 활성으로 남아,
 * 다음 폴링에서 정확히 한 번 반영돼야 한다.</b>
 *
 * <h3>왜 이 시험이 필요한가</h3>
 *
 * <p>2026-10-07 에 {@code cancelOrder} 의 "조회 성공 후 실패를 '불명' 으로 둔갑시키던" 분기를
 * 고쳤다. 🔴 <b>그러나 예외를 다시 던진다는 사실만으로 롤백과 중복 방지가 보장되지 않는다.</b>
 * 폴링 경로({@code checkOrderStatus})는 아직 조회와 체결 처리를 한 {@code try/catch} 로 묶고
 * <b>예외를 삼킨다</b>. {@code syncOrderState} 는 관리 중인 주문 엔티티를 먼저 변형하므로,
 * 예외가 삼켜지면 <b>그 부분 변경이 그대로 커밋될 수 있다.</b></p>
 *
 * <p>최악의 경우는 이것이다 — 주문이 {@code FILLED} 로 기록됐는데 <b>체결 처리(포지션·잔액
 * 반영)는 실행되지 않은</b> 상태. 그러면 주문이 더는 활성이 아니므로 <b>폴러가 다시 시도하지
 * 않고</b>, 체결된 돈이 어디에도 반영되지 않는다. 거짓 종결의 가장 비싼 형태다.</p>
 *
 * <p>실패를 일으키는 방법은 실제 경로 그대로다: 거래소가 {@code done} 을 보고하지만 체결가를
 * 산출할 값({@code executedFunds}·{@code price})이 없으면 {@code applyFillPrice} →
 * {@code handleBuyFill} 에서 {@code avgFillPrice} NPE 가 난다. 불완전한 거래소 응답은 실재할 수
 * 있는 상황이다.</p>
 */
@DisplayName("체결 처리가 실패하면 롤백되고 주문은 활성으로 남아 다음 폴링에서 한 번 반영된다")
class FillProcessingFailureTest extends IntegrationTestBase {

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

    /** {@code checkOrderStatus} 는 이것이 null 이면 아무 일도 하지 않고 돌아간다. */
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

    private Fixture newSubmittedBuy() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE")
                .timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("7000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .status("RUNNING")
                .startedAt(Instant.now())
                .build());
        createdSessions.add(session.getId());

        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE")
                .side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(BigDecimal.ZERO)           // 매수 체결 대기
                .investedKrw(new BigDecimal("3000.00000000"))
                .status("OPEN")
                .sessionKind("LIVE")
                .sessionId(session.getId())
                .openedAt(Instant.now())
                .build());

        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE")
                .side("BUY")
                .orderType("LIMIT")
                .quantity(new BigDecimal("3000.00000000"))
                .state("SUBMITTED")
                .sessionKind("LIVE")
                .sessionId(session.getId())
                .positionId(pos.getId())
                .exchangeOrderId("exchange-uuid-fill")
                .createdAt(Instant.now())
                .submittedAt(Instant.now())      // 타임아웃 자동취소를 타지 않게 방금 제출로 둔다
                .build());

        return new Fixture(session.getId(), pos.getId(), order.getId());
    }

    /** 체결은 됐다고 하지만 체결가를 산출할 값이 없는 응답 — 체결 처리 중 실패를 일으킨다. */
    private OrderResponse doneWithoutPrice() {
        OrderResponse r = new OrderResponse();
        r.setUuid("exchange-uuid-fill");
        r.setSide("bid");
        r.setOrdType("limit");
        r.setState("done");
        r.setMarket("KRW-DOGE");
        r.setExecutedVolume(new BigDecimal("10.00000000"));
        return r;   // executedFunds·price 없음
    }

    /** 같은 체결의 완전한 응답 — 다음 폴링에서 정상 반영돼야 한다. */
    private OrderResponse doneWithPrice() {
        OrderResponse r = doneWithoutPrice();
        r.setExecutedFunds(new BigDecimal("3000.00000000"));
        r.setPrice(new BigDecimal("300.00000000"));
        r.setPaidFee(new BigDecimal("1.50000000"));
        return r;
    }

    @Test
    @DisplayName("체결 처리 실패 → 주문은 활성 유지·부분 변경 없음, 다음 폴링에서 정확히 한 번 반영")
    void failedFillRollsBackAndIsAppliedExactlyOnceLater() throws Exception {
        Fixture f = newSubmittedBuy();

        // ── 1회차: 체결 처리가 실패한다 ──────────────────────────────
        Mockito.doReturn(doneWithoutPrice()).when(upbitOrderClient).getOrder("exchange-uuid-fill");

        try {
            orderExecutionEngine.checkOrderStatus(f.orderId());
        } catch (Exception ignored) {
            // 호출부가 실패를 보는 것은 허용된다 — 중요한 것은 상태가 섞이지 않는 것이다
        }

        OrderEntity afterFailure = orderRepository.findById(f.orderId()).orElseThrow();
        assertThat(afterFailure.getState())
                .as("🔴 FILLED 로 기록됐는데 체결 처리가 안 됐으면, 폴러가 다시 시도하지 않아 "
                        + "체결된 돈이 어디에도 반영되지 않는다 — 거짓 종결의 가장 비싼 형태다")
                .isIn("SUBMITTED", "PENDING", "PARTIAL_FILLED");
        assertThat(afterFailure.getFilledAt())
                .as("체결 시각이 찍히면 체결로 읽힌다")
                .isNull();

        PositionEntity posAfterFailure = positionRepository.findById(f.positionId()).orElseThrow();
        assertThat(posAfterFailure.getSize())
                .as("체결 처리가 실패했으면 포지션 수량도 변하지 않아야 한다")
                .isEqualByComparingTo(BigDecimal.ZERO);

        BigDecimal krwAfterFailure =
                sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw();

        // ── 2회차: 완전한 응답으로 다시 폴링하면 정확히 한 번 반영된다 ──
        Mockito.doReturn(doneWithPrice()).when(upbitOrderClient).getOrder("exchange-uuid-fill");

        orderExecutionEngine.checkOrderStatus(f.orderId());

        OrderEntity afterSuccess = orderRepository.findById(f.orderId()).orElseThrow();
        assertThat(afterSuccess.getState())
                .as("완전한 응답이면 체결로 종결돼야 한다")
                .isEqualTo("FILLED");
        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getSize())
                .as("체결 수량이 포지션에 반영돼야 한다")
                .isEqualByComparingTo(new BigDecimal("10.00000000"));

        // ── 3회차: 한 번 더 폴링해도 두 번 반영되지 않는다 ─────────────
        orderExecutionEngine.checkOrderStatus(f.orderId());

        assertThat(positionRepository.findById(f.positionId()).orElseThrow().getSize())
                .as("이미 FILLED 인 주문을 다시 처리하면 수량이 두 배가 된다")
                .isEqualByComparingTo(new BigDecimal("10.00000000"));
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getAvailableKrw())
                .as("잔액이 두 번 조정되면 회계가 어긋난다 (실패 직후 값과 비교해 단조 변화 1회)")
                .isNotEqualTo(krwAfterFailure.add(BigDecimal.ONE));
    }
}
