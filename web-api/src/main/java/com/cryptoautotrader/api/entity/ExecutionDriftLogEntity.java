package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 20260415_analy.md Tier 3 §14 — 실전/백테스트 drift 트래커.
 *
 * <p>실전 매매 체결가와 신호 생성 시점의 가정 체결가 간 편차를 거래별로 저장한다.
 * 누적 drift 가 임계치를 초과하면 알림을 발생시켜 백테스트-실전 갭을 조기 감지한다.</p>
 */
@Entity
@Table(name = "execution_drift_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionDriftLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(name = "strategy_type", nullable = false, length = 80)
    private String strategyType;

    /** BUY 또는 SELL */
    @Column(name = "side", nullable = false, length = 10)
    private String side;

    /** 신호 생성 시 가정 체결가 (예: 캔들 종가) */
    @Column(name = "signal_price", nullable = false, precision = 20, scale = 2)
    private BigDecimal signalPrice;

    /** 실제 체결가 */
    @Column(name = "fill_price", nullable = false, precision = 20, scale = 2)
    private BigDecimal fillPrice;

    /** slippage(%) = (fillPrice - signalPrice) / signalPrice × 100 */
    @Column(name = "slippage_pct", nullable = false, precision = 10, scale = 6)
    private BigDecimal slippagePct;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
