package com.cryptoautotrader.exchange.upbit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-10-23 리뷰 — <b>업비트 요청 예산을 인스턴스들이 원자적으로 함께 쓰는가</b>, 그리고 <b>같은 IP 의 감시 요청이 겹쳐도 초당 10회 안인가</b>.
 *
 * <p>운영 관측: 매시 :19 에 서버의 파이썬 감시 작업(`start_66.py freshness`, 종전 초당 2회)이 도는 11초 안에서만 앱의 캔들 요청이 429 를 받았다
 * (10-22 21:19Z·10-23 01:19Z). 종전 제한기는 마지막 시각을 static 으로 공유하면서 잠금은 인스턴스별이라 인스턴스끼리 같은 시각을 동시에 읽을 수 있었다.</p>
 *
 * <p>🔴 평균 합산(앱 8회 + 감시 1회 = 9회)만으로는 "어느 1초에도 10회 이하" 를 보장하지 못한다 — 실제 시작 시각을 모아 <b>모든 1초 창</b>을 센다.</p>
 */
class UpbitRequestLimiterTest {

    private static final long SEC = TimeUnit.SECONDS.toNanos(1);

    /** 정렬된 시작 시각들에서 길이 1초인 모든 창 [t, t+1s) 의 최대 요청 수. */
    private static int maxInAnySecond(List<Long> starts) {
        List<Long> s = new ArrayList<>(starts);
        Collections.sort(s);
        int best = 0;
        int j = 0;
        for (int i = 0; i < s.size(); i++) {
            while (s.get(i) - s.get(j) >= SEC) j++;
            best = Math.max(best, i - j + 1);
        }
        return best;
    }

    @Test
    @DisplayName("예약은 원자적이다 — 여러 스레드가 같은 순간에 예약해도 예약 시각이 서로 정확히 간격 이상 떨어진다")
    void reservationsAreAtomicUnderContention() throws Exception {
        long interval = TimeUnit.MILLISECONDS.toNanos(125);
        UpbitRequestLimiter limiter = new UpbitRequestLimiter(interval);
        int threads = 16, perThread = 50;
        List<Long> slots = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                for (int k = 0; k < perThread; k++) {
                    long now = System.nanoTime();
                    slots.add(now + limiter.reserve(now));
                }
            });
            ts.add(th);
            th.start();
        }
        go.countDown();
        for (Thread th : ts) th.join();

        List<Long> sorted = new ArrayList<>(slots);
        Collections.sort(sorted);
        assertThat(sorted).hasSize(threads * perThread);
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i) - sorted.get(i - 1))
                    .as("예약 %d 와 %d 의 간격", i - 1, i)
                    .isGreaterThanOrEqualTo(interval);
        }
    }

    @Test
    @DisplayName("🔴 다른 인스턴스도 같은 예산을 쓴다 — 두 인스턴스가 연달아 요청하면 두 번째가 간격만큼 기다린다")
    void instancesShareOneBudget() throws Exception {
        UpbitRestClient a = new UpbitRestClient();
        UpbitRestClient b = new UpbitRestClient();
        a.throttle();
        long t0 = System.nanoTime();
        b.throttle();
        long waited = System.nanoTime() - t0;
        assertThat(waited).as("인스턴스가 달라도 간격(125ms)을 지킨다 — 종전엔 인스턴스별 잠금이라 경합 시 함께 통과했다")
                .isGreaterThanOrEqualTo(UpbitRequestLimiter.QUOTATION.intervalNanos() - TimeUnit.MILLISECONDS.toNanos(5));
    }

    @Test
    @DisplayName("🔴 여러 인스턴스·스레드가 동시에 요청하고 감시 작업(초당 1회)이 겹쳐도 어느 1초 창에도 10회를 넘지 않는다")
    void multiInstancePlusMonitor_neverExceedsTenPerSecond() throws Exception {
        int instances = 4, threadsPerInstance = 3, perThread = 9;     // 앱 요청 108건 — 125ms 간격이면 약 13.5초
        List<Long> app = Collections.synchronizedList(new ArrayList<>());
        List<Long> monitor = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            UpbitRestClient client = new UpbitRestClient();
            for (int t = 0; t < threadsPerInstance; t++) {
                Thread th = new Thread(() -> {
                    try {
                        go.await();
                        for (int k = 0; k < perThread; k++) {
                            client.throttle();
                            app.add(System.nanoTime());     // 요청이 나가는 시각
                        }
                    } catch (InterruptedException ignored) {
                    }
                });
                ts.add(th);
                th.start();
            }
        }
        // 같은 IP 의 다른 프로세스(감시 작업) — 이 예산을 모른 채 1초마다 요청한다(완충책 적용 뒤 속도)
        Thread mon = new Thread(() -> {
            try {
                go.await();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(14);
                while (System.nanoTime() < end) {
                    monitor.add(System.nanoTime());
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {
            }
        });
        mon.start();
        go.countDown();
        for (Thread th : ts) th.join();
        mon.join();

        List<Long> all = new ArrayList<>(app);
        all.addAll(monitor);
        int appMax = maxInAnySecond(app);
        int allMax = maxInAnySecond(all);
        assertThat(app).hasSize(instances * threadsPerInstance * perThread);
        assertThat(appMax).as("앱만 — 125ms 간격이면 1초 창에 8회(깨어남 지연 여유 1)").isLessThanOrEqualTo(9);
        assertThat(allMax).as("앱 + 감시 합산 — 업비트 같은 IP 한도 초당 10회 (앱 최대 %d)", appMax).isLessThanOrEqualTo(10);
    }
}
