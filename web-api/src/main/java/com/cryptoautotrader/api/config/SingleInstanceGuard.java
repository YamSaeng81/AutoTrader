package com.cryptoautotrader.api.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <b>단일 인스턴스 강제</b> (2026-10-14).
 *
 * <p>주문 폴러·복구·정리 작업·철회 판단은 "이 앱은 하나만 돈다" 를 전제한다(V86 의 {@code INSTANCE_ID}: 소유자가 지금
 * 프로세스가 아니면 죽은 것으로 본다). 둘이 겹치면 한쪽이 다른 쪽의 살아 있는 의도를 철회하고, 같은 정리 대기를 둘이
 * 처리한다. 그 전제를 규칙이 아니라 <b>구조로</b> 강제한다.</p>
 *
 * <ul>
 *   <li>기동 시 PostgreSQL <b>세션 단위 advisory lock</b> 을 전용 연결로 잡는다. 이미 다른 인스턴스가 쥐고 있으면
 *       <b>기동을 거부</b>한다 — 싱글턴 초기화 단계라 어떤 예약 작업·기동 복구보다 먼저다.</li>
 *   <li>전용 스레드가 5초마다 그 연결이 잠금을 아직 쥐고 있는지 확인한다. 연결이 끊기거나 잠금을 잃으면(DB 재시작,
 *       관리자 종료) <b>프로세스를 즉시 멈춘다</b> — 다른 인스턴스가 잠금을 잡을 수 있게 된 순간 이 인스턴스가 계속
 *       주문을 내면 실행 주체가 둘이 된다. 재기동은 프로세스 관리자의 몫이고, 재기동하면 잠금부터 다시 잡는다.</li>
 *   <li>🔴 감시 주기(5초) 사이에는 잠금을 잃고도 모를 수 있다. 그 사이 거래소로 주문이 나가지 않도록 <b>claim 직전에
 *       한 번 더 확인</b>한다({@link #verifyBeforeSubmit()}) — 잃었으면 그 자리에서 멈춘다. 의도는 {@code INTENT_READY}
 *       로 남고 다음 인스턴스의 복구가 소유자 상실로 철회한다(외부 호출 0).</li>
 *   <li>잠금 연결은 {@code ApplicationName=cat-single-instance} 로 열어 업무용 풀 연결과 구분해 관측한다.</li>
 *   <li>🔴 기본값은 <b>켜짐</b>이다(설정이 없어도 켜진다). H2 시험 프로필만 끈다 — H2 에는 advisory lock 이 없다.</li>
 * </ul>
 */
@Component
@Slf4j
public class SingleInstanceGuard {

    /** 잠금 키 — 이 앱 전용 상수. */
    static final long LOCK_KEY = 0x43415431L;   // "CAT1"
    static final String APPLICATION_NAME = "cat-single-instance";

    private final DataSource dataSource;
    private final boolean enabled;
    private final long watchdogSeconds;
    private Connection lockConnection;
    private ScheduledExecutorService watchdog;

    public SingleInstanceGuard(DataSource dataSource,
                               @Value("${trading.single-instance.enabled:true}") boolean enabled,
                               @Value("${trading.single-instance.watchdog-seconds:5}") long watchdogSeconds) {
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.watchdogSeconds = watchdogSeconds;
    }

    @PostConstruct
    void acquire() throws Exception {
        if (!enabled) {
            log.warn("🔴 단일 인스턴스 강제가 꺼져 있다(trading.single-instance.enabled=false) — 시험 전용 설정이다");
            return;
        }
        // 🔴 풀 밖의 전용 연결이다 — 풀에서 빌려 영구히 쥐면 누수 감지가 경고하고 풀도 하나 준다.
        com.zaxxer.hikari.HikariDataSource hikari = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", hikari.getUsername());
        props.setProperty("password", hikari.getPassword() == null ? "" : hikari.getPassword());
        props.setProperty("ApplicationName", APPLICATION_NAME);
        lockConnection = java.sql.DriverManager.getConnection(hikari.getJdbcUrl(), props);
        lockConnection.setAutoCommit(true);
        boolean got;
        try (PreparedStatement ps = lockConnection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                got = rs.getBoolean(1);
            }
        }
        if (!got) {
            lockConnection.close();
            throw new IllegalStateException("🔴 다른 인스턴스가 이미 실행 중이다(advisory lock " + LOCK_KEY
                    + " 보유) — 단일 인스턴스 전제를 지키기 위해 기동을 거부한다");
        }
        log.info("단일 인스턴스 잠금 획득 (key={})", LOCK_KEY);
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "single-instance-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleWithFixedDelay(() -> verifyStillHeld("감시 주기"), watchdogSeconds, watchdogSeconds, TimeUnit.SECONDS);
    }

    /**
     * 거래소 제출 직전 확인 — 잠금을 잃었으면 즉시 멈춘다(반환하지 않는다). 꺼져 있으면 아무것도 하지 않는다.
     */
    public void verifyBeforeSubmit() {
        if (enabled) {
            verifyStillHeld("claim 직전");
        }
    }

    /** 잠금을 아직 쥐고 있는가 — 아니면 즉시 멈춘다. 연결 하나를 감시 스레드와 제출 스레드가 나눠 쓰므로 직렬화한다. */
    synchronized void verifyStillHeld(String when) {
        boolean held;
        try (PreparedStatement ps = lockConnection.prepareStatement(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid() AND granted "
                        + "AND ((classid::bigint << 32) | objid::bigint) = ?")) {
            ps.setQueryTimeout(10);
            ps.setLong(1, LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                held = rs.getLong(1) > 0;
            }
        } catch (Exception e) {
            log.error("🔴 [{}] 단일 인스턴스 잠금 연결을 확인하지 못했다 — 잠금을 잃었다고 보고 즉시 멈춘다: {}", when, e.getMessage());
            halt(when);
            return;
        }
        if (!held) {
            log.error("🔴 [{}] 단일 인스턴스 잠금을 잃었다 — 다른 인스턴스가 잡을 수 있으므로 즉시 멈춘다", when);
            halt(when);
        }
    }

    private void halt(String when) {
        System.err.println("SINGLE_INSTANCE_LOCK_LOST [" + when + "] — 프로세스를 즉시 멈춘다");
        System.err.flush();
        Runtime.getRuntime().halt(3);
    }

    @PreDestroy
    void release() {
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        if (lockConnection != null) {
            try {
                lockConnection.close();   // 세션 종료 = 잠금 해제
            } catch (Exception ignored) {
                // 종료 중
            }
        }
    }
}
