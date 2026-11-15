package com.cryptoautotrader.api.support;

import java.net.InetAddress;
import java.net.SocketPermission;
import java.security.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

/**
 * 🔒 시험 외부 연결 감시 — <b>시험 전용</b> (총괄 v1.166 §3-2·3, 2026-11-13).
 *
 * <p>종전 {@link OutboundNetworkGuard}(기본 {@code ProxySelector} 교체)는 선택기를 묻지 않는 원시 소켓·NIO 직접 연결과 설치 전에 만든
 * HttpClient 를 잡지 못했고, 차단 건수를 단언하지 않았다. 이 감시는 JDK 17 의 {@link SecurityManager} 로 <b>모든</b> 자바 소켓 연결
 * ({@code Socket}·{@code SocketChannel}·HttpClient·WebSocket·JDBC)과 호스트 이름 해석이 거치는 {@link SocketPermission} 검사만 본다.
 * 루프백(127.0.0.0/8·::1·localhost)·이 기계 인터페이스 주소(따로 집계)와 이 기계 자신의 이름 해석은 허용한다(로컬 PG·H2·루프백 대역 서버). 그 밖의 대상은 <b>기록하고
 * {@link SecurityException} 으로 막는다</b> — 실제로 나가지 않는다. 클래스 단위 실패 처리는 {@link NetworkIsolationExtension} 이 한다.</p>
 *
 * <p>소켓 이외의 권한 검사는 아무것도 막지 않는다(이전 관리자가 있으면 그대로 위임). 설치 직전에 공용 ForkJoinPool 을 먼저 초기화해
 * 관리자 유무에 따라 바뀌는 작업자 공장(Innocuous)이 쓰이지 않게 한다. 실행 끝에 {@link #uninstall()} 이 원래 관리자로 되돌린다.</p>
 *
 * <p>기본은 켜짐. {@code TEST_NET_GUARD=off} 일 때만 설치하지 않는다(감시 자체를 의심할 때의 대조용).</p>
 */
public final class NetworkIsolationGuard {

    public static final String ENV = "TEST_NET_GUARD";

    private static final List<String> ATTEMPTS = new ArrayList<>();
    private static final List<String> PROBES = new ArrayList<>();
    private static volatile boolean installed;
    private static volatile SecurityManager previous;
    private static volatile Set<String> ownNames = Set.of();
    /** 이 기계 네트워크 인터페이스에 붙은 주소 — 그 주소로의 연결은 기계 밖으로 나가지 않는다(예: Tomcat 종료의 unlockAccept 자기 연결). */
    private static volatile Set<String> ownAddresses = Set.of();
    private static final List<String> SELF = new ArrayList<>();
    private static volatile String disabledReason;

    private NetworkIsolationGuard() { }

