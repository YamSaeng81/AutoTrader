package com.cryptoautotrader.exchange.upbit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-10-23 리뷰 — <b>업비트 요청 간격을 인스턴스들이 함께 지키는가</b>, <b>늦게 깨어나도 몰려 통과하지 않는가</b>, <b>서버가 실제로 받는 간격은 어떤가</b>.
 *
 * <p>운영 관측: 매시 :19 에 서버의 파이썬 감시 작업(`start_66.py freshness`, 종전 초당 2회)이 도는 11초 안에서만 앱의 캔들 요청이 429 를 받았다.
 * 종전 제한기는 마지막 시각을 static 으로 공유하면서 잠금은 인스턴스별이었다(1차 수정). 1차 수정은 미래 시각을 예약하고 잠금 밖에서 기다려,
 * 늦게 깨어난 스레드들이 지난 예약으로 한꺼번에 통과할 수 있었다(2차 수정 — 통과 시점에 다시 확인·갱신).</p>
 *
 * <p>🔴 확인 범위: ① 통과 시각(제한기 잠금 안에서 읽은 시각)의 최소 간격 — 결정적으로 확인 ② 모의 HTTP 서버가 받은 시각 — 이 실행 조건에서의 분포.
 * 평균 합산(앱 8 + 감시 1 = 9)만으로 "어느 1초에도 10회 이하" 를 보장하지 않으므로 받은 시각의 모든 1초 창을 센다.</p>
 */
class UpbitRequestLimiterTest {

    private static final long SEC = TimeUnit.SECONDS.toNanos(1);
    private static final long INTERVAL = TimeUnit.MILLISECONDS.toNanos(125);

    private HttpServer server;
    private final List<Long> arrivals = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> monitorArrivals = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            long t = System.nanoTime();                       // 서버가 요청을 받은 시각(같은 JVM — nanoTime 비교 가능)
            (ex.getRequestURI().getPath().contains("monitor") ? monitorArrivals : arrivals).add(t);
            ex.getRequestBody().readAllBytes();
            byte[] out = "[]".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 정렬된 시각들에서 길이 1초인 모든 창 [t, t+1s) 의 최대 개수. */
    private static int maxInAnySecond(List<Long> times) {
        List<Long> s = new ArrayList<>(times);
        Collections.sort(s);
        int best = 0, j = 0;
        for (int i = 0; i < s.size(); i++) {
            while (s.get(i) - s.get(j) >= SEC) j++;
            best = Math.max(best, i - j + 1);
        }
        return best;
    }

    private static long minGap(List<Long> times) {
        List<Long> s = new ArrayList<>(times);
        Collections.sort(s);
        long min = Long.MAX_VALUE;
        for (int i = 1; i < s.size(); i++) min = Math.min(min, s.get(i) - s.get(i - 1));
        return min;
    }

