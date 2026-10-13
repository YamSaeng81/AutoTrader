package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 정산 불일치 이력 (V82, 2026-10-12).
 *
 * <p>🔴 <b>감소 응답을 자동 반영하거나 폐기하지 않는다.</b> 실측값끼리 감소하는 응답(이전 보고값보다
 * 작은 수수료·체결금액·체결량)은 거래소의 정산 정정일 수도, 우리 쪽 해석 오류일 수도 있다. 어느 쪽인지
 * 모르므로 <b>덮어쓰기 전에 이전 값과 수신값을 함께 보존</b>하고 사람이 확인한다.</p>
 *
 * <p>컬럼이 아니라 테이블로 둔 이유: 같은 주문이 <b>여러 번 어긋날 수 있고</b> 그 이력을 모두 남겨야
 * 한다. 컬럼 한 쌍으로는 마지막 것만 남는다.</p>
 */
@Entity
@Table(name = "order_settlement_discrepancy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderSettlementDiscrepancyEntity {

    /** 이전 보고값보다 수수료가 줄었다. */
    public static final String KIND_FEE_DECREASE = "FEE_DECREASE";
    /** 이전 보고값보다 체결금액이 줄었다. */
    public static final String KIND_FUNDS_DECREASE = "FUNDS_DECREASE";
    /** 이전 반영값보다 누적 체결량이 줄었다. */
    public static final String KIND_VOLUME_DECREASE = "VOLUME_DECREASE";
    /** 종결된 주문에 활성 상태 응답이 도착했다. */
    public static final String KIND_STATE_REGRESS = "STATE_REGRESS";
    /** 포지션에 이미 반영한 매도의 누적 체결량이 늘었다 (2026-10-13) — 자동 반영하지 않는다. */
    public static final String KIND_VOLUME_AFTER_APPLY = "VOLUME_AFTER_APPLY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    /** 덮어쓰기 전의 값. 금액·수량 불일치에 쓴다. */
    @Column(name = "previous_value", precision = 20, scale = 8)
    private BigDecimal previousValue;

    /** 수신한 값. */
    @Column(name = "received_value", precision = 20, scale = 8)
    private BigDecimal receivedValue;

    /** 상태 역행에 쓴다. */
    @Column(name = "previous_state", length = 20)
    private String previousState;

    @Column(name = "received_state", length = 20)
    private String receivedState;

    /** 🔴 사람이 확인하기 전까지 미해결로 남는다. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @PrePersist
    void onCreate() {
        if (detectedAt == null) detectedAt = Instant.now();
    }
}
