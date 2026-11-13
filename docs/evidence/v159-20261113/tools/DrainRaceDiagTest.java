package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v1.159 진단(제품 트리에 넣지 않음) — N16InvalidPriceReproTest.drain 과 같은 판정식이, 막 넘긴 작업이 시작하기 전에 '비었다'고 답하는지.
 * 실행기는 AsyncConfig.orderExecutor 와 같은 설정(코어 2·최대 4·대기열 50). 외부 접속·스프링 문맥 없음.
 */
class DrainRaceDiagTest {

    private static boolean drained(ThreadPoolTaskExecutor executor) {   // N16 drain 의 판정식 그대로
        var pool = executor.getThreadPoolExecutor();
        return pool.getQueue().isEmpty() && pool.getActiveCount() == 0
                && pool.getTaskCount() == pool.getCompletedTaskCount();
    }

    private static ThreadPoolTaskExecutor orderLike() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(2); e.setMaxPoolSize(4); e.setQueueCapacity(50); e.setThreadNamePrefix("diag-order-");
        e.initialize();
        return e;
    }

    @Test
    void drainSaysEmptyBeforeHandedTaskRuns() throws Exception {
        int trials = 20000, earlyNew = 0, earlyWarm = 0;
        // ① 새 실행기마다 첫 작업(작업자 생성 + firstTask 경로) — N16 클래스처럼 문맥의 실행기가 아직 작업자가 없을 때
        for (int i = 0; i < 2000; i++) {
            ThreadPoolTaskExecutor e = orderLike();
            AtomicBoolean ran = new AtomicBoolean();
            e.execute(() -> ran.set(true));
            if (drained(e) && !ran.get()) earlyNew++;
            e.shutdown();
        }
        // ② 작업자가 이미 있는 실행기(대기열 경유) — 작업자가 꺼낸 뒤 잠그기 전의 틈
        ThreadPoolTaskExecutor w = orderLike();
        for (int i = 0; i < 4; i++) { w.execute(() -> {}); }
        Thread.sleep(200);
        for (int i = 0; i < trials; i++) {
            AtomicBoolean ran = new AtomicBoolean();
            w.execute(() -> ran.set(true));
            if (drained(w) && !ran.get()) earlyWarm++;
            while (!ran.get()) Thread.onSpinWait();
        }
        w.shutdown();
        System.out.println("DRAINRACE 새 실행기 첫 작업 2000 중 '비었다'인데 작업 미실행 " + earlyNew
                + " · 작업자 있는 실행기 " + trials + " 중 " + earlyWarm);
    }
}
