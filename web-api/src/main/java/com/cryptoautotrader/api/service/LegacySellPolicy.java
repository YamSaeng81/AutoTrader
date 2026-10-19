package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.OrderEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
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
 * <p>경계 시각: 운영 DB 의 Flyway 이력에서 V87 이 설치된 시각. 이력을 읽지 못하면 {@code trading.sell-quarantine.legacy-before}(ISO 시각) 속성을 쓰고,
 * 그것도 없으면 경계가 없다(모든 미반영 매도를 종전대로 자동 반영 — 시험 기반). 운영에서 경계를 정하지 못하면 ERROR 로 알린다.</p>
 */
@Slf4j
@Component
public class LegacySellPolicy {

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    @Value("${trading.sell-quarantine.legacy-before:}")
    private String configuredCutoff;

    private volatile boolean loaded;
    private volatile Instant cutoff;

    /** 반영 표시가 없고 경계 시각 전에 만든 매도 — 자동 반영하지 않는다. */
    public boolean isUnverifiableLegacy(OrderEntity o) {
        if (o == null || !"SELL".equalsIgnoreCase(o.getSide()) || o.getSellAppliedAt() != null) return false;
        Instant c = cutoff();
        return c != null && o.getCreatedAt() != null && o.getCreatedAt().isBefore(c);
    }

    /** 자동 반영 후보인 미반영 매도인가 — 반영 표시가 없고 판별 불가가 아닌 것. */
    public boolean isAutoApplicableUnapplied(OrderEntity o) {
        return "SELL".equalsIgnoreCase(o.getSide()) && o.getSellAppliedAt() == null && !isUnverifiableLegacy(o);
    }

    public Instant cutoff() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    cutoff = load();
                    loaded = true;
                }
            }
        }
        return cutoff;
    }

    /** 시험용 — 경계를 바꾼다(null 이면 경계 없음). */
    void overrideCutoffForTest(Instant c) {
        this.cutoff = c;
        this.loaded = true;
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
            Instant c = Instant.parse(configuredCutoff.trim());
            log.info("[판별 불가 매도] 경계 = 설정값 {}", c);
            return c;
        }
        if (jdbc != null && CapitalPoolLimits.testRuntime()) {
            return null;
        }
        log.error("🔴 [판별 불가 매도] 경계 시각을 정하지 못했다(Flyway 이력 V87 없음·설정 없음) — V87 이전 매도도 자동 반영 후보가 된다");
        return null;
    }
}
