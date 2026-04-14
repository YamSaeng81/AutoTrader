package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "nightly_scheduler_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NightlySchedulerConfigEntity {

    /** 싱글톤 행 — 항상 id=1 */
    @Id
    private Long id;

    @Column(nullable = false)
    private Boolean enabled;

    /** 실행 시각 — KST 시 (0~23) */
    @Column(name = "run_hour", nullable = false)
    private Integer runHour;

    /** 실행 시각 — 분 (0~59) */
    @Column(name = "run_minute", nullable = false)
    private Integer runMinute;

    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** 콤마 구분 코인 목록 (예: KRW-BTC,KRW-ETH) */
    @Column(name = "coin_pairs", nullable = false, columnDefinition = "TEXT")
    private String coinPairs;

    /** 콤마 구분 전략 목록 */
    @Column(name = "strategy_types", nullable = false, columnDefinition = "TEXT")
    private String strategyTypes;

    @Column(name = "include_backtest", nullable = false)
    private Boolean includeBacktest;

    @Column(name = "include_walk_forward", nullable = false)
    private Boolean includeWalkForward;

    @Column(name = "in_sample_ratio", nullable = false)
    private Double inSampleRatio;

    @Column(name = "window_count", nullable = false)
    private Integer windowCount;

    @Column(name = "initial_capital", nullable = false)
    private BigDecimal initialCapital;

    @Column(name = "slippage_pct", nullable = false)
    private BigDecimal slippagePct;

    @Column(name = "fee_pct", nullable = false)
    private BigDecimal feePct;

    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    @Column(name = "last_batch_job_id")
    private Long lastBatchJobId;

    @Column(name = "last_wf_job_id")
    private Long lastWfJobId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    // ── 편의 변환 메서드 ────────────────────────────────────────────────────────

    public java.util.List<String> coinPairList() {
        return java.util.Arrays.stream(coinPairs.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public java.util.List<String> strategyTypeList() {
        return java.util.Arrays.stream(strategyTypes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