    /** 여러 스레드가 같은 제한기로 동시에 통과하게 하고, 통과 시각(잠금 안의 시각)을 모은다. */
    private static List<Long> passTimes(UpbitRequestLimiter limiter, int threads, int perThread) throws Exception {
        List<Long> passed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    go.await();
                    long[] at = new long[1];
                    for (int k = 0; k < perThread; k++) {
                        limiter.acquire(at);
                        passed.add(at[0]);
                    }
                } catch (InterruptedException ignored) {
                }
            });
            ts.add(th);
            th.start();
        }
        go.countDown();
        for (Thread th : ts) th.join();
        return passed;
    }

    @Test
    @DisplayName("통과 시각은 원자적으로 간격을 지킨다 — 12스레드 동시 통과에서도 서로 125ms 이상")
    void passTimesKeepIntervalUnderContention() throws Exception {
        List<Long> passed = passTimes(new UpbitRequestLimiter(INTERVAL, UpbitRequestLimiter.REAL_SLEEP), 12, 4);

        assertThat(passed).hasSize(48);
        assertThat(minGap(passed)).as("통과 시각 최소 간격").isGreaterThanOrEqualTo(INTERVAL);
    }

    @Test
    @DisplayName("🔴 늦게 깨어나도(GC·스케줄링 지연 흉내 — 매번 100~400ms 더 잔다) 몰려 통과하지 않는다 — 통과 시점에 다시 확인")
    void lateWakersDoNotPassTogether() throws Exception {
        AtomicInteger sleeps = new AtomicInteger();
        UpbitRequestLimiter.Sleeper oversleeping = nanos -> {
            sleeps.incrementAndGet();
            TimeUnit.NANOSECONDS.sleep(nanos + TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextLong(100, 400)));
        };
        List<Long> passed = passTimes(new UpbitRequestLimiter(INTERVAL, oversleeping), 10, 3);

        assertThat(sleeps.get()).as("실제로 지연된 대기가 일어났다").isPositive();
        assertThat(passed).hasSize(30);
        assertThat(minGap(passed))
                .as("1차 수정(미래 시각 예약 후 잠금 밖 대기)에서는 늦게 깨어난 스레드들이 지난 예약으로 거의 동시에 통과할 수 있었다")
                .isGreaterThanOrEqualTo(INTERVAL);
    }

    @Test
    @DisplayName("서로 다른 인스턴스도 같은 제한기를 쓴다 — 두 인스턴스의 연속 요청이 서버에 간격을 두고 닿는다(통과 뒤 전송 지연만큼 흔들림)")
    void instancesShareOneLimiter_atServer() throws Exception {
        UpbitRequestLimiter shared = new UpbitRequestLimiter(INTERVAL, UpbitRequestLimiter.REAL_SLEEP);
        UpbitRestClient a = new UpbitRestClient(base(), shared);
        UpbitRestClient b = new UpbitRestClient(base(), shared);

        a.getCandles("KRW-BTC", "minutes", 60, null, 1);
        b.getCandles("KRW-ETH", "minutes", 60, null, 1);

        assertThat(arrivals).hasSize(2);
        // 🔴 처음 이 시험은 서버 쪽 간격 ≥ 120ms 를 요구했고 104ms 로 실패했다 — 첫 요청은 연결 수립이 끼어 늦게 닿고 둘째는 그만큼
        //    빨리 닿는다. 통과 시각은 125ms 이상 떨어져도 서버가 받는 간격은 전송 지연 차이만큼 좁아질 수 있다는 리뷰 지적 그대로다.
        //    엄격한 125ms 는 통과 시각 시험이 맡고, 여기서는 인스턴스가 달라도 함께 통과하지 않는다(간격이 크게 벌어진다)는 것만 본다.
        //    종전(인스턴스별 잠금)에서는 두 인스턴스가 동시에 시도하면 수 ms 안에 함께 닿을 수 있었다.
        assertThat(minGap(arrivals)).as("서버가 받은 간격 %d ms", TimeUnit.NANOSECONDS.toMillis(minGap(arrivals)))
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(60));
    }

    /** 다중 인스턴스 × 스레드가 실제 getCandles 로 모의 서버에 요청하고, 같은 IP 감시 작업(1초 간격)이 겹친다. */
    private void runAppAndMonitor(UpbitRequestLimiter shared, int instances, int threadsPerInstance, int perThread,
                                  long monitorSeconds) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            UpbitRestClient client = new UpbitRestClient(base(), shared);
            for (int t = 0; t < threadsPerInstance; t++) {
                Thread th = new Thread(() -> {
                    try {
                        go.await();
                        for (int k = 0; k < perThread; k++) {
                            client.getCandles("KRW-BTC", "minutes", 60, null, 1);
                        }
                    } catch (Exception ignored) {
                    }
                });
                ts.add(th);
                th.start();
            }
        }
        HttpClient raw = HttpClient.newHttpClient();     // 감시 작업 — 이 프로세스의 제한기를 모른다
        Thread mon = new Thread(() -> {
            try {
                go.await();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(monitorSeconds);
                while (System.nanoTime() < end) {
                    raw.send(HttpRequest.newBuilder(URI.create(base() + "/monitor")).GET().build(), HttpResponse.BodyHandlers.discarding());
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) {
            }
        });
        mon.start();
        go.countDown();
        for (Thread th : ts) th.join();
        mon.join();
    }

    @Test
    @DisplayName("🔴 모의 HTTP 서버가 받은 시각 — 4인스턴스×3스레드(96건) + 감시 1초 간격이 겹쳐도 어느 1초 창에도 10회 이하(이 실행 조건)")
    void serverSideArrivals_multiInstancePlusMonitor() throws Exception {
        runAppAndMonitor(new UpbitRequestLimiter(INTERVAL, UpbitRequestLimiter.REAL_SLEEP), 4, 3, 8, 13);

        List<Long> all = new ArrayList<>(arrivals);
        all.addAll(monitorArrivals);
        assertThat(arrivals).hasSize(96);
        int appMax = maxInAnySecond(arrivals);
        assertThat(appMax).as("앱 요청이 서버에 닿은 1초 창 최대 — 간격 125ms 면 8, 통과 뒤 전송 지연의 흔들림 여유 1 (서버 쪽 최소 간격 %d ms)",
                TimeUnit.NANOSECONDS.toMillis(minGap(arrivals))).isLessThanOrEqualTo(9);
        assertThat(maxInAnySecond(all)).as("앱 + 감시 합산 (앱 최대 %d)", appMax).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("🔴 늦게 깨어나는 제한기로 같은 HTTP 경로를 돌려도 서버가 받은 1초 창은 10회 이하(지연은 요청을 흩을 뿐 몰리게 하지 않는다)")
    void serverSideArrivals_withLateWakers() throws Exception {
        UpbitRequestLimiter.Sleeper oversleeping = nanos ->
                TimeUnit.NANOSECONDS.sleep(nanos + TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextLong(50, 300)));
        runAppAndMonitor(new UpbitRequestLimiter(INTERVAL, oversleeping), 4, 3, 4, 10);

        List<Long> all = new ArrayList<>(arrivals);
        all.addAll(monitorArrivals);
        assertThat(arrivals).hasSize(48);
        assertThat(maxInAnySecond(arrivals)).isLessThanOrEqualTo(9);
        assertThat(maxInAnySecond(all)).isLessThanOrEqualTo(10);
    }
}
