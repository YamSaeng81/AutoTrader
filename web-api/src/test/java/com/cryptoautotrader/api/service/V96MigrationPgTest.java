package com.cryptoautotrader.api.service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.150 §4 — 실제 PostgreSQL 에서 V94 → V96 이행 (2026-11-11). 격리 로컬 DB 전용(환경 변수 {@code V96_PG_MIG_URL}, 빈 DB).
 *
 * <p>Flyway 를 V94 까지 적용 → 기존 행(세션 1·포지션 2)을 넣음 → 최신(V96)까지 적용·validate → 두 열의 타입·NULL 허용·기본값 없음,
 * 기존 행의 기존 열 값 불변·새 열 NULL 을 확인한다. Spring 컨텍스트 없이 Flyway API 와 JDBC 만 쓴다.</p>
 */
@EnabledIfEnvironmentVariable(named = "V96_PG_MIG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("V96 실제 PG 이행 — V94 기점 + 두 열, 기존 행 불변, validate")
class V96MigrationPgTest {

    private static final String URL = System.getenv("V96_PG_MIG_URL");
    private static final String USER = System.getenv().getOrDefault("V96_PG_USER", "trader");

    private Flyway flyway(String target) {
        var c = Flyway.configure().dataSource(URL, USER, "").locations("classpath:db/migration");
        if (target != null) c = c.target(target);
        return c.load();
    }

    private static List<String> rows(Connection c, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= n; i++) b.append(i > 1 ? "|" : "").append(rs.getString(i));
                out.add(b.toString());
            }
        }
        return out;
    }

    @Test
    @DisplayName("V94 → V96: 두 열 nullable·기본값 없음·타입, 기존 행 불변·새 열 NULL, validate 통과, 이력 94·96")
    void migrateFrom94To96KeepsExistingRows() throws Exception {
        Flyway at94 = flyway("94");
        at94.migrate();
        assertThat(at94.info().current().getVersion().getVersion()).isEqualTo("94");

        String oldCols = "SELECT v.id, v.total_krw, v.available_krw, v.status, v.version, v.strategy_params::text FROM paper_trading.virtual_balance v ORDER BY v.id";
        String oldPos = "SELECT p.id, p.session_id, p.status, p.size, p.avg_price, p.exit_reason, p.closed_at, p.realized_pnl FROM paper_trading.position p ORDER BY p.id";
        List<String> balBefore;
        List<String> posBefore;
        try (Connection c = DriverManager.getConnection(URL, USER, "")) {
            try (Statement st = c.createStatement()) {
                // 이행이 만든 기본 세션 행이 이미 있을 수 있다 — 포지션은 이 시험이 넣은 세션에만 붙인다(1회차 시험 결함 정정).
                long sid;
                try (ResultSet rs = st.executeQuery("INSERT INTO paper_trading.virtual_balance (total_krw, available_krw, status, strategy_name, coin_pair, timeframe, "
                        + "initial_capital, started_at, strategy_params) VALUES (20200, 200, 'RUNNING', 'DAILY_SMA20', 'KRW-BTC', 'H1', 20200, now(), "
                        + "'{\"paperMaxInvestment\":20000,\"paperExecutionMode\":\"LIVE_ALIGNED\"}') RETURNING id")) {
                    rs.next();
                    sid = rs.getLong(1);
                }
                st.executeUpdate("INSERT INTO paper_trading.position (session_id, coin_pair, side, entry_price, avg_price, size, status, opened_at) "
                        + "VALUES (" + sid + ", 'KRW-BTC', 'BUY', 100, 100, 200, 'OPEN', now())");
                st.executeUpdate("INSERT INTO paper_trading.position (session_id, coin_pair, side, entry_price, avg_price, size, status, opened_at, closed_at, "
                        + "realized_pnl, exit_reason) VALUES (" + sid + ", 'KRW-BTC', 'BUY', 100, 100, 200, 'CLOSED', now(), now(), 12.34, 'STOP_LOSS')");
            }
            balBefore = rows(c, oldCols);
            posBefore = rows(c, oldPos);
        }

        Flyway latest = flyway(null);
        latest.migrate();
        latest.validate();
        MigrationInfo cur = latest.info().current();

        try (Connection c = DriverManager.getConnection(URL, USER, "")) {
            List<String> cols = rows(c, "SELECT table_name, column_name, data_type, numeric_precision, numeric_scale, is_nullable, coalesce(column_default,'<none>') "
                    + "FROM information_schema.columns WHERE table_schema='paper_trading' AND column_name IN ('exit_decided_at','mdd_peak_krw') ORDER BY table_name");
            List<String> history = rows(c, "SELECT version, success FROM flyway_schema_history WHERE version IN ('94','95','96') ORDER BY installed_rank");
            List<String> newNull = rows(c, "SELECT (SELECT count(*) FROM paper_trading.position WHERE exit_decided_at IS NOT NULL), "
                    + "(SELECT count(*) FROM paper_trading.virtual_balance WHERE mdd_peak_krw IS NOT NULL)");
            List<String> balAfter = rows(c, oldCols);
            List<String> posAfter = rows(c, oldPos);
            System.out.println("[V96 PG 이행] 현재=" + cur.getVersion() + " 이력=" + history + " 열=" + cols + " 새 열 NOT NULL 수=" + newNull
                    + " 세션 전/후 같음=" + balBefore.equals(balAfter) + " 포지션 전/후 같음=" + posBefore.equals(posAfter));
            System.out.println("[V96 PG 이행] 세션=" + balAfter + " 포지션=" + posAfter);

            assertThat(cur.getVersion().getVersion()).isEqualTo("96");
            assertThat(history).containsExactly("94|t", "96|t");
            assertThat(cols).containsExactly(
                    "position|exit_decided_at|timestamp with time zone|null|null|YES|<none>",
                    "virtual_balance|mdd_peak_krw|numeric|20|2|YES|<none>");
            assertThat(newNull).containsExactly("0|0");
            assertThat(balAfter).isEqualTo(balBefore);
            assertThat(posAfter).isEqualTo(posBefore);
            assertThat(posAfter).hasSize(2);
        }
    }
}
