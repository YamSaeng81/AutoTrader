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

    /**
     * 포트폴리오 자본 사용률 상한 (%).
     * 투입 자본 / 전체 자본 × 100 이 이 값을 초과하면 신규 매수 차단.
     * null 이면 80% 기본값 사용.
     */
    @Column(name = "max_capital_utilization_pct", precision = 5, scale = 2)
    private BigDecimal maxCapitalUtilizationPct;

    /**
     * 글로벌 포트폴리오 드로우다운 상한 (%).
     * 전체 RUNNING 세션의 (initialCapital 합 - totalAssetKrw 합) / initialCapital 합 × 100
     * 이 값 초과 시 신규 매수 차단. null 또는 0이면 비활성화. 기본 15%.
     */
    @Column(name = "max_portfolio_drawdown_pct", precision = 5, scale = 2)
    private BigDecimal maxPortfolioDrawdownPct;

    /** 서킷 브레이커: 세션 MDD 임계값 (%) — 초과 시 세션 강제 정지 */
    @Column(name = "mdd_threshold_pct", precision = 5, scale = 2)
    private BigDecimal mddThresholdPct;

    /** 서킷 브레이커: 연속 손실 허용 횟수 — 초과 시 세션 강제 정지 */
    @Column(name = "consecutive_loss_limit")
    private Integer consecutiveLossLimit;

    /** 서킷 브레이커 활성화 여부 */
    @Column(name = "circuit_breaker_enabled")
    private Boolean circuitBreakerEnabled;

    // ── 포지션 수준 리스크 규칙 (ExitRuleConfig) ──────────────

    /** 기본 손절 비율 (%) */
    @Column(name = "stop_loss_pct", precision = 5, scale = 2)
    private BigDecimal stopLossPct;

    /** 익절 배수 — TP% = SL% × 배수 */
    @Column(name = "take_profit_multiplier", precision = 5, scale = 2)
    private BigDecimal takeProfitMultiplier;

    /** 트레일링 스탑 활성화 */
    @Column(name = "trailing_enabled")
    private Boolean trailingEnabled;

    /** 트레일링 TP 마진 (%) — 고점 대비 */
    @Column(name = "trailing_tp_margin_pct", precision = 5, scale = 3)
    private BigDecimal trailingTpMarginPct;

    /** 트레일링 SL 조임 마진 (%) — 저점 대비 */
    @Column(name = "trailing_sl_margin_pct", precision = 5, scale = 3)
    private BigDecimal trailingSlMarginPct;

    /** 가용자금 대비 투자 비율 (%) */
    @Column(name = "invest_ratio_pct", precision = 5, scale = 2)
    private BigDecimal investRatioPct;

    @PrePersist
    void prePersist() {
        if (updatedAt == null) updatedAt = Instant.now();
        if (maxDailyLossPct == null) maxDailyLossPct = new BigDecimal("3.0");
        if (maxWeeklyLossPct == null) maxWeeklyLossPct = new BigDecimal("7.0");
        if (maxMonthlyLossPct == null) maxMonthlyLossPct = new BigDecimal("15.0");
        if (maxPositions == null) maxPositions = 20;
        if (maxCapitalUtilizationPct == null) maxCapitalUtilizationPct = new BigDecimal("80.0");
        if (cooldownMinutes == null) cooldownMinutes = 60;
        if (maxPortfolioDrawdownPct == null) maxPortfolioDrawdownPct = new BigDecimal("15.0");
        if (mddThresholdPct == null) mddThresholdPct = new BigDecimal("20.0");
        if (consecutiveLossLimit == null) consecutiveLossLimit = 5;
        if (circuitBreakerEnabled == null) circuitBreakerEnabled = Boolean.TRUE;
        if (stopLossPct == null) stopLossPct = new BigDecimal("5.0");
        if (takeProfitMultiplier == null) takeProfitMultiplier = new BigDecimal("2.0");
        if (trailingEnabled == null) trailingEnabled = Boolean.TRUE;
        if (trailingTpMarginPct == null) trailingTpMarginPct = new BigDecimal("0.5");
        if (trailingSlMarginPct == null) trailingSlMarginPct = new BigDecimal("0.3");
        if (investRatioPct == null) investRatioPct = new BigDecimal("80.0");
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

    public BigDecimal getMaxCapitalUtilizationPct() { return maxCapitalUtilizationPct; }
    public void setMaxCapitalUtilizationPct(BigDecimal v) { this.maxCapitalUtilizationPct = v; }

    public BigDecimal getMaxPortfolioDrawdownPct() { return maxPortfolioDrawdownPct; }
    public void setMaxPortfolioDrawdownPct(BigDecimal v) { this.maxPortfolioDrawdownPct = v; }

    public BigDecimal getMddThresholdPct() { return mddThresholdPct; }
    public void setMddThresholdPct(BigDecimal mddThresholdPct) { this.mddThresholdPct = mddThresholdPct; }

    public Integer getConsecutiveLossLimit() { return consecutiveLossLimit; }
    public void setConsecutiveLossLimit(Integer consecutiveLossLimit) { this.consecutiveLossLimit = consecutiveLossLimit; }

    public Boolean getCircuitBreakerEnabled() { return circuitBreakerEnabled; }
    public void setCircuitBreakerEnabled(Boolean circuitBreakerEnabled) { this.circuitBreakerEnabled = circuitBreakerEnabled; }

    public BigDecimal getStopLossPct() { return stopLossPct; }
    public void setStopLossPct(BigDecimal stopLossPct) { this.stopLossPct = stopLossPct; }

    public BigDecimal getTakeProfitMultiplier() { return takeProfitMultiplier; }
    public void setTakeProfitMultiplier(BigDecimal takeProfitMultiplier) { this.takeProfitMultiplier = takeProfitMultiplier; }

    public Boolean getTrailingEnabled() { return trailingEnabled; }
    public void setTrailingEnabled(Boolean trailingEnabled) { this.trailingEnabled = trailingEnabled; }

    public BigDecimal getTrailingTpMarginPct() { return trailingTpMarginPct; }
    public void setTrailingTpMarginPct(BigDecimal trailingTpMarginPct) { this.trailingTpMarginPct = trailingTpMarginPct; }

    public BigDecimal getTrailingSlMarginPct() { return trailingSlMarginPct; }
    public void setTrailingSlMarginPct(BigDecimal trailingSlMarginPct) { this.trailingSlMarginPct = trailingSlMarginPct; }

    public BigDecimal getInvestRatioPct() { return investRatioPct; }
    public void setInvestRatioPct(BigDecimal investRatioPct) { this.investRatioPct = investRatioPct; }
}
