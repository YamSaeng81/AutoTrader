package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고정 재현 — <b>"큐 비었음·active 0·taskCount == completedTaskCount" 대기 조건은 작업 완료를 뜻하지 않는다</b>
 * (2026-11-11, 총괄 v1.145 §3-1).
 *
 * <p>{@code DynamicStopRaceTest} ①이 전체 시험 1회차에서 {@code INTENT_READY} 를 읽은 뒤에야 작업자
 * {@code order-exec-2} 가 철회를 기록했다. 그 시험의 {@code awaitDrained()} 는 위 세 조건을 썼다. 이 시험은
 * 운영 {@code orderExecutor} 와 같은 Spring 실행기 종류·같은 설정(core 2, max 4, queue 50)에서 그 조건이
 * 언제 참이 되는지를 latch 로 고정해 보인다. Spring 컨텍스트·DB·거래소는 쓰지 않는다.</p>
 *
 * <ul>
 *   <li>① 이미 만들어져 <b>놀고 있는 작업자</b>가 큐에서 작업을 꺼낸 뒤, 실행 표시(lock)를 잡기 전:
 *       세 조건이 모두 참인데 작업은 아직 실행되지 않았다. 전체 시험에서는 앞선 시험 클래스가 같은
 *       컨텍스트의 실행기에 작업자 2개를 이미 만들어 두었으므로 이 경로다.</li>
 *   <li>② 작업자가 없어 <b>새 작업자</b>가 첫 작업으로 받는 경우: 작업자 생성 상태(-1)가 active 로 세어져
 *       조건이 거짓이다. 단독 실행(작업자 {@code order-exec-1} 신규)에서 ①이 통과한 이유다.</li>
 *   <li>③ 같은 실행기의 {@code submit()} 이 돌려주는 Future 는 작업 종료 전에는 완료가 아니다 — 보완한
 *       대기가 기대는 신호다.</li>
 * </ul>
 */
@DisplayName("orderExecutor 대기 조건 공백 — 놀고 있는 작업자 경로에서 비워짐 ≠ 완료")
class OrderExecutorDrainPredicateGapTest {

    /** 큐에서 꺼낸 직후(작업자가 실행 표시를 잡기 전)를 latch 로 붙잡는 실행기. 설정은 AsyncConfig.orderExecutor 와 같다. */
    private static final class GatedTakeExecutor extends ThreadPoolTaskExecutor {
        final CountDownLatch dequeued = new CountDownLatch(1);
        final CountDownLatch allowWorker = new CountDownLatch(1);

        GatedTakeExecutor() {
            setCorePoolSize(2);
            setMaxPoolSize(4);
            setQueueCapacity(50);
            setThreadNamePrefix("gap-exec-");
            initialize();
        }

        @Override
        protected BlockingQueue<Runnable> createQueue(int queueCapacity) {
            return new LinkedBlockingQueue<>(queueCapacity) {
                @Override
                public Runnable take() throws InterruptedException {
                    Runnable task = super.take();
                    dequeued.countDown();
                    allowWorker.await();
                    return task;
                }
            };
        }
    }

    /** 새 작업자가 첫 작업을 실행하기 직전을 latch 로 붙잡는 실행기(스레드 본체 앞에서 대기). */
    private static final class GatedNewWorkerExecutor extends ThreadPoolTaskExecutor {
        final CountDownLatch workerStarted = new CountDownLatch(1);
        final CountDownLatch allowWorker = new CountDownLatch(1);

        GatedNewWorkerExecutor() {
            setCorePoolSize(2);
            setMaxPoolSize(4);
            setQueueCapacity(50);
            setThreadNamePrefix("gap-new-");
            setThreadFactory(r -> new Thread(() -> {
                workerStarted.countDown();
                try {
                    allowWorker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                r.run();
            }, "gap-new-" + System.nanoTime()));
            initialize();
        }
    }

    private static boolean oldPredicate(ThreadPoolExecutor pool) {
        return pool.getQueue().isEmpty() && pool.getActiveCount() == 0
                && pool.getTaskCount() == pool.getCompletedTaskCount();
    }

