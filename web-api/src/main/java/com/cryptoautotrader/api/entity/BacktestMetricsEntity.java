package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;

@Entity
@Table(name = "backtest_metrics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacktestMetricsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "backtest_run_id", nullable = false)
    private Long backtestRunId;

    @Column(name = "total_return_pct")
    private BigDecimal totalReturnPct;

    @Column(name = "win_rate_pct")
    private BigDecimal winRatePct;

    @Column(name = "mdd_pct")
    private BigDecimal mddPct;

    @Column(name = "sharpe_ratio")
    private BigDecimal sharpeRatio;

    @Column(name = "sortino_ratio")
    private BigDecimal sortinoRatio;

    @Column(name = "calmar_ratio")
    private BigDecimal calmarRatio;

    @Column(name = "win_loss_ratio")
    private BigDecimal winLossRatio;

    @Column(name = "recovery_factor")
    private BigDecimal recoveryFactor;

    @Column(name = "total_trades")
    private Integer totalTrades;

    @Column(name = "winning_trades")
    private Integer winningTrades;

    @Column(name = "losing_trades")
    private Integer losingTrades;

    @Column(name = "avg_profit_pct")
    private BigDecimal avgProfitPct;

    @Column(name = "avg_loss_pct")
    private BigDecimal avgLossPct;

    @Column(name = "max_consecutive_loss")
    private Integer maxConsecutiveLoss;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "monthly_returns_json", columnDefinition = "jsonb")
    private Map<String, BigDecimal> monthlyReturnsJson;

    @Column(name = "segment", length = 20)
    private String segment;
}
