package com.cryptoautotrader.api.entity.paper;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "\"order\"", schema = "paper_trading")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaperOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(name = "side", nullable = false, length = 4)
    private String side;  // BUY | SELL

    @Column(name = "order_type", nullable = false, length = 10)
    private String orderType;  // MARKET | LIMIT

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private BigDecimal quantity;

    @Column(name = "state", nullable = false, length = 20)
    private String state;  // PENDING | FILLED | CANCELLED

    @Column(name = "exchange_order_id", length = 100)
    private String exchangeOrderId;  // 모의투자는 "PAPER-{id}" 형태

    @Column(name = "filled_quantity")
    private BigDecimal filledQuantity;

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

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (state == null) state = "PENDING";
        if (filledQuantity == null) filledQuantity = BigDecimal.ZERO;
        if (orderType == null) orderType = "MARKET";
    }
}
