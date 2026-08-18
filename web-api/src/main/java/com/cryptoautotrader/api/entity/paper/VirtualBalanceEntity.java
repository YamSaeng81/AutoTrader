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
}
