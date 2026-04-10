package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "strategy_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false, length = 50)
    private String strategyName;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(length = 10)
    private String signal;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "indicators_json", columnDefinition = "jsonb")
    private String indicatorsJson;

    @Column(name = "market_regime", length = 10)
    private String marketRegime;

    /** PAPER / LIVE */
    @Column(name = "session_type", length = 10)
    private String sessionType;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "created_at")
    private Instant createdAt;

    // ── 신호 품질 추적 (V31) ──────────────────────────────────────

    /** 신호 발생 시점 현재가 */
    @Column(name = "signal_price", precision = 20, scale = 8)
    private BigDecimal signalPrice;

    /** 실제 매수/매도 주문이 제출됐는지 여부 */
    @Column(name = "was_executed", nullable = false)
    @Builder.Default
    private boolean wasExecuted = false;

    /** 신호가 차단된 경우 이유 (리스크 한도 초과, 이미 포지션 보유 등) */
    @Column(name = "blocked_reason", columnDefinition = "TEXT")
    private String blockedReason;

    /** 신호 발생 4시간 후 가격 (스케줄러가 채움) */
    @Column(name = "price_after_4h", precision = 20, scale = 8)
    private BigDecimal priceAfter4h;

    /** 신호 발생 24시간 후 가격 (스케줄러가 채움) */
    @Column(name = "price_after_24h", precision = 20, scale = 8)
    private BigDecimal priceAfter24h;

    /** 신호 방향 기준 4시간 후 수익률 (%) — BUY: 상승이 +, SELL: 하락이 + */
    @Column(name = "return_4h_pct", precision = 8, scale = 4)
    private BigDecimal return4hPct;

    /** 신호 방향 기준 24시간 후 수익률 (%) */
    @Column(name = "return_24h_pct", precision = 8, scale = 4)
    private BigDecimal return24hPct;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
