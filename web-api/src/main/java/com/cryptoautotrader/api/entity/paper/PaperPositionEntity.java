package com.cryptoautotrader.api.entity.paper;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "position", schema = "paper_trading")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaperPositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(name = "side", nullable = false, length = 4)
    private String side;  // BUY | SELL

    @Column(name = "entry_price", nullable = false)
    private BigDecimal entryPrice;

    @Column(name = "avg_price", nullable = false)
    private BigDecimal avgPrice;

    @Column(name = "size", nullable = false)
    private BigDecimal size;

    @Column(name = "unrealized_pnl")
    private BigDecimal unrealizedPnl;

    @Column(name = "realized_pnl")
    private BigDecimal realizedPnl;

    /** 포지션 누적 수수료 (매수 수수료 + 매도 수수료 합산) */
    @Column(name = "position_fee", nullable = false)
    private BigDecimal positionFee;

    /** 어떤 전략이 진입했는지 기록 (strategy_config_id 대신 이름으로 관리) */
    @Column(name = "strategy_config_id")
    private Long strategyConfigId;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "status", length = 10)
    private String status;  // OPEN | CLOSED

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** 진입 시 계산된 손절가 (null이면 손절 미적용) */
    @Column(name = "stop_loss_price")
    private BigDecimal stopLossPrice;

    /** 진입 시 계산된 익절가 (null이면 익절 미적용) */
    @Column(name = "take_profit_price")
    private BigDecimal takeProfitPrice;

    @PrePersist
    void prePersist() {
        if (openedAt == null) openedAt = Instant.now();
        if (status == null) status = "OPEN";
        if (unrealizedPnl == null) unrealizedPnl = BigDecimal.ZERO;
        if (realizedPnl == null) realizedPnl = BigDecimal.ZERO;
        if (positionFee == null) positionFee = BigDecimal.ZERO;
    }

    /**
     * 이 행을 만든 매매 규칙 지문 (V71, 2026-08-19).
     *
     * <p>규칙이 바뀌어도 과거 데이터를 버리지 않기 위한 라벨이다 — 같은 지문끼리만 합산하면
     * 표본이 오염되지 않는다. NULL 은 V71 이전 데이터로 <b>규칙 미상</b>을 뜻하며,
     * 소급 추정하지 않는다(근거가 없다). 원문은 {@code ruleset_snapshot} 에서 역참조한다.</p>
     */
    @Column(name = "ruleset_hash", length = 16)
    private String rulesetHash;

    public String getRulesetHash() { return rulesetHash; }
    public void setRulesetHash(String rulesetHash) { this.rulesetHash = rulesetHash; }

    // ── V73 (2026-08-19): public.position 과 컬럼을 맞춘다 ─────────────────────
    // 페이퍼 함대와 동적 세션 성과를 같은 쿼리로 볼 수 없던 비대칭을 없앤다.
    // closing_at 은 넣지 않는다 — closePosition 이 동기라 CLOSING 중간 상태가 없다.

    /** 청산 사유. {@link com.cryptoautotrader.api.entity.ExitReason} 참조. */
    @Enumerated(EnumType.STRING)
    @Column(name = "exit_reason", length = 20)
    private com.cryptoautotrader.api.entity.ExitReason exitReason;

    /** 진입 시점 시장 레짐 — 이게 없으면 "이 전략은 횡보장에서만 되는가" 를 물을 수 없다. */
    @Column(name = "market_regime", length = 20)
    private String marketRegime;

    /** 진입에 실제로 투입한 원화. size × entryPrice 로 재계산하지 않아도 되게 남긴다. */
    @Column(name = "invested_krw", precision = 20, scale = 8)
    private BigDecimal investedKrw;

    /** 이 포지션을 만든 엔진 — public.position.session_kind 와 같은 어휘를 쓴다. */
    @Column(name = "session_kind", length = 20)
    private String sessionKind;

    public com.cryptoautotrader.api.entity.ExitReason getExitReason() { return exitReason; }
    public void setExitReason(com.cryptoautotrader.api.entity.ExitReason v) { this.exitReason = v; }
    public String getMarketRegime() { return marketRegime; }
    public void setMarketRegime(String v) { this.marketRegime = v; }
    public BigDecimal getInvestedKrw() { return investedKrw; }
    public void setInvestedKrw(BigDecimal v) { this.investedKrw = v; }
    public String getSessionKind() { return sessionKind; }
    public void setSessionKind(String v) { this.sessionKind = v; }
}
