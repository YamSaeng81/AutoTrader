package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.OrderRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 시험이 직접 닫은 REAL 포지션에 <b>확정 주문 원천</b>을 남긴다 (총괄 v1.192 §2 맞춤, 시험 전용).
 *
 * <p>비용 기준 판정은 주문 원천 없는 청산을 저장 손익으로 대신하지 않고 판정 불가로 본다. 실제 청산은 체결 매수와 확정 매도를 남기므로,
 * 저장 손익만 둔 고정물에 같은 손익의 확정 BUY(수수료 0)·확정 SELL(수수료 0, 반영 시각 = 청산 시각)을 붙인다.
 * 비용 기준 손익 = 매도 대금 − 매수 대금 = {@code pnl}. 세션 연속 손실은 포지션 id 로 원천을 읽으므로 주문에 세션 id 를 두지 않는다.</p>
 */
final class CostSourceFixture {
    private CostSourceFixture() {}

    static void roundTrip(OrderRepository orders, JdbcTemplate jdbc, PositionEntity p,
                          BigDecimal qty, BigDecimal buyFunds, BigDecimal pnl, Instant appliedAt) {
        save(orders, p, "BUY", qty, buyFunds);
        Long sellId = save(orders, p, "SELL", qty, buyFunds.add(pnl));
        jdbc.update("UPDATE \"order\" SET sell_applied_at = ? WHERE id = ?",
                Timestamp.from(appliedAt != null ? appliedAt : Instant.now()), sellId);
    }

    private static Long save(OrderRepository orders, PositionEntity p, String side, BigDecimal qty, BigDecimal funds) {
        return orders.save(OrderEntity.builder()
                .coinPair(p.getCoinPair()).side(side).orderType("MARKET")
                .quantity(qty).filledQuantity(qty)
                .state("FILLED").sessionKind(p.getSessionKind()).positionId(p.getId())   // session_id 없음 — 세션 주문 목록·전역 손실 합(세션 매도만)에 섞이지 않게(종전 저장 행과 같은 전역 범위)
                .appliedExecutedFunds(funds).appliedPaidFee(BigDecimal.ZERO)
                .feeSource("REPORTED")
                .clientOrderId("csf-" + UUID.randomUUID())
                .build()).getId();
    }
}
