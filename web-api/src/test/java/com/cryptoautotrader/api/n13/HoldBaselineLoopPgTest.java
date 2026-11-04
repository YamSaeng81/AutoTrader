package com.cryptoautotrader.api.n13;

import com.cryptoautotrader.api.service.SignalQualityService;
import com.cryptoautotrader.api.support.TestRedisConfig;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.cryptoautotrader.api.n13.HoldBaselinePgSupport.insert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * N13 — 후보 SQL 로 실제 앱 경로(정기 {@code evaluateSignalQuality}·시작 {@code catchupOnStartup})를 실제 PostgreSQL 에서 돌려
 * 회차 예산·배치·중복 제외·실패/오류 뒤 반환과 후속 회차를 단언한다. 외부 접속 없음(UpbitRestClient 목).
 *
 * <p>연결 관측: 데이터소스를 감싸 스레드별 보유 연결 수를 센다. 가격 조회(목) 안에서 그 스레드가 쥔 연결 수와
 * 트랜잭션 활성 여부를 기록해 단언한다. HOLD 질의 실행마다 전달된 limit 값을 기록한다.</p>
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
@Import({TestRedisConfig.class, HoldBaselineLoopPgTest.Wrap.class})
class HoldBaselineLoopPgTest {

    static volatile HikariDataSource hikari;
    static final Map<Thread, AtomicInteger> HELD = new ConcurrentHashMap<>();
    /** HOLD 질의 실행 기록: "4h:100" 형태(종류:limit). */
    static final List<String> HOLD_QUERIES = Collections.synchronizedList(new ArrayList<>());
    /** 가격 조회 안 관측: "thread|held|tx". */
    static final List<String> FETCH_OBS = Collections.synchronizedList(new ArrayList<>());
    static final AtomicInteger FAIL_QUERY = new AtomicInteger();
    static volatile Set<String> failCoins = Set.of();

    static AtomicInteger held() { return HELD.computeIfAbsent(Thread.currentThread(), k -> new AtomicInteger()); }

