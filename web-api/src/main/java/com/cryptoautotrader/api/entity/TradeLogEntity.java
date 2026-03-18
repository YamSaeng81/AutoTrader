package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 거래 로그 엔티티 — V6 trade_log 테이블 매핑
 */
@Entity
@Table(name = "trade_log")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "old_state", length = 20)
    private String oldState;

    @Column(name = "new_state", length = 20)
    private String newState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    private String detailJson;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // ── 명시적 getter ──────────────────────────────────

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getEventType() { return eventType; }
    public String getOldState() { return oldState; }
    public String getNewState() { return newState; }
    public String getDetailJson() { return detailJson; }
    public Instant getCreatedAt() { return createdAt; }
}
