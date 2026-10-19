package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.OrderEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * <b>판별 불가 매도</b> — 반영 표시({@code sell_applied_at}, V87)가 생기기 <b>전에</b> 만든 매도 (2026-10-19).
 *
 * <p>그때는 반영 표시가 없어 NULL 이 "반영됨" 인지 "미반영" 인지 판별할 수 없다. 매도 입구는 이미 이런 매도가 있는 열린 포지션을 막는다(격리,
 * {@link SellQuarantineReport}). 🔴 그런데 <b>청산 확정·정리·유령 정산</b>은 반영 표시 없는 체결 매도를 "아직 반영 안 됨" 으로 보고 대금을 넣었다 —
 * 격리된 포지션이 정지로 CLOSING 이 되면 이미 반영된 옛 매도를 다시 반영해 대금이 두 번 들어가고 수량이 줄었다
 * ({@code SellQuarantineReleaseTest} ⑩ 재현: 6.8 → 3.8, 잔액 +899.55). 이 정책으로 그 경로들이 판별 불가 매도를 <b>자동 반영 후보에서 뺀다.</b>
 * 사람이 판정해 해제({@link SellQuarantineReleaseService} — "이미 반영됨" 표시)하기 전까지 격리로 남는다.</p>
 *
 * <h3>경계 시각</h3>
 * <ol>
 *   <li>운영 DB 의 Flyway 이력에서 V87 이 설치된 시각</li>
 *   <li>그것을 읽지 못하면 {@code trading.sell-quarantine.legacy-before}(ISO 시각) 속성</li>
 *   <li>🔴 둘 다 없으면 <b>경계 불명</b> — 판정할 수 없으므로 <b>반영 표시 없는 매도를 모두 판별 불가로 보고 자동 반영을 보류</b>한다(리뷰 2026-10-19:
 *       종전에는 ERROR 만 남기고 모두 자동 반영 후보로 두었다). ERROR·경보를 남기고 {@link #RETRY} 마다 다시 읽는다 — 읽히면 바로 풀린다.
 *       기동을 거부하지 않는 이유: 앱이 멈추면 보유 포지션의 손절·익절 감시도 멈춘다. 보류 동안 청산 확정이 대금을 넣지 않으므로 CLOSING 이 남을 수 있다.</li>
 *   <li>시험 기반(H2)에서 설정이 없으면 경계 없음 — 종전 동작(모두 자동 반영 후보).</li>
 * </ol>
 */
@Slf4j
@Component
public class LegacySellPolicy {

    /** 경계 불명일 때 다시 읽는 간격. */
    static final Duration RETRY = Duration.ofMinutes(1);

    enum State { RESOLVED, NO_BOUNDARY, UNKNOWN }

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    @Autowired(required = false)
    private TelegramNotificationService telegram;

    @Value("${trading.sell-quarantine.legacy-before:}")
    private String configuredCutoff;

    /** 경계 없이 종전 동작을 허용하는가 — 시험 기반만. */
    private boolean allowNoBoundary = CapitalPoolLimits.testRuntime();

    private volatile State state;
    private volatile Instant cutoff;
    private volatile Instant lastAttempt;

    /**
     * 반영 표시가 없는 매도 중 자동 반영하지 않을 것 — 경계 전에 만든 매도, 또는 경계 불명일 때의 모든 미반영 매도.
     */
    public boolean isUnverifiableLegacy(OrderEntity o) {
        if (o == null || !"SELL".equalsIgnoreCase(o.getSide()) || o.getSellAppliedAt() != null) return false;
        resolve(Instant.now());
        return switch (state) {
            case RESOLVED -> o.getCreatedAt() != null && o.getCreatedAt().isBefore(cutoff);
            case NO_BOUNDARY -> false;
            case UNKNOWN -> true;   // 🔴 판정할 수 없으면 보류
        };
    }

    /** 자동 반영 후보인 미반영 매도인가 — 반영 표시가 없고 판별 불가가 아닌 것. */
    public boolean isAutoApplicableUnapplied(OrderEntity o) {
        return "SELL".equalsIgnoreCase(o.getSide()) && o.getSellAppliedAt() == null && !isUnverifiableLegacy(o);
    }

    /** 경계 시각 — 불명·없음이면 {@code null}. */
    public Instant cutoff() {
        resolve(Instant.now());
        return state == State.RESOLVED ? cutoff : null;
    }

    State state() {
        resolve(Instant.now());
        return state;
    }

    private synchronized void resolve(Instant now) {
        if (state == State.RESOLVED || state == State.NO_BOUNDARY) return;
        if (state == State.UNKNOWN && lastAttempt != null && lastAttempt.plus(RETRY).isAfter(now)) return;
        lastAttempt = now;
        State before = state;
        Instant c = load();
        if (c != null) {
            cutoff = c;
            state = State.RESOLVED;
            if (before == State.UNKNOWN) {
                log.warn("[판별 불가 매도] 경계를 다시 읽었다({}) — 자동 반영 보류를 푼다", c);
            }
        } else if (allowNoBoundary) {
            state = State.NO_BOUNDARY;
        } else {
            state = State.UNKNOWN;
            if (before != State.UNKNOWN) {
                String msg = "🔴 [판별 불가 매도] 경계 시각을 정하지 못했다(Flyway 이력 V87 없음·설정 trading.sell-quarantine.legacy-before 없음) — "
                        + "반영 표시 없는 매도를 모두 판별 불가로 보고 자동 반영을 보류한다(청산 확정이 대금을 넣지 않아 CLOSING 이 남을 수 있다). "
                        + RETRY.toMinutes() + "분마다 다시 읽는다";
                log.error(msg);
                if (telegram != null) {
                    try {
                        telegram.notifyRiskLimitBreached(msg);
                    } catch (Exception e) {
                        log.warn("경계 불명 경보 전송 실패: {}", e.getMessage());
                    }
                }
            }
        }
    }

    private Instant load() {
        if (jdbc != null) {
            try {
                Timestamp t = jdbc.queryForObject("SELECT MIN(installed_on) FROM flyway_schema_history WHERE version = '87' AND success",
                        Timestamp.class);
                if (t != null) {
                    log.info("[판별 불가 매도] 경계 = V87 설치 시각 {} — 그 전에 만든 반영 표시 없는 매도는 자동 반영하지 않는다", t.toInstant());
                    return t.toInstant();
                }
            } catch (Exception e) {
                log.debug("[판별 불가 매도] Flyway 이력을 읽지 못했다: {}", e.getMessage());
            }
        }
        if (configuredCutoff != null && !configuredCutoff.isBlank()) {
            try {
                Instant c = Instant.parse(configuredCutoff.trim());
                log.info("[판별 불가 매도] 경계 = 설정값 {}", c);
                return c;
            } catch (Exception e) {
                log.error("[판별 불가 매도] 설정 trading.sell-quarantine.legacy-before 를 읽지 못했다({}): {}", configuredCutoff, e.getMessage());
            }
        }
        return null;
    }

    // ── 시험용 ───────────────────────────────────────────────────────

    /** 경계를 정한다(null 이면 경계 없음 — 종전 동작). */
    void overrideCutoffForTest(Instant c) {
        synchronized (this) {
            cutoff = c;
            state = c == null ? State.NO_BOUNDARY : State.RESOLVED;
        }
    }

    /** 운영처럼(경계 없음 불허) 다시 읽게 한다 — 경계 불명 시험용. */
    void resetForTest(boolean allowNoBoundary) {
        synchronized (this) {
            this.allowNoBoundary = allowNoBoundary;
            state = null;
            cutoff = null;
            lastAttempt = null;
        }
    }
}
