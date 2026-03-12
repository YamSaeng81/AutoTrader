package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "backtest_trade")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacktestTradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "backtest_run_id", nullable = false)
    private Long backtestRunId;

    @Column(name = "side", nullable = false, length = 4)
    private String side;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private BigDecimal quantity;

    @Column(name = "fee")
    private BigDecimal fee;

    @Column(name = "slippage")
    private BigDecimal slippage;

    @Column(name = "pnl")
    private BigDecimal pnl;

    @Column(name = "cumulative_pnl")
    private BigDecimal cumulativePnl;

    @Column(name = "signal_reason", columnDefinition = "text")
    private String signalReason;

    @Column(name = "market_regime", length = 10)
    private String marketRegime;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;
}
