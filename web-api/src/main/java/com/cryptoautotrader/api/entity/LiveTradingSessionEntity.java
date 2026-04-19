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

    /** 투자 비율 (0.1 ~ 1.0, 기본 0.80) — availableKrw × investRatio 가 매수 금액 */
    @Column(name = "invest_ratio", nullable = false, precision = 5, scale = 4)
    private BigDecimal investRatio;

    @Column(name = "max_investment", precision = 20, scale = 2)
    private BigDecimal maxInvestment;

    @Column(name = "stop_loss_pct", precision = 5, scale = 2)
    private BigDecimal stopLossPct;

    /** MDD 계산용 최고 자산 피크 (세션 시작 이후 기록된 최대 totalAssetKrw) */
    @Column(name = "mdd_peak_capital", precision = 20, scale = 2)
    private BigDecimal mddPeakCapital;

    /** 서킷 브레이커 발동 시각 */
    @Column(name = "circuit_breaker_triggered_at")
    private Instant circuitBreakerTriggeredAt;

    /** 서킷 브레이커 발동 사유 */
    @Column(name = "circuit_breaker_reason", length = 255)
    private String circuitBreakerReason;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * 낙관적 락 버전 — 20260415_analy.md Tier 2 §7 race 방지.
     *
     * <p>LiveTradingService·reconcile·WS 이벤트·finalize 가 동일 세션의
     * {@code availableKrw}/{@code totalAssetKrw} 를 read-modify-write 하던 중 last-write-wins
     * 덮어쓰기가 발생하던 문제(잔고 드리프트) 방지용. JPA 가 UPDATE 시 WHERE version=? 절을 추가해
     * 충돌이 있으면 {@code ObjectOptimisticLockingFailureException} 을 던진다. 호출부는
     * {@code SessionBalanceUpdater.apply()} 로 감싸 자동 retry 한다.</p>
     */
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        if (status == null) status = "CREATED";
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
        if (stopLossPct == null) stopLossPct = new BigDecimal("5.0");
        if (investRatio == null) investRatio = new BigDecimal("0.8000");
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

    public BigDecimal getInvestRatio() { return investRatio; }
    public void setInvestRatio(BigDecimal investRatio) { this.investRatio = investRatio; }

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

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public BigDecimal getMddPeakCapital() { return mddPeakCapital; }
    public void setMddPeakCapital(BigDecimal mddPeakCapital) { this.mddPeakCapital = mddPeakCapital; }

    public Instant getCircuitBreakerTriggeredAt() { return circuitBreakerTriggeredAt; }
    public void setCircuitBreakerTriggeredAt(Instant circuitBreakerTriggeredAt) { this.circuitBreakerTriggeredAt = circuitBreakerTriggeredAt; }

    public String getCircuitBreakerReason() { return circuitBreakerReason; }
    public void setCircuitBreakerReason(String circuitBreakerReason) { this.circuitBreakerReason = circuitBreakerReason; }
}
