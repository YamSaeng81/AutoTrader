package com.cryptoautotrader.api.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 장벽이 옛 조건의 공백 창(꺼낸 뒤·실행 전)에서 끝나지 않고, 작업이 끝난 뒤에만 끝나는지 — 운영 orderExecutor 와 같은 설정. */
@DisplayName("실행기 장벽 — 꺼낸 뒤·실행 전 창에서는 기다리고, 작업 종료 뒤에 성립")
class ExecutorBarrierTest {

    private static final class Gated extends ThreadPoolTaskExecutor {
        final CountDownLatch dequeued = new CountDownLatch(1);
        final CountDownLatch allow = new CountDownLatch(1);
        final AtomicBoolean gateOnce = new AtomicBoolean(true);

        Gated() {
            setCorePoolSize(2);
            setMaxPoolSize(4);
            setQueueCapacity(50);
            setThreadNamePrefix("barrier-");
            initialize();
        }

        @Override
        protected BlockingQueue<Runnable> createQueue(int queueCapacity) {
            return new LinkedBlockingQueue<>(queueCapacity) {
                @Override
                public Runnable take() throws InterruptedException {
                    Runnable r = super.take();
                    if (gateOnce.compareAndSet(true, false)) {   // 첫 작업만 붙잡는다(장벽 작업은 그대로)
                        dequeued.countDown();
                        allow.await();
                    }
                    return r;
                }
            };
        }
    }

    @Test
    void waitsThroughTheGapWindowAndCompletesAfterTask() throws Exception {
        Gated ex = new Gated();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            ThreadPoolExecutor pool = ex.getThreadPoolExecutor();
            pool.prestartAllCoreThreads();
            AtomicBoolean executed = new AtomicBoolean(false);
            ex.execute(() -> executed.set(true));
            assertThat(ex.dequeued.await(5, TimeUnit.SECONDS)).isTrue();
            boolean oldPredicate = pool.getQueue().isEmpty() && pool.getActiveCount() == 0
                    && pool.getTaskCount() == pool.getCompletedTaskCount();
            Future<?> barrier = caller.submit(() -> ExecutorBarrier.awaitPriorTasks(pool, 10_000));
            boolean barrierDoneInWindow;
            try {
                barrier.get(1, TimeUnit.SECONDS);
                barrierDoneInWindow = true;
            } catch (java.util.concurrent.TimeoutException e) {
                barrierDoneInWindow = false;
            }
            System.out.println("[장벽] 창 안: 옛 조건=" + oldPredicate + " 장벽 성립=" + barrierDoneInWindow + " 실행=" + executed.get());
            assertThat(oldPredicate).as("옛 조건은 이 창에서 참").isTrue();
            assertThat(barrierDoneInWindow).as("장벽은 작업이 끝나기 전에 성립하지 않는다").isFalse();
            ex.allow.countDown();
            barrier.get(10, TimeUnit.SECONDS);
            assertThat(executed.get()).as("장벽 성립 시점에 앞선 작업은 끝났다").isTrue();
        } finally {
            ex.allow.countDown();
            caller.shutdownNow();
            ex.shutdown();
        }
    }
}
