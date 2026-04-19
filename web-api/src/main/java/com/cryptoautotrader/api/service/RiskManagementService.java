package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.RiskConfigRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.core.risk.ExitRuleChecker;
import com.cryptoautotrader.core.risk.ExitRuleConfig;
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
    private final LiveTradingSessionRepository sessionRepository;

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
                .mddThresholdPct(newConfig.getMddThresholdPct())
                .consecutiveLossLimit(newConfig.getConsecutiveLossLimit())
                .circuitBreakerEnabled(newConfig.getCircuitBreakerEnabled())
                // 포지션 수준 리스크 규칙
                .stopLossPct(newConfig.getStopLossPct())
                .takeProfitMultiplier(newConfig.getTakeProfitMultiplier())
                .trailingEnabled(newConfig.getTrailingEnabled())
                .trailingTpMarginPct(newConfig.getTrailingTpMarginPct())
                .trailingSlMarginPct(newConfig.getTrailingSlMarginPct())
                .investRatioPct(newConfig.getInvestRatioPct())
                .build();
        entity = riskConfigRepository.save(entity);
        log.info("리스크 설정 업데이트: 일일={}%, 주간={}%, 월간={}%, 최대포지션={}, 쿨다운={}분, SL={}%, TP={}×, 투자비율={}%",
                entity.getMaxDailyLossPct(), entity.getMaxWeeklyLossPct(),
                entity.getMaxMonthlyLossPct(), entity.getMaxPositions(), entity.getCooldownMinutes(),
                entity.getStopLossPct(), entity.getTakeProfitMultiplier(), entity.getInvestRatioPct());
        return entity;
    }

    /**
     * DB 설정 기반 ExitRuleConfig 반환. 설정이 없으면 코드 기본값 사용.
     */
    @Transactional(readOnly = true)
    public ExitRuleConfig getExitRuleConfig() {
        RiskConfigEntity cfg = getRiskConfig();
        BigDecimal pct100 = BigDecimal.valueOf(100);
        return ExitRuleConfig.builder()
                .stopLossPct(cfg.getStopLossPct() != null ? cfg.getStopLossPct() : new BigDecimal("5.0"))
                .takeProfitMultiplier(cfg.getTakeProfitMultiplier() != null ? cfg.getTakeProfitMultiplier() : new BigDecimal("2.0"))
                .trailingEnabled(cfg.getTrailingEnabled() != null ? cfg.getTrailingEnabled() : Boolean.TRUE)
                .trailingTpMargin(cfg.getTrailingTpMarginPct() != null
                        ? cfg.getTrailingTpMarginPct().divide(pct100, 6, RoundingMode.HALF_UP)
                        : new BigDecimal("0.005"))
                .trailingSlMargin(cfg.getTrailingSlMarginPct() != null
                        ? cfg.getTrailingSlMarginPct().divide(pct100, 6, RoundingMode.HALF_UP)
                        : new BigDecimal("0.003"))
                .investRatio(cfg.getInvestRatioPct() != null
                        ? cfg.getInvestRatioPct().divide(pct100, 4, RoundingMode.HALF_UP)
                        : new BigDecimal("0.80"))
                .build();
    }

    /**
     * DB 설정 기반 ExitRuleChecker 반환.
     */
    public ExitRuleChecker getExitRuleChecker() {
        return new ExitRuleChecker(getExitRuleConfig());
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

        // 포트폴리오 한도: 실제 운영 중인 RUNNING 세션들의 initialCapital 합을 우선 사용.
        // 실전매매 소액 운영(예: 1만원) 시 하드코딩 1,000만원 분모가 실손실을 희석해
        // 서킷 브레이커가 영영 발동하지 않는 문제를 방지한다 — 20260415_analy.md Tier 1 §5.
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
     * 특정 시점 이후의 "총손실(gross loss)" 기반 손실률 계산 (%).
     *
     * <p>순손익(net PnL)이 아닌 손실 거래만 합산해 분자로 사용한다. 이익이 손실을 상쇄해
     * "순이익이라 loss 0%" 로 판정되던 기존 버그를 해소한다 — 20260415_analy.md Tier 1 §5.</p>
     */
    private BigDecimal calculateLossPct(Instant since, BigDecimal portfolioLimit) {
        if (portfolioLimit == null || portfolioLimit.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal grossLoss = tradeLogRepository.sumRealizedLossSince(since);
        if (grossLoss == null || grossLoss.compareTo(BigDecimal.ZERO) >= 0) {
            return BigDecimal.ZERO;
        }
        return grossLoss.abs()
                .divide(portfolioLimit, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    /**
     * 실제 운영 중인 RUNNING 세션들의 initialCapital 합계로 포트폴리오 한도를 계산한다.
     * RUNNING 세션이 없으면 config의 portfolio_limit_krw 를 사용하고, 그것도 없으면
     * 안전한 기본값(1,000만원)으로 폴백한다.
     *
     * <p>소액 운영 시(예: DOGE 1만원 세션) 하드코딩 1,000만원을 분모로 쓰던 구버그를 해소한다.</p>
     */
    private BigDecimal resolvePortfolioLimit(RiskConfigEntity config) {
        BigDecimal runningSum = sessionRepository.findByStatus("RUNNING").stream()
                .map(LiveTradingSessionEntity::getInitialCapital)
                .filter(c -> c != null && c.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (runningSum.compareTo(BigDecimal.ZERO) > 0) {
            return runningSum;
        }

        BigDecimal limit = config.getPortfolioLimitKrw();
        return (limit == null || limit.compareTo(BigDecimal.ZERO) <= 0)
                ? new BigDecimal("10000000")  // 최종 폴백: 1,000만원
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
