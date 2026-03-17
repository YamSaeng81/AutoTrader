package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "strategy_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false, length = 50)
    private String strategyName;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(length = 10)
    private String signal;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "indicators_json", columnDefinition = "jsonb")
    private String indicatorsJson;

    @Column(name = "market_regime", length = 10)
    private String marketRegime;

    /** PAPER / LIVE */
    @Column(name = "session_type", length = 10)
    private String sessionType;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
