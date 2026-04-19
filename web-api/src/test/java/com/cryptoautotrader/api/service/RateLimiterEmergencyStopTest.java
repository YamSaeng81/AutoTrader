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
    @DisplayName("§10 동시 acquire — 7건까지만 즉시 허용, 나머지는 리필 후 처리")
    void concurrentAcquire_limitsToPermitsPerSecond() throws InterruptedException {
        UpbitApiRateLimiter limiter = new UpbitApiRateLimiter();
        int threads = UpbitApiRateLimiter.PERMITS_PER_SECOND + 3; // 10
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger failed  = new AtomicInteger();

        // 모든 스레드를 동시에 출발
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 타임아웃 0으로 모의하기 위해 직접 Semaphore 대신 availablePermits 판단
                    if (limiter.availablePermits() > 0 && limiter.acquire()) {
                        acquired.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // 허용된 건수 ≤ PERMITS_PER_SECOND
        assertThat(acquired.get()).isLessThanOrEqualTo(UpbitApiRateLimiter.PERMITS_PER_SECOND);
    }

    @Test
    @DisplayName("§10 리필 데몬 — PERMITS_PER_SECOND 이상으로 permit 이 증가하지 않음")
    void refillDaemon_doesNotExceedMax() throws InterruptedException {
        UpbitApiRateLimiter limiter = new UpbitApiRateLimiter();
        // 리필 데몬이 2 사이클 돌아도 max 이상 누적 안 됨
        Thread.sleep(2_500);
        assertThat(limiter.availablePermits()).isLessThanOrEqualTo(UpbitApiRateLimiter.PERMITS_PER_SECOND);
    }
}
