package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 20260415_analy.md Tier 2 §10 — emergencyStopAll 연쇄 충격.
 *
 * <p>UpbitApiRateLimiter 의 permit 발급/소진/리필 동작과
 * 동시 접근 안전성을 검증한다.</p>
 */
class RateLimiterEmergencyStopTest {

    @Test
    @DisplayName("§10 초기 permit = PERMITS_PER_SECOND")
    void initialPermits_equalsPermitsPerSecond() {
        UpbitApiRateLimiter limiter = new UpbitApiRateLimiter();
        assertThat(limiter.availablePermits()).isEqualTo(UpbitApiRateLimiter.PERMITS_PER_SECOND);
    }

    @Test
    @DisplayName("§10 permit 소진 후 acquire 실패 (timeout=0 모의)")
    void acquireAfterExhaustion_returnsFalse() throws InterruptedException {
        UpbitApiRateLimiter limiter = new UpbitApiRateLimiter();
        int permits = UpbitApiRateLimiter.PERMITS_PER_SECOND;

        // 모든 permit 소진
        for (int i = 0; i < permits; i++) {
            assertThat(limiter.acquire()).isTrue();
        }
        assertThat(limiter.availablePermits()).isZero();

        // 1초 대기 → 리필 데몬이 permit 을 복구
        Thread.sleep(1_200);
        assertThat(limiter.availablePermits()).isEqualTo(permits);
    }

    @Test
    @DisplayName("§10 동시 acquire — 첫 1초 안에 돌아온 발급은 7건 이하, 나머지는 창이 열린 뒤")
    void concurrentAcquire_limitsToPermitsPerSecond() throws InterruptedException {
        UpbitApiRateLimiter limiter = new UpbitApiRateLimiter();
        int threads = UpbitApiRateLimiter.PERMITS_PER_SECOND + 3; // 10
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Long> returnedAt = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (limiter.acquire()) {
                        returnedAt.add(System.nanoTime());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        long t0 = System.nanoTime();   // 🔴 모든 발급은 이 시각 이후다
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        // 🔴 **불변식: 어느 1초 창에서도 7건 이하** (2026-10-12 재작성).
        //
        //    두 번 틀렸다. ① 종전 `acquired <= 7` 은 버스트가 리필 시각을 걸치지 않는다는 가정에
        //    의존했다. ② 그것을 "경과 시간만큼의 리필" 로 넓힌 3차 수정도 틀렸다 — 구현이 슬라이딩
        //    윈도우가 아니라 **생성 시점 기준 고정 주기 리필**이라, 999ms 안에 8건(최대 14건)이 나갈
        //    수 있었다. 시험이 아니라 **구현이 한도를 어겼다.** 제한기를 슬라이딩 윈도우로 바꿨다.
        //
        //    측정은 거짓 실패가 없게 한다: 반환 시각은 발급 시각 **이후**이므로, t0+1초 전에 돌아온
        //    건수는 그 창의 실제 발급 수를 넘지 않는다.
        long withinFirstSecond = returnedAt.stream()
                .filter(t -> t - t0 < TimeUnit.SECONDS.toNanos(1)).count();
        assertThat(withinFirstSecond)
                .as("첫 1초 창 안에 돌아온 발급은 %d건 이하여야 한다", UpbitApiRateLimiter.PERMITS_PER_SECOND)
                .isLessThanOrEqualTo(UpbitApiRateLimiter.PERMITS_PER_SECOND);
        assertThat(returnedAt).as("나머지는 버려지지 않고 창이 열린 뒤 발급된다").hasSize(threads);
    }

    @Test
    @DisplayName("§10 여덟 번째 발급은 첫 발급으로부터 1초가 지나야 돌아온다")
    void eighthPermitWaitsForWindow() {
        UpbitApiRateLimiter limiter = new UpbitApiRateLimiter();
        long t0 = System.nanoTime();
        for (int i = 0; i < UpbitApiRateLimiter.PERMITS_PER_SECOND; i++) {
            assertThat(limiter.acquire()).isTrue();
        }
        assertThat(limiter.acquire()).isTrue();
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(waitedMs)
                .as("🔴 고정 주기 리필이면 리필 시각에 따라 즉시 돌아올 수 있었다")
                .isGreaterThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("§10 시간이 지나도 PERMITS_PER_SECOND 이상으로 permit 이 늘지 않음")
    void refillDaemon_doesNotExceedMax() throws InterruptedException {
        UpbitApiRateLimiter limiter = new UpbitApiRateLimiter();
        // 리필 데몬이 2 사이클 돌아도 max 이상 누적 안 됨
        Thread.sleep(2_500);
        assertThat(limiter.availablePermits()).isLessThanOrEqualTo(UpbitApiRateLimiter.PERMITS_PER_SECOND);
    }
}
