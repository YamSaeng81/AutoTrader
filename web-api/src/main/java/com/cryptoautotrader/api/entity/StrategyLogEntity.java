package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "indicators_json", columnDefinition = "jsonb")
    private String indicatorsJson;

    @Column(name = "market_regime", length = 10)
    private String marketRegime;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
