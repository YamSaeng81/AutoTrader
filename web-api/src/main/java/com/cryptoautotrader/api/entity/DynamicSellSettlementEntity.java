package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 동적 세션 매도 정산 멱등 표식 (V72, 2026-08-19 P0). 매도 1건당 1행.
 *
 * <p>{@code finalizeDynamicSell} 은 매도대금을 {@code REQUIRES_NEW} 로 <b>선커밋</b>하는데
 * 포지션을 CLOSED 로 바꾸는 일은 바깥 트랜잭션에 있다. 바깥이 롤백되면 포지션은 OPEN 으로
 * 되돌아오고 대금만 남아, 다음 시도에서 또 지급된다 — 운영 세션 49 는 이 루프로
 * {@code available_krw} 가 10,000 → 174,752 까지 불어났다.</p>
 *
 * <p>이 표식은 <b>대금 반영과 같은 트랜잭션</b>에 쓰인다. 둘이 함께 커밋되므로 바깥이
 * 롤백돼도 표식이 남고, 재시도는 대금을 건너뛴다. 기록 자체가 감사 로그이기도 하다.</p>
 *
 * <p><b>키 선택</b>: 주문의 {@code exchangeOrderId}. 페이퍼는
 * {@code "PAPER-DYNAMIC-SELL-{positionId}"} 로 포지션에서 결정되므로 롤백 후 재시도해도
 * 같은 값이 나온다. {@code order.id} 를 쓰면 주문 행이 롤백에 휩쓸려 사라진 뒤 재시도마다
 * 새 시퀀스 값이 생겨 막지 못한다.</p>
 */
@Entity
@Table(name = "dynamic_sell_settlement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DynamicSellSettlementEntity {

    /** 거래소 주문 ID(페이퍼는 포지션에서 결정되는 고정 문자열). 재시도에도 변하지 않아야 한다. */
    @Id
    @Column(name = "order_ref", length = 120, nullable = false)
    private String orderRef;

    @Column(name = "position_id", nullable = false)
    private Long positionId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "session_kind", length = 20, nullable = false)
    private String sessionKind;

    @Column(name = "sold_qty", precision = 30, scale = 8, nullable = false)
    private BigDecimal soldQty;

    @Column(name = "net_proceeds", precision = 20, scale = 8, nullable = false)
    private BigDecimal netProceeds;

    @Column(name = "realized_pnl", precision = 20, scale = 8, nullable = false)
    private BigDecimal realizedPnl;

    @Column(name = "settled_at", nullable = false)
    private Instant settledAt;

    @PrePersist
    void prePersist() {
        if (settledAt == null) settledAt = Instant.now();
    }
}
