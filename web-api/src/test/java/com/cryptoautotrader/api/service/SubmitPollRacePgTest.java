package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.OrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SubmitPollRaceTest} 의 사례 A~E 를 <b>실제 PostgreSQL</b> 의 별도 연결·트랜잭션에서 다시 실행하고, 읽기와 저장 사이의
 * 행 잠금 대기 사례 F·G 를 더한다 — 총괄 v1.167 §4-1·3 (2026-11-15).
 *
 * <p>H2 는 READ COMMITTED 갱신의 재평가·행 잠금 대기를 운영과 같게 보여 주지 않는다. F·G 는 시험이 <b>별도 JDBC 연결</b>로 행을
 * 잠그고 갱신한 채 멈춰 있는 동안 제품 스레드가 그 행에서 <b>잠금 대기에 들어간 것을 {@code pg_stat_activity} 로 확인한 뒤</b>
 * 커밋한다. 시간 대기로 순서를 만들지 않는다. 연결 초기화는 운영과 같은 {@code lock_timeout = '10s'} 다.</p>
 *
 * <ul>
 *   <li>F — 제출 스레드가 결과를 기록하려는 순간 다른 경로가 같은 행을 잠그고 체결·정산(FILLED)을 반영 중이다. 제출 결과는
 *       그 커밋 뒤의 최신 행을 보고 아무것도 덮지 않아야 한다.</li>
 *   <li>G — 폴러가 조회 "없음" 으로 재개하려는 순간 제출 결과(SUBMITTED·주문번호)가 같은 행을 잠그고 커밋 전이다. 폴러는 그 커밋
 *       뒤 조건부 갱신이 0행이 되어 아무것도 쓰지 않아야 한다.</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "RACE_PG_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${RACE_PG_URL}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=${RACE_PG_USER:trader}",
        "spring.datasource.password=${RACE_PG_PASSWORD:}",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '10s'",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
})
@DisplayName("제출 결과와 미해결 제출 해소의 커밋 순서 경합 — 실제 PostgreSQL (v1.167)")
class SubmitPollRacePgTest extends SubmitPollRaceTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("PG 전제 — 실제 PostgreSQL·lock_timeout 10s")
    void realPostgres() {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("10s");
    }

    /** 별도 연결에서 행을 잠그고 갱신한 채 커밋 신호를 기다린다. */
    private Thread holdRowLock(String updateSql, Object[] args, CountDownLatch locked, CountDownLatch commit,
                               AtomicReference<Throwable> failure) {
        Thread t = new Thread(() -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(updateSql)) {
                    for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                    assertThat(ps.executeUpdate()).as("잠금 보유 갱신 1행").isEqualTo(1);
                }
                locked.countDown();
                if (!commit.await(15, TimeUnit.SECONDS)) {
                    c.rollback();
                    throw new IllegalStateException("커밋 신호 시간 초과");
                }
                c.commit();
            } catch (Throwable e) {
                failure.set(e);
                locked.countDown();
            }
        }, "race-row-lock");
        t.start();
        return t;
    }

    /** 제품 스레드 하나가 "order" 행 잠금을 기다리고 있음을 확인한다(신호). */
    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query ILIKE '%order%'", Integer.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("제품 스레드의 행 잠금 대기가 관측되지 않았다");
    }

    private void finish(Thread t, AtomicReference<Throwable> failure) throws InterruptedException {
        t.join(15_000);
        assertThat(t.isAlive()).isFalse();
        assertThat(failure.get()).as("잠금 보유 연결 예외").isNull();
    }

    @Test
    @DisplayName("F — 제출 결과 기록 중 다른 경로가 행을 잠그고 FILLED 를 반영하면, 그 커밋 뒤 최신 행을 보고 덮지 않는다")
    void submitResultWaitsForConcurrentSettlement() throws Exception {
        Fixture f = runningSessionWithBuyRequest();
        CreateGate create = new CreateGate();
        stubCreate(create, "uuid-race-f", false);
        stubIdentifierQuery(null, null);
        Long id = submitAndPark(f, create);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = holdRowLock("UPDATE \"order\" SET state = 'FILLED', exchange_order_id = ?, submitted_at = now(), "
                        + "filled_quantity = ?, settlement_version = settlement_version + 1 WHERE id = ? AND state = 'SUBMITTING'",
                new Object[]{"uuid-race-f", new BigDecimal("9.80000000"), id}, locked, commit, failure);
        assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();

        create.release.countDown();     // 제출 스레드가 결과를 기록하러 간다 → 이 행에서 잠금 대기
        awaitLockWaiter();
        commit.countDown();
        finish(holder, failure);
        awaitSubmitThreadDone();

        OrderEntity o = reload(id);
        assertThat(o.getState()).as("🔴 잠금 대기 뒤 최신 행(FILLED)을 보고 SUBMITTED 로 되돌리지 않는다").isEqualTo("FILLED");
        assertThat(o.getFilledQuantity()).as("체결량 보존").isEqualByComparingTo("9.8");
        assertThat(o.getSettlementVersion()).as("정산 버전 보존").isEqualTo(1L);
        assertThat(transitions(id)).doesNotContain("SUBMITTING→SUBMITTED", "FILLED→SUBMITTED");
        verifySubmittedOnce();
    }

    @Test
    @DisplayName("G — 폴러가 조회 '없음' 으로 재개할 때 제출 결과가 행을 잠그고 커밋 전이면, 그 커밋 뒤 0행이 되어 쓰지 않는다")
    void resolverWaitsForConcurrentSubmitCommit() throws Exception {
        Fixture f = runningSessionWithBuyRequest();
        CreateGate create = new CreateGate();
        QueryGate query = new QueryGate();
        stubCreate(create, "uuid-race-g", false);
        stubIdentifierQuery(query, null);
        Long id = submitAndPark(f, create);

        AtomicReference<Throwable> resolverFailure = new AtomicReference<>();
        Thread resolver = startResolver(id, resolverFailure);
        await(query.querying, "폴러의 식별자 조회");

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = holdRowLock("UPDATE \"order\" SET state = 'SUBMITTED', exchange_order_id = ?, submitted_at = now() "
                        + "WHERE id = ? AND state = 'SUBMITTING'",
                new Object[]{"uuid-race-g", id}, locked, commit, failure);
        assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();

        query.release.countDown();      // 폴러가 "없음" 으로 재개 → 이 행에서 잠금 대기
        awaitLockWaiter();
        commit.countDown();
        finish(holder, failure);
        join(resolver, resolverFailure);

        OrderEntity o = reload(id);
        assertThat(o.getState()).as("🔴 잠금 대기 뒤 0행 — SUBMITTED 를 UNKNOWN 으로 덮지 않는다").isEqualTo("SUBMITTED");
        assertThat(o.getExchangeOrderId()).isEqualTo("uuid-race-g");
        assertThat(o.getUnknownReason()).isNull();
        assertThat(transitions(id)).doesNotContain("SUBMITTING→UNKNOWN");

        // 실제 제출 스레드는 거래소 호출 안에 서 있다 — 풀어 주고, 같은 주문번호의 결과가 상태를 바꾸지 않는지 본다.
        create.release.countDown();
        awaitSubmitThreadDone();
        assertThat(reload(id).getState()).isEqualTo("SUBMITTED");
        verifySubmittedOnce();
    }
}