    /** 환경 변수가 {@code off} 가 아니면 설치한다. 이미 설치됐으면 아무것도 하지 않는다. */
    public static synchronized void installIfEnabled() {
        if (installed) return;
        if ("off".equalsIgnoreCase(System.getenv(ENV))) {
            disabledReason = ENV + "=off";
            return;
        }
        // 관리자 설치 전에 공용 풀을 초기화한다 — 클래스 초기화 때 관리자가 있으면 Innocuous 작업자(문맥 로더·ThreadLocal 정리)가 된다.
        ForkJoinPool.commonPool();
        java.util.Set<String> names = new java.util.HashSet<>();
        try {
            InetAddress self = InetAddress.getLocalHost();
            names.add(self.getHostName().toLowerCase(Locale.ROOT));
            names.add(self.getCanonicalHostName().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            // 자기 이름을 모르면 그 해석은 기록된다 — 실제로 필요한지 결과로 드러난다
        }
        ownNames = Set.copyOf(names);
        java.util.Set<String> addrs = new java.util.HashSet<>();
        try {
            for (java.net.NetworkInterface nif : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : java.util.Collections.list(nif.getInetAddresses())) {
                    String h = a.getHostAddress();
                    int pct = h.indexOf('%');
                    addrs.add((pct >= 0 ? h.substring(0, pct) : h).toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception ignored) {
            // 목록을 못 얻으면 자기 주소 연결은 기록된다
        }
        ownAddresses = Set.copyOf(addrs);
        if ("1".equals(System.getenv(TRACE_ENV))) installTraceSelector();
        previous = System.getSecurityManager();
        System.setSecurityManager(new Guard());
        installed = true;
    }

    /**
     * 🔬 출처 추적(총괄 v1.168 §3-1, 기본 꺼짐 — {@code TEST_NET_TRACE=1}). HttpClient 의 이름 해석은 작업 스레드에서 JDK 프레임만으로
     * 일어나 호출자를 알 수 없다. 그 클라이언트는 <b>생성 시점</b>의 기본 {@link java.net.ProxySelector} 를 잡고, 요청·WebSocket 열기 때
     * <b>호출 스레드</b>에서 {@code select(uri)} 를 부른다. 그 순간의 스레드·앱 호출 위치·시험 클래스를 기록하고 원래 선택기에 그대로 위임한다
     * (동작 불변, 막는 것은 위 관리자). 감시 설치 때 — 어떤 HttpClient 생성보다 먼저 — 바꾸고, {@link #uninstall()} 이 되돌린다.
     */
    public static final String TRACE_ENV = "TEST_NET_TRACE";
    private static final List<String> TRACES = new ArrayList<>();
    private static volatile java.net.ProxySelector traceOriginal;
    private static volatile java.net.ProxySelector traceMine;
    /** 지금 실행 중인 시험 클래스(확장이 갱신) — 추적 기록에 붙인다. */
    static volatile String currentClass = "-";

    private static void installTraceSelector() {
        java.net.ProxySelector orig = java.net.ProxySelector.getDefault();
        traceOriginal = orig;
        traceMine = new java.net.ProxySelector() {
            @Override
            public List<java.net.Proxy> select(java.net.URI uri) {
                String host = uri.getHost() == null ? "" : uri.getHost();
                if (!isLocal(host)) {
                    StringBuilder sb = new StringBuilder();
                    int shown = 0;
                    for (StackTraceElement e : new Throwable().getStackTrace()) {
                        String c = e.getClassName();
                        if (c.startsWith("com.cryptoautotrader") && !c.contains("NetworkIsolation")) {
                            if (shown++ > 0) sb.append(" <- ");
                            sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
                            if (shown == 6) break;
                        }
                    }
                    synchronized (TRACES) {
                        TRACES.add(java.time.LocalTime.now() + " class=" + currentClass + " uri=" + uri.getScheme() + "://" + host + uri.getRawPath()
                                + " [" + Thread.currentThread().getName() + "] " + (shown == 0 ? "(앱 프레임 없음)" : sb));
                    }
                }
                return orig != null ? orig.select(uri) : List.of(java.net.Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
                if (orig != null) orig.connectFailed(uri, sa, ioe);
            }
        };
        java.net.ProxySelector.setDefault(traceMine);
    }

    public static List<String> traces() {
        synchronized (TRACES) {
            return List.copyOf(TRACES);
        }
    }

    /** 원래 보안 관리자로 되돌린다(보통 null). */
    public static synchronized void uninstall() {
        if (traceMine != null && java.net.ProxySelector.getDefault() == traceMine) java.net.ProxySelector.setDefault(traceOriginal);
        if (!installed) return;
        if (System.getSecurityManager() instanceof Guard) {
            System.setSecurityManager(previous);
        }
        installed = false;
    }

    public static boolean isInstalled() {
        return installed && System.getSecurityManager() instanceof Guard;
    }

    public static String disabledReason() {
        return disabledReason;
    }

    public static int count() {
        synchronized (ATTEMPTS) {
            return ATTEMPTS.size();
        }
    }

    public static List<String> since(int from) {
        synchronized (ATTEMPTS) {
            return List.copyOf(ATTEMPTS.subList(Math.min(from, ATTEMPTS.size()), ATTEMPTS.size()));
        }
    }

    static boolean isLocal(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return host.isEmpty() || host.equals("localhost") || host.startsWith("127.") || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1") || host.equals("0.0.0.0")
                // 미지정 주소(::) — 로컬 서버 바인드 때 해석 검사로 온다. 바깥으로 나가는 대상이 아니다
                || host.equals("::") || host.equals("0:0:0:0:0:0:0:0") || ownNames.contains(host);
    }

    /** {@code SocketPermission} 이름(host, host:port, [v6]:port)에서 호스트만 뽑는다. */
    static String hostOf(String name) {
        if (name.startsWith("[")) {
            int end = name.indexOf(']');
            return end > 0 ? name.substring(0, end + 1) : name;
        }
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(0, colon) : name;
    }

    static boolean isOwnAddress(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        int pct = host.indexOf('%');
        if (pct >= 0) host = host.substring(0, pct);
        return ownAddresses.contains(host);
    }

    /** 이 기계 인터페이스 주소로의 연결(기계 안) — 허용하고 따로 센 목록. */
    public static List<String> selfConnections() {
        synchronized (SELF) {
            return List.copyOf(SELF);
        }
    }

    /** 감시가 있을 때만 생기는 JNDI 이름 서버 권한 확인(패킷 없음) — 차단하지 않고 따로 센 목록. */
    public static List<String> probes() {
        synchronized (PROBES) {
            return List.copyOf(PROBES);
        }
    }

    private static boolean isJndiNameServerProbe() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if ("com.sun.jndi.dns.DnsContextFactory".equals(e.getClassName()) && "filterNameServers".equals(e.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static String origin() {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("com.cryptoautotrader") && !c.contains("NetworkIsolation")) {
                if (shown++ > 0) sb.append(" <- ");
                sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
                if (shown == 3) break;
            }
        }
        if (shown > 0) return sb.toString();
        // 앱 프레임이 없으면 JDK 밖의 첫 라이브러리 프레임을 남긴다 — 어느 구성요소가 연결했는지 귀속하려고
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("javax.") || c.startsWith("jdk.") || c.startsWith("sun.")
                    || c.startsWith("com.sun.") || c.contains("NetworkIsolation")) continue;
            if (shown++ > 0) sb.append(" <- ");
            sb.append(c).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
            if (shown == 4) break;
        }
        return shown == 0 ? "(JDK 프레임만)" : "(앱 프레임 없음) " + sb;
    }

    private static final class Guard extends SecurityManager {
        private final ThreadLocal<Boolean> busy = new ThreadLocal<>();

        @Override
        public void checkPermission(Permission perm) {
            if (perm instanceof SocketPermission sp) {
                inspect(sp);
            } else if (previous != null) {
                previous.checkPermission(perm);
            }
        }

        @Override
        public void checkPermission(Permission perm, Object context) {
            if (perm instanceof SocketPermission sp) {
                inspect(sp);
            } else if (previous != null) {
                previous.checkPermission(perm, context);
            }
        }

        private void inspect(SocketPermission sp) {
            String actions = sp.getActions();
            if (!actions.contains("connect") && !actions.contains("resolve")) return; // listen·accept = 로컬 서버
            if (Boolean.TRUE.equals(busy.get())) return;
            busy.set(Boolean.TRUE);
            try {
                String host = hostOf(sp.getName());
                if (isLocal(host)) return;
                if (isOwnAddress(host)) {
                    synchronized (SELF) {
                        SELF.add(actions + " " + sp.getName() + " [" + Thread.currentThread().getName() + "] " + origin());
                    }
                    return;
                }
                if (isJndiNameServerProbe()) {
                    // JDK DnsContextFactory.filterNameServers 는 관리자가 있을 때만 이름 서버마다 checkConnect 를 부른다(패킷 없음).
                    // 관리자가 없으면 목록을 그대로 돌려주므로, 막지 않아야 감시가 없을 때와 같은 동작이 된다. 따로 센다.
                    synchronized (PROBES) {
                        PROBES.add(actions + " " + sp.getName() + " [" + Thread.currentThread().getName() + "]");
                    }
                    return;
                }
                String entry = actions + " " + sp.getName() + " [" + Thread.currentThread().getName() + "] " + origin()
                        + ("1".equals(System.getenv(TRACE_ENV)) ? " @" + java.time.LocalTime.now() + " class=" + currentClass : "");
                synchronized (ATTEMPTS) {
                    ATTEMPTS.add(entry);
                }
            } finally {
                busy.remove();
            }
            throw new SecurityException("[NET-GUARD] 시험 외부 연결 차단: " + actions + " " + sp.getName());
        }
    }
}
