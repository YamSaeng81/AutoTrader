package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "strategy_config")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "strategy_type", nullable = false, length = 50)
    private String strategyType;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(nullable = false, length = 10)
    private String timeframe;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> configJson;

    @Column(name = "is_active")
    private Boolean isActive;

    /**
     * 수동 오버라이드 플래그.
     * true 인 경우 MarketRegimeAwareScheduler 의 자동 활성/비활성 대상에서 제외된다.
     * 사용자가 직접 toggle 한 전략 설정에만 true 를 설정한다.
     */
    @Column(name = "manual_override")
    private Boolean manualOverride;

    @Column(name = "max_investment")
    private BigDecimal maxInvestment;

    @Column(name = "stop_loss_pct")
    private BigDecimal stopLossPct;

    @Column(name = "reinvest_pct")
    private BigDecimal reinvestPct;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (isActive == null) isActive = true;
        if (manualOverride == null) manualOverride = false;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // ── 명시적 getter/setter (IDE Lombok 인식 문제 회피) ──────────────

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }

    public String getCoinPair() { return coinPair; }
    public void setCoinPair(String coinPair) { this.coinPair = coinPair; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    public Map<String, Object> getConfigJson() { return configJson; }
    public void setConfigJson(Map<String, Object> configJson) { this.configJson = configJson; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Boolean getManualOverride() { return manualOverride; }
    public void setManualOverride(Boolean manualOverride) { this.manualOverride = manualOverride; }

    public BigDecimal getMaxInvestment() { return maxInvestment; }
    public void setMaxInvestment(BigDecimal maxInvestment) { this.maxInvestment = maxInvestment; }

    public BigDecimal getStopLossPct() { return stopLossPct; }
    public void setStopLossPct(BigDecimal stopLossPct) { this.stopLossPct = stopLossPct; }

    public BigDecimal getReinvestPct() { return reinvestPct; }
    public void setReinvestPct(BigDecimal reinvestPct) { this.reinvestPct = reinvestPct; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
