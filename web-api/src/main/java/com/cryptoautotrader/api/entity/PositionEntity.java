package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 포지션 엔티티 — V4 position 테이블 매핑
 */
@Entity
@Table(name = "position")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(nullable = false, length = 4)
    private String side;

    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "avg_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal avgPrice;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal size;

    @Column(name = "unrealized_pnl", precision = 20, scale = 8)
    private BigDecimal unrealizedPnl;

    @Column(name = "realized_pnl", precision = 20, scale = 8)
    private BigDecimal realizedPnl;

    @Column(name = "strategy_config_id")
    private Long strategyConfigId;

    @Column(length = 10)
    private String status;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** CLOSING 상태 진입 시각 — 5분 초과 시 reconcileClosingPositions()에서 OPEN 롤백 */
    @Column(name = "closing_at")
    private Instant closingAt;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "position_fee", precision = 20, scale = 2)
    private BigDecimal positionFee;

    /** 매수 시 차감된 KRW 금액 — 주문 엔티티 없이도 KRW 복원 가능하도록 포지션에 직접 저장 */
    @Column(name = "invested_krw", precision = 20, scale = 8)
    private BigDecimal investedKrw;

    /** 진입 시 계산된 손절가 (null이면 세션 stopLossPct 기반 % 비교로 대체) */
    @Column(name = "stop_loss_price", precision = 20, scale = 8)
    private BigDecimal stopLossPrice;

    /** 진입 시 계산된 익절가 (null이면 익절 자동 청산 미적용) */
    @Column(name = "take_profit_price", precision = 20, scale = 8)
    private BigDecimal takeProfitPrice;

    /** 진입 시점 시장 레짐 (TREND / RANGE / VOLATILITY / TRANSITIONAL) */
    @Column(name = "market_regime", length = 20)
    private String marketRegime;

    @PrePersist
    void prePersist() {
        if (status == null) status = "OPEN";
        if (openedAt == null) openedAt = Instant.now();
        if (unrealizedPnl == null) unrealizedPnl = BigDecimal.ZERO;
        if (realizedPnl == null) realizedPnl = BigDecimal.ZERO;
        if (positionFee == null) positionFee = BigDecimal.ZERO;
    }

}
