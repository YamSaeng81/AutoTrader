package com.cryptoautotrader.api.service;

import com.cryptoautotrader.core.risk.KillCriteriaConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>포지션별 한도</b> — 선행 조건 1번 1-g (2026-10-15, {@code LIVE_ENTRY_PREREG.md} §6).
 *
 * <pre>
 * 매수 원가   = Σ 매수 (반영 대금 + 반영 수수료)                       ← 정산이 바꾸면 따라 바뀐다
 * 평가액      = Σ 매도 (반영 대금 − 반영 수수료) + 잔량 × 시세 × (1 − 수수료율)
 * 손익        = 평가액 − 매수 원가
 *
 * CAPITAL_LOSS : 손익            ≤ −15% × 진입 배정액     (배정액은 예약 때 한 번 쓰고 바꾸지 않는다)
 * MAX_DRAWDOWN : 평가액 − 고점   ≤ −20% × 고점           (고점 = 관측한 평가액의 최댓값, DB 에 남는다)
 * 대상         : 실자금(공유 풀) LIVE·DYNAMIC 의 OPEN·CLOSING, 잔량 > 0
 * </pre>
 *
 * <h3>세 경우에 분모와 고점이 유지되는 이유</h3>
 * <ul>
 *   <li><b>부분 청산</b> — 평가액에 이미 판 몫의 순대금이 들어 있다. 시세에 수수료율만큼 팔면 평가액이 그대로이므로, 잔량이 줄었다고
 *       낙폭이 생기지 않는다(잔량 평가액만 보면 절반을 판 순간 −50% 가 된다).</li>
 *   <li><b>추가 정산</b> — 매수 원가는 주문 반영값에서 다시 계산하므로 수수료 정정·추가 체결을 따라간다. 배정액은 {@code invested_krw}
 *       (정산이 덮어쓴다)가 아니라 따로 둔 {@code entry_allocation_krw} 이고, 엔티티 저장으로 바뀌지 않는다.</li>
 *   <li><b>프로세스 재시작</b> — 배정액·고점·도달 기록이 모두 DB 에 있다. 이 클래스는 메모리 상태가 없다. 고점은 조건부 UPDATE
 *       (더 클 때만)로만 올라가고 엔티티 저장 대상이 아니므로, 엔진이 먼저 읽어 둔 포지션을 저장해도 되돌아가지 않는다.</li>
 * </ul>
 *
 * <h3>도달하면</h3>
 * <p>포지션당 한 번 {@code position_limit_breach} 에 남긴다. 그 기록이 있는 세션의 실자금 신규 진입은 공유 예약 지점
 * ({@link CapitalReservationService#reserveForBuy}, 계좌 잠금 안)에서 거절된다 — LIVE·DYNAMIC 공통, 재시작 뒤에도. 정지·정리는
 * {@link PositionLimitMonitor} 가 잇는다.</p>
 *
 * <h3>🔴 한계</h3>
 * <ul>
 *   <li>고점은 <b>관측 주기</b>(감시 10초)의 평가액이다. 주기 사이의 순간 고점·프로세스가 멈춘 동안의 고점은 잡히지 않는다 —
 *       낙폭이 실제보다 작게 계산될 수 있다(늦게 발동하는 쪽).</li>
 *   <li>도달과 차단 사이에 감시 주기만큼의 간격이 있다. 그 사이의 신규 진입은 공유 풀 한도(누적·24시간)만 막는다.</li>
 *   <li>시세는 공유 풀 손실 한도와 같다({@link PoolLossGuard#markFor}) — 청산 슬리피지는 넣지 않았다. 🔴 단 <b>실시간 시세일 때만</b>
 *       판정한다({@link #MARK_UNAVAILABLE}). 시세가 끊긴 동안 포지션별 한도는 판정하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
public class PositionLimitGuard {

    public static final String POSITION_LIMIT = "POSITION_LIMIT";
    public static final String CAPITAL_LOSS = "CAPITAL_LOSS";
    public static final String MAX_DRAWDOWN = "MAX_DRAWDOWN";
    /** 반영값 없는 체결 주문이 있어 평가액을 계산하지 않았다. 고점도 올리지 않는다. */
    public static final String VALUATION_UNKNOWN = "VALUATION_UNKNOWN";
    /**
     * 실시간 시세가 없어(재시작 직후·구독 끊김) 대체 시세(평가 루프 값·평균단가)로만 평가할 수 있다. 고점을 올리지 않고 도달을 기록하지
     * 않는다 — 도달은 세션을 영구히 막고 되돌리지 않으므로 낡은 값으로 남기면 안 된다.
     */
    public static final String MARK_UNAVAILABLE = "MARK_UNAVAILABLE";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PoolLossGuard poolLossGuard;
    private final KillCriteriaConfig config = KillCriteriaConfig.defaults();

    @PersistenceContext
    private EntityManager em;

    public PositionLimitGuard(PoolLossGuard poolLossGuard) {
        this.poolLossGuard = poolLossGuard;
    }

    /**
     * 포지션 하나의 판정.
     *
     * @param code       {@code null} 이면 한도 안, 아니면 {@link #CAPITAL_LOSS}·{@link #MAX_DRAWDOWN}·{@link #VALUATION_UNKNOWN}
     * @param newBreach  이번 판정에서 도달 기록을 처음 남겼다
     */
    public record Evaluation(Long positionId, String sessionKind, Long sessionId, String coinPair,
                             BigDecimal cost, BigDecimal value, BigDecimal allocation, String allocationBasis,
                             BigDecimal peak, String mark, String code, String detail, boolean newBreach) {
        public BigDecimal pnl() {
            return value == null || cost == null ? null : value.subtract(cost);
        }
    }

    /** 실자금 열린 포지션 전부를 판정한다 — 고점을 올리고, 도달하면 기록한다. */
    @Transactional
    public List<Evaluation> evaluateAll(Instant now) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT p.id, p.session_kind, p.session_id, p.coin_pair, p.size, p.avg_price, p.unrealized_pnl, "
                        + "p.entry_allocation_krw, p.invested_krw, p.peak_value_krw, "
                        + "COALESCE(SUM(CASE WHEN o.side = 'BUY' THEN COALESCE(o.applied_executed_funds, 0) + COALESCE(o.applied_paid_fee, 0) ELSE 0 END), 0), "
                        + "COALESCE(SUM(CASE WHEN o.side = 'SELL' THEN COALESCE(o.applied_executed_funds, 0) - COALESCE(o.applied_paid_fee, 0) ELSE 0 END), 0), "
                        + "COALESCE(SUM(CASE WHEN o.filled_quantity > 0 AND o.applied_executed_funds IS NULL THEN 1 ELSE 0 END), 0) "
                        + "FROM position p LEFT JOIN \"order\" o ON o.position_id = p.id "
                        + "WHERE p.session_kind IN ('LIVE', 'DYNAMIC') AND p.status IN ('OPEN', 'CLOSING') AND p.size > 0 "
                        + "GROUP BY p.id, p.session_kind, p.session_id, p.coin_pair, p.size, p.avg_price, p.unrealized_pnl, "
                        + "p.entry_allocation_krw, p.invested_krw, p.peak_value_krw "
                        + "ORDER BY p.id")
                .getResultList();
        List<Evaluation> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(evaluateRow(r, now));
        }
        return out;
    }

    private Evaluation evaluateRow(Object[] r, Instant now) {
        Long positionId = ((Number) r[0]).longValue();
        String kind = String.valueOf(r[1]).trim();
        Long sessionId = r[2] == null ? null : ((Number) r[2]).longValue();
        String coin = (String) r[3];
        BigDecimal size = dec(r[4]);
        BigDecimal avg = dec(r[5]);
        BigDecimal unrealized = r[6] == null ? null : dec(r[6]);
        BigDecimal allocation = r[7] == null ? null : dec(r[7]);
        BigDecimal invested = r[8] == null ? null : dec(r[8]);
        BigDecimal oldPeak = r[9] == null ? null : dec(r[9]);
        BigDecimal cost = dec(r[10]);
        BigDecimal sellNet = dec(r[11]);
        long unapplied = ((Number) r[12]).longValue();

        String basis = "진입 배정액";
        if (allocation == null) {
            // V90 이전에 열린 포지션은 마이그레이션이 채운다. 그래도 비어 있으면 투입금으로 대신하고 그렇게 적는다.
            allocation = invested;
            basis = "투입금 대체(배정액 없음)";
        }
        if (unapplied > 0 || cost.signum() <= 0) {
            String detail = String.format("포지션 #%d %s %s#%s — 반영값 없는 체결 주문 %d건(매수 원가 %s): 평가하지 않는다(고점 유지)",
                    positionId, coin, kind, sessionId, unapplied, plain(cost));
            log.warn("[포지션 한도] {}", detail);
            return new Evaluation(positionId, kind, sessionId, coin, cost, null, allocation, basis, oldPeak, null,
                    VALUATION_UNKNOWN, detail, false);
        }

        PoolLossGuard.PriceMark pm = poolLossGuard.markFor(coin, size, avg, unrealized);
        BigDecimal value = sellNet.add(size.multiply(pm.price()).multiply(BigDecimal.ONE.subtract(PoolLossGuard.FEE_RATE)))
                .setScale(8, RoundingMode.HALF_UP);
        String mark = pm.price().stripTrailingZeros().toPlainString() + "(" + pm.source() + ")";
        if (!pm.realtime()) {
            String detail = String.format("포지션 #%d %s %s#%s — 실시간 시세 없음, 대체 시세 %s 로는 판정하지 않는다(고점 %s 유지, 평가액 참고 %s)",
                    positionId, coin, kind, sessionId, mark, plain(oldPeak), plain(value));
            log.warn("[포지션 한도] {}", detail);
            return new Evaluation(positionId, kind, sessionId, coin, cost, value, allocation, basis, oldPeak, mark,
                    MARK_UNAVAILABLE, detail, false);
        }

        // 고점 — 더 클 때만 올린다. 동시에 다른 판정이 더 큰 값을 썼으면 그 값이 남는다.
        BigDecimal peak = oldPeak == null || value.compareTo(oldPeak) > 0 ? value : oldPeak;
        if (oldPeak == null || value.compareTo(oldPeak) > 0) {
            em.createNativeQuery("UPDATE position SET peak_value_krw = ?1, peak_value_at = ?2 "
                            + "WHERE id = ?3 AND (peak_value_krw IS NULL OR peak_value_krw < ?1)")
                    .setParameter(1, value).setParameter(2, Timestamp.from(now)).setParameter(3, positionId)
                    .executeUpdate();
        }

        BigDecimal pnl = value.subtract(cost);
        String code = null;
        String detail;
        String common = String.format("포지션 #%d %s %s#%s 평가액 %s(매도 순대금 %s + 잔량 %s × %s) 매수 원가 %s 손익 %s | %s %s · 고점 %s",
                positionId, coin, kind, sessionId, plain(value), plain(sellNet), size.stripTrailingZeros().toPlainString(), mark,
                plain(cost), plain(pnl), basis, allocation == null ? "N/A" : plain(allocation), plain(peak));
        BigDecimal lossLimit = allocation == null ? null
                : allocation.multiply(config.getCapitalLossPct()).divide(HUNDRED, 8, RoundingMode.HALF_UP);   // 음수
        BigDecimal ddLimit = peak.multiply(config.getMaxDrawdownPct()).divide(HUNDRED, 8, RoundingMode.HALF_UP);   // 음수
        if (lossLimit != null && pnl.compareTo(lossLimit) <= 0) {
            code = CAPITAL_LOSS;
            detail = String.format("CAPITAL_LOSS 손익 %s ≤ %s (%s%% × %s %s) — %s", plain(pnl), plain(lossLimit),
                    config.getCapitalLossPct().toPlainString(), basis, plain(allocation), common);
        } else if (value.subtract(peak).compareTo(ddLimit) <= 0) {
            code = MAX_DRAWDOWN;
            detail = String.format("MAX_DRAWDOWN 고점 대비 %s ≤ %s (%s%% × 고점 %s) — %s", plain(value.subtract(peak)), plain(ddLimit),
                    config.getMaxDrawdownPct().toPlainString(), plain(peak), common);
        } else {
            detail = common;
        }

        boolean inserted = false;
        if (code != null) {
            inserted = em.createNativeQuery("INSERT INTO position_limit_breach "
                            + "(position_id, session_kind, session_id, code, value_krw, cost_krw, allocation_krw, peak_krw, detail, breached_at) "
                            + "SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10 "
                            + "WHERE NOT EXISTS (SELECT 1 FROM position_limit_breach WHERE position_id = ?1)")
                    .setParameter(1, positionId).setParameter(2, kind).setParameter(3, sessionId).setParameter(4, code)
                    .setParameter(5, value).setParameter(6, cost).setParameter(7, allocation).setParameter(8, peak)
                    .setParameter(9, detail).setParameter(10, Timestamp.from(now))
                    .executeUpdate() == 1;
            if (inserted) {
                log.error("🔴 [포지션 한도] 도달 — 이 세션의 실자금 신규 진입을 막는다: {}", detail);
            }
        }
        return new Evaluation(positionId, kind, sessionId, coin, cost, value, allocation, basis, peak, mark, code, detail, inserted);
    }

    // ── 신규 진입 ────────────────────────────────────────────────────

    /**
     * 신규 진입 판정 — 공유 예약 지점(계좌 잠금 안)에서 부른다. 이 세션의 포지션 중 한도에 도달한 기록이 있으면 거절한다.
     * 🔴 기록은 포지션이 닫힌 뒤에도 남는다 — A군 도달은 세션 폐기 사유이므로, 포지션이 정리됐다고 같은 세션이 다시 들어가지 않는다.
     */
    public CapitalReservationService.Decision checkSessionForEntry(String kind, Long sessionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT position_id, code, breached_at FROM position_limit_breach WHERE session_kind = ?1 AND session_id = ?2 "
                        + "ORDER BY breached_at, position_id")
                .setParameter(1, kind).setParameter(2, sessionId).getResultList();
        if (rows.isEmpty()) {
            return CapitalReservationService.Decision.approve("포지션 한도: 도달 기록 없음");
        }
        Object[] first = rows.get(0);
        return CapitalReservationService.Decision.reject(POSITION_LIMIT, String.format(
                "포지션별 한도 도달 기록이 있는 세션 — 신규 진입을 승인하지 않는다(%s#%s, 포지션 #%s %s, %s, 기록 %d건)",
                kind, sessionId, first[0], String.valueOf(first[1]).trim(), first[2], rows.size()));
    }

    // ── 정지·정리 기록 ────────────────────────────────────────────────

    /** 아직 정지·경보 처리를 하지 않은 도달 기록 — 재시작 뒤에도 남아 있으면 다시 처리된다. */
    public record PendingBreach(Long positionId, String sessionKind, Long sessionId, String code, String detail) {}

    @Transactional(readOnly = true)
    public List<PendingBreach> pendingBreaches() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT position_id, session_kind, session_id, code, detail FROM position_limit_breach "
                        + "WHERE stop_action IS NULL ORDER BY breached_at, position_id")
                .getResultList();
        List<PendingBreach> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new PendingBreach(((Number) r[0]).longValue(), String.valueOf(r[1]).trim(), ((Number) r[2]).longValue(),
                    String.valueOf(r[3]).trim(), String.valueOf(r[4])));
        }
        return out;
    }

    /** 처리 결과를 남긴다 — 한 번 남기면 다시 처리하지 않는다. */
    @Transactional
    public void recordStopAction(Long positionId, String action, String note, Instant at) {
        em.createNativeQuery("UPDATE position_limit_breach SET stop_action = ?1, stop_note = ?2, stop_at = ?3 "
                        + "WHERE position_id = ?4 AND stop_action IS NULL")
                .setParameter(1, action).setParameter(2, note).setParameter(3, Timestamp.from(at)).setParameter(4, positionId)
                .executeUpdate();
    }

    // ── 보조 ────────────────────────────────────────────────────────

    private static BigDecimal dec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }

    private static String plain(BigDecimal v) {
        return v == null ? "N/A" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
