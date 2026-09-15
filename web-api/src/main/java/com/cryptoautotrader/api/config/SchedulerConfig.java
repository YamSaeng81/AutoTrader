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
 * @Scheduled 작업 분포 (2026-09-15 실측, 총 34개):
 *   - 5초  × 4 : LiveTradingService.reconcileClosingPositions / pollRestTickerFallback,
 *                DynamicTradingService.reconcileDynamicClosingPositions,
 *                OrderExecutionEngine.pollActiveOrders
 *   - 30초 × 4 : LIVE 1, DYNAMIC 2, ExchangeHealthMonitor 1
 *   - 60초 × 9 : LIVE 3(executeStrategies 포함), DYNAMIC 3(tick 포함),
 *                MarketDataSyncService, PaperTradingService.runStrategy,
 *                BacktestAutoSchedulerService
 *   - 그 외 17 : 5분~1시간 주기 및 일별 cron (리포트·뉴스·kill criteria·가중치 최적화 등)
 *
 * 개수를 셀 때는 `grep -a` 를 쓸 것 — StrategyWeightOptimizer.java 가 맵 키 구분자로
 * NUL 문자를 쓰기 때문에 grep 이 이 파일을 binary 로 보고 조용히 건너뛴다.
 * 소스에 등장하는 "@Scheduled" 문자열 37회 중 3회는 주석이다(이 파일 2 + DynamicTradingService 1).
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    /**
     * 스케줄러 전용 스레드 풀.
     * - 풀 크기 8.
     *
     *   ⚠ 이 값은 @Scheduled 가 6개이던 시절의 산정치다. 현재는 34개이고, 그중 주기 60초
     *   이하만 17개다. 60초 tick 9개는 워치리스트 전체를 도는 네트워크 I/O 라 실행이 길어,
     *   이들이 겹치면 스레드 8개를 모두 점유하고 5초 주기 손절 reconcile·주문 폴링이
     *   큐에서 대기한다 — 즉 아래 경고가 이미 현실적인 시나리오다.
     *   풀 부족 시 손절/reconcile 지연으로 실손 발생 가능.
     *
     *   조치 전 실측할 것: Grafana "운영 개요" 대시보드 최상단 행의
     *   <b>스레드 포화</b> 패널 — executor_active_threads{name="taskScheduler"} 가
     *   풀 크기 8 에 지속적으로 붙어 있으면 확정이다.
     *
     *   ⚠️ executor_queued_tasks 를 적체로 읽으면 안 된다(2026-09-15 실측 중 오독).
     *   ThreadPoolTaskScheduler 는 ScheduledThreadPoolExecutor 기반이라 그 DelayedWorkQueue 에
     *   <b>아직 실행 시각이 안 된 예약 작업이 전부</b> 들어앉는다 — 운영 실측값 33 은
     *   @Scheduled 34개 중 1개 실행 중, 33개 대기라는 뜻으로 정상이다.
     *
     *   근본 해법은 풀 상향보다 5초 크리티컬 작업의 전용 스케줄러 분리다.
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
