package com.cryptoautotrader.api.news;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * N11 시험 전용 HTTP 대역 — 127.0.0.1 에만 열고 외부로 나가지 않는다.
 * 경로별로 ① 요청을 읽은 뒤 RST 로 끊기(SO_LINGER 0 — 서버 쪽 연결 강제 종료. 예외 문구는 OS·JDK 마다 다르며
 * Windows JDK 17 에서는 "HTTP/1.1 header parser received no bytes", 운영 리눅스 로그는 "Connection reset") ② 지정 상태·본문 응답을 고른다.
 * 쿼리 문자열은 경로 판정에서 뺀다(CryptoPanic 은 "?public=true" 를 붙인다).
 */
final class LocalHttpStub implements AutoCloseable {

    record Reply(boolean dropWithRst, int status, String body) {
        static Reply reset() { return new Reply(true, 0, null); }
        static Reply ok(String body) { return new Reply(false, 200, body); }
        static Reply status(int status, String body) { return new Reply(false, status, body); }
    }

    private final ServerSocket server;
    private final Map<String, Reply> routes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Thread acceptor;

    LocalHttpStub() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "n11-http-stub");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void route(String path, Reply reply) {
        routes.put(path, reply);
        hits.put(path, new AtomicInteger());
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getLocalPort() + path;
    }

    int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                handle(s);
            } catch (IOException e) {
                // 닫힘
            }
        }
    }

    private void handle(Socket s) throws IOException {
        String head = readHead(s.getInputStream());
        String path = "/";
        String[] first = head.split("\r\n", 2)[0].split(" ");
        if (first.length >= 2) path = first[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        Reply r = routes.get(path);
        AtomicInteger n = hits.get(path);
        if (n != null) n.incrementAndGet();
        if (r == null) r = Reply.status(404, "");
        if (r.dropWithRst()) {
            // 요청을 다 읽고 잠시 뒤 RST — 클라이언트가 응답을 기다리는 중에 끊긴다
            try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            s.setSoLinger(true, 0); // close → RST
            s.close();
            return;
        }
        byte[] body = r.body() == null ? new byte[0] : r.body().getBytes(StandardCharsets.UTF_8);
        String resp = "HTTP/1.1 " + r.status() + " X\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(resp.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
        s.close();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            // \r\n\r\n
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = (b == '\r') ? 1 : 0;
            if (state == 4) break;
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
