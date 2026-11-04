package com.cryptoautotrader.api.n13;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.support.TestRedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.cryptoautotrader.api.n13.HoldBaselinePgSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * N13 — HOLD 기준선 두 질의의 후보 SQL 이 고정 운영판(6995c52)과 같은 결과를 내는지 실제 PostgreSQL 에서 단언한다.
 *
 * <p>같은 연결·같은 스냅숏(REPEATABLE READ 읽기 전용 트랜잭션 하나)에서 원문과 후보를 같은 cutoff·limit 으로 실행해
 * 행 순서와 전체 열까지 비교한다. 경계 자료의 기대 결과는 손으로 적은 값으로도 단언한다(원문과 후보가 함께 틀리는 것을 막기 위해).</p>
 *
 * <p>동률: 같은 코인·시간대 안에서 created_at 이 같은 적격 행이 둘 이상이면 원문의 대표 선택은 비결정적이다
 * (ORDER BY 가 created_at 까지만 정한다). 그 시간대는 "대표가 동률 집합 안의 한 행"인지만 보고,
 * 행 자체는 비교하지 않는다. 나머지 시간대는 정확히 비교한다.</p>
 */
@EnabledIfEnvironmentVariable(named = "N13_PG_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=${N13_PG_URL}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=${N13_PG_USER:trader}",
        "spring.datasource.password=${N13_PG_PASSWORD:}",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '10s'",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
})
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class HoldBaselineSqlEquivalencePgTest {

    static final Instant H0 = Instant.parse("2026-10-01T00:00:00Z");
    static final BigDecimal P = new BigDecimal("100.00000000");
    static final BigDecimal DONE = new BigDecimal("101.00000000");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired StrategyLogRepository repo;

    final Map<String, Long> id = new LinkedHashMap<>();
    Set<Long> tieSet;

    static Instant h(double hours) { return H0.plusMillis(Math.round(hours * 3_600_000)); }

    void row(String name, String coin, String signal, Instant at, BigDecimal price, BigDecimal p4, BigDecimal p24) {
        row(name, coin, signal, at, price, p4, p24, "STRAT_A", 1);
    }

    void row(String name, String coin, String signal, Instant at, BigDecimal price, BigDecimal p4, BigDecimal p24,
             String strategy, long session) {
        id.put(name, insert(jdbc, coin, signal, at, price, p4, p24, strategy, session));
    }

    /** 경계 자료. 주석의 판정은 cutoff = H0+7h30m 기준이다. */
    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM strategy_log");
        String A = "KRW-AAA", B = "KRW-BBB", C = "KRW-CCC";
        // A 시간대0: 미평가 2행 → 대표 a0(정각 정확히)
        row("a0", A, "HOLD", h(0), P, null, null);
        row("a1", A, "HOLD", h(0.5), P, null, null);
        // A 시간대1: 정각 정확히(구간 시작, 포함)에 4h 완료 행 → 4h 는 시간대 전체 제외. 24h 는 e1 이 대표.
        row("e1", A, "HOLD", h(1), P, DONE, null);
        row("a2", A, "HOLD", h(1).plusSeconds(600), P, null, null);
        // A 시간대2: 끝 직전 1µs 행은 같은 시간대. 다음 정각 정확히의 완료 행(e2)은 시간대3 소속이라 시간대2를 제외하지 않는다.
        row("a3", A, "HOLD", h(2).plusSeconds(1200), P, null, null);
        row("a3b", A, "HOLD", h(3).minusNanos(1000), P, null, null);
        row("e2", A, "HOLD", h(3), P, DONE, null);
        // A 시간대3: e2 로 4h 제외. 24h 는 e2 가 대표.
        row("a4", A, "HOLD", h(3).plusSeconds(300), P, null, null);
        // A 시간대4: signal_price NULL 인 완료 행도 제외 근거다(e 에는 가격 조건이 없다). 24h 는 a5 대표.
        row("e3", A, "HOLD", h(4).plusSeconds(2400), null, DONE, null);
        row("a5", A, "HOLD", h(4).plusSeconds(600), P, null, null);
        // A 시간대5: 다른 전략·세션의 완료 HOLD 도 제외 근거다(4h·24h 모두 완료).
        row("e4", A, "HOLD", h(5).plusSeconds(3000), P, DONE, DONE, "STRAT_X", 999);
        row("a6", A, "HOLD", h(5).plusSeconds(300), P, null, null);
        // A 시간대6: 완료 행이 BUY 면 제외하지 않는다.
        row("b6", A, "BUY", h(6).plusSeconds(60), P, DONE, DONE);
        row("a7", A, "HOLD", h(6).plusSeconds(1800), P, null, null);
        // A 시간대7: 같은 시간대의 cutoff 뒤 완료 행(e5)도 4h 제외 근거다. 24h 는 a8 대표(e5 는 cutoff 뒤라 대상 아님).
        row("a8", A, "HOLD", h(7).plusSeconds(600), P, null, null);
        row("e5", A, "HOLD", h(7).plusSeconds(3000), P, DONE, null);
        // A 시간대8: cutoff 뒤 → 대상 아님.
        row("a9", A, "HOLD", h(8).plusSeconds(60), P, null, null);
        // B 시간대0: 가격 NULL 행은 대표가 될 수 없다 → b1 대표.
        row("b0", B, "HOLD", h(0).plusSeconds(1), null, null, null);
        row("b1", B, "HOLD", h(0).plusSeconds(1200), P, null, null);
        // B 시간대1: 끝 1초 전.
        row("b2", B, "HOLD", h(2).minusSeconds(1), P, null, null);
        // B 시간대2: 24h 만 완료(e6) → 4h 는 e6 자신이 대표(4h 미평가·가격 있음), 24h 는 시간대 제외(4h·24h 독립).
        row("e6", B, "HOLD", h(2).plusSeconds(60), P, null, DONE);
        row("b3", B, "HOLD", h(2).plusSeconds(120), P, null, null);
        // B 시간대7: cutoff 와 같은 시각은 제외(엄격한 <), 1초 전은 포함.
        row("b5", B, "HOLD", h(7.5).minusSeconds(1), P, null, null);
        row("b4", B, "HOLD", h(7.5), P, null, null);
        // C 시간대3: created_at 동률 두 행 → 대표는 둘 중 하나(비결정).
        row("c0", C, "HOLD", h(3).plusSeconds(900), P, null, null);
        row("c1", C, "HOLD", h(3).plusSeconds(900), P, null, null);
        row("c2", C, "HOLD", h(3).plusSeconds(2400), P, null, null);
        tieSet = Set.of(id.get("c0"), id.get("c1"));
    }

    static String candidate(String method) throws Exception {
        return StrategyLogRepository.class.getMethod(method, Instant.class, int.class).getAnnotation(Query.class).value();
    }

    /** 같은 연결의 한 스냅숏에서 원문과 후보를 실행한다. */
    record Pair(List<Map<String, Object>> original, List<Map<String, Object>> candidate) {}

    Pair runBoth(String originalSql, String candidateSql, Instant cutoff, int limit, String timeZone) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setReadOnly(true);
            try {
                JdbcTemplate one = new JdbcTemplate(new SingleConnectionDataSource(c, true));
                one.execute("SET LOCAL TIME ZONE '" + timeZone + "'");
                List<Map<String, Object>> o = one.queryForList(jdbc(originalSql), Timestamp.from(cutoff), limit);
                List<Map<String, Object>> k = one.queryForList(jdbc(candidateSql), Timestamp.from(cutoff), limit);
                return new Pair(o, k);
            } finally {
                c.rollback();
                c.setReadOnly(false);
                c.setAutoCommit(true);
            }
        }
    }

    /** 동률 시간대의 대표는 표지로 바꿔 비교한다. 대표가 동률 집합 안에 있는지는 따로 단언한다. */
    List<Object> normalize(List<Map<String, Object>> rows) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long rid = ((Number) r.get("id")).longValue();
            if (tieSet.contains(rid)) out.add("TIE(KRW-CCC,H3)");
            else out.add(new TreeMap<>(r));
        }
        return out;
    }

    void assertTieMembership(List<Map<String, Object>> rows) {
        long inTie = rows.stream().map(r -> ((Number) r.get("id")).longValue()).filter(tieSet::contains).count();
        assertThat(inTie).as("동률 시간대 대표는 많아야 1행").isLessThanOrEqualTo(1);
    }

    List<String> names(List<Map<String, Object>> rows) {
        Map<Long, String> rev = id.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        return rows.stream().map(r -> {
            long rid = ((Number) r.get("id")).longValue();
            return tieSet.contains(rid) ? "c0|c1" : rev.get(rid);
        }).toList();
    }

    @Test
    @DisplayName("경계 자료: cutoff=H0+7h30m·limit 100 에서 손으로 적은 기대 결과와 원문·후보가 모두 같다(4h·24h)")
    void handWrittenExpectations() throws Exception {
        Instant cutoff = h(7.5);
        for (String tz : List.of("UTC", "Asia/Seoul")) {
            Pair p4 = runBoth(ORIGINAL_4H, candidate("findPendingHoldFor4hEval"), cutoff, 100, tz);
            List<String> exp4 = List.of("a0", "a3", "a7", "b1", "b2", "e6", "b5", "c0|c1");
            assertThat(names(p4.original())).as("원문 4h " + tz).isEqualTo(exp4);
            assertThat(names(p4.candidate())).as("후보 4h " + tz).isEqualTo(exp4);

            Pair p24 = runBoth(original24h(), candidate("findPendingHoldFor24hEval"), cutoff, 100, tz);
            List<String> exp24 = List.of("a0", "e1", "a3", "e2", "a5", "a7", "a8", "b1", "b2", "b5", "c0|c1");
            assertThat(names(p24.original())).as("원문 24h " + tz).isEqualTo(exp24);
            assertThat(names(p24.candidate())).as("후보 24h " + tz).isEqualTo(exp24);
        }
    }

    @Test
    @DisplayName("limit 1·3·5 와 결과 0(cutoff 가 모든 행보다 앞) — 원문·후보·기대가 같다")
    void limitsAndEmpty() throws Exception {
        Instant cutoff = h(7.5);
        Map<Integer, List<String>> exp = Map.of(1, List.of("a0"), 3, List.of("a0", "a3", "a7"),
                5, List.of("a0", "a3", "a7", "b1", "b2"));
        for (var e : exp.entrySet()) {
            Pair p = runBoth(ORIGINAL_4H, candidate("findPendingHoldFor4hEval"), cutoff, e.getKey(), "UTC");
            assertThat(names(p.original())).isEqualTo(e.getValue());
            assertThat(names(p.candidate())).isEqualTo(e.getValue());
        }
        for (String m : List.of("4h", "24h")) {
            Pair p = runBoth(m.equals("4h") ? ORIGINAL_4H : original24h(),
                    candidate(m.equals("4h") ? "findPendingHoldFor4hEval" : "findPendingHoldFor24hEval"), H0, 100, "UTC");
            assertThat(p.original()).isEmpty();
            assertThat(p.candidate()).isEmpty();
        }
    }

    @Test
    @DisplayName("격자 대조: 모든 행 시각·정각의 ±1µs·정확값 cutoff × limit{1,2,3,5,7,100} × TimeZone{UTC,Asia/Seoul} — 행 순서·전체 열 일치")
    void grid() throws Exception {
        Set<Instant> cutoffs = new TreeSet<>();
        List<Instant> marks = new ArrayList<>(jdbc.queryForList("SELECT created_at FROM strategy_log", Timestamp.class)
                .stream().map(Timestamp::toInstant).toList());
        for (int i = -1; i <= 10; i++) marks.add(h(i));
        for (Instant m : marks) {
            cutoffs.add(m.minusNanos(1000));
            cutoffs.add(m);
            cutoffs.add(m.plusNanos(1000));
        }
        int compared = 0, nonEmpty = 0;
        for (String tz : List.of("UTC", "Asia/Seoul")) {
            for (Instant cutoff : cutoffs) {
                for (int limit : new int[]{1, 2, 3, 5, 7, 100}) {
                    for (String m : List.of("4h", "24h")) {
                        Pair p = runBoth(m.equals("4h") ? ORIGINAL_4H : original24h(),
                                candidate(m.equals("4h") ? "findPendingHoldFor4hEval" : "findPendingHoldFor24hEval"),
                                cutoff, limit, tz);
                        assertTieMembership(p.original());
                        assertTieMembership(p.candidate());
                        assertThat(normalize(p.candidate()))
                                .as("%s tz=%s cutoff=%s limit=%d", m, tz, cutoff, limit)
                                .isEqualTo(normalize(p.original()));
                        compared++;
                        if (!p.original().isEmpty()) nonEmpty++;
                    }
                }
            }
        }
        System.out.println("[N13] 격자 대조 " + compared + "건(결과 있는 경우 " + nonEmpty + "건), cutoff " + cutoffs.size() + "개");
        assertThat(compared).isGreaterThan(1000);
        assertThat(nonEmpty).isGreaterThan(compared / 2);
    }

    @Test
    @DisplayName("리포지토리(Hibernate 엔티티) 호출 결과가 같은 스냅숏의 원문 JDBC 결과와 id·순서·주요 필드가 같다")
    void repositoryEntities() {
        Instant cutoff = h(7.5);
        for (String m : List.of("4h", "24h")) {
            List<StrategyLogEntity> got = m.equals("4h") ? repo.findPendingHoldFor4hEval(cutoff, 100)
                    : repo.findPendingHoldFor24hEval(cutoff, 100);
            List<Map<String, Object>> orig = jdbc.queryForList(jdbc(m.equals("4h") ? ORIGINAL_4H : original24h()),
                    Timestamp.from(cutoff), 100);
            assertThat(got).hasSameSizeAs(orig);
            for (int i = 0; i < got.size(); i++) {
                StrategyLogEntity g = got.get(i);
                Map<String, Object> o = orig.get(i);
                long oid = ((Number) o.get("id")).longValue();
                if (tieSet.contains(oid)) {
                    assertThat(tieSet).contains(g.getId());
                    continue;
                }
                assertThat(g.getId()).isEqualTo(oid);
                assertThat(g.getCoinPair()).isEqualTo(o.get("coin_pair"));
                assertThat(g.getCreatedAt()).isEqualTo(((Timestamp) o.get("created_at")).toInstant());
                assertThat(g.getSignalPrice()).isEqualByComparingTo((BigDecimal) o.get("signal_price"));
                assertThat(g.getStrategyName()).isEqualTo(o.get("strategy_name"));
                assertThat(g.getSessionId()).isEqualTo(((Number) o.get("session_id")).longValue());
            }
        }
    }

    @Test
    @DisplayName("대표 평가 저장 뒤 다음 배치: 원문·후보 모두 그 시간대를 다시 내지 않고 같은 다음 대표를 낸다")
    void nextBatchAfterSave() throws Exception {
        Instant cutoff = h(7.5);
        Pair first = runBoth(ORIGINAL_4H, candidate("findPendingHoldFor4hEval"), cutoff, 3, "UTC");
        assertThat(names(first.candidate())).containsExactly("a0", "a3", "a7");
        // 앱과 같게 받은 대표 행에만 price_after_4h 를 채운다.
        for (Map<String, Object> r : first.candidate()) {
            jdbc.update("UPDATE strategy_log SET price_after_4h = ? WHERE id = ?", DONE, r.get("id"));
        }
        Pair next = runBoth(ORIGINAL_4H, candidate("findPendingHoldFor4hEval"), cutoff, 3, "UTC");
        assertThat(names(next.original())).containsExactly("b1", "b2", "e6");
        assertThat(names(next.candidate())).containsExactly("b1", "b2", "e6");
        // a1(시간대0 의 두 번째 행)·a3b(시간대2 의 두 번째 행)는 새 대표가 되지 않는다.
        Pair all = runBoth(ORIGINAL_4H, candidate("findPendingHoldFor4hEval"), cutoff, 100, "UTC");
        assertThat(names(all.candidate())).doesNotContain("a1", "a3b").isEqualTo(names(all.original()));
        // 24h 는 4h 완료와 무관하다.
        Pair p24 = runBoth(original24h(), candidate("findPendingHoldFor24hEval"), cutoff, 3, "UTC");
        assertThat(names(p24.candidate())).containsExactly("a0", "e1", "a3").isEqualTo(names(p24.original()));
    }

    @Test
    @DisplayName("후보 SQL 은 원문에서 NOT EXISTS 안쪽만 다르다(바깥 선택 조건·정렬·limit 그대로, 안쪽 조건은 HOLD·평가됨·같은 코인·같은 정시뿐)")
    void textualScope() throws Exception {
        for (String m : List.of("4h", "24h")) {
            String col = "price_after_" + m;
            String orig = m.equals("4h") ? ORIGINAL_4H : original24h();
            String cand = candidate(m.equals("4h") ? "findPendingHoldFor4hEval" : "findPendingHoldFor24hEval");
            List<String> o = new ArrayList<>(orig.lines().toList());
            List<String> k = new ArrayList<>(cand.lines().toList());
            List<String> onlyO = new ArrayList<>(o); onlyO.removeAll(k);
            List<String> onlyK = new ArrayList<>(k); onlyK.removeAll(o);
            assertThat(onlyO).as(m + " 원문에만 있는 줄").containsExactly(
                    "        SELECT 1 FROM strategy_log e",
                    "        WHERE e.signal = 'HOLD'",
                    "          AND e.coin_pair = l.coin_pair",
                    "          AND e.created_at >= date_trunc('hour', l.created_at)",
                    "          AND e.created_at <  date_trunc('hour', l.created_at) + interval '1 hour'",
                    "          AND e." + col + " IS NOT NULL)");
            assertThat(onlyK).as(m + " 후보에만 있는 줄").containsExactly(
                    "        SELECT 1",
                    "        FROM (SELECT DISTINCT e.coin_pair, date_trunc('hour', e.created_at) AS hour_start",
                    "              FROM strategy_log e",
                    "              WHERE e.signal = 'HOLD'",
                    "                AND e." + col + " IS NOT NULL) d",
                    "        WHERE d.coin_pair = l.coin_pair",
                    "          AND d.hour_start = date_trunc('hour', l.created_at))");
            // 두 판의 바깥 줄(선택 조건·DISTINCT ON·ORDER BY·LIMIT)은 순서까지 같다.
            List<String> outerO = new ArrayList<>(o); outerO.removeAll(onlyO);
            List<String> outerK = new ArrayList<>(k); outerK.removeAll(onlyK);
            assertThat(outerK).isEqualTo(outerO);
        }
    }
}
