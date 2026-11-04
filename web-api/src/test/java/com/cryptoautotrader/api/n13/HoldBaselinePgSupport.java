package com.cryptoautotrader.api.n13;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * N13 HOLD 기준선 질의 — 실제 PostgreSQL 시험 공용 부분.
 *
 * <p>이 시험들은 환경 변수 {@code N13_PG_URL}(예: {@code jdbc:postgresql://127.0.0.1:15433/n13_it})이 있을 때만 돈다.
 * 기본 {@code ./gradlew test}(H2)에서는 건너뛴다. 대상 DB 는 Flyway 로 V1~V94 를 적용한 격리 DB 여야 하며,
 * 시험이 {@code strategy_log} 를 비운다 — 운영·공유 DB 를 가리키지 않는다.</p>
 */
final class HoldBaselinePgSupport {

    private HoldBaselinePgSupport() {}

    /**
     * 고정 운영판(6995c52) {@code findPendingHoldFor4hEval} 원문. 대조 기준이다 — 바꾸지 않는다.
     * 24h 판은 {@link #original24h()} 로 {@code price_after_4h} 두 곳만 바꿔 만든다(원문도 그 두 곳만 다르다).
     */
    static final String ORIGINAL_4H = """
            SELECT DISTINCT ON (l.coin_pair, date_trunc('hour', l.created_at)) l.*
            FROM strategy_log l
            WHERE l.signal = 'HOLD'
              AND l.created_at < :cutoff
              AND l.signal_price IS NOT NULL
              AND l.price_after_4h IS NULL
              AND NOT EXISTS (
                    SELECT 1 FROM strategy_log e
                    WHERE e.signal = 'HOLD'
                      AND e.coin_pair = l.coin_pair
                      AND e.created_at >= date_trunc('hour', l.created_at)
                      AND e.created_at <  date_trunc('hour', l.created_at) + interval '1 hour'
                      AND e.price_after_4h IS NOT NULL)
            ORDER BY l.coin_pair, date_trunc('hour', l.created_at), l.created_at
            LIMIT :limit
            """;

    static String original24h() {
        return ORIGINAL_4H.replace("price_after_4h", "price_after_24h");
    }

    /** {@code :cutoff}·{@code :limit} 를 JDBC 자리표시자로 바꾼다(각 1회). */
    static String jdbc(String namedSql) {
        if (count(namedSql, ":cutoff") != 1 || count(namedSql, ":limit") != 1) {
            throw new IllegalStateException("자리표시자 수가 예상과 다르다");
        }
        return namedSql.replace(":cutoff", "?").replace(":limit", "?");
    }

    private static int count(String s, String t) {
        int n = 0;
        for (int i = s.indexOf(t); i >= 0; i = s.indexOf(t, i + 1)) n++;
        return n;
    }

    static long insert(JdbcTemplate jdbc, String coin, String signal, Instant at, BigDecimal signalPrice,
                       BigDecimal p4, BigDecimal p24, String strategy, long sessionId) {
        return jdbc.queryForObject("""
                INSERT INTO strategy_log(strategy_name, coin_pair, signal, reason, session_type, session_id,
                                         created_at, signal_price, was_executed, price_after_4h, price_after_24h, timeframe)
                VALUES (?, ?, ?, 'n13', 'DYN_PAPER', ?, ?, ?, false, ?, ?, '1h')
                RETURNING id
                """, Long.class, strategy, coin, signal, sessionId, Timestamp.from(at), signalPrice, p4, p24);
    }
}
