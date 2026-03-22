package com.cryptoautotrader.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Spring Scheduler 전용 설정.
 * AsyncConfig 의 스레드 풀과 분리하여 스케줄러 작업이 일반 비동기 작업의
 * 부하에 영향받지 않도록 한다.
 *
 * 현재 @Scheduled 사용처:
 *   - LiveTradingService.executeStrategies()          (fixedDelay 60초)
 *   - LiveTradingService.reconcileClosingPositions()  (fixedDelay 5초)
 *   - MarketDataSyncService.syncMarketData()          (fixedDelay 60초)
 *   - PaperTradingService.runStrategy()               (fixedDelay 60초, initialDelay 35초)
 *   - ExchangeHealthMonitor.checkExchangeHealth()     (fixedDelay 별도)
 *   - PortfolioSyncService.scheduledSync()            (fixedDelay 300초)
 *   + 여유분 (추후 스케줄 작업 증가 대비)
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    /**
     * 스케줄러 전용 스레드 풀.
     * - 풀 크기 8: @Scheduled 작업 최소 5개 + 여유분 3. 풀 부족 시 손절/reconcile 지연으로 실손 발생 가능.
     * - 스레드 이름 prefix: "scheduler-"
     * - 작업이 완료되지 않아도 JVM 종료를 기다리도록 setWaitForTasksToCompleteOnShutdown(true)
     * - 최대 대기 시간: 30초 (진행 중인 스케줄 작업이 마무리될 시간)
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setErrorHandler(throwable ->
                org.slf4j.LoggerFactory.getLogger(SchedulerConfig.class)
                        .error("스케줄러 작업 오류: {}", throwable.getMessage(), throwable)
        );
        return scheduler;
    }
}
