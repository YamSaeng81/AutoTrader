package com.cryptoautotrader.api.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExecutorBarrierTest 의 전제("옛 조건은 이 창에서 참") 실패 원인 탐침 — 총괄 v1.167 §4-5 (2026-11-15, 시험만).
 *
 * <p>JDK17 {@code ThreadPoolExecutor.Worker} 는 생성 때 상태 -1 이고 {@code runWorker} 첫 줄의 {@code unlock()} 에서 0 이 된다.
 * {@code isLocked()} 는 상태 ≠ 0 이므로, <b>시작은 됐지만 아직 {@code runWorker} 에 들어가지 못한 작업자</b>는
 * {@code getActiveCount()} 에 1 로 세지고 {@code getTaskCount()} 에도 1 을 더한다. 전체 회귀처럼 CPU 가 붐비면
 * {@code prestartAllCoreThreads()} 직후 둘째 작업자가 그 상태에 머물 수 있다. 이 탐침은 스레드 공장에서 둘째 작업자의 실행을
 * 신호로 붙잡아 그 창을 결정적으로 만든다.</p>
 */
@DisplayName("실행기 장벽 시험 전제 탐침 — 아직 runWorker 전인 작업자는 활성으로 세진다")
class ExecutorBarrierPremiseProbeTest {

    @Test
    void workerNotYetInRunWorkerCountsAsActive() throws Exception {
        CountDownLatch holdSecond = new CountDownLatch(1);
        AtomicInteger made = new AtomicInteger();
        ThreadFactory factory = r -> new Thread(() -> {
            if (made.incrementAndGet() == 2) {
                try { holdSecond.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            r.run();
        }, "probe-" + made.get());
        CountDownLatch firstTook = new CountDownLatch(1);
        CountDownLatch allow = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(50) {
            @Override
            public Runnable take() throws InterruptedException {
                Runnable r = super.take();
                firstTook.countDown();
                allow.await();
                return r;
            }
        }, factory);
        try {
            pool.prestartAllCoreThreads();
            pool.execute(() -> { });
            assertThat(firstTook.await(5, TimeUnit.SECONDS)).isTrue();
            String state = "pool=" + pool.getPoolSize() + " active=" + pool.getActiveCount() + " queue=" + pool.getQueue().size()
                    + " task=" + pool.getTaskCount() + " completed=" + pool.getCompletedTaskCount();
            System.out.println("[전제 탐침] 둘째 작업자 runWorker 전: " + state);
            boolean oldPredicate = pool.getQueue().isEmpty() && pool.getActiveCount() == 0
                    && pool.getTaskCount() == pool.getCompletedTaskCount();
            assertThat(oldPredicate).as("둘째 작업자가 runWorker 전이면 옛 조건은 거짓이다(ExecutorBarrierTest 실패 문구와 같은 전제 실패)").isFalse();
            assertThat(pool.getActiveCount()).as("작업을 꺼내 멈춘 첫째는 잠기지 않았고, runWorker 전인 둘째가 활성으로 세진다").isEqualTo(1);

            holdSecond.countDown();
            long deadline = System.currentTimeMillis() + 5_000;
            while (pool.getActiveCount() != 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            System.out.println("[전제 탐침] 둘째 작업자 시작 뒤: active=" + pool.getActiveCount() + " task=" + pool.getTaskCount());
            assertThat(pool.getActiveCount()).as("둘째가 runWorker 에 들어가 대기열에서 기다리면 활성 0").isZero();
            assertThat(pool.getQueue().isEmpty() && pool.getTaskCount() == pool.getCompletedTaskCount()).isTrue();
        } finally {
            holdSecond.countDown();
            allow.countDown();
            pool.shutdownNow();
        }
    }
}
