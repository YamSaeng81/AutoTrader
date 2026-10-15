package com.cryptoautotrader.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * <b>매도가 막힌(격리된) 실자금 포지션</b>을 기동 때 알린다 (2026-10-15 리뷰 7회차).
 *
 * <p>매도 입구는 같은 포지션에 <b>체결됐지만 반영 표시가 없는 매도</b>가 있으면 새 매도를 내지 않는다
 * ({@link OrderExecutionEngine} 이중 매도 차단). 그런 매도는 두 가지다 — 정지 롤백으로 반영이 되돌아간 매도(정리·확정이 반영하면
 * 풀린다), 그리고 <b>V87 이전</b>(반영 표시가 없던 시절)의 매도. 후자는 반영됐는지 판별할 수 없으므로 안전하다고 간주하지 않고
 * 막는다. 막힌 포지션은 손절도 새로 나가지 않으므로 <b>사람이 판정해야 한다.</b></p>
 *
 * <p>질의는 배포 전 대조 SQL({@code LIVE_RISK_IMPLEMENTATION_REVIEW.md} §6.8)과 같다. 읽기만 한다.</p>
 */
@Slf4j
@Component
public class SellQuarantineReport {

    static final String SQL = "SELECT p.id AS position_id, p.session_kind, p.session_id, p.coin_pair, p.status, p.size, "
            + "o.id AS order_id, o.state, o.filled_quantity, o.created_at "
            + "FROM position p JOIN \"order\" o ON o.position_id = p.id "
            + "WHERE p.session_kind IN ('LIVE', 'DYNAMIC') AND p.status IN ('OPEN', 'CLOSING') "
            + "AND o.side = 'SELL' AND o.sell_applied_at IS NULL AND o.filled_quantity > 0 AND o.state IN ('FILLED', 'CANCELLED') "
            + "ORDER BY p.id, o.id";

    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    private TelegramNotificationService telegram;

    public SellQuarantineReport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportAtStartup() {
        try {
            report();
        } catch (Exception e) {
            log.error("매도 격리 점검 실패 — 배포 전 대조 SQL 로 직접 확인하라: {}", e.toString());
        }
    }

    /** 시험에서도 쓰는 본체. 격리된 행을 돌려준다. */
    public List<Map<String, Object>> report() {
        List<Map<String, Object>> rows = jdbc.queryForList(SQL);
        if (rows.isEmpty()) {
            log.info("매도 격리 점검: 반영 표시 없이 체결된 매도가 있는 열린 실자금 포지션 없음");
            return rows;
        }
        String msg = "🔴 매도가 막힌 실자금 포지션 " + rows.stream().map(r -> r.get("position_id")).distinct().count()
                + "개 — 반영 표시 없이 체결된 매도가 있어 새 매도(손절 포함)를 내지 않는다. 사람이 판정하라: " + rows;
        log.error(msg);
        if (telegram != null) {
            try {
                telegram.notifyRiskLimitBreached(msg);
            } catch (Exception e) {
                log.warn("매도 격리 경보 전송 실패: {}", e.getMessage());
            }
        }
        return rows;
    }
}
