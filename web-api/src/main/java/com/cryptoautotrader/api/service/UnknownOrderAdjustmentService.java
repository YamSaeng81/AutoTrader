package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * <b>장기 {@code UNKNOWN} 주문의 수동 조정</b> — 사람이 종결하는 기록 경로 (2026-10-15, 선행 조건 5-i ·
 * {@code UNKNOWN_ORDER_STATE_DESIGN.md} §1.1·§7-16, V91).
 *
 * <h3>규칙</h3>
 * <ul>
 *   <li>🔴 <b>주문 상태를 바꾸지 않는다.</b> {@code FAILED} 는 "거래소가 거절했음을 응답으로 확인" 이고 사람의 판단은 그 증거가
 *       아니다. 주문은 {@code UNKNOWN} 으로 남고 식별자 조회도 이어진다.</li>
 *   <li>회계만 조정한다 — 세션 잔액에 {@code releasedKrw} 를 <b>한 번</b> 돌려준다(주문당 한 기록, 같은 트랜잭션).</li>
 *   <li>근거 · 금액 · 책임자 · 시각 · <b>미해결 위험</b>을 함께 남긴다. 셋(책임자·근거·미해결 위험)이 비면 받지 않는다.</li>
 *   <li>🔴 <b>공유 풀은 이 주문을 계속 미결로 센다</b> — 예약({@code reserved_krw})과 상태를 그대로 두므로 풀 상한·계좌 여력
 *       계산에서 빠지지 않는다. 사람의 판단이 틀렸어도(실제로는 체결됐어도) 공유 풀이 과대 승인하지 않는 쪽이다.
 *       그 대가로 해당 금액·슬롯은 거래소 결과가 나올 때까지 풀에서 묶여 있다.</li>
 *   <li>조정 뒤 거래소가 그 주문을 보고하면 <b>자동으로 정산하지 않는다</b> — 사람이 돌려준 금액과 거래소 결과가 겹치기 때문이다.
 *       관측을 이 기록에 덧붙이고 정산 불일치({@code AFTER_MANUAL_ADJ})로 남겨 사람이 다시 본다.</li>
 * </ul>
 *
 * <h3>대상</h3>
 * <p>실자금(LIVE·DYNAMIC) <b>시장가 매수</b>, 상태 {@code UNKNOWN}, 회계 반영값 없음, 만든 지 {@link #MIN_AGE} 이상. 돌려줄 금액은
 * 0 이상, 예약액 이하. 0 이면 기록만 남긴다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnknownOrderAdjustmentService {

    /** "장기" 의 기준 — 이보다 짧은 불명은 조회가 아직 해소할 수 있다. */
    public static final Duration MIN_AGE = Duration.ofMinutes(30);

    public static final String DISCREPANCY_KIND = "AFTER_MANUAL_ADJ";

    private final OrderRepository orderRepository;
    private final SessionBalanceUpdater liveBalanceUpdater;
    private final DynamicSessionBalanceUpdater dynamicBalanceUpdater;
    private final JdbcTemplate jdbc;

    public record Adjustment(Long orderId, String sessionKind, Long sessionId, String orderStateAt, String adjustedBy,
                             String reason, String evidence, BigDecimal releasedKrw, String unresolvedRisk,
                             Instant createdAt, String laterObservation, Instant laterObservedAt) {}

    @Transactional
    public Adjustment record(Long orderId, String adjustedBy, String reason, String evidence,
                             BigDecimal releaseKrw, String unresolvedRisk, Instant now) {
        if (blank(adjustedBy) || blank(reason) || blank(unresolvedRisk)) {
            throw new IllegalArgumentException("책임자·근거·미해결 위험은 비울 수 없다");
        }
        if (releaseKrw == null || releaseKrw.signum() < 0) {
            throw new IllegalArgumentException("돌려줄 금액은 0 이상이어야 한다");
        }
        // 주문 행을 잠근다 — 같은 순간의 식별자 조회·정산과 직렬화된다.
        OrderEntity order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문이 없다: " + orderId));
        if (!"UNKNOWN".equals(order.getState())) {
            throw new IllegalStateException("UNKNOWN 이 아닌 주문은 수동 조정하지 않는다(현재 " + order.getState() + ")");
        }
        if (!"BUY".equalsIgnoreCase(order.getSide()) || !"MARKET".equalsIgnoreCase(order.getOrderType())
                || !OrderSettlementService.isSharedPoolKind(order.getSessionKind()) || order.getSessionId() == null) {
            throw new IllegalStateException("실자금 시장가 매수만 대상이다");
        }
        if (order.getAppliedExecutedFunds() != null) {
            throw new IllegalStateException("회계 반영값이 있는 주문은 대상이 아니다 — 정산 불일치 경로로 본다");
        }
        Instant created = order.getCreatedAt() != null ? order.getCreatedAt() : order.getSubmittedAt();
        if (created == null || Duration.between(created, now).compareTo(MIN_AGE) < 0) {
            throw new IllegalStateException("만든 지 " + MIN_AGE.toMinutes() + "분이 지나지 않은 불명 주문은 조정하지 않는다");
        }
        BigDecimal cap = order.getReservedKrw() != null ? order.getReservedKrw() : order.getQuantity();
        if (cap == null || releaseKrw.compareTo(cap) > 0) {
            throw new IllegalArgumentException("돌려줄 금액 " + releaseKrw + " 이 예약액 " + cap + " 을 넘는다");
        }
        // 주문당 한 번 — PK 가 막는다. 같은 트랜잭션의 잔액 반영도 함께 롤백된다.
        int inserted = jdbc.update("INSERT INTO order_manual_adjustment (order_id, session_kind, session_id, order_state_at, "
                        + "adjusted_by, reason, evidence, released_krw, unresolved_risk, created_at) "
                        + "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM order_manual_adjustment WHERE order_id = ?)",
                order.getId(), order.getSessionKind(), order.getSessionId(), order.getState(), adjustedBy.trim(), reason.trim(),
                evidence, releaseKrw, unresolvedRisk.trim(), Timestamp.from(now), order.getId());
        if (inserted == 0) {
            throw new IllegalStateException("이미 수동 조정한 주문이다: " + orderId);
        }
        if (releaseKrw.signum() > 0) {
            if ("DYNAMIC".equals(order.getSessionKind())) {
                dynamicBalanceUpdater.applyWithin(order.getSessionId(), s -> s.setAvailableKrw(s.getAvailableKrw().add(releaseKrw)));
            } else {
                liveBalanceUpdater.applyWithin(order.getSessionId(), s -> s.setAvailableKrw(s.getAvailableKrw().add(releaseKrw)));
            }
        }
        log.warn("🔴 장기 UNKNOWN 수동 조정 — 주문 #{} 는 UNKNOWN 그대로, 세션 {}#{} 에 {} 반환. 책임자 {}, 근거: {}, 미해결 위험: {}",
                order.getId(), order.getSessionKind(), order.getSessionId(), releaseKrw.toPlainString(), adjustedBy, reason, unresolvedRisk);
        return find(orderId);
    }

    /** 조정 기록이 있는가 — 식별자 조회·정산이 자동 반영 전에 본다. */
    public boolean isAdjusted(Long orderId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM order_manual_adjustment WHERE order_id = ?", Integer.class, orderId);
        return n != null && n > 0;
    }

    /**
     * 조정 뒤 거래소가 그 주문을 보고했다 — 자동 정산 대신 관측을 남긴다. 같은 관측이 반복되면 다시 쓰지 않는다(불일치 한 건).
     * 🔴 호출자 트랜잭션과 따로 커밋한다 — 호출 경로가 롤백돼도 관측은 남는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLaterObservation(Long orderId, String exchangeState, BigDecimal executedVolume, String observation, Instant at) {
        int n = jdbc.update("UPDATE order_manual_adjustment SET later_observation = ?, later_observed_at = ? "
                + "WHERE order_id = ? AND (later_observation IS NULL OR later_observation <> ?)", observation, Timestamp.from(at), orderId, observation);
        if (n == 1) {
            String st = exchangeState == null ? null : (exchangeState.length() > 20 ? exchangeState.substring(0, 20) : exchangeState);
            jdbc.update("INSERT INTO order_settlement_discrepancy (order_id, detected_at, kind, received_value, previous_state, received_state) "
                    + "VALUES (?, ?, ?, ?, 'UNKNOWN', ?)", orderId, Timestamp.from(at), DISCREPANCY_KIND, executedVolume, st);
            log.error("🔴 수동 조정한 UNKNOWN 주문 #{} 를 거래소가 보고했다 — 자동 정산하지 않는다. 사람이 다시 본다: {}", orderId, observation);
        }
    }

    public Adjustment find(Long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM order_manual_adjustment WHERE order_id = ?", orderId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        return new Adjustment(((Number) get(r, "order_id")).longValue(), (String) get(r, "session_kind"),
                ((Number) get(r, "session_id")).longValue(), (String) get(r, "order_state_at"), (String) get(r, "adjusted_by"),
                (String) get(r, "reason"), (String) get(r, "evidence"), (BigDecimal) get(r, "released_krw"),
                (String) get(r, "unresolved_risk"), instant(get(r, "created_at")), (String) get(r, "later_observation"),
                instant(get(r, "later_observed_at")));
    }

    private static Object get(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v != null ? v : r.get(col.toUpperCase(java.util.Locale.ROOT));
    }

    private static Instant instant(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp t) return t.toInstant();
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant();
        if (v instanceof Instant i) return i;
        return Instant.parse(v.toString());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
