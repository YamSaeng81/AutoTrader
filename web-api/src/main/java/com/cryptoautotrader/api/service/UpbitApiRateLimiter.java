package com.cryptoautotrader.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 20260415_analy.md Tier 2 §10 — Upbit API rate limit 대응.
 *
 * <p>Upbit 주문 API 한도: 초당 8건, 분당 200건.
 * 비상 청산(emergencyStopAll) 시 다수 SELL + CANCEL 이 동시에 발사되면
 * rate limit 에 걸려 일부 주문이 누락될 수 있다.</p>
 *
 * <p>초당 {@value #PERMITS_PER_SECOND} 건 허용하는 슬라이딩 윈도우 방식 제한기.
 * Semaphore 기반 — 1초마다 데몬 스레드가 permit 을 리필한다.</p>
 */
@Component
@Slf4j
public class UpbitApiRateLimiter {

    /** Upbit 공식 한도 8/sec 에서 1건 마진을 둔다 */
    static final int PERMITS_PER_SECOND = 7;
    /** acquire 시 최대 대기 시간 (초) */
    private static final long ACQUIRE_TIMEOUT_SEC = 10;

    private final Semaphore semaphore;

    public UpbitApiRateLimiter() {
        this.semaphore = new Semaphore(PERMITS_PER_SECOND);
        startRefillDaemon();
    }

    /**
     * API 호출 전 permit 확보. 한도 초과 시 리필까지 최대 10초 대기.
     * 타임아웃 시 false 반환 — 호출부가 재시도 또는 로깅 결정.
     */
    public boolean acquire() {
        try {
            boolean acquired = semaphore.tryAcquire(ACQUIRE_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[RateLimiter] {}초 대기 후에도 permit 확보 실패 — 호출부가 재시도 필요", ACQUIRE_TIMEOUT_SEC);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 현재 가용 permit 수 (모니터링/테스트용) */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    private void startRefillDaemon() {
        Thread refiller = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1_000);
                    int toRelease = PERMITS_PER_SECOND - semaphore.availablePermits();
                    if (toRelease > 0) {
                        semaphore.release(toRelease);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "upbit-rate-refill");
        refiller.setDaemon(true);
        refiller.start();
    }
}
