package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 주문 엔티티 — V4 order 테이블 매핑
 * "order"는 SQL 예약어이므로 테이블명을 쌍따옴표로 감싼다.
 */
@Entity
@Table(name = "\"order\"")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(nullable = false, length = 4)
    private String side;

    @Column(name = "order_type", nullable = false, length = 10)
    private String orderType;

    @Column(precision = 20, scale = 8)
    private BigDecimal price;

    /**
     * ⚠️ <b>단위가 두 가지다.</b> 시장가 매수(MARKET+BUY)는 Upbit {@code price} 타입 주문이라
     * 이 컬럼에 <b>KRW 총액</b>이 들어가고, 그 외(시장가 매도·지정가)는 <b>코인 수량</b>이 들어간다.
     *
     * <p>따라서 {@code price × quantity} 로 체결금액을 계산하면 시장가 매수 행에서 값이 폭주한다.
     * 읽을 때는 {@link com.cryptoautotrader.api.util.OrderAmounts} 를 쓸 것 —
     * 코인 수량은 {@code coinQuantity()}, KRW 금액은 {@code krwAmount()}.</p>
     */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal quantity;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(name = "exchange_order_id", length = 100)
    private String exchangeOrderId;

    @Column(name = "filled_quantity", precision = 20, scale = 8)
    private BigDecimal filledQuantity;

    /** 실제 사용된 KRW 금액 — price-type 매수 부분체결 후 취소 시 미사용 KRW 복원에 사용 */
    @Column(name = "executed_funds", precision = 20, scale = 8)
    private BigDecimal executedFunds;

    @Column(name = "signal_reason", columnDefinition = "TEXT")
    private String signalReason;

    /**
     * 신호/트리거 시점 가격 (V54) — 주문 생성 시 설정. 체결 후 order.price는 실제 체결가로
     * 덮이므로, drift 측정(신호가 vs 체결가)의 기준가는 여기서 보존한다.
     */
    @Column(name = "signal_price", precision = 20, scale = 8)
    private BigDecimal signalPrice;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "filled_at")
    private Instant filledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "failed_reason", columnDefinition = "TEXT")
    private String failedReason;

    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "session_id")
    private Long sessionId;

    /**
     * 소속 세션 테이블 구분 — LIVE(live_trading_session) / DYNAMIC(dynamic_session).
     * position.session_kind(V51)와 동일한 이유로 필요 — 두 세션 테이블이 별도 BIGSERIAL 이라
     * session_id 값이 겹칠 수 있다.
     */
    @Column(name = "session_kind", nullable = false, length = 10)
    private String sessionKind;

    @PrePersist
    void prePersist() {
        if (state == null) state = "PENDING";
        if (createdAt == null) createdAt = Instant.now();
        if (filledQuantity == null) filledQuantity = BigDecimal.ZERO;
        if (sessionKind == null) sessionKind = "LIVE";
    }

    // ── 명시적 getter/setter ──────────────────────────────────

    public Long getId() { return id; }

    public Long getPositionId() { return positionId; }
    public void setPositionId(Long positionId) { this.positionId = positionId; }

    public String getCoinPair() { return coinPair; }
    public void setCoinPair(String coinPair) { this.coinPair = coinPair; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getExchangeOrderId() { return exchangeOrderId; }
    public void setExchangeOrderId(String exchangeOrderId) { this.exchangeOrderId = exchangeOrderId; }

    public BigDecimal getFilledQuantity() { return filledQuantity; }
    public void setFilledQuantity(BigDecimal filledQuantity) { this.filledQuantity = filledQuantity; }

    // ── 정산: 거래소 보고 원본과 회계 반영값의 분리 (2026-10-07, V81) ──────────
    //
    // 🔴 원본(reported_*)과 반영값(applied_*)을 섞지 않는다. 한 컬럼에 쓰면 같은 응답이 다시
    //    와도 "이미 반영했는지" 를 구분할 수 없다. NULL 은 **0 원이 아니라 "모른다"** 다 —
    //    수수료 미보고를 0 으로 처리하면 "수수료가 없었다" 가 되고, applied_* 를 0 으로 채우면
    //    기존 주문의 반영분을 다시 차감할 수 있다.

    @Column(name = "reported_executed_funds", precision = 20, scale = 8)
    private BigDecimal reportedExecutedFunds;

    @Column(name = "reported_paid_fee", precision = 20, scale = 8)
    private BigDecimal reportedPaidFee;

    @Column(name = "applied_executed_funds", precision = 20, scale = 8)
    private BigDecimal appliedExecutedFunds;

    @Column(name = "applied_paid_fee", precision = 20, scale = 8)
    private BigDecimal appliedPaidFee;

    /** {@code ESTIMATED}=자체 계산 추정값 / {@code REPORTED}=거래소 보고값. NULL=전환 이전 데이터. */
    @Column(name = "fee_source", length = 10)
    private String feeSource;

    /**
     * 정산 반영의 동시 쓰기 제어 카운터.
     *
     * <p>🔴 이것은 <b>동시 쓰기만</b> 막는다. 응답의 <b>최신성은 판단하지 못한다</b> — 오래된 응답이
     * 최신 버전을 읽고 통과할 수 있으므로, 누적 단조성·종결 역행 금지를 코드에서 따로 건다.</p>
     */
    @Column(name = "settlement_version", nullable = false)
    @Builder.Default
    private Long settlementVersion = 0L;

    /**
     * 정산 확정 여부. {@code false} 인 동안만 재조회 대상이다.
     *
     * <p>🔴 재조회 대상은 <b>"보고값 ≠ 반영값" 보다 넓다.</b> 보고값과 반영값이 같거나 둘 다
     * {@code null} 이어도 나중에 체결금액·수수료가 도착할 수 있다(시장가 매도의 {@code trades} 가
     * 뒤늦게 채워지는 사례). 그래서 대상 조건은 <b>"정산이 아직 확정되지 않았다"</b> 다.</p>
     *
     * <p>🔴 <b>조회 횟수 소진으로 이 값이 {@code true} 가 되지 않는다.</b> 확정은 거래소 응답으로
     * 결말이 났을 때, 또는 사람이 판정해 불일치로 보존했을 때만 성립한다.</p>
     */
    @Column(name = "settlement_final", nullable = false)
    @Builder.Default
    private Boolean settlementFinal = Boolean.FALSE;

    /** 다음 정산 조회 시각(백오프). NULL 이면 즉시 대상. */
    @Column(name = "settlement_next_check_at")
    private Instant settlementNextCheckAt;

    /** 정산 조회 시도 횟수. 백오프 계산과 **사람 판정 상향** 기준이며 자동 종결 근거가 아니다. */
    @Column(name = "settlement_attempts", nullable = false)
    @Builder.Default
    private Integer settlementAttempts = 0;

    // ── 체결 관측 (V83) — 회계 반영과 **독립적으로** 보존한다 ─────────────────

    @Column(name = "reported_executed_volume", precision = 20, scale = 8)
    private BigDecimal reportedExecutedVolume;

    /** 거래소가 보고한 상태 원문(wait/done/cancel). 로컬 {@code state} 와 구분한다. */
    @Column(name = "reported_state", length = 20)
    private String reportedState;

    @Column(name = "reported_observed_at")
    private Instant reportedObservedAt;

    /**
     * 체결을 관측했으나 회계를 반영하지 못한 상태 (V83).
     *
     * <p>🔴 거래소 체결과 DB 커밋은 원자적으로 만들 수 없다. 그 간극을 없애는 대신 <b>명시적으로
     * 추적</b>한다 — 관측을 먼저 저장하고 이 표시를 세운 뒤, 별도 트랜잭션에서 회계를 반영한다.
     * 반영이 실패해도 <b>재처리할 근거가 남는다.</b></p>
     *
     * <p>🔴 이 표시가 있는 주문의 세션은 <b>신규 매수를 막는다</b> — 포지션 수량이 아직 0 이라
     * 슬롯 점유로 보이지 않으므로, 평가 루프가 "포지션 없음" 으로 보고 재진입하는 것을 막아야 한다.
     * <b>추적과 위험 축소(청산·손절)는 막지 않는다.</b></p>
     */
    @Column(name = "fill_accounting_pending", nullable = false)
    @Builder.Default
    private Boolean fillAccountingPending = Boolean.FALSE;

    public BigDecimal getReportedExecutedVolume() { return reportedExecutedVolume; }
    public void setReportedExecutedVolume(BigDecimal v) { this.reportedExecutedVolume = v; }

    public String getReportedState() { return reportedState; }
    public void setReportedState(String v) { this.reportedState = v; }

    public Instant getReportedObservedAt() { return reportedObservedAt; }
    public void setReportedObservedAt(Instant v) { this.reportedObservedAt = v; }

    public Boolean getFillAccountingPending() { return fillAccountingPending; }
    public void setFillAccountingPending(Boolean v) { this.fillAccountingPending = v; }

    public Boolean getSettlementFinal() { return settlementFinal; }
    public void setSettlementFinal(Boolean v) { this.settlementFinal = v; }

    public Instant getSettlementNextCheckAt() { return settlementNextCheckAt; }
    public void setSettlementNextCheckAt(Instant v) { this.settlementNextCheckAt = v; }

    public Integer getSettlementAttempts() { return settlementAttempts; }
    public void setSettlementAttempts(Integer v) { this.settlementAttempts = v; }

    public BigDecimal getReportedExecutedFunds() { return reportedExecutedFunds; }
    public void setReportedExecutedFunds(BigDecimal v) { this.reportedExecutedFunds = v; }

    public BigDecimal getReportedPaidFee() { return reportedPaidFee; }
    public void setReportedPaidFee(BigDecimal v) { this.reportedPaidFee = v; }

    public BigDecimal getAppliedExecutedFunds() { return appliedExecutedFunds; }
    public void setAppliedExecutedFunds(BigDecimal v) { this.appliedExecutedFunds = v; }

    public BigDecimal getAppliedPaidFee() { return appliedPaidFee; }
    public void setAppliedPaidFee(BigDecimal v) { this.appliedPaidFee = v; }

    public String getFeeSource() { return feeSource; }
    public void setFeeSource(String v) { this.feeSource = v; }

    public Long getSettlementVersion() { return settlementVersion; }
    public void setSettlementVersion(Long v) { this.settlementVersion = v; }

    public BigDecimal getExecutedFunds() { return executedFunds; }
    public void setExecutedFunds(BigDecimal executedFunds) { this.executedFunds = executedFunds; }

    public String getSignalReason() { return signalReason; }
    public void setSignalReason(String signalReason) { this.signalReason = signalReason; }

    public BigDecimal getSignalPrice() { return signalPrice; }
    public void setSignalPrice(BigDecimal signalPrice) { this.signalPrice = signalPrice; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public Instant getFilledAt() { return filledAt; }
    public void setFilledAt(Instant filledAt) { this.filledAt = filledAt; }

    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }

    public String getFailedReason() { return failedReason; }
    public void setFailedReason(String failedReason) { this.failedReason = failedReason; }

    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getSessionKind() { return sessionKind; }
    public void setSessionKind(String sessionKind) { this.sessionKind = sessionKind; }
}
