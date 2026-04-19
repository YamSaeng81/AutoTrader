package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExecutionDriftLogEntity;
import com.cryptoautotrader.api.repository.ExecutionDriftLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 20260415_analy.md Tier 3 §14 — 실전/백테스트 drift 트래커.
 *
 * <p>실전 체결가(fillPrice)와 신호 생성 시 가정 체결가(signalPrice)의 편차를
 * 거래별로 {@link ExecutionDriftLogEntity}에 기록하고, 7일 누적 평균 slippage 가
 * {@value #ALERT_THRESHOLD_PCT}% 이상이면 경고 로그를 남긴다.</p>
 *
 * <p>호출 시점: {@code LiveTradingService} 또는 {@code OrderExecutionEngine} 에서
 * 주문이 체결될 때 {@link #record} 를 호출한다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionDriftTracker {

    /** 7일 누적 평균 slippage 경고 임계치(%) — 이를 초과하면 전략 재검증 필요 */
    static final double ALERT_THRESHOLD_PCT = 0.5;

    private final ExecutionDriftLogRepository driftRepo;
    private final TelegramNotificationService telegramService;

    /**
     * 체결 완료 시 drift 기록.
     *
     * @param sessionId    세션 ID
     * @param coinPair     코인 페어 (예: KRW-BTC)
     * @param strategyType 전략명
     * @param side         BUY | SELL
     * @param signalPrice  신호 생성 시 가정 체결가 (캔들 종가 등)
     * @param fillPrice    실제 체결가
     * @param executedAt   체결 시각
     */
    @Transactional
    public void record(Long sessionId, String coinPair, String strategyType,
                       String side, BigDecimal signalPrice, BigDecimal fillPrice,
                       Instant executedAt) {
        if (signalPrice == null || signalPrice.compareTo(BigDecimal.ZERO) == 0
                || fillPrice == null) {
            return; // 가정가 없으면 기록 생략
        }
        BigDecimal slippagePct = fillPrice.subtract(signalPrice)
                .divide(signalPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        ExecutionDriftLogEntity record = ExecutionDriftLogEntity.builder()
                .sessionId(sessionId)
                .coinPair(coinPair)
                .strategyType(strategyType)
                .side(side)
                .signalPrice(signalPrice)
                .fillPrice(fillPrice)
                .slippagePct(slippagePct)
                .executedAt(executedAt)
                .build();
        driftRepo.save(record);

        log.info("[DriftTracker] {} {} 체결 — 신호가:{} 체결가:{} slippage:{}%",
                strategyType, side, signalPrice, fillPrice, slippagePct);
    }

    /**
     * 1시간마다 전략별 7일 평균 slippage 점검.
     * 임계치 초과 시 경고 로그 + Telegram 알림.
     */
    @Scheduled(fixedDelay = 3_600_000)
    public void checkDriftAlert() {
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);

        // 최근 50건에서 전략 목록 추출
        List<String> strategies = driftRepo.findAll().stream()
                .map(ExecutionDriftLogEntity::getStrategyType)
                .distinct()
                .toList();

        for (String strategy : strategies) {
            BigDecimal avgSlippage = driftRepo.avgSlippagePctSince(strategy, since);
            if (avgSlippage.abs().doubleValue() > ALERT_THRESHOLD_PCT) {
                String msg = String.format(
                        "[DriftAlert] %s — 7일 평균 slippage %.4f%% (임계치 %.1f%% 초과). "
                        + "백테스트 가정가와 실전 체결가 갭 확인 필요.",
                        strategy, avgSlippage.doubleValue(), ALERT_THRESHOLD_PCT);
                log.warn(msg);
                telegramService.sendCustomNotification(msg);
            }
        }
    }

    /** 세션별 최근 20건 drift 조회 (API/모니터링용) */
    public List<ExecutionDriftLogEntity> getRecentBySession(Long sessionId) {
        return driftRepo.findTop20BySessionIdOrderByExecutedAtDesc(sessionId);
    }

    /** 전략별 7일 평균 slippage(%) 조회 */
    public BigDecimal getWeeklyAvgSlippage(String strategyType) {
        return driftRepo.avgSlippagePctSince(strategyType,
                Instant.now().minus(7, ChronoUnit.DAYS));
    }
}
