package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "regime_change_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegimeChangeLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(nullable = false, length = 10)
    private String timeframe;

    /** 이전 레짐 — 최초 감지 시 NULL */
    @Column(name = "from_regime", length = 20)
    private String fromRegime;

    /** 전환 후 레짐 */
    @Column(name = "to_regime", nullable = false, length = 20)
    private String toRegime;

    /** 전환 시점에 활성화/비활성화된 전략 목록 (JSON 문자열) */
    @Column(name = "strategy_changes_json", columnDefinition = "TEXT")
    private String strategyChangesJson;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @PrePersist
    void prePersist() {
        if (detectedAt == null) detectedAt = Instant.now();
    }
}
