package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.RiskConfigRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.core.risk.RiskCheckResult;
import com.cryptoautotrader.core.risk.RiskConfig;
import com.cryptoautotrader.core.risk.RiskEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 리스크 관리 서비스
 * - DB에서 리스크 설정 관리
 * - core-engine의 RiskEngine을 활용한 리스크 체크
 * - 일일/주간/월간 손실률 계산
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskManagementService {

    private final RiskConfigRepository riskConfigRepository;
    private final PositionRepository positionRepository;
    private final TradeLogRepository tradeLogRepository;

    /**
     * 현재 리스크 설정 조회. 설정이 없으면 기본값으로 신규 생성.
     */
    @Transactional
    public RiskConfigEntity getRiskConfig() {
        return riskConfigRepository.findTopByOrderByIdDesc()
                .orElseGet(this::createDefaultConfig);
    }

    /**
     * 리스크 설정 수정 — 새 레코드를 INSERT (이력 보존)
     */
    @Transactional
    public RiskConfigEntity updateRiskConfig(RiskConfigEntity newConfig) {
        RiskConfigEntity entity = RiskConfigEntity.builder()
                .maxDailyLossPct(newConfig.getMaxDailyLossPct())
                .maxWeeklyLossPct(newConfig.getMaxWeeklyLossPct())
                .maxMonthlyLossPct(newConfig.getMaxMonthlyLossPct())
                .maxPositions(newConfig.getMaxPositions())
                .cooldownMinutes(newConfig.getCooldownMinutes())
                .portfolioLimitKrw(newConfig.getPortfolioLimitKrw())
                .build();
        entity = riskConfigRepository.save(entity);
        log.info("리스크 설정 업데이트: 일일={}%, 주간={}%, 월간={}%, 최대포지션={}, 쿨다운={}분",
                entity.getMaxDailyLossPct(), entity.getMaxWeeklyLossPct(),
                entity.getMaxMonthlyLossPct(), entity.getMaxPositions(), entity.getCooldownMinutes());
        return entity;
    }

    /**
     * 현재 포지션 기반 리스크 체크.
     * trade_log의 FILL 이벤트에서 일일/주간/월간 실현 손익을 조회하여
     * core-engine RiskEngine으로 한도 초과 여부를 판단한다.
     */
    @Transactional(readOnly = true)
    public RiskCheckResult checkRisk() {
        RiskConfigEntity configEntity = getRiskConfig();
        RiskConfig coreConfig = toRiskConfig(configEntity);
        RiskEngine engine = new RiskEngine(coreConfig);

        // 포트폴리오 한도를 한 번만 조회하여 3회 반복 사용
        BigDecimal portfolioLimit = resolvePortfolioLimit(configEntity);

        Instant now = Instant.now();
        BigDecimal dailyLoss = calculateLossPct(now.minus(1, ChronoUnit.DAYS), portfolioLimit);
        BigDecimal weeklyLoss = calculateLossPct(now.minus(7, ChronoUnit.DAYS), portfolioLimit);
        BigDecimal monthlyLoss = calculateLossPct(now.minus(30, ChronoUnit.DAYS), portfolioLimit);
        // size > 0인 실제 체결 포지션만 카운팅 — FAILED 매수로 인한 size=0 고아 포지션 제외
        int currentPositions = (int) positionRepository.countRealPositionsByStatus("OPEN");

        RiskCheckResult result = engine.check(dailyLoss, weeklyLoss, monthlyLoss, currentPositions);

        if (!result.isApproved()) {
            log.warn("리스크 체크 거부: {} (일일={}%, 주간={}%, 월간={}%, 포지션={})",
                    result.getReason(), dailyLoss, weeklyLoss, monthlyLoss, currentPositions);
        }

        return result;
    }

    /**
     * 세션 단위 서킷 브레이커 체크.
     * ① 세션 MDD가 임계값 초과 → 트리거
     * ② 연속 손실 횟수가 한도 초과 → 트리거
     * 둘 다 미초과이면 pass 반환.
     */
    @Transactional(readOnly = true)
    public CircuitBreakerResult checkCircuitBreaker(LiveTradingSessionEntity session) {
        RiskConfigEntity config = getRiskConfig();

        if (!Boolean.TRUE.equals(config.getCircuitBreakerEnabled())) {
            return CircuitBreakerResult.pass();
        }

        BigDecimal mddThreshold = config.getMddThresholdPct() != null
                ? config.getMddThresholdPct() : new BigDecimal("20.0");
        int consecutiveLossLimit = config.getConsecutiveLossLimit() != null
                ? config.getConsecutiveLossLimit() : 5;

        // ── MDD 체크 ──────────────────────────────────────────
        BigDecimal peak = session.getMddPeakCapital();
        if (peak == null || peak.compareTo(BigDecimal.ZERO) <= 0) {
            peak = session.getInitialCapital();
        }
        BigDecimal current = session.getTotalAssetKrw();
        if (peak.compareTo(BigDecimal.ZERO) > 0 && current.compareTo(peak) < 0) {
            BigDecimal drawdownPct = peak.subtract(current)
                    .divide(peak, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            if (drawdownPct.compareTo(mddThreshold) >= 0) {
                return CircuitBreakerResult.triggered(
                        String.format("MDD %.2f%% 초과 (한도: %.2f%%, 피크: %s → 현재: %s)",
                                drawdownPct, mddThreshold, peak.toPlainString(), current.toPlainString()));
            }
        }

        // ── 연속 손실 체크 ────────────────────────────────────
        List<PositionEntity> closedPositions = positionRepository
                .findBySessionIdAndStatusOrderByClosedAtDesc(session.getId(), "CLOSED");
        int consecutiveLosses = 0;
        for (PositionEntity pos : closedPositions) {
            if (pos.getRealizedPnl() != null
                    && pos.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0) {
                consecutiveLosses++;
            } else {
                break;
            }
        }
        if (consecutiveLosses >= consecutiveLossLimit) {
            return CircuitBreakerResult.triggered(
                    String.format("연속 손실 %d회 초과 (한도: %d회)", consecutiveLosses, consecutiveLossLimit));
        }

        return CircuitBreakerResult.pass();
    }

    // ── 내부 메서드 ───────────────────────────────────────────

    /**
     * 특정 시점 이후의 손실률 계산 (%)
     * trade_log에서 FILL 이벤트의 realizedPnl을 합산하고,
     * 포트폴리오 한도 기준으로 손실 퍼센트를 계산한다.
     */
    private BigDecimal calculateLossPct(Instant since, BigDecimal portfolioLimit) {
        BigDecimal realizedPnl = tradeLogRepository.sumRealizedPnlSince(since);
        if (realizedPnl.compareTo(BigDecimal.ZERO) >= 0) {
            return BigDecimal.ZERO; // 이익이면 손실률 0
        }

        return realizedPnl.abs()
                .divide(portfolioLimit, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private BigDecimal resolvePortfolioLimit(RiskConfigEntity config) {
        BigDecimal limit = config.getPortfolioLimitKrw();
        return (limit == null || limit.compareTo(BigDecimal.ZERO) <= 0)
                ? new BigDecimal("10000000")  // 미설정 시 기본 1,000만원
                : limit;
    }

    private RiskConfig toRiskConfig(RiskConfigEntity entity) {
        return RiskConfig.builder()
                .maxDailyLossPct(entity.getMaxDailyLossPct())
                .maxWeeklyLossPct(entity.getMaxWeeklyLossPct())
                .maxMonthlyLossPct(entity.getMaxMonthlyLossPct())
                .maxPositions(entity.getMaxPositions())
                .cooldownMinutes(entity.getCooldownMinutes())
                .portfolioLimitKrw(entity.getPortfolioLimitKrw())
                .build();
    }

    private RiskConfigEntity createDefaultConfig() {
        return riskConfigRepository.save(RiskConfigEntity.builder().build());
    }
}
