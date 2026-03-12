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

    @PrePersist
    void prePersist() {
        if (status == null) status = "OPEN";
        if (openedAt == null) openedAt = Instant.now();
        if (unrealizedPnl == null) unrealizedPnl = BigDecimal.ZERO;
        if (realizedPnl == null) realizedPnl = BigDecimal.ZERO;
    }

    // ── 명시적 getter/setter ──────────────────────────────────

    public Long getId() { return id; }

    public String getCoinPair() { return coinPair; }
    public void setCoinPair(String coinPair) { this.coinPair = coinPair; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getAvgPrice() { return avgPrice; }
    public void setAvgPrice(BigDecimal avgPrice) { this.avgPrice = avgPrice; }

    public BigDecimal getSize() { return size; }
    public void setSize(BigDecimal size) { this.size = size; }

    public BigDecimal getUnrealizedPnl() { return unrealizedPnl; }
    public void setUnrealizedPnl(BigDecimal unrealizedPnl) { this.unrealizedPnl = unrealizedPnl; }

    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }

    public Long getStrategyConfigId() { return strategyConfigId; }
    public void setStrategyConfigId(Long strategyConfigId) { this.strategyConfigId = strategyConfigId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
}
