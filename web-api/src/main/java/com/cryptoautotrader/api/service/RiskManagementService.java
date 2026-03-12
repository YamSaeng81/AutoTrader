package com.cryptoautotrader.api.service;

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
    @Transactional(readOnly = true)
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

        Instant now = Instant.now();
        BigDecimal dailyLoss = calculateLossPct(now.minus(1, ChronoUnit.DAYS));
        BigDecimal weeklyLoss = calculateLossPct(now.minus(7, ChronoUnit.DAYS));
        BigDecimal monthlyLoss = calculateLossPct(now.minus(30, ChronoUnit.DAYS));
        int currentPositions = (int) positionRepository.countByStatus("OPEN");

        RiskCheckResult result = engine.check(dailyLoss, weeklyLoss, monthlyLoss, currentPositions);

        if (!result.isApproved()) {
            log.warn("리스크 체크 거부: {} (일일={}%, 주간={}%, 월간={}%, 포지션={})",
                    result.getReason(), dailyLoss, weeklyLoss, monthlyLoss, currentPositions);
        }

        return result;
    }

    // ── 내부 메서드 ───────────────────────────────────────────

    /**
     * 특정 시점 이후의 손실률 계산 (%)
     * trade_log에서 FILL 이벤트의 realizedPnl을 합산하고,
     * 포트폴리오 한도 기준으로 손실 퍼센트를 계산한다.
     */
    private BigDecimal calculateLossPct(Instant since) {
        BigDecimal realizedPnl = tradeLogRepository.sumRealizedPnlSince(since);
        if (realizedPnl.compareTo(BigDecimal.ZERO) >= 0) {
            return BigDecimal.ZERO; // 이익이면 손실률 0
        }

        RiskConfigEntity config = getRiskConfig();
        BigDecimal portfolioLimit = config.getPortfolioLimitKrw();
        if (portfolioLimit == null || portfolioLimit.compareTo(BigDecimal.ZERO) <= 0) {
            // 포트폴리오 한도 미설정 시 기본 1,000만원
            portfolioLimit = new BigDecimal("10000000");
        }

        return realizedPnl.abs()
                .divide(portfolioLimit, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
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

    @Transactional
    private RiskConfigEntity createDefaultConfig() {
        RiskConfigEntity defaultConfig = RiskConfigEntity.builder().build();
        return riskConfigRepository.save(defaultConfig);
    }
}
