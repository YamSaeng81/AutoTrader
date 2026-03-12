package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 실전매매 세션 엔티티 -- V12 live_trading_session 테이블 매핑
 * 각 세션은 특정 종목 + 전략 + 타임프레임 + 투자금액 조합을 나타낸다.
 */
@Entity
@Table(name = "live_trading_session")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LiveTradingSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_type", nullable = false, length = 50)
    private String strategyType;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column(name = "initial_capital", nullable = false, precision = 20, scale = 2)
    private BigDecimal initialCapital;

    @Column(name = "available_krw", nullable = false, precision = 20, scale = 2)
    private BigDecimal availableKrw;

    @Column(name = "total_asset_krw", nullable = false, precision = 20, scale = 2)
    private BigDecimal totalAssetKrw;

    @Column(nullable = false, length = 20)
    private String status; // RUNNING, STOPPED, EMERGENCY_STOPPED

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_params", columnDefinition = "jsonb")
    private Map<String, Object> strategyParams;

    @Column(name = "max_investment", precision = 20, scale = 2)
    private BigDecimal maxInvestment;

    @Column(name = "stop_loss_pct", precision = 5, scale = 2)
    private BigDecimal stopLossPct;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (status == null) status = "STOPPED";
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
        if (stopLossPct == null) stopLossPct = new BigDecimal("5.0");
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // -- 명시적 getter/setter (Lombok IDE 문제 회피용) --

    public Long getId() { return id; }

    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }

    public String getCoinPair() { return coinPair; }
    public void setCoinPair(String coinPair) { this.coinPair = coinPair; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    public BigDecimal getInitialCapital() { return initialCapital; }
    public void setInitialCapital(BigDecimal initialCapital) { this.initialCapital = initialCapital; }

    public BigDecimal getAvailableKrw() { return availableKrw; }
    public void setAvailableKrw(BigDecimal availableKrw) { this.availableKrw = availableKrw; }

    public BigDecimal getTotalAssetKrw() { return totalAssetKrw; }
    public void setTotalAssetKrw(BigDecimal totalAssetKrw) { this.totalAssetKrw = totalAssetKrw; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getStrategyParams() { return strategyParams; }
    public void setStrategyParams(Map<String, Object> strategyParams) { this.strategyParams = strategyParams; }

    public BigDecimal getMaxInvestment() { return maxInvestment; }
    public void setMaxInvestment(BigDecimal maxInvestment) { this.maxInvestment = maxInvestment; }

    public BigDecimal getStopLossPct() { return stopLossPct; }
    public void setStopLossPct(BigDecimal stopLossPct) { this.stopLossPct = stopLossPct; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(Instant stoppedAt) { this.stoppedAt = stoppedAt; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
