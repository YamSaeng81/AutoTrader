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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 알림 쿨다운 — 같은 전략은 이 시간 내 재발송하지 않는다 (7일 롤링 평균이라 값이 거의 안 변함) */
    static final long ALERT_COOLDOWN_HOURS = 24;

    /** 쿨다운 중이라도 평균값이 이 폭(%p) 이상 변하면 즉시 재발송 */
    static final double ALERT_RESEND_DELTA_PCT = 0.1;

    private final ExecutionDriftLogRepository driftRepo;
    private final TelegramNotificationService telegramService;

    /** 전략별 마지막 알림 상태 — 재시작 시 초기화되어 1회 재발송될 수 있음(허용) */
    private final Map<String, AlertState> lastAlerts = new ConcurrentHashMap<>();

    private record AlertState(Instant sentAt, double valuePct) {}

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
     *
     * <p>알림 쿨다운: 7일 롤링 평균은 시간당 거의 변하지 않아 같은 값이 매시간 재발송되던
     * 문제(2026-07-08, 오탐 1건이 최대 168통)를 막기 위해, 전략별 {@value #ALERT_COOLDOWN_HOURS}시간
     * 내 재발송을 억제한다. 단 평균값이 {@value #ALERT_RESEND_DELTA_PCT}%p 이상 변하면 즉시 재발송.</p>
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
            double value = avgSlippage.doubleValue();
            if (avgSlippage.abs().doubleValue() > ALERT_THRESHOLD_PCT) {
                AlertState prev = lastAlerts.get(strategy);
                boolean cooldownActive = prev != null
                        && Duration.between(prev.sentAt(), Instant.now()).toHours() < ALERT_COOLDOWN_HOURS
                        && Math.abs(value - prev.valuePct()) < ALERT_RESEND_DELTA_PCT;
                if (cooldownActive) {
                    continue;
                }
                String msg = String.format(
                        "[DriftAlert] %s — 7일 평균 slippage %.4f%% (임계치 %.1f%% 초과). "
                        + "백테스트 가정가와 실전 체결가 갭 확인 필요.",
                        strategy, value, ALERT_THRESHOLD_PCT);
                log.warn(msg);
                telegramService.sendCustomNotification(msg);
                lastAlerts.put(strategy, new AlertState(Instant.now(), value));
            } else {
                // 임계치 아래로 회복 — 상태 제거해 재돌파 시 즉시 알림
                lastAlerts.remove(strategy);
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
