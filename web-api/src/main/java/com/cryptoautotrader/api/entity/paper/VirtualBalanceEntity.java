package com.cryptoautotrader.api.entity.paper;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "virtual_balance", schema = "paper_trading")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VirtualBalanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "total_krw", nullable = false)
    private BigDecimal totalKrw;

    @Column(name = "available_krw", nullable = false)
    private BigDecimal availableKrw;

    @Column(name = "initial_capital")
    private BigDecimal initialCapital;

    @Column(name = "strategy_name", length = 50)
    private String strategyName;

    @Column(name = "coin_pair", length = 20)
    private String coinPair;

    @Column(name = "timeframe", length = 10)
    private String timeframe;

    @Column(name = "status", length = 10, nullable = false)
    private String status;   // RUNNING | STOPPED

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "telegram_enabled", nullable = false)
    @Builder.Default
    private Boolean telegramEnabled = false;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
