package com.cryptoautotrader.api.support.sqltrace;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL 실행 기록 — <b>test B 의 관측 장치</b> (2026-09-26 신설).
 *
 * <p><b>왜 필요한가</b>: {@code org.hibernate.SQL} 로그만 보면 같은
 * {@code update dynamic_session …} 이 <b>바깥 트랜잭션에서 나온 것인지 REQUIRES_NEW 안쪽에서
 * 나온 것인지 구분할 수 없다.</b> Hibernate 가 정적 UPDATE(전 컬럼)를 쓰므로 SQL 문자열까지
 * 동일하다. 그래서 문장마다 <b>연결·트랜잭션·스레드</b>와 <b>실행 단계</b>를 함께 남긴다.
 *
 * <h3>세 가지 설계 제약</h3>
 * <ol>
 *   <li><b>연결 식별의 한계를 명시한다.</b> {@link #connTag} 는 감싼 객체의
 *       {@code identityHashCode} 이므로 <b>풀 래퍼의 식별자일 수 있고 서버 백엔드와 1:1 이
 *       아니다.</b> PostgreSQL 검증에서는 {@code pg_backend_pid()} 를 함께 기록해야
 *       운영에서 본 blocker·waiter(pid)와 이을 수 있다 — {@link #BACKEND_PID_ENABLED} 참조.</li>
 *   <li><b>시작만으로 완료를 단정하지 않는다.</b> 한 문장이 {@link Phase#START} ·
 *       {@link Phase#END} 또는 {@link Phase#FAIL} 로 <b>두 줄</b> 남는다. START 만 있는 문장은
 *       <b>실행 중이거나 응답을 못 받은 것</b>이며 UPDATE 완료나 잠금 획득의 증거가 아니다.</li>
 *   <li><b>관측은 개입하지 않는다.</b> 이 장치는 추가 flush·commit·연결 종료를 하지 않는다.
 *       🔴 A 의 {@code Connection is closed} 원인이 미확정이므로 <b>연결 수명에 영향을 주지
 *       않는 것</b>이 특히 중요하다 — 그래서 {@code pg_backend_pid()} 조회조차 기본으로 끈다
 *       (추가 질의는 그 자체로 개입이다).</li>
 * </ol>
 */
public final class SqlTrace {

    /** 🔴 PostgreSQL 검증에서만 켠다. 추가 질의는 개입이므로 H2 테스트에서는 끈 채로 둔다. */
    public static final AtomicBoolean BACKEND_PID_ENABLED = new AtomicBoolean(false);

    private static final AtomicBoolean ON = new AtomicBoolean(false);
    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<Row> ROWS = new CopyOnWriteArrayList<>();

    private SqlTrace() {}

    public enum Phase { START, END, FAIL }

    /**
     * @param connTag   감싼 커넥션의 identityHashCode — ⚠️ 래퍼 식별자일 수 있다 (위 제약 1)
     * @param backendPid PostgreSQL 백엔드 pid. 끄거나 얻지 못하면 {@code null}
     * @param txName    Spring 트랜잭션 이름. 없으면 {@code null} (= 트랜잭션 밖)
     */
    public record Row(long seq, Phase phase, String sql, List<String> params,
                      int connTag, Integer backendPid, String thread,
                      String txName, boolean txActive, String error) {

        public boolean isUpdateOf(String table) {
            String s = sql.toLowerCase();
            return s.startsWith("update ") && s.contains(table.toLowerCase());
        }

        public boolean isInsertInto(String table) {
            String s = sql.toLowerCase();
            return s.startsWith("insert into ") && s.contains(table.toLowerCase());
        }
    }

    public static void start() {
        ROWS.clear();
        SEQ.set(0);
        ON.set(true);
    }

    public static void stop() {
        ON.set(false);
    }

    public static boolean isOn() {
        return ON.get();
    }

    public static List<Row> rows() {
        return List.copyOf(ROWS);
    }

    static long record(Phase phase, String sql, List<String> params, int connTag,
                       Integer backendPid, String error) {
        if (!ON.get()) return -1;
        long seq = SEQ.incrementAndGet();
        ROWS.add(new Row(seq, phase, sql == null ? "" : sql.trim(), params, connTag, backendPid,
                Thread.currentThread().getName(),
                TransactionSynchronizationManager.getCurrentTransactionName(),
                TransactionSynchronizationManager.isActualTransactionActive(),
                error));
        return seq;
    }

    /** 사람이 읽기 위한 덤프 — 실패 진단용. */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        for (Row r : ROWS) {
            sb.append(String.format("#%d %-5s conn=%d pid=%s thread=%s tx=%s active=%s%n      %s%n",
                    r.seq(), r.phase(), r.connTag(), String.valueOf(r.backendPid()),
                    r.thread(), r.txName(), r.txActive(),
                    r.sql().length() > 140 ? r.sql().substring(0, 140) + "…" : r.sql()));
            if (r.error() != null) sb.append("      error=").append(r.error()).append('\n');
            if (!r.params().isEmpty()) sb.append("      params=").append(r.params()).append('\n');
        }
        return sb.toString();
    }
}
