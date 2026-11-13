package com.cryptoautotrader.api.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 🔬 주문 흐름 관측 — <b>시험 전용 계측</b> (총괄 v1.160 §3, 2026-11-13).
 *
 * <p>정리 작업의 CLOSING·의도 저장 → 호출자 커밋 → 실행기 접수·작업 시작 → claim → 거래소 대역 호출 → 결과 저장·커밋을
 * 시각·스레드·트랜잭션 자원 식별자로 잇기 위한 기록 장치다. 제품 코드를 바꾸지 않고, 실제 메서드가 그대로 돌게 하면서
 * 그 앞뒤만 적는다.</p>
 *
 * <h3>붙이는 곳 세 군데 (컨텍스트 차이)</h3>
 * <ol>
 *   <li>{@link Config#orderExecutorObserver()} — {@code orderExecutor} 빈의 초기화 콜백 <b>직전에</b> 작업 꾸밈 장치만 설정한다.
 *       이 빈은 {@code AsyncConfig} 의 생성 메서드에서 한 번, 스프링 초기화 콜백({@code afterPropertiesSet → initialize})에서
 *       다시 한 번 초기화된다(운영도 같다, spring-context 6.1.6 바이트코드로 확인). 두 번째 초기화가 꾸밈 장치를 넣은 풀을 만든다.
 *       빈 종류·이름·프록시·풀 크기·대기열·종료 설정은 바뀌지 않는다. 바뀌는 것은 작업마다 꾸밈 장치를 한 번 거친다는 점이다.</li>
 *   <li>{@link Inspector} — Hibernate 의 {@code hibernate.session_factory.statement_inspector}. SQL 을 바꾸지 않고(받은 문자열을
 *       그대로 돌려준다) 실행 스레드·트랜잭션 자원만 적는다. 이 속성은 시험 클래스의 컨텍스트 키를 바꾼다(이 클래스는 원래
 *       {@code scheduler} 프로필·자체 대역으로 별도 컨텍스트다).</li>
 *   <li>트랜잭션 완료 기록 — 관측 대상 SQL 이 처음 나온 트랜잭션에 완료 콜백을 하나 등록해 커밋/롤백 시각을 남긴다. 기존 콜백의
 *       순서나 결과에는 관여하지 않는다(기록만 하고 예외를 삼킨다).</li>
 * </ol>
 *
 * <p>🔴 인증값·거래소 응답 원문은 적지 않는다. SQL 은 매개변수 없이 문장 앞부분만 적는다.</p>
 */
public final class OrderFlowObservation {

    private OrderFlowObservation() { }

    /** 기록 한 줄. {@code task} 는 실행기 작업 번호(작업 스레드 안의 기록만). */
    public record Event(long seq, long nanos, Instant at, String thread, String kind, String tx, Long task, String detail) {
        public String format(long baseNanos) {
            return String.format(Locale.ROOT, "#%d +%.3fs %s [%s] %s %s%s %s", seq, (nanos - baseNanos) / 1e9, at, thread,
                    kind, tx, task == null ? "" : " task=" + task, detail == null ? "" : detail);
        }
    }

    private static final int CAP = 20_000;
    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicLong TASK_SEQ = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final ThreadLocal<Long> CURRENT_TASK = new ThreadLocal<>();
    /** 완료 콜백을 이미 등록한 트랜잭션(EntityManager 동일성). */
    private static final Set<Object> TX_WATCHED = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static volatile ThreadPoolTaskExecutor orderExecutor;

    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern WATCHED_TABLES = Pattern.compile(
            "(?i)\\b(position|\"order\"|order|position_cleanup|trade_log|live_trading_session|dynamic_session)\\b");

    // ---- 기록 ----------------------------------------------------------------------------------

    public static void record(String kind, String detail) {
        Event e = new Event(SEQ.incrementAndGet(), System.nanoTime(), Instant.now(), Thread.currentThread().getName(),
                kind, txId(), CURRENT_TASK.get(), detail);
        synchronized (EVENTS) {
            if (EVENTS.size() >= CAP) {
                EVENTS.pollFirst();
                DROPPED.incrementAndGet();
            }
            EVENTS.addLast(e);
        }
    }

    /** 표지를 남기고 그 번호를 돌려준다 — 이후 {@link #since(long)} 로 그 뒤 기록만 꺼낸다. */
    public static long mark(String label) {
        record("MARK", label);
        return SEQ.get();
    }

    public static List<Event> since(long seq) {
        synchronized (EVENTS) {
            List<Event> out = new ArrayList<>();
            for (Event e : EVENTS) {
                if (e.seq() >= seq) out.add(e);
            }
            return out;
        }
    }

    public static long dropped() {
        return DROPPED.get();
    }

    /** 현재 스레드의 트랜잭션 자원 식별자 — 이름·EntityManager·ConnectionHolder 의 객체 동일성(16진). */
    public static String txId() {
        try {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                return "tx=-";
            }
            StringBuilder sb = new StringBuilder("tx=");
            String name = TransactionSynchronizationManager.getCurrentTransactionName();
            sb.append(name == null ? "?" : shortName(name));
            for (Object v : TransactionSynchronizationManager.getResourceMap().values()) {
                if (v instanceof EntityManagerHolder h) {
                    sb.append(" EM@").append(Integer.toHexString(System.identityHashCode(h.getEntityManager())));
                } else if (v instanceof ConnectionHolder ch) {
                    sb.append(" CH@").append(Integer.toHexString(System.identityHashCode(ch)));
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "tx=관측실패:" + t.getClass().getSimpleName();
        }
    }

    private static String shortName(String name) {
        int i = name.lastIndexOf('.', name.lastIndexOf('.') - 1);
        return i > 0 ? name.substring(i + 1) : name;
    }

    /** 이 트랜잭션의 완료(커밋/롤백)를 한 번만 기록하도록 콜백을 건다. */
    private static void watchTransaction() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        Object key = null;
        for (Object v : TransactionSynchronizationManager.getResourceMap().values()) {
            if (v instanceof EntityManagerHolder h) {
                key = h.getEntityManager();
            }
        }
        if (key == null || !TX_WATCHED.add(key)) {
            return;
        }
        final Object k = key;
        final String id = txId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            // 커밋 직후 콜백 — 이 콜백은 관측 대상 SQL 이 처음 나올 때 등록되므로, 같은 트랜잭션에서 뒤에 등록된 커밋 후 제출 콜백
            // (실행기 접수)보다 먼저 돈다(같은 순서값은 등록 순). 커밋 시각과 접수 시각의 순서가 실제와 같게 남는다.
            @Override
            public void afterCommit() {
                try {
                    record("TX_COMMIT", "커밋 " + id);
                } catch (Throwable ignore) {
                    // 관측은 결과를 바꾸지 않는다
                }
            }

            @Override
            public void afterCompletion(int status) {
                try {
                    if (status != STATUS_COMMITTED) {
                        record(status == STATUS_ROLLED_BACK ? "TX_ROLLBACK" : "TX_UNKNOWN", "완료 " + id);
                    }
                } catch (Throwable ignore) {
                    // 관측은 결과를 바꾸지 않는다
                } finally {
                    TX_WATCHED.remove(k);
                }
            }
        });
    }

    static void onSql(String sql) {
        String thread = Thread.currentThread().getName();
        boolean worker = thread.startsWith("order-exec-");
        boolean scheduler = thread.startsWith("scheduler-");
        if (!worker && !scheduler) {
            return;
        }
        String flat = WS.matcher(sql).replaceAll(" ").trim();
        String lower = flat.toLowerCase(Locale.ROOT);
        if (!WATCHED_TABLES.matcher(lower).find()) {
            return;
        }
        boolean write = lower.startsWith("insert") || lower.startsWith("update") || lower.startsWith("delete");
        boolean lock = lower.contains(" for update") || lower.contains(" for no key update");
        // 스케줄러 스레드는 다른 예약 작업도 돈다 — 쓰기·잠금 조회만 적는다. 실행기 스레드는 모두 적는다.
        if (scheduler && !write && !lock) {
            return;
        }
        record(write ? "SQL_W" : lock ? "SQL_LOCK" : "SQL_R", flat.length() > 160 ? flat.substring(0, 160) + "…" : flat);
        watchTransaction();
    }

    // ---- 실행기 -------------------------------------------------------------------------------

    public static String executorStats() {
        ThreadPoolTaskExecutor tpe = orderExecutor;
        if (tpe == null) {
            return "orderExecutor=관측 장치 미설치";
        }
        try {
            ThreadPoolExecutor ex = tpe.getThreadPoolExecutor();
            return "orderExecutor pool=" + ex.getPoolSize() + "/core" + ex.getCorePoolSize() + "/max" + ex.getMaximumPoolSize()
                    + " active=" + ex.getActiveCount() + " queued=" + ex.getQueue().size()
                    + " remaining=" + ex.getQueue().remainingCapacity() + " tasks=" + ex.getTaskCount()
                    + " completed=" + ex.getCompletedTaskCount() + " largest=" + ex.getLargestPoolSize()
                    + " shutdown=" + ex.isShutdown();
        } catch (Throwable t) {
            return "orderExecutor=조회실패:" + t;
        }
    }

    static TaskDecorator decorator() {
        return command -> {
            long id = TASK_SEQ.incrementAndGet();
            long accepted = System.nanoTime();
            // 접수 시점은 호출 스레드다 — 커밋 후 콜백에서 넘겼다면 막 끝난 트랜잭션의 자원이 아직 묶여 있다.
            record("EXEC_SUBMIT", "task=" + id + " " + executorStats());
            return () -> {
                CURRENT_TASK.set(id);
                record("EXEC_START", String.format(Locale.ROOT, "대기 %.3fs", (System.nanoTime() - accepted) / 1e9));
                try {
                    command.run();
                    record("EXEC_END", null);
                } catch (Throwable t) {
                    record("EXEC_ERROR", t.getClass().getName());
                    throw t;
                } finally {
                    CURRENT_TASK.remove();
                }
            };
        };
    }

    // ---- 스레드 --------------------------------------------------------------------------------

    /** 주문 실행기·스케줄러 스레드와 BLOCKED 스레드의 스택. 잠금 소유자 정보를 함께 적는다. */
    public static List<String> threadDump() {
        List<String> out = new ArrayList<>();
        try {
            ThreadInfo[] infos = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
            for (ThreadInfo ti : infos) {
                String n = ti.getThreadName();
                if (!(n.startsWith("order-exec-") || n.startsWith("scheduler-") || ti.getThreadState() == Thread.State.BLOCKED)) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                sb.append('"').append(n).append("\" ").append(ti.getThreadState());
                if (ti.getLockName() != null) sb.append(" on ").append(ti.getLockName());
                if (ti.getLockOwnerName() != null) sb.append(" owned by \"").append(ti.getLockOwnerName()).append('"');
                for (StackTraceElement el : ti.getStackTrace()) {
                    sb.append("\n    at ").append(el);
                }
                out.add(sb.toString());
            }
        } catch (Throwable t) {
            out.add("스레드 덤프 실패: " + t);
        }
        return out;
    }

    /** 스택에서 보이는 대기 종류 — 분류 보조. */
    public static List<String> waitHints(List<String> dump) {
        List<String> hints = new ArrayList<>();
        for (String s : dump) {
            if (!s.startsWith("\"order-exec-")) continue;
            String head = s.substring(0, s.indexOf('\n') > 0 ? s.indexOf('\n') : s.length());
            if (s.contains("org.h2.mvstore.tx") || s.contains("org.h2.mvstore.db.MVTable.lock")) hints.add(head + " → H2 행/표 잠금 경로");
            if (s.contains("UpbitApiRateLimiter")) hints.add(head + " → 요청 속도 제한 대기");
            if (s.contains("ReentrantLock") || s.contains("AbstractQueuedSynchronizer")) hints.add(head + " → 프로세스 잠금 대기");
            if (s.contains("HikariPool.getConnection")) hints.add(head + " → 연결 풀 대기");
            if (head.contains("BLOCKED")) hints.add(head + " → 모니터 대기");
        }
        return hints;
    }

    // ---- 스프링 연결 -----------------------------------------------------------------------------

    /** Hibernate 문장 관찰기 — 문자열을 바꾸지 않는다. */
    public static class Inspector implements StatementInspector {
        @Override
        public String inspect(String sql) {
            try {
                onSql(sql);
            } catch (Throwable ignore) {
                // 관측은 결과를 바꾸지 않는다
            }
            return sql;
        }
    }

    /** 시험 클래스가 {@code @Import} 한다. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        static BeanPostProcessor orderExecutorObserver() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                    if ("orderExecutor".equals(beanName) && bean instanceof ThreadPoolTaskExecutor tpe) {
                        tpe.setTaskDecorator(decorator());
                        orderExecutor = tpe;
                        record("EXEC_OBSERVER", "orderExecutor 에 관측 꾸밈 장치 설정(초기화 콜백 전)");
                    }
                    return bean;
                }
            };
        }
    }

    /** 컨텍스트 시작 직후 꾸밈 장치가 실제 풀에 들어갔는지(두 번째 초기화) 확인 — 들어가지 않았으면 기록이 비어 있다. */
    public static boolean executorObserved() {
        return orderExecutor != null;
    }

    /** 맵 출력용 — null 은 "-". */
    public static String kv(Map<String, ?> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append('=').append(v == null ? "-" : v).append(' '));
        return sb.toString().trim();
    }
}
