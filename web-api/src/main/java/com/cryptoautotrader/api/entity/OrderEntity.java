package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 주문 엔티티 — V4 order 테이블 매핑
 * "order"는 SQL 예약어이므로 테이블명을 쌍따옴표로 감싼다.
 */
@Entity
@Table(name = "\"order\"")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(nullable = false, length = 4)
    private String side;

    @Column(name = "order_type", nullable = false, length = 10)
    private String orderType;

    @Column(precision = 20, scale = 8)
    private BigDecimal price;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal quantity;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(name = "exchange_order_id", length = 100)
    private String exchangeOrderId;

    @Column(name = "filled_quantity", precision = 20, scale = 8)
    private BigDecimal filledQuantity;

    /** 실제 사용된 KRW 금액 — price-type 매수 부분체결 후 취소 시 미사용 KRW 복원에 사용 */
    @Column(name = "executed_funds", precision = 20, scale = 8)
    private BigDecimal executedFunds;

    @Column(name = "signal_reason", columnDefinition = "TEXT")
    private String signalReason;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "filled_at")
    private Instant filledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "failed_reason", columnDefinition = "TEXT")
    private String failedReason;

    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "session_id")
    private Long sessionId;

    @PrePersist
    void prePersist() {
        if (state == null) state = "PENDING";
        if (createdAt == null) createdAt = Instant.now();
        if (filledQuantity == null) filledQuantity = BigDecimal.ZERO;
    }

    // ── 명시적 getter/setter ──────────────────────────────────

    public Long getId() { return id; }

    public Long getPositionId() { return positionId; }
    public void setPositionId(Long positionId) { this.positionId = positionId; }

    public String getCoinPair() { return coinPair; }
    public void setCoinPair(String coinPair) { this.coinPair = coinPair; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getExchangeOrderId() { return exchangeOrderId; }
    public void setExchangeOrderId(String exchangeOrderId) { this.exchangeOrderId = exchangeOrderId; }

    public BigDecimal getFilledQuantity() { return filledQuantity; }
    public void setFilledQuantity(BigDecimal filledQuantity) { this.filledQuantity = filledQuantity; }

    public BigDecimal getExecutedFunds() { return executedFunds; }
    public void setExecutedFunds(BigDecimal executedFunds) { this.executedFunds = executedFunds; }

    public String getSignalReason() { return signalReason; }
    public void setSignalReason(String signalReason) { this.signalReason = signalReason; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public Instant getFilledAt() { return filledAt; }
    public void setFilledAt(Instant filledAt) { this.filledAt = filledAt; }

    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }

    public String getFailedReason() { return failedReason; }
    public void setFailedReason(String failedReason) { this.failedReason = failedReason; }

    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
}
