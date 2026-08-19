package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 전략 폐기 기준 판정 이력 (V70, 2026-08-19).
 *
 * <p><b>왜 필요한가</b>: 08-19 09:00 첫 판정이 정상 발동했는데 그 근거가 Discord 메시지에만
 * 남았다. {@code discord_send_log.message_preview} 는 102자에서 잘리므로
 * "PULLBACK_MTF@H1 이 언제 어떤 수치로 걸렸는가" 를 나중에 조회할 방법이 없었다.
 * 폐기는 되돌리기 어려운 결정인데(부활 경로가 Walk Forward 재검증뿐) 근거가 채팅에만 남는 것은
 * 거버넌스로 성립하지 않는다. {@code docs/KILL_CRITERIA.md} §5 참조.
 *
 * <p>KEEP 은 저장하지 않는다 — 매일 116행이 쌓여 신호 대 잡음비만 떨어진다.
 * 조치가 필요한 판정(KILL/WARN)만 남긴다.
 */
@Entity
@Table(name = "kill_criteria_judgment")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KillCriteriaJudgmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    /** LIVE | DYNAMIC | DYN_PAPER | PAPER */
    @Column(name = "session_kind", nullable = false, length = 20)
    private String sessionKind;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "strategy_type", length = 50)
    private String strategyType;

    @Column(name = "timeframe", length = 10)
    private String timeframe;

    /** KILL | WARN */
    @Column(nullable = false, length = 10)
    private String verdict;

    /** CAPITAL_LOSS | MAX_DRAWDOWN | CB_REPEAT | NEGATIVE_EV | NEGATIVE_ALPHA | NO_SIGNAL */
    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "trade_count", nullable = false)
    private Integer tradeCount;

    @Column(name = "return_pct", precision = 10, scale = 2)
    private BigDecimal returnPct;

    /** {@code kill-criteria.auto-stop} 이 켜져 있어 실제로 정지시켰는지. false 면 경보만 나갔다. */
    @Column(name = "auto_stop_applied", nullable = false)
    private Boolean autoStopApplied;

    @PrePersist
    void prePersist() {
        if (evaluatedAt == null) evaluatedAt = Instant.now();
        if (tradeCount == null) tradeCount = 0;
        if (autoStopApplied == null) autoStopApplied = Boolean.FALSE;
    }

    // -- 명시적 getter/setter (Lombok IDE 인식 문제 회피) --
    public Long getId() { return id; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public String getSessionKind() { return sessionKind; }
    public Long getSessionId() { return sessionId; }
    public String getStrategyType() { return strategyType; }
    public String getTimeframe() { return timeframe; }
    public String getVerdict() { return verdict; }
    public String getCode() { return code; }
    public String getReason() { return reason; }
    public Integer getTradeCount() { return tradeCount; }
    public BigDecimal getReturnPct() { return returnPct; }
    public Boolean getAutoStopApplied() { return autoStopApplied; }
}
