package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "strategy_type_enabled")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class StrategyTypeEnabledEntity {

    @Id
    @Column(name = "strategy_name", length = 50)
    private String strategyName;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }
}
