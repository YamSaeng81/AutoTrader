package com.cryptoautotrader.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    // 시세 수신 전용 (WebSocket → 이벤트 발행)
    @Bean("marketDataExecutor")
    public Executor marketDataExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("market-data-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        // 🔴 (2026-11-12, 총괄 v1.152 F — 조사 후보, 미승인) 포화 시 거절을 발행 쪽으로 던지지 않는다. 시세 이벤트는 리스너를 차례로 부르므로
        //    @Async 리스너 하나(실측: DYNAMIC)의 TaskRejectedException 이 같은 이벤트의 LIVE 실시간 손절 처리기·PAPER·시세 장부 전달을 끊었다
        //    (SharedMarketDataExecutorSaturationTest). 크기·대기열은 그대로 두고, 거절된 그 작업만 버리고 건수·경고를 남긴다.
        executor.setRejectedExecutionHandler((r, e) -> {
            long n = MARKET_DATA_REJECTED.incrementAndGet();
            if (n == 1 || n % 100 == 0) {
                log.warn("marketDataExecutor 포화 — 작업 1건 버림(다른 리스너·발행 쪽은 계속), 누적 {}건 (active={}, queue={})",
                        n, e.getActiveCount(), e.getQueue().size());
            }
        });
        executor.initialize();
        return executor;
    }

    /** marketDataExecutor 가 포화로 버린 작업 누적 수(관측용). */
    public static final java.util.concurrent.atomic.AtomicLong MARKET_DATA_REJECTED = new java.util.concurrent.atomic.AtomicLong();

    // 주문 실행 전용 (Risk 체크 → Upbit API 호출)
    // 재시작 시 진행 중 주문이 강제 종료되지 않도록 graceful shutdown
    @Bean("orderExecutor")
    public Executor orderExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("order-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    // 텔레그램 알림 전송 전용 — 메인 트랜잭션과 완전 분리
    @Bean("telegramExecutor")
    public Executor telegramExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("telegram-");
        // 큐가 차면 거절을 호출한 쪽으로 던진다(2026-10-21 리뷰) — 여기서 WARN 만 남기고 버리면 어떤 유형이 빠졌는지 모르고
        // 이력에도 남지 않는다. TelegramNotificationService.sendMarkdownAndLog 가 받아 중요 경보는 유형과 함께 기록한다.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    // 일반 작업 (백테스팅, 데이터 수집 등)
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }

    // 백테스트 비동기 실행 전용
    // 대용량 캔들(수십만 건) 처리가 오래 걸리므로 graceful shutdown 대기 시간을 길게 설정
    @Bean("backtestExecutor")
    public Executor backtestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("backtest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(600); // 최대 10분 대기
        executor.setRejectedExecutionHandler((r, e) ->
                log.warn("백테스트 실행 큐 포화 — 요청 드롭 (현재 큐 크기 초과)"));
        executor.initialize();
        return executor;
    }
}
