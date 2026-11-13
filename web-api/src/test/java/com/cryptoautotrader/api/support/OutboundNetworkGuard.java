package com.cryptoautotrader.api.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 🔬 외부 연결 감시 — <b>시험 전용</b> (총괄 v1.165 §3-1, 2026-11-13).
 *
 * <p>409차 진단 실행에서 실제 Upbit WebSocket 연결·구독 전송이 확인됐다. 대역(@MockBean)으로 격리한 뒤에도 남은 외부 연결이 있는지
 * 확인하려고 기본 {@link ProxySelector} 를 바꾼다. {@code java.net.http.HttpClient}(빌드 시점의 기본 선택기)와
 * {@code HttpURLConnection} 이 연결 전에 이 선택기를 묻는다. 루프백이 아닌 대상은 <b>기록하고</b> 닫힌 로컬 포트(127.0.0.1:9)를
 * 프록시로 돌려줘 실제로 나가지 못하게 한다.</p>
 *
 * <p>한계: 선택기를 묻지 않는 원시 소켓·NIO 직접 연결은 잡지 못한다. 이 저장소의 외부 클라이언트는 위 두 방식이다. 설치 전에
 * 만들어진 HttpClient 는 옛 선택기를 쓴다 — 그래서 시험 클래스의 정적 초기화(컨텍스트 생성 전)에서 설치한다.</p>
 */
public final class OutboundNetworkGuard {

    private static final List<String> ATTEMPTS = new ArrayList<>();
    private static volatile boolean installed;

    private OutboundNetworkGuard() { }

    /** 환경 변수 {@code G1_OUTBOUND_GUARD=1} 일 때만 설치한다 — 전역 선택기라 같은 JVM 의 뒤 클래스 동작을 실행 순서에 따라 바꾸지 않도록 기본은 끈다. */
    public static synchronized void installIfRequested() {
        if ("1".equals(System.getenv("G1_OUTBOUND_GUARD"))) install();
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static synchronized void install() {
        if (installed) return;
        ProxySelector original = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String host = uri.getHost();
                if (host == null || host.equals("localhost") || host.startsWith("127.") || host.equals("::1")
                        || host.equals("[::1]")) {
                    return original != null ? original.select(uri) : List.of(Proxy.NO_PROXY);
                }
                synchronized (ATTEMPTS) {
                    ATTEMPTS.add(uri.getScheme() + "://" + host + " [" + Thread.currentThread().getName() + "]");
                }
                OrderFlowObservation.record("OUTBOUND_BLOCKED", uri.getScheme() + "://" + host);
                return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // 기록은 select 에서 했다
            }
        });
        installed = true;
    }

    public static List<String> attempts() {
        synchronized (ATTEMPTS) {
            return List.copyOf(ATTEMPTS);
        }
    }
}
