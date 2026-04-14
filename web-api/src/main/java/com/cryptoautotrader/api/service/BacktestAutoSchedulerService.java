package com.cryptoautotrader.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 야간 자동 백테스트 스케줄 실행기.
 *
 * <p>1분마다 {@link NightlySchedulerConfigService#checkAndRun()}을 호출해
 * DB에 저장된 설정(활성 여부·실행 시각·코인·전략 등)을 기반으로 실행 여부를 결정한다.
 * 실제 설정 변경은 {@code /backtest/scheduler} 페이지 또는
 * {@code /api/v1/scheduler/nightly} API로 수행한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestAutoSchedulerService {

    private final NightlySchedulerConfigService schedulerConfigService;

    /**
     * 1분마다 실행 — DB 설정의 시각과 일치하면 배치 백테스트 + Walk-Forward 제출.
     * initialDelay 90초: 서버 기동 직후 DB·캔들 데이터 준비 완료 후 첫 체크.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void tick() {
        try {
            schedulerConfigService.checkAndRun();
        } catch (Exception e) {
            log.error("[BacktestAutoScheduler] 스케줄 체크 오류: {}", e.getMessage(), e);
        }
    }
}
