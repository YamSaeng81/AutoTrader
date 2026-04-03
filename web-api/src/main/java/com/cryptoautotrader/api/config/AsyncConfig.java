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
        executor.initialize();
        return executor;
    }

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
        executor.setRejectedExecutionHandler((r, e) ->
                log.warn("텔레그램 알림 큐 포화 — 메시지 드롭"));
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
