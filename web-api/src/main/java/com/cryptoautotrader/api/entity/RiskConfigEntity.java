package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 리스크 설정 엔티티 — V5 risk_config 테이블 매핑
 */
@Entity
@Table(name = "risk_config")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "max_daily_loss_pct", precision = 5, scale = 2)
    private BigDecimal maxDailyLossPct;

    @Column(name = "max_weekly_loss_pct", precision = 5, scale = 2)
    private BigDecimal maxWeeklyLossPct;

    @Column(name = "max_monthly_loss_pct", precision = 5, scale = 2)
    private BigDecimal maxMonthlyLossPct;

    @Column(name = "max_positions")
    private Integer maxPositions;

    @Column(name = "cooldown_minutes")
    private Integer cooldownMinutes;

    @Column(name = "portfolio_limit_krw", precision = 20, scale = 2)
    private BigDecimal portfolioLimitKrw;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** 서킷 브레이커: 세션 MDD 임계값 (%) — 초과 시 세션 강제 정지 */
    @Column(name = "mdd_threshold_pct", precision = 5, scale = 2)
    private BigDecimal mddThresholdPct;

    /** 서킷 브레이커: 연속 손실 허용 횟수 — 초과 시 세션 강제 정지 */
    @Column(name = "consecutive_loss_limit")
    private Integer consecutiveLossLimit;

    /** 서킷 브레이커 활성화 여부 */
    @Column(name = "circuit_breaker_enabled")
    private Boolean circuitBreakerEnabled;

    @PrePersist
    void prePersist() {
        if (updatedAt == null) updatedAt = Instant.now();
        if (maxDailyLossPct == null) maxDailyLossPct = new BigDecimal("3.0");
        if (maxWeeklyLossPct == null) maxWeeklyLossPct = new BigDecimal("7.0");
        if (maxMonthlyLossPct == null) maxMonthlyLossPct = new BigDecimal("15.0");
        if (maxPositions == null) maxPositions = 3;
        if (cooldownMinutes == null) cooldownMinutes = 60;
        if (mddThresholdPct == null) mddThresholdPct = new BigDecimal("20.0");
        if (consecutiveLossLimit == null) consecutiveLossLimit = 5;
        if (circuitBreakerEnabled == null) circuitBreakerEnabled = Boolean.TRUE;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // ── 명시적 getter/setter ──────────────────────────────────

    public Long getId() { return id; }

    public BigDecimal getMaxDailyLossPct() { return maxDailyLossPct; }
    public void setMaxDailyLossPct(BigDecimal maxDailyLossPct) { this.maxDailyLossPct = maxDailyLossPct; }

    public BigDecimal getMaxWeeklyLossPct() { return maxWeeklyLossPct; }
    public void setMaxWeeklyLossPct(BigDecimal maxWeeklyLossPct) { this.maxWeeklyLossPct = maxWeeklyLossPct; }

    public BigDecimal getMaxMonthlyLossPct() { return maxMonthlyLossPct; }
    public void setMaxMonthlyLossPct(BigDecimal maxMonthlyLossPct) { this.maxMonthlyLossPct = maxMonthlyLossPct; }

    public Integer getMaxPositions() { return maxPositions; }
    public void setMaxPositions(Integer maxPositions) { this.maxPositions = maxPositions; }

    public Integer getCooldownMinutes() { return cooldownMinutes; }
    public void setCooldownMinutes(Integer cooldownMinutes) { this.cooldownMinutes = cooldownMinutes; }

    public BigDecimal getPortfolioLimitKrw() { return portfolioLimitKrw; }
    public void setPortfolioLimitKrw(BigDecimal portfolioLimitKrw) { this.portfolioLimitKrw = portfolioLimitKrw; }

    public Instant getUpdatedAt() { return updatedAt; }

    public BigDecimal getMddThresholdPct() { return mddThresholdPct; }
    public void setMddThresholdPct(BigDecimal mddThresholdPct) { this.mddThresholdPct = mddThresholdPct; }

    public Integer getConsecutiveLossLimit() { return consecutiveLossLimit; }
    public void setConsecutiveLossLimit(Integer consecutiveLossLimit) { this.consecutiveLossLimit = consecutiveLossLimit; }

    public Boolean getCircuitBreakerEnabled() { return circuitBreakerEnabled; }
    public void setCircuitBreakerEnabled(Boolean circuitBreakerEnabled) { this.circuitBreakerEnabled = circuitBreakerEnabled; }
}