    @TestConfiguration
    static class Wrap {
        @Bean
        static BeanPostProcessor n13LoopDataSourceWrap() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof HikariDataSource h && "dataSource".equals(name)) {
                        hikari = h;
                        return new DelegatingDataSource(h) {
                            @Override
                            public Connection getConnection() throws SQLException { return wrap(h.getConnection()); }
                        };
                    }
                    return bean;
                }
            };
        }
    }

    static Connection wrap(Connection c) {
        held().incrementAndGet();
        boolean[] closed = {false};
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
            try {
                if (m.getName().equals("close")) {
                    Object r = m.invoke(c, a);
                    if (!closed[0]) { closed[0] = true; held().decrementAndGet(); }
                    return r;
                }
                Object r = m.invoke(c, a);
                if (m.getName().equals("prepareStatement") && a != null && a[0] instanceof String sql && sql.contains("DISTINCT ON")) {
                    String which = sql.contains("price_after_24h IS NULL") ? "24h" : "4h";
                    PreparedStatement ps = (PreparedStatement) r;
                    int[] limit = {-1};
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p2, m2, a2) -> {
                        if (m2.getName().startsWith("set") && a2 != null && a2.length >= 2 && a2[0] instanceof Integer i && i == 2
                                && a2[1] instanceof Number n) limit[0] = n.intValue();
                        if (m2.getName().equals("executeQuery") && (a2 == null || a2.length == 0)) {
                            HOLD_QUERIES.add(which + ":" + limit[0]);
                            if (FAIL_QUERY.get() > 0 && FAIL_QUERY.decrementAndGet() >= 0) {
                                throw new SQLException("n13 질의 대역 오류");
                            }
                        }
                        try { return m2.invoke(ps, a2); } catch (InvocationTargetException e) { throw e.getCause(); }
                    });
                }
                return r;
            } catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }

    @Autowired SignalQualityService svc;
    @Autowired ThreadPoolTaskScheduler taskScheduler;
    @Autowired JdbcTemplate jdbc;
    @MockBean UpbitRestClient upbit;

    final AtomicInteger fetchCalls = new AtomicInteger();
    Instant base;

    @BeforeEach
    void setUp() throws Exception {
        waitPoolIdle();
        jdbc.update("DELETE FROM strategy_log");
        base = Instant.now().minus(12, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        failCoins = Set.of();
        FAIL_QUERY.set(0);
        when(upbit.getCandles(anyString(), anyString(), anyInt(), any(), anyInt())).thenAnswer(inv -> {
            fetchCalls.incrementAndGet();
            FETCH_OBS.add(Thread.currentThread().getName() + "|" + held().get() + "|"
                    + TransactionSynchronizationManager.isActualTransactionActive());
            if (failCoins.contains((String) inv.getArgument(0))) throw new Exception("Upbit API 호출 실패: 500");
            UpbitCandleResponse r = new UpbitCandleResponse();
            r.setTradePrice(new BigDecimal("101"));
            return List.of(r);
        });
        HOLD_QUERIES.clear();
        FETCH_OBS.clear();
        fetchCalls.set(0);
    }

    void waitPoolIdle() throws InterruptedException {
        long idleSince = System.currentTimeMillis(), until = idleSince + 60_000;
        while (System.currentTimeMillis() < until) {
            if (hikari.getHikariPoolMXBean().getActiveConnections() != 0) idleSince = System.currentTimeMillis();
            else if (System.currentTimeMillis() - idleSince >= 1000) return;
            Thread.sleep(50);
        }
        throw new IllegalStateException("풀이 비지 않는다");
    }

    /** 코인 n개 × 시간대 hours개, 시간대마다 미평가 HOLD 3행. */
    void seed(List<String> coins, int hours) {
        for (String coin : coins) {
            for (int hh = 0; hh < hours; hh++) {
                Instant hs = base.plus(hh, ChronoUnit.HOURS);
                for (int k = 0; k < 3; k++) {
                    insert(jdbc, coin, "HOLD", hs.plusSeconds(300 + k * 600L), new BigDecimal("100"), null, null, "STRAT_A", 1);
                }
            }
        }
    }

    /** 정기 경로: 앱 scheduler 스레드에서 프록시의 evaluateSignalQuality. 예외는 그 스레드 안에서 받아 돌려준다. */
    Throwable scheduledRound() throws Exception {
        Future<Throwable> f = taskScheduler.submit(() -> {
            try { svc.evaluateSignalQuality(); return null; } catch (Throwable t) { return t; }
        });
        return f.get(120, TimeUnit.SECONDS);
    }

    int evaluated(String col) { return jdbc.queryForObject("SELECT count(*) FROM strategy_log WHERE " + col + " IS NOT NULL", Integer.class); }

    /** 시간대마다 평가된 행이 많아야 1개인지. */
    int hoursWithMoreThanOne(String col) {
        return jdbc.queryForObject("SELECT count(*) FROM (SELECT coin_pair, date_trunc('hour', created_at) h FROM strategy_log WHERE "
                + col + " IS NOT NULL GROUP BY 1, 2 HAVING count(*) > 1) x", Integer.class);
    }

    void assertNoConnectionDuringFetch() {
        assertThat(FETCH_OBS).isNotEmpty();
        assertThat(FETCH_OBS).allSatisfy(o -> {
            String[] f = o.split("\\|");
            assertThat(f[1]).as("가격 조회 중 그 스레드의 보유 연결 수: " + o).isEqualTo("0");
            assertThat(f[2]).as("가격 조회 중 트랜잭션 활성: " + o).isEqualTo("false");
        });
    }

    @Test
    @DisplayName("예산·배치: 250 시간대 → 1회차 4h·24h 각 200(limit 100·100), 2회차 50(limit 100·100, 두 번째는 0행), 3회차 0 — 시간대당 평가 1행, 가격 조회 중 연결 0")
    void budgetBatchesAndNoDuplicates() throws Exception {
        seed(List.of("KRW-K01", "KRW-K02", "KRW-K03", "KRW-K04", "KRW-K05"), 50);

        assertThat(scheduledRound()).isNull();
        assertThat(evaluated("price_after_4h")).isEqualTo(200);
        assertThat(evaluated("price_after_24h")).isEqualTo(200);
        assertThat(HOLD_QUERIES).containsExactly("4h:100", "4h:100", "24h:100", "24h:100");
        assertThat(fetchCalls.get()).isEqualTo(400);
        assertNoConnectionDuringFetch();
        assertThat(FETCH_OBS).allSatisfy(o -> assertThat(o).startsWith("scheduler"));
        waitPoolIdle();

        HOLD_QUERIES.clear(); FETCH_OBS.clear(); fetchCalls.set(0);
        assertThat(scheduledRound()).isNull();
        assertThat(evaluated("price_after_4h")).isEqualTo(250);
        assertThat(evaluated("price_after_24h")).isEqualTo(250);
        assertThat(HOLD_QUERIES).containsExactly("4h:100", "4h:100", "24h:100", "24h:100");
        assertThat(fetchCalls.get()).isEqualTo(100);
        assertNoConnectionDuringFetch();

        HOLD_QUERIES.clear(); FETCH_OBS.clear(); fetchCalls.set(0);
        assertThat(scheduledRound()).isNull();
        assertThat(evaluated("price_after_4h")).isEqualTo(250);
        assertThat(HOLD_QUERIES).containsExactly("4h:100", "24h:100");
        assertThat(fetchCalls.get()).isZero();

        assertThat(hoursWithMoreThanOne("price_after_4h")).isZero();
        assertThat(hoursWithMoreThanOne("price_after_24h")).isZero();
        // 평가된 행은 각 시간대의 첫 행(정각+5분)이다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM strategy_log WHERE price_after_4h IS NOT NULL "
                + "AND created_at <> date_trunc('hour', created_at) + interval '5 minute'", Integer.class)).isZero();
        waitPoolIdle();
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    @DisplayName("가격 조회 실패: 실패 코인 행은 미평가로 남고 연결은 반환, 다른 코인은 평가 — 후속 회차에서 실패 코인만 평가(중복 없음)")
    void fetchFailureThenNextRound() throws Exception {
        seed(List.of("KRW-AFAIL", "KRW-BOK"), 3);
        failCoins = Set.of("KRW-AFAIL");

        assertThat(scheduledRound()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM strategy_log WHERE coin_pair='KRW-AFAIL' AND price_after_4h IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM strategy_log WHERE coin_pair='KRW-BOK' AND price_after_4h IS NOT NULL", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM strategy_log WHERE coin_pair='KRW-BOK' AND price_after_24h IS NOT NULL", Integer.class)).isEqualTo(3);
        // 첫 조회(6시간대: 실패3+성공3) → 진전 있음 → 두 번째 조회(limit=min(100, 200-3)=100, 실패 코인 3시간대만) → 전량 실패로 회차 접음.
        assertThat(HOLD_QUERIES).containsExactly("4h:100", "4h:100", "24h:100", "24h:100");
        assertNoConnectionDuringFetch();
        waitPoolIdle();
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isZero();

        failCoins = Set.of();
        HOLD_QUERIES.clear(); FETCH_OBS.clear(); fetchCalls.set(0);
        assertThat(scheduledRound()).isNull();
        assertThat(evaluated("price_after_4h")).isEqualTo(6);
        assertThat(evaluated("price_after_24h")).isEqualTo(6);
        assertThat(fetchCalls.get()).isEqualTo(6);
        assertThat(hoursWithMoreThanOne("price_after_4h")).isZero();
        assertThat(hoursWithMoreThanOne("price_after_24h")).isZero();
        assertNoConnectionDuringFetch();
    }

    @Test
    @DisplayName("질의 오류: 4h 첫 HOLD 질의가 실패하면 그 회차는 예외로 끝나고 연결은 모두 반환 — 후속 회차는 정상 평가")
    void queryErrorThenNextRound() throws Exception {
        seed(List.of("KRW-Q01"), 4);
        FAIL_QUERY.set(1);

        Throwable t = scheduledRound();
        assertThat(t).isNotNull();
        assertThat(t).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(evaluated("price_after_4h")).isZero();
        assertThat(evaluated("price_after_24h")).isZero();
        assertThat(HOLD_QUERIES).containsExactly("4h:100");
        waitPoolIdle();
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(HELD.entrySet().stream().filter(e -> e.getKey().getName().startsWith("scheduler"))
                .mapToInt(e -> e.getValue().get()).sum()).as("scheduler 스레드가 쥔 연결").isZero();

        HOLD_QUERIES.clear(); FETCH_OBS.clear(); fetchCalls.set(0);
        assertThat(scheduledRound()).isNull();
        assertThat(evaluated("price_after_4h")).isEqualTo(4);
        assertThat(evaluated("price_after_24h")).isEqualTo(4);
        assertNoConnectionDuringFetch();
    }

    @Test
    @DisplayName("시작 catchup(@Async task 스레드): 후보 SQL 로 같은 예산·결과, 가격 조회 중 연결 0·트랜잭션 없음")
    void catchupPath() throws Exception {
        seed(List.of("KRW-S01", "KRW-S02"), 2);
        svc.catchupOnStartup();
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end && (evaluated("price_after_4h") < 4 || evaluated("price_after_24h") < 4)) {
            Thread.sleep(100);
        }
        waitPoolIdle();
        assertThat(evaluated("price_after_4h")).isEqualTo(4);
        assertThat(evaluated("price_after_24h")).isEqualTo(4);
        assertThat(HOLD_QUERIES).containsExactly("4h:100", "4h:100", "24h:100", "24h:100");
        assertNoConnectionDuringFetch();
        assertThat(FETCH_OBS).allSatisfy(o -> assertThat(o).doesNotStartWith("Test worker"));
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
