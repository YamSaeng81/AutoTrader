package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 포지션 엔티티 — V4 position 테이블 매핑
 */
@Entity
@Table(name = "position")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(nullable = false, length = 4)
    private String side;

    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "avg_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal avgPrice;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal size;

    @Column(name = "unrealized_pnl", precision = 20, scale = 8)
    private BigDecimal unrealizedPnl;

    @Column(name = "realized_pnl", precision = 20, scale = 8)
    private BigDecimal realizedPnl;

    @Column(name = "strategy_config_id")
    private Long strategyConfigId;

    @Column(length = 10)
    private String status;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "position_fee", precision = 20, scale = 2)
    private BigDecimal positionFee;

    @PrePersist
    void prePersist() {
        if (status == null) status = "OPEN";
        if (openedAt == null) openedAt = Instant.now();
        if (unrealizedPnl == null) unrealizedPnl = BigDecimal.ZERO;
        if (realizedPnl == null) realizedPnl = BigDecimal.ZERO;
        if (positionFee == null) positionFee = BigDecimal.ZERO;
    }

}
