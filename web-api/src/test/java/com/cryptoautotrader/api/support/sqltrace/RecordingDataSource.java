package com.cryptoautotrader.api.support.sqltrace;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * SQL 실행을 {@link SqlTrace} 에 남기는 {@link DataSource} 래퍼 — <b>테스트 전용</b>.
 *
 * <p>🔴 <b>비개입 원칙</b>: 이 래퍼는 위임만 한다. 추가 flush·commit·rollback·close 를
 * <b>일으키지 않으며</b>, 커넥션 수명에 관여하지 않는다({@code close()} 도 그대로 전달한다).
 * A 의 {@code Connection is closed} 원인이 미확정이므로 관측 장치가 그 변수를 늘리면 안 된다.
 *
 * <p>{@code pg_backend_pid()} 는 <b>추가 질의</b>이므로 기본으로 조회하지 않는다.
 * {@link SqlTrace#BACKEND_PID_ENABLED} 를 켠 경우에만, <b>커넥션당 한 번</b> 조회해 캐시한다
 * (PostgreSQL 검증에서 운영의 blocker·waiter pid 와 잇기 위한 것이다).
 * 조회가 실패하면 조용히 {@code null} 로 둔다 — 관측이 본 시험을 깨뜨리지 않게 한다.
 */
public class RecordingDataSource implements DataSource {

    private final DataSource delegate;
    /** 커넥션 래퍼 식별자 → PostgreSQL 백엔드 pid. 커넥션당 1회만 조회한다. */
    private final ConcurrentHashMap<Integer, Integer> backendPids = new ConcurrentHashMap<>();

    public RecordingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    public DataSource getDelegate() {
        return delegate;
    }

    // ── DataSource ────────────────────────────────────────────────────────────

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return java.util.logging.Logger.getLogger("RecordingDataSource");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return (T) this;
        if (iface.isInstance(delegate)) return (T) delegate;
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || iface.isInstance(delegate) || delegate.isWrapperFor(iface);
    }

    // ── 프록시 ────────────────────────────────────────────────────────────────

    private Connection wrap(Connection real) {
        int connTag = System.identityHashCode(real);
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                new ConnHandler(real, connTag));
    }

    private Integer backendPid(Connection real, int connTag) {
        if (!SqlTrace.BACKEND_PID_ENABLED.get()) return null;
        return backendPids.computeIfAbsent(connTag, k -> {
            // 🔴 추가 질의다 — 켠 경우에만, 커넥션당 한 번만. 실패해도 시험을 깨뜨리지 않는다.
            try (Statement st = real.createStatement();
                 java.sql.ResultSet rs = st.executeQuery("select pg_backend_pid()")) {
                return rs.next() ? rs.getInt(1) : null;
            } catch (Exception ignored) {
                return null;
            }
        });
    }

    private final class ConnHandler implements InvocationHandler {
        private final Connection real;
        private final int connTag;

        ConnHandler(Connection real, int connTag) {
            this.real = real;
            this.connTag = connTag;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            Object out = call(real, m, args);
            if (out instanceof PreparedStatement ps) {
                String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : "";
                return (PreparedStatement) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                        new StmtHandler(ps, sql, connTag, real));
            }
            if (out instanceof Statement st && !(out instanceof PreparedStatement)) {
                return (Statement) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{Statement.class},
                        new StmtHandler(st, "", connTag, real));
            }
            return out;
        }
    }

    private final class StmtHandler implements InvocationHandler {
        private final Statement real;
        private final String sql;
        private final int connTag;
        private final Connection conn;
        private final List<String> params = new ArrayList<>();

        StmtHandler(Statement real, String sql, int connTag, Connection conn) {
            this.real = real;
            this.sql = sql;
            this.connTag = connTag;
            this.conn = conn;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String name = m.getName();

            // 바인드 값 수집 — "어느 세션 행을 건드렸는가"를 보려면 파라미터가 필요하다.
            if (name.startsWith("set") && args != null && args.length >= 2) {
                params.add(String.valueOf(args[1]));
                return call(real, m, args);
            }

            if (!name.startsWith("execute") || !SqlTrace.isOn()) {
                return call(real, m, args);
            }

            String effective = sql.isEmpty() && args != null && args.length > 0
                    && args[0] instanceof String s ? s : sql;
            List<String> snapshot = List.copyOf(params);
            SqlTrace.record(SqlTrace.Phase.START, effective, snapshot, connTag,
                    backendPid(conn, connTag), null);
            try {
                Object out = call(real, m, args);
                SqlTrace.record(SqlTrace.Phase.END, effective, snapshot, connTag,
                        backendPid(conn, connTag), null);
                return out;
            } catch (Throwable t) {
                SqlTrace.record(SqlTrace.Phase.FAIL, effective, snapshot, connTag,
                        backendPid(conn, connTag),
                        t.getClass().getName() + ": " + t.getMessage());
                throw t;
            }
        }
    }

    private static Object call(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }
}
