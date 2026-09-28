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
 *       (추가 질의는 그 자체로 개입이다).
 *       ⚠️ {@code SqlTraceSelfTest} 가 "켜고/끄고 결과가 같다"를 확인하지만, 그것은
 *       <b>자기 시험한 그 경로</b>에 한한 근거다 — 🔴 <b>모든 상황의 비개입성을 보장하지
 *       않는다.</b> 관측 결과는 이 한계 아래에서 읽는다.</li>
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

        // 🔴 테이블 이름이 **다른 테이블의 컬럼명**에 들어 있는 경우를 걸러야 한다.
        //    처음 구현은 contains 만 써서 `insert into risk_config (… max_position …)` 이
        //    "insert into position" 으로 잡혔다 — 관측이 아니라 오독을 만들었다.
        public boolean isUpdateOf(String table) {
            return sql.toLowerCase().startsWith("update " + table.toLowerCase() + " ");
        }

        public boolean isInsertInto(String table) {
            String s = sql.toLowerCase();
            return s.startsWith("insert into " + table.toLowerCase() + " ")
                    || s.startsWith("insert into " + table.toLowerCase() + "(");
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

    /**
     * <b>현재 스레드가 낸 문장만</b> 돌려준다 — 🔴 2026-09-28 추가.
     *
     * <p><b>왜 필요한가</b>: 이 기록부는 전역이고 테스트 컨텍스트에는 스케줄러가 함께 돈다.
     * 전체 회귀로 돌리면 다른 스레드(`scheduler-*`)의 문장이 섞여 <b>순서 인덱스가 밀린다</b> —
     * 단독 실행에서는 통과하고 전체 실행에서는 실패하는 일이 실제로 있었다.
     * 관심 경계(바깥 트랜잭션과 REQUIRES_NEW 안쪽)는 <b>같은 스레드에서 동기적으로</b>
     * 실행되므로, 스레드로 좁히면 잡음을 제거하면서 관측 대상은 온전히 남는다.
     */
    public static List<Row> rowsOfCurrentThread() {
        String me = Thread.currentThread().getName();
        return ROWS.stream().filter(r -> me.equals(r.thread())).toList();
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
