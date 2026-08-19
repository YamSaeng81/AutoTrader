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

    /**
     * 소속 세션 테이블 구분 — LIVE(live_trading_session) / DYNAMIC(dynamic_session).
     * 두 세션 테이블 모두 BIGSERIAL 이라 session_id 값이 겹칠 수 있어 이 컬럼 없이는
     * 어느 세션 소속인지 구분할 수 없다.
     */
    @Column(name = "session_kind", nullable = false, length = 10)
    private String sessionKind;

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
        if (sessionKind == null) sessionKind = "LIVE";
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

    /**
     * 이 포지션이 <b>왜</b> 청산됐는가 (V73, 2026-08-19). {@link ExitReason} 참조.
     *
     * <p>{@code order.signal_reason} 자유 텍스트는 사람이 읽을 정보(구체적 가격·지표값)를,
     * 이 컬럼은 집계 가능한 축을 담당한다. NULL 은 V73 이전 데이터이거나 아직 미청산이다.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "exit_reason", length = 20)
    private ExitReason exitReason;

    public ExitReason getExitReason() { return exitReason; }
    public void setExitReason(ExitReason exitReason) { this.exitReason = exitReason; }
}
