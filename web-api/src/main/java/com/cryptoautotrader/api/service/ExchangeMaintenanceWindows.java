package com.cryptoautotrader.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * <b>거래소 공식 점검 구간</b> (V93, 2026-10-19) — 캔들 최신성 관문이 "원래 없는 봉" 으로 볼 수 있는 시장 공통 공백.
 *
 * <p>운영자가 공지·거래소 대조로 확인한 구간만 표에 넣는다(출처·시간대·확인 시각·확인자). 관문은 <b>끝난</b> {@code ALL_MARKETS} 구간만
 * 쓴다 — 진행 중이거나 끝 시각이 미래인 구간은 쓰지 않는다(현재 적재 정지를 가리지 않게).</p>
 *
 * <p>표는 {@link #REFRESH} 마다 다시 읽는다. 읽기가 실패하면 직전 목록을 쓴다(이미 확인된 사실이다). 처음부터 읽지 못하면 빈 목록 —
 * 예외 없이 종전 규칙(모든 결측을 센다)이 된다.</p>
 */
@Slf4j
@Component
public class ExchangeMaintenanceWindows {

    static final Duration REFRESH = Duration.ofMinutes(1);

    /** 한 점검 구간. 시각은 UTC 기준 시각. */
    public record Window(long id, Instant startsAt, Instant endsAt, String source, String sourceTimezone,
                         Instant confirmedAt, String confirmedBy) {
        /**
         * 봉 구간 [slotStart, slotStart + tf) 이 <b>통째로</b> 이 점검 구간 안에 있는가 (리뷰 2026-10-19).
         *
         * <p>겹치기만 하는 경계 봉(예: 점검 끝 06:55 의 M15 06:45~07:00, H1 06:00~07:00)은 재개 뒤 시간도 포함한다 — 그 봉이 없는 것은
         * 재개 뒤 무체결일 수 있으므로 예외로 빼지 않는다(일반 결측으로 센다).</p>
         */
        boolean contains(Instant slotStart, long tfSec) {
            return !slotStart.isBefore(startsAt) && !slotStart.plusSeconds(tfSec).isAfter(endsAt);
        }

        String describe() {
            return "#" + id + " " + startsAt + "~" + endsAt + " (" + source + ", 원문 시간대 " + sourceTimezone
                    + ", 확인 " + confirmedBy + " " + confirmedAt + ")";
        }
    }

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    private volatile List<Window> windows = List.of();
    private volatile Instant loadedAt;

    /** 끝난 시장 공통 점검 구간 중 이 봉 구간을 통째로 담는 것 — 없으면 {@code null}. 경계에 걸친 봉은 담지 않는다. */
    public Window covering(Instant slotStart, long tfSec, Instant now) {
        for (Window w : current(now)) {
            if (!w.endsAt().isAfter(now) && w.contains(slotStart, tfSec)) return w;
        }
        return null;
    }

    List<Window> current(Instant now) {
        Instant at = loadedAt;
        if (at == null || at.plus(REFRESH).isBefore(now)) reload(now);
        return windows;
    }

    /** 표를 다시 읽는다 — 시험은 행을 넣은 뒤 부른다. */
    public void reload(Instant now) {
        loadedAt = now;
        if (jdbc == null) return;
        try {
            windows = List.copyOf(jdbc.query(
                    "SELECT id, starts_at, ends_at, source, source_timezone, confirmed_at, confirmed_by "
                            + "FROM exchange_maintenance_window WHERE exchange = 'UPBIT' AND scope = 'ALL_MARKETS' ORDER BY starts_at",
                    (rs, i) -> new Window(rs.getLong(1),
                            rs.getObject(2, OffsetDateTime.class).toInstant(),
                            rs.getObject(3, OffsetDateTime.class).toInstant(),
                            rs.getString(4), rs.getString(5),
                            rs.getObject(6, OffsetDateTime.class).toInstant(),
                            rs.getString(7))));
        } catch (Exception e) {
            log.warn("[점검 구간] 표 읽기 실패 — 직전 목록({}개)을 쓴다: {}", windows.size(), e.getMessage());
        }
    }
}
