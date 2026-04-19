package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 20260415_analy.md Tier 4 §15 — OrderExecutionEngine 상태머신 테스트.
 *
 * <p>거래소 클라이언트(UpbitOrderClient) 없이 검증 가능한 경로만 다룬다:
 * cancelOrder 상태 전이, 중복 주문 방지, cancelAllActiveOrders 집계.</p>
 */
class OrderExecutionEngineTest extends IntegrationTestBase {

    @Autowired
    private OrderExecutionEngine engine;

    @Autowired
    private OrderRepository orderRepository;

    @AfterEach
    void cleanup() {
        orderRepository.deleteAll();
    }

    // ── cancelOrder ──────────────────────────────────────────────────────

    @Test
    @DisplayName("§15 cancelOrder — PENDING → CANCELLED 전이")
    void cancelOrder_pendingToCancelled() {
        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-BTC")
                .side("BUY")
                .orderType("LIMIT")
                .quantity(new BigDecimal("0.001"))
                .state("PENDING")
                .build());

        OrderEntity cancelled = engine.cancelOrder(order.getId());

        assertThat(cancelled.getState()).isEqualTo("CANCELLED");
        assertThat(cancelled.getCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("§15 cancelOrder — SUBMITTED → CANCELLED 전이")
    void cancelOrder_submittedToCancelled() {
        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-ETH")
                .side("SELL")
                .orderType("MARKET")
                .quantity(new BigDecimal("0.1"))
                .state("SUBMITTED")
                .build());

        OrderEntity cancelled = engine.cancelOrder(order.getId());

        assertThat(cancelled.getState()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("§15 cancelOrder — FILLED 상태는 취소 불가 (IllegalStateException)")
    void cancelOrder_filledStateThrows() {
        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-BTC")
                .side("BUY")
                .orderType("LIMIT")
                .quantity(new BigDecimal("0.001"))
                .state("FILLED")
                .build());

        assertThatThrownBy(() -> engine.cancelOrder(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("취소 불가 상태");
    }

    @Test
    @DisplayName("§15 cancelOrder — FAILED 상태는 취소 불가 (IllegalStateException)")
    void cancelOrder_failedStateThrows() {
        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-BTC")
                .side("BUY")
                .orderType("LIMIT")
                .quantity(new BigDecimal("0.001"))
                .state("FAILED")
                .build());

        assertThatThrownBy(() -> engine.cancelOrder(order.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("§15 cancelOrder — 존재하지 않는 주문 ID (IllegalArgumentException)")
    void cancelOrder_notFoundThrows() {
        assertThatThrownBy(() -> engine.cancelOrder(99999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("주문을 찾을 수 없습니다");
    }

    // ── cancelAllActiveOrders ────────────────────────────────────────────

    @Test
    @DisplayName("§15 cancelAllActiveOrders — 활성 주문 전체 취소 카운트")
    void cancelAllActiveOrders_cancelsAllActive() {
        // 활성 3건 + 최종 2건
        orderRepository.save(pendingOrder("KRW-BTC", "BUY"));
        orderRepository.save(pendingOrder("KRW-ETH", "BUY"));
        orderRepository.save(submittedOrder("KRW-SOL", "SELL"));
        orderRepository.save(filledOrder("KRW-BTC", "SELL"));
        orderRepository.save(failedOrder("KRW-ETH", "BUY"));

        int count = engine.cancelAllActiveOrders();

        assertThat(count).isEqualTo(3);
        assertThat(orderRepository.findByStateIn(java.util.List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED")))
                .isEmpty();
    }

    // ── 중복 주문 방지 (submitOrder 내부 로직은 거래소 클라이언트 불필요 경로 없음)
    // existsByCoinPairAndSideAndStateIn 은 Repository 레벨에서 검증한다.

    @Test
    @DisplayName("§15 OrderRepository — 동일 코인·방향 활성 주문 존재 여부 쿼리")
    void repository_duplicateDetection() {
        orderRepository.save(submittedOrder("KRW-BTC", "BUY"));

        boolean exists = orderRepository.existsByCoinPairAndSideAndStateIn(
                "KRW-BTC", "BUY", java.util.List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED"));
        boolean notExists = orderRepository.existsByCoinPairAndSideAndStateIn(
                "KRW-ETH", "BUY", java.util.List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED"));

        assertThat(exists).isTrue();
        assertThat(notExists).isFalse();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private OrderEntity pendingOrder(String coin, String side) {
        return OrderEntity.builder().coinPair(coin).side(side)
                .orderType("LIMIT").quantity(new BigDecimal("0.001")).state("PENDING").build();
    }

    private OrderEntity submittedOrder(String coin, String side) {
        return OrderEntity.builder().coinPair(coin).side(side)
                .orderType("MARKET").quantity(new BigDecimal("0.001")).state("SUBMITTED").build();
    }

    private OrderEntity filledOrder(String coin, String side) {
        return OrderEntity.builder().coinPair(coin).side(side)
                .orderType("MARKET").quantity(new BigDecimal("0.001")).state("FILLED").build();
    }

    private OrderEntity failedOrder(String coin, String side) {
        return OrderEntity.builder().coinPair(coin).side(side)
                .orderType("LIMIT").quantity(new BigDecimal("0.001")).state("FAILED").build();
    }
}
