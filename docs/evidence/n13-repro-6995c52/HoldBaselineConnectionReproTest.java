package com.cryptoautotrader.api.n13;

import com.cryptoautotrader.api.service.SignalQualityService;
import com.cryptoautotrader.api.support.TestRedisConfig;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
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

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * N13 국소 재현(앱 코드 무변경) — 6995c52 의 HOLD 기준선 경로가 연결을 언제 빌리고 돌려주는지 기록한다.
 * 실제 Spring 컨텍스트(H2)·실제 Hikari·실제 프록시를 쓴다. 외부 접속 없음: UpbitRestClient 는 목,
 * 질의 지연은 JDBC 대역(HOLD 기준선 native 질의 executeQuery 앞 대기), 가격 조회 지연은 목 안 대기.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import({TestRedisConfig.class, HoldBaselineConnectionReproTest.Wrap.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HoldBaselineConnectionReproTest {

    static final long QUERY_WAIT_MS = 400, FETCH_WAIT_MS = 400;
    static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());
    static volatile boolean recording = false;
    static volatile long t0 = System.nanoTime();
    static volatile HikariDataSource hikari;
    static final Map<String, AtomicInteger> HELD = new ConcurrentHashMap<>();
    static final AtomicInteger CONN_SEQ = new AtomicInteger();
    static volatile int failQueryOnce = 0;
    static volatile String fetchMode = "ok";

    static AtomicInteger heldCounter() {
        return HELD.computeIfAbsent(Thread.currentThread().getName(), k -> new AtomicInteger());
    }

    static String kind(String t) { return t.replaceAll("-\\d+$", "-N"); }

    static final ThreadLocal<Boolean> MUTE = ThreadLocal.withInitial(() -> false);

    static void ev(String what) {
        if (!recording || MUTE.get()) return;
        int active = hikari == null ? -1 : hikari.getHikariPoolMXBean().getActiveConnections();
        EVENTS.add(String.format("%7.0fms %-12s tx=%-5s 이스레드보유=%d 풀사용중=%d  %s",
                (System.nanoTime() - t0) / 1e6, kind(Thread.currentThread().getName()),
                TransactionSynchronizationManager.isActualTransactionActive(), heldCounter().get(), active, what));
    }

    @TestConfiguration
    static class Wrap {
        @Bean
        static BeanPostProcessor n13DataSourceWrap() {
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
        String id = "c" + CONN_SEQ.incrementAndGet();
        heldCounter().incrementAndGet();
        ev("연결 대여 " + id);
        boolean[] closed = {false};
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
            try {
                if (m.getName().equals("close")) {
                    Object r = m.invoke(c, a);
                    if (!closed[0]) {
                        closed[0] = true;
                        heldCounter().decrementAndGet();
                        ev("연결 반환 " + id);
                    }
                    return r;
                }
                // H2 시험 기반 한정: PostgreSQL 간격 표기 '1 hour' 를 H2 가 읽지 못해 같은 뜻의 표준 표기로만 바꾼다.
                if (m.getName().equals("prepareStatement") && a != null && a[0] instanceof String s0 && s0.contains("interval '1 hour'")) {
                    a = a.clone();
                    a[0] = s0.replace("interval '1 hour'", "INTERVAL '1' HOUR");
                }
                Object r = m.invoke(c, a);
                if (m.getName().equals("prepareStatement") && a != null && a[0] instanceof String sql && sql.contains("DISTINCT ON")) {
                    String which = sql.contains("price_after_24h IS NULL") ? "24h" : "4h";
                    PreparedStatement ps = (PreparedStatement) r;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p2, m2, a2) -> {
                        if (m2.getName().equals("executeQuery") && (a2 == null || a2.length == 0)) {
                            ev("HOLD " + which + " 질의 대기 시작(" + id + ")");
                            Thread.sleep(QUERY_WAIT_MS);
                            if (failQueryOnce > 0) {
                                failQueryOnce--;
                                ev("HOLD " + which + " 질의 대역 오류 던짐(" + id + ")");
                                throw new SQLException("n13 질의 대역 오류");
                            }
                            try {
                                Object rs = m2.invoke(ps, a2);
                                ev("HOLD " + which + " 질의 끝(" + id + ")");
                                return rs;
                            } catch (InvocationTargetException e) { throw e.getCause(); }
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

    @BeforeEach
    void seed() throws Exception {
        recording = false;
        // 기동 시 자동 catchup(ApplicationReadyEvent) 등 앞선 작업이 끝나 풀이 2초 연속 비어 있을 때까지 기다린다.
        long idleSince = System.currentTimeMillis(), until = idleSince + 60_000;
        while (System.currentTimeMillis() < until) {
            if (hikari.getHikariPoolMXBean().getActiveConnections() != 0) idleSince = System.currentTimeMillis();
            else if (System.currentTimeMillis() - idleSince >= 2000) break;
            Thread.sleep(50);
        }
        jdbc.update("DELETE FROM strategy_log");
        Instant at = Instant.now().minus(30, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS).plus(5, ChronoUnit.MINUTES);
        for (String coin : List.of("KRW-AAA", "KRW-BBB")) {
            jdbc.update("INSERT INTO strategy_log(strategy_name, coin_pair, signal, reason, session_type, created_at, signal_price, was_executed) "
                    + "VALUES ('N13', ?, 'HOLD', 'r', 'PAPER', ?, 100, false)", coin, java.sql.Timestamp.from(at));
        }
        when(upbit.getCandles(anyString(), anyString(), anyInt(), any(), anyInt())).thenAnswer(inv -> {
            ev("가격 조회 대기 시작(" + inv.getArgument(0) + ")");
            Thread.sleep(FETCH_WAIT_MS);
            if ("fail".equals(fetchMode)) {
                ev("가격 조회 실패 던짐");
                throw new Exception("Upbit API 호출 실패: 500");
            }
            UpbitCandleResponse r = new UpbitCandleResponse();
            r.setTradePrice(new BigDecimal("101"));
            ev("가격 조회 끝");
            return List.of(r);
        });
        EVENTS.clear();
        HELD.clear();
        t0 = System.nanoTime();
    }

    @AfterEach
    void dump(TestInfo info) throws Exception {
        recording = false;
        Path out = Path.of(System.getProperty("n13.out", "build/n13-events.txt"));
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            w.println("== " + info.getDisplayName());
            synchronized (EVENTS) { EVENTS.forEach(w::println); }
            w.println("-- 끝: 풀사용중=" + hikari.getHikariPoolMXBean().getActiveConnections() + " " + rows());
        }
    }

    /** 스케줄러 오류 처리기가 예외를 삼키므로 호출 스레드 안에서 결과를 기록한다. */
    static void run(Runnable r) {
        try { r.run(); ev("호출 정상 반환"); }
        catch (RuntimeException e) { ev("호출 예외로 끝남: " + e.getClass().getSimpleName()); }
    }

    String rows() {
        Map<String, Object> m = jdbc.queryForMap("SELECT COUNT(price_after_4h) AS p4, COUNT(price_after_24h) AS p24 FROM strategy_log");
        return "평가행 4h=" + m.get("P4") + " 24h=" + m.get("P24") + " (전체 2)";
    }

    @Test @Order(0) @DisplayName("0 프록시 확인")
    void proxyCheck() {
        EVENTS.add("SignalQualityService 빈 AOP 프록시=" + AopUtils.isAopProxy(svc)
                + " 대상=" + AopUtils.getTargetClass(svc).getSimpleName());
        Assertions.assertTrue(AopUtils.isAopProxy(svc));
    }

    @Test @Order(1) @DisplayName("1 정기 경로 정상: scheduler 스레드에서 프록시로 evaluateSignalQuality")
    void scheduledOk() throws Exception {
        fetchMode = "ok";
        recording = true;
        taskScheduler.submit(() -> run(svc::evaluateSignalQuality)).get(60, TimeUnit.SECONDS);
        ev("정기 회차 끝 — " + rows());
    }

    @Test @Order(2) @DisplayName("2 정기 경로 가격 조회 실패 뒤 후속 회차")
    void scheduledFetchFailThenNext() throws Exception {
        fetchMode = "fail";
        recording = true;
        taskScheduler.submit(() -> run(svc::evaluateSignalQuality)).get(60, TimeUnit.SECONDS);
        ev("실패 회차 끝 — " + rows());
        fetchMode = "ok";
        taskScheduler.submit(() -> run(svc::evaluateSignalQuality)).get(60, TimeUnit.SECONDS);
        ev("후속 회차 끝 — " + rows());
    }

    @Test @Order(3) @DisplayName("3 시작 catchup 경로: 프록시로 catchupOnStartup(@Async)")
    void catchupPath() throws Exception {
        fetchMode = "ok";
        recording = true;
        svc.catchupOnStartup();
        ev("catchupOnStartup 호출 반환(호출 스레드)");
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            MUTE.set(true);
            String r = rows();
            MUTE.set(false);
            if (r.startsWith("평가행 4h=2 24h=2")) break;
            Thread.sleep(100);
        }
        Thread.sleep(1500);
        recording = false;
        EVENTS.add("catchup 뒤 " + rows());
    }

    @Test @Order(4) @DisplayName("4 질의 오류 뒤 연결 반환과 후속 회차")
    void queryErrorThenNext() throws Exception {
        fetchMode = "ok";
        failQueryOnce = 1;
        recording = true;
        taskScheduler.submit(() -> run(svc::evaluateSignalQuality)).get(60, TimeUnit.SECONDS);
        taskScheduler.submit(() -> run(svc::evaluateSignalQuality)).get(60, TimeUnit.SECONDS);
        ev("후속 회차 끝 — " + rows());
    }

    @Test @Order(5) @DisplayName("5 대조군: 다른 빈처럼 프록시로 evaluateHoldBaselineLoop 직접 호출")
    void controlExternalProxyCall() throws Exception {
        fetchMode = "ok";
        recording = true;
        taskScheduler.submit(() -> run(() -> svc.evaluateHoldBaselineLoop(true, 200))).get(60, TimeUnit.SECONDS);
        ev("대조군 끝 — " + rows());
    }
}