    @Test
    @DisplayName("① 놀고 있는 작업자가 꺼낸 뒤·실행 전 — 옛 조건은 비워짐이라 답하지만 작업은 실행 전이다")
    void idleWorkerWindowSaysDrainedBeforeExecution() throws Exception {
        GatedTakeExecutor ex = new GatedTakeExecutor();
        try {
            ThreadPoolExecutor pool = ex.getThreadPoolExecutor();
            assertThat(pool.prestartAllCoreThreads()).as("전체 시험처럼 작업자 2개가 미리 있다").isEqualTo(2);
            AtomicBoolean executed = new AtomicBoolean(false);
            CountDownLatch completed = new CountDownLatch(1);
            ex.execute(() -> { executed.set(true); completed.countDown(); });
            assertThat(ex.dequeued.await(5, TimeUnit.SECONDS)).as("작업자가 큐에서 꺼냈다").isTrue();

            boolean drained = oldPredicate(pool);
            System.out.println("[① 꺼낸 뒤·실행 전] drained=" + drained + " executed=" + executed.get()
                    + " queue=" + pool.getQueue().size() + " active=" + pool.getActiveCount()
                    + " tasks=" + pool.getTaskCount() + " completed=" + pool.getCompletedTaskCount());
            assertThat(drained).as("옛 대기 조건이 이 창에서 참이다 — 그래서 INTENT_READY 를 읽을 수 있었다").isTrue();
            assertThat(executed.get()).as("그런데 작업은 아직 실행되지 않았다").isFalse();

            ex.allowWorker.countDown();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executed.get()).isTrue();
        } finally {
            ex.allowWorker.countDown();
            ex.shutdown();
        }
    }

    @Test
    @DisplayName("② 작업자가 없어 새 작업자가 첫 작업으로 받으면 — 옛 조건은 거짓이다(단독 실행이 통과한 이유)")
    void freshWorkerIsCountedActiveBeforeRunning() throws Exception {
        GatedNewWorkerExecutor ex = new GatedNewWorkerExecutor();
        try {
            ThreadPoolExecutor pool = ex.getThreadPoolExecutor();
            assertThat(pool.getPoolSize()).as("작업자 0개에서 시작").isZero();
            AtomicBoolean executed = new AtomicBoolean(false);
            CountDownLatch completed = new CountDownLatch(1);
            ex.execute(() -> { executed.set(true); completed.countDown(); });
            assertThat(ex.workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            boolean drained = oldPredicate(pool);
            System.out.println("[② 새 작업자·실행 전] drained=" + drained + " executed=" + executed.get()
                    + " queue=" + pool.getQueue().size() + " active=" + pool.getActiveCount()
                    + " tasks=" + pool.getTaskCount() + " completed=" + pool.getCompletedTaskCount());
            assertThat(executed.get()).isFalse();
            assertThat(drained).as("새 작업자는 생성 상태부터 active 로 세어져 옛 조건이 거짓이다").isFalse();

            ex.allowWorker.countDown();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            ex.allowWorker.countDown();
            ex.shutdown();
        }
    }

    @Test
    @DisplayName("③ 같은 창에서 submit() 의 Future 는 완료가 아니다 — 보완한 대기는 이 신호를 기다린다")
    void futureIsNotDoneInsideTheWindow() throws Exception {
        GatedTakeExecutor ex = new GatedTakeExecutor();
        try {
            ThreadPoolExecutor pool = ex.getThreadPoolExecutor();
            pool.prestartAllCoreThreads();
            AtomicBoolean executed = new AtomicBoolean(false);
            Future<?> done = ex.submit(() -> executed.set(true));
            assertThat(ex.dequeued.await(5, TimeUnit.SECONDS)).isTrue();

            System.out.println("[③ 꺼낸 뒤·실행 전] oldPredicate=" + oldPredicate(pool)
                    + " future.isDone=" + done.isDone() + " executed=" + executed.get());
            assertThat(oldPredicate(pool)).isTrue();
            assertThat(done.isDone()).as("Future 는 작업 종료 전에 완료가 아니다").isFalse();

            ex.allowWorker.countDown();
            done.get(5, TimeUnit.SECONDS);
            assertThat(executed.get()).isTrue();
        } finally {
            ex.allowWorker.countDown();
            ex.shutdown();
        }
    }
}
