package com.cryptoautotrader.api.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 실행기 장벽 — "이 호출 전에 넣은 작업이 모두 끝났다"를 확정 신호로 기다린다 (2026-11-11, 총괄 v1.147 3-2d).
 *
 * <p>옛 대기 조건(큐 비었음·active 0·taskCount == completed)은 놀고 있던 작업자가 작업을 꺼낸 뒤 실행 표시를 잡기 전에도 참이었다
 * (DrainWitness·OrderExecutorDrainPredicateGapTest). 장벽은 현재 작업자 수만큼의 장벽 작업을 같은 실행기에 넣고 그 작업들이 <b>모두 동시에</b>
 * 실행될 때까지 기다린다. 큐는 선입선출이므로 모든 작업자가 장벽 작업을 잡았다면 그 앞에 넣은 작업은 어떤 작업자에서도 실행 중이 아니고
 * 큐에도 없다 — 즉 끝났다. 시간 초과는 실패다(미완료를 정상으로 보지 않는다). sleep 없음.</p>
 *
 * <p>한계: 장벽을 넣은 <b>뒤에</b> 다른 스레드가 넣은 작업은 기다리지 않는다. 작업자 수가 장벽 도중 늘면(큐가 가득 찰 때) 실패로 끝난다.</p>
 */
public final class ExecutorBarrier {

    private ExecutorBarrier() {
    }

    public static void awaitPriorTasks(ThreadPoolExecutor pool, long timeoutMs) {
        pool.prestartAllCoreThreads();
        int parties = pool.getPoolSize();
        CyclicBarrier barrier = new CyclicBarrier(parties);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < parties; i++) {
            fs.add(pool.submit(() -> {
                try {
                    barrier.await(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("장벽 미성립 — 앞선 작업이 끝나지 않았거나 작업자 수가 바뀌었다", e);
                }
            }));
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (Future<?> f : fs) {
            try {
                f.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                fs.forEach(x -> x.cancel(true));
                throw new AssertionError("실행기 장벽이 " + timeoutMs + "ms 안에 성립하지 않았다 — 앞선 작업 미완료", e);
            } catch (Exception e) {
                fs.forEach(x -> x.cancel(true));
                throw new AssertionError("실행기 장벽 실패: " + e, e);
            }
        }
    }
}
