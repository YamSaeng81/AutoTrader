package com.cryptoautotrader.api.entity.paper;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

    /**
     * 손절률(%) — NULL이면 {@code risk_config} 기본값(5.0)으로 폴백.
     * LIVE와 마찬가지로 ATR 기반 손절폭 산정({@code ExitRuleCalculator})의 <b>하한</b>으로 쓰인다. (V66)
     */
    @Column(name = "stop_loss_pct")
    private BigDecimal stopLossPct;

    /** 투자 비율(0.1~1.0) — NULL이면 {@code risk_config} 기본값(0.80). (V66) */
    @Column(name = "invest_ratio")
    private BigDecimal investRatio;

    /**
     * 최대 보유시간(시) — time stop. 0 이하면 비활성. (V66)
     *
     * <p>미지정 시 {@link com.cryptoautotrader.api.entity.LiveTradingSessionEntity#DEFAULT_MAX_HOLD_HOURS}
     * 로 폴백한다 — 페이퍼가 LIVE 예측에 쓰이려면 time stop 유무가 갈리면 안 된다(2026-08-18).</p>
     */
    @Column(name = "max_hold_hours")
    private Integer maxHoldHours;

    /** 세션 누적 실현 손익 (매도 체결 시마다 합산) */
    @Column(name = "realized_pnl", nullable = false)
    @Builder.Default
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    /** 세션 누적 수수료 (매수 + 매도 수수료 합산) */
    @Column(name = "total_fee", nullable = false)
    @Builder.Default
    private BigDecimal totalFee = BigDecimal.ZERO;

    /** 낙관적 락 — 동시 업데이트 시 덮어쓰기 방지 */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /**
     * 세션별 전략 파라미터 오버라이드 (V74, 2026-08-19) — A/B 실험용.
     *
     * <p>{@code emaFilterDampenFactor}, {@code emaFilterDeadbandPct},
     * {@code weakThreshold}, {@code strongThreshold} 등을 세션마다 다르게 줄 수 있다.
     * 이 값들은 원래 {@code risk_config} 전역값이라 바꾸면 모든 세션이 함께 움직여
     * 대조군을 만들 수 없었다.</p>
     *
     * <p>NULL 이면 기존 동작 그대로(전역값 → 코드 기본값)다. <b>지문에 실리므로</b>
     * 서로 다른 파라미터의 거래는 다른 표본으로 갈린다 — 그게 A/B 의 전제다.</p>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_params", columnDefinition = "jsonb")
    private java.util.Map<String, Object> strategyParams;

    public java.util.Map<String, Object> getStrategyParams() { return strategyParams; }
    public void setStrategyParams(java.util.Map<String, Object> v) { this.strategyParams = v; }
}
