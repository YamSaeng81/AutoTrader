package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * <b>매도 격리 해제</b> — 사람이 판정하고 기록을 남기는 반영 표시 (2026-10-19, V94 · {@code LIVE_RISK_IMPLEMENTATION_REVIEW.md} §6.8-③).
 *
 * <h3>무엇을 푸는가</h3>
 * <p>열린 실자금 포지션에 <b>체결됐지만 반영 표시({@code sell_applied_at})가 없는 매도</b>가 있으면 매도 입구가 새 매도(손절 포함)를 막는다 — 격리.
 * V87 이전(반영 표시가 없던 시절)의 매도는 반영됐는지 판별할 수 없어서 막는다. 그 상태는 위험이다: 청산 확정은 반영 표시 없는 체결 매도를 "아직 반영 안 됨" 으로
 * 보고 대금을 넣으므로, <b>이미 반영된 매도</b>가 표시 없이 남아 있으면 대금이 두 번 들어갈 수 있다.</p>
 *
 * <h3>규칙</h3>
 * <ul>
 *   <li>해제는 "<b>이 매도는 이미 포지션·잔액에 반영돼 있다</b>" 는 사람의 판정을 반영 표시로 남기는 것이다. <b>잔액·포지션 수량·주문 상태는 바꾸지 않는다.</b></li>
 *   <li>🔴 반영되지 않은 매도는 해제 대상이 아니다 — 해제하면 그 수량을 다시 팔 수 있게 되고(이미 팔린 코인을 또 판다), 대금도 빠진다.
 *       그래서 DB 로 확인할 수 있는 조건을 강제한다: <b>포지션 수량 = 체결 매수 합 − 체결 매도 합</b>(이 매도 포함). 맞지 않으면 이 매도가 수량에 빠지지 않은
 *       것으로 보고 거절한다.</li>
 *   <li>사람이 업비트 주문 내역과 대조한 <b>포지션 수량</b>을 함께 받는다 — 지금 DB 값과 다르면(대조 뒤 바뀌었으면) 거절한다.</li>
 *   <li>같은 포지션에 <b>미결 매도</b>(제출 중·불명·부분 체결 등)가 있으면 거절한다 — 그것은 식별자 조회가 결론 낼 일이다.</li>
 *   <li>책임자 · 근거 · 증거(업비트 주문 식별자·체결량 등)가 비면 받지 않는다. 주문당 한 번(PK).</li>
 *   <li>매도 제출과 같은 포지션 잠금({@link OrderExecutionEngine#lockSellSubmission})을 먼저 쥔다 — 해제와 새 매도 판정이 엇갈리지 않는다.</li>
 * </ul>
 *
 * <p>🔴 이 판정은 잔액까지 맞는지는 확인하지 않는다(V87 이전 대금 반영의 근거 열이 없다). 그 부분은 사람의 대조이며 증거 칸에 남긴다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellQuarantineReleaseService {

    private static final List<String> UNRESOLVED_SELL_STATES =
            List.of("PENDING", "INTENT_READY", "SUBMITTING", "UNKNOWN", "SUBMITTED", "PARTIAL_FILLED");

    private final OrderRepository orderRepository;
    private final OrderExecutionEngine orderExecutionEngine;
    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    private TelegramNotificationService telegram;

    public record Release(Long orderId, Long positionId, String sessionKind, Long sessionId, String orderStateAt,
                          BigDecimal orderFilledQty, String positionStatusAt, BigDecimal positionSizeAt,
                          BigDecimal buyFilledQty, BigDecimal sellFilledQty, String releasedBy, String reason,
                          String evidence, Instant releasedAt) {}

    /**
     * @param confirmedPositionSize 사람이 업비트 주문 내역과 대조한 이 포지션의 수량 — 지금 DB 값과 같아야 한다
     */
    @Transactional
    public Release release(Long orderId, String releasedBy, String reason, String evidence,
                           BigDecimal confirmedPositionSize, Instant now) {
        if (blank(releasedBy) || blank(reason) || blank(evidence)) {
            throw new IllegalArgumentException("책임자·근거·증거는 비울 수 없다");
        }
        if (confirmedPositionSize == null) {
            throw new IllegalArgumentException("대조한 포지션 수량을 적어야 한다");
        }
        OrderEntity peek = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문이 없다: " + orderId));
        if (peek.getPositionId() == null) {
            throw new IllegalStateException("포지션에 묶이지 않은 주문은 격리 대상이 아니다");
        }
        // 매도 제출과 같은 잠금 → 주문 행 잠금 순서(제출 경로와 같다)
        orderExecutionEngine.lockSellSubmission(peek.getPositionId());
        OrderEntity order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문이 없다: " + orderId));

        // ── 격리를 일으키는 매도인가 (SellQuarantineReport 와 같은 조건)
        if (!"SELL".equalsIgnoreCase(order.getSide()) || !OrderSettlementService.isSharedPoolKind(order.getSessionKind())) {
            throw new IllegalStateException("실자금 매도만 대상이다");
        }
        if (order.getSellAppliedAt() != null) {
            throw new IllegalStateException("이미 반영 표시가 있는 매도다 — 격리 대상이 아니다");
        }
        BigDecimal filled = nz(order.getFilledQuantity());
        if (filled.signum() <= 0 || !List.of("FILLED", "CANCELLED").contains(order.getState())) {
            throw new IllegalStateException("체결된 매도(FILLED·체결 후 CANCELLED)만 대상이다 (현재 " + order.getState() + ", 체결량 " + filled + ")");
        }

        List<Map<String, Object>> pos = jdbc.queryForList(
                "SELECT status, size FROM position WHERE id = ? FOR UPDATE", order.getPositionId());
        if (pos.isEmpty()) {
            throw new IllegalStateException("포지션이 없다: " + order.getPositionId());
        }
        String status = (String) col(pos.get(0), "status");
        BigDecimal size = scale((BigDecimal) col(pos.get(0), "size"));
        if (!"OPEN".equals(status) && !"CLOSING".equals(status)) {
            throw new IllegalStateException("열린 포지션(OPEN·CLOSING)의 매도만 격리된다 (현재 " + status + ") — 해제할 것이 없다");
        }
        if (size.compareTo(scale(confirmedPositionSize)) != 0) {
            throw new IllegalStateException("대조한 포지션 수량 " + confirmedPositionSize.toPlainString() + " 이 지금 DB 값 " + size.toPlainString()
                    + " 과 다르다 — 대조 뒤 바뀌었다. 다시 대조하라");
        }

        // ── 같은 포지션의 미결 매도 — 식별자 조회가 결론 낼 일이다
        Integer unresolved = jdbc.queryForObject("SELECT COUNT(*) FROM \"order\" WHERE position_id = ? AND side = 'SELL' "
                + "AND sell_applied_at IS NULL AND state IN ('" + String.join("','", UNRESOLVED_SELL_STATES) + "')",
                Integer.class, order.getPositionId());
        if (unresolved != null && unresolved > 0) {
            throw new IllegalStateException("같은 포지션에 미결 매도 " + unresolved + "건이 있다 — 거래소 조회로 결론 난 뒤 판정하라");
        }

        // ── 이 매도가 포지션 수량에 이미 빠져 있는가: 수량 = 체결 매수 합 − 체결 매도 합(이 매도 포함)
        BigDecimal buys = scale(jdbc.queryForObject("SELECT COALESCE(SUM(filled_quantity), 0) FROM \"order\" WHERE position_id = ? "
                + "AND side = 'BUY' AND filled_quantity > 0", BigDecimal.class, order.getPositionId()));
        BigDecimal sells = scale(jdbc.queryForObject("SELECT COALESCE(SUM(filled_quantity), 0) FROM \"order\" WHERE position_id = ? "
                + "AND side = 'SELL' AND filled_quantity > 0 AND state IN ('FILLED', 'CANCELLED')", BigDecimal.class, order.getPositionId()));
        if (buys.signum() <= 0) {
            throw new IllegalStateException("이 포지션의 체결 매수 기록이 없다 — 수량 근거를 DB 로 확인할 수 없어 해제하지 않는다");
        }
        BigDecimal expected = buys.subtract(sells);
        if (size.compareTo(expected) != 0) {
            throw new IllegalStateException("포지션 수량 " + size.toPlainString() + " ≠ 체결 매수 " + buys.toPlainString() + " − 체결 매도 "
                    + sells.toPlainString() + " = " + expected.toPlainString()
                    + " — 이 매도가 수량에 반영되지 않은 것으로 보인다. 해제 대상이 아니다(해제하면 이미 판 수량을 다시 팔 수 있다)");
        }

        // ── 기록과 반영 표시를 한 트랜잭션으로
        int inserted = jdbc.update("INSERT INTO sell_quarantine_release (order_id, position_id, session_kind, session_id, order_state_at, "
                        + "order_filled_qty, position_status_at, position_size_at, buy_filled_qty, sell_filled_qty, released_by, reason, "
                        + "evidence, released_at) SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? "
                        + "WHERE NOT EXISTS (SELECT 1 FROM sell_quarantine_release WHERE order_id = ?)",
                order.getId(), order.getPositionId(), order.getSessionKind(), order.getSessionId(), order.getState(), filled,
                status, size, buys, sells, releasedBy.trim(), reason.trim(), evidence.trim(), Timestamp.from(now), order.getId());
        if (inserted == 0) {
            throw new IllegalStateException("이미 해제한 매도다: " + orderId);
        }
        if (orderRepository.markSellApplied(order.getId(), now) != 1) {
            throw new IllegalStateException("반영 표시를 쓰지 못했다(다른 경로가 먼저 썼다) — 해제를 되돌린다");
        }
        String msg = String.format("매도 격리 해제 — 주문 #%d (포지션 #%d, %s#%d, 체결 %s) 를 '이미 반영됨' 으로 표시했다. 잔액·수량은 바꾸지 않았다. "
                        + "수량 %s = 매수 %s − 매도 %s. 책임자 %s, 근거: %s, 증거: %s",
                order.getId(), order.getPositionId(), order.getSessionKind(), order.getSessionId(), filled.toPlainString(),
                size.toPlainString(), buys.toPlainString(), sells.toPlainString(), releasedBy.trim(), reason.trim(), evidence.trim());
        log.info("매도 격리 해제 기록 작성 — 커밋 대기 (주문 #{})", order.getId());
        // 🔴 완료 알림은 커밋이 성공한 뒤에만 (리뷰 2026-10-19) — 트랜잭션 안에서 보내면 커밋이 실패해도 "해제했다" 가 나간다.
        //    롤백되면 기록·반영 표시·알림이 모두 남지 않는다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.warn("🔴 {}", msg);
                if (telegram != null) {
                    try {
                        telegram.notifyRiskLimitBreached(msg);
                    } catch (Exception e) {
                        log.warn("격리 해제 알림 전송 실패: {}", e.getMessage());
                    }
                }
            }
        });
        return find(orderId);
    }

    public Release find(Long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sell_quarantine_release WHERE order_id = ?", orderId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        return new Release(((Number) col(r, "order_id")).longValue(), ((Number) col(r, "position_id")).longValue(),
                (String) col(r, "session_kind"), ((Number) col(r, "session_id")).longValue(), (String) col(r, "order_state_at"),
                (BigDecimal) col(r, "order_filled_qty"), (String) col(r, "position_status_at"), (BigDecimal) col(r, "position_size_at"),
                (BigDecimal) col(r, "buy_filled_qty"), (BigDecimal) col(r, "sell_filled_qty"), (String) col(r, "released_by"),
                (String) col(r, "reason"), (String) col(r, "evidence"), instant(col(r, "released_at")));
    }

    private static Object col(Map<String, Object> r, String c) {
        Object v = r.get(c);
        return v != null ? v : r.get(c.toUpperCase(java.util.Locale.ROOT));
    }

    private static Instant instant(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp t) return t.toInstant();
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant();
        if (v instanceof Instant i) return i;
        return Instant.parse(v.toString());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(8, RoundingMode.HALF_UP);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
