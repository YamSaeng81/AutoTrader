package com.cryptoautotrader.core.risk;

import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 리스크 엔진: 일일/주간/월간 손실 한도, 최대 포지션 수 제한,
 * Fixed Fractional 포지션 사이징, 상관관계 기반 유효 슬롯 계산
 */
@RequiredArgsConstructor
public class RiskEngine {

    private final RiskConfig config;

    /**
     * 자산 간 상관계수 테이블 (양방향 조회 가능하도록 양쪽 키 등록).
     * 코인 심볼을 대문자로 정규화하여 매핑한다 (예: "KRW-BTC" → "BTC").
     */
    private static final Map<String, Double> CORRELATION_MAP = Map.of(
            "BTC:ETH", 0.85,
            "ETH:BTC", 0.85,
            "BTC:BNB", 0.78,
            "BNB:BTC", 0.78,
            "ETH:BNB", 0.80,
            "BNB:ETH", 0.80
    );

    // ── 기존 한도 검사 ────────────────────────────────────────────────────

    /**
     * 포트폴리오 수준 리스크 체크.
     *
     * <p>체크 순서:
     * <ol>
     *   <li>일일/주간/월간 손실 한도</li>
     *   <li>자본 사용률 한도 — totalInvested / totalCapital × 100 이 maxCapitalUtilizationPct 초과 시 차단</li>
     *   <li>포지션 수 안전망 — maxPositions(기본 20)을 초과하는 극단적 상황 방지</li>
     * </ol>
     *
     * @param dailyLossPct         오늘 실현 손실률 (%)
     * @param weeklyLossPct        이번 주 실현 손실률 (%)
     * @param monthlyLossPct       이번 달 실현 손실률 (%)
     * @param currentPositions     현재 열린 포지션 수 (안전망 용도)
     * @param capitalUtilizationPct 현재 자본 사용률 (%) = 투입 자본 / 전체 자본 × 100
     */
    public RiskCheckResult check(BigDecimal dailyLossPct, BigDecimal weeklyLossPct,
                                  BigDecimal monthlyLossPct, int currentPositions,
                                  BigDecimal capitalUtilizationPct) {
        if (dailyLossPct.abs().compareTo(config.getMaxDailyLossPct()) > 0) {
            return RiskCheckResult.reject(
                    String.format("일일 손실 한도 초과: %.2f%% > %.2f%%",
                            dailyLossPct.abs(), config.getMaxDailyLossPct()));
        }
        if (weeklyLossPct.abs().compareTo(config.getMaxWeeklyLossPct()) > 0) {
            return RiskCheckResult.reject(
                    String.format("주간 손실 한도 초과: %.2f%% > %.2f%%",
                            weeklyLossPct.abs(), config.getMaxWeeklyLossPct()));
        }
        if (monthlyLossPct.abs().compareTo(config.getMaxMonthlyLossPct()) > 0) {
            return RiskCheckResult.reject(
                    String.format("월간 손실 한도 초과: %.2f%% > %.2f%%",
                            monthlyLossPct.abs(), config.getMaxMonthlyLossPct()));
        }
        // 자본 사용률 한도 — 주 제어 (포지션 수 대체)
        if (capitalUtilizationPct != null
                && capitalUtilizationPct.compareTo(config.getMaxCapitalUtilizationPct()) > 0) {
            return RiskCheckResult.reject(
                    String.format("자본 사용률 한도 초과: %.1f%% > %.1f%% (추가 진입 시 과투자 위험)",
                            capitalUtilizationPct.doubleValue(),
                            config.getMaxCapitalUtilizationPct().doubleValue()));
        }
        // 포지션 수 안전망 (기본 20 — 정상 운영에서는 사용률 체크가 먼저 발동됨)
        if (currentPositions >= config.getMaxPositions()) {
            return RiskCheckResult.reject(
                    String.format("최대 포지션 수 초과: %d >= %d (안전망)",
                            currentPositions, config.getMaxPositions()));
        }
        return RiskCheckResult.approve();
    }

    /**
     * 하위 호환용 오버로드 — capitalUtilizationPct 를 계산할 수 없는 경우.
     * 자본 사용률 체크를 건너뛰고 손실 한도·포지션 수만 검사한다.
     */
    public RiskCheckResult check(BigDecimal dailyLossPct, BigDecimal weeklyLossPct,
                                  BigDecimal monthlyLossPct, int currentPositions) {
        return check(dailyLossPct, weeklyLossPct, monthlyLossPct, currentPositions, null);
    }

    // ── Fixed Fractional 포지션 사이징 ───────────────────────────────────

    /**
     * Fixed Fractional 방식으로 투자 금액을 계산한다.
     *
     * <pre>
     * 투자금액 = 계좌잔고 × 리스크비율 / 스탑거리비율
     * </pre>
     *
     * @param accountBalance  현재 계좌 잔고 (KRW)
     * @param stopDistancePct 진입가 대비 스탑 거리 비율 (예: 2% → 0.02)
     * @return 투자 금액 (KRW), 소수점 0자리 반올림
     */
    public BigDecimal calculatePositionSize(BigDecimal accountBalance, BigDecimal stopDistancePct) {
        if (stopDistancePct.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("스탑 거리는 0보다 커야 합니다: " + stopDistancePct);
        }
        return accountBalance
                .multiply(config.getDefaultRiskPercentage())
                .divide(stopDistancePct, 0, RoundingMode.HALF_UP);
    }

    // ── 상관관계 기반 유효 슬롯 계산 ─────────────────────────────────────

    /**
     * 보유 자산 목록의 유효 슬롯 수를 반환한다.
     *
     * <p>상관계수 > correlationThreshold 인 쌍마다 슬롯 패널티 +1이 부과된다.
     * 예) [BTC, ETH] corr=0.85, threshold=0.7 → 슬롯 = 2 + 1 = 3
     *
     * @param assets 현재 보유 자산 심볼 목록 (예: ["KRW-BTC", "KRW-ETH"])
     * @return 유효 슬롯 수 (maxPositions 와 비교에 사용)
     */
    public int effectiveSlots(List<String> assets) {
        int slots = assets.size();
        for (int i = 0; i < assets.size(); i++) {
            for (int j = i + 1; j < assets.size(); j++) {
                String a = extractCoin(assets.get(i));
                String b = extractCoin(assets.get(j));
                double corr = CORRELATION_MAP.getOrDefault(a + ":" + b, 0.0);
                if (corr > config.getCorrelationThreshold()) {
                    slots++;
                }
            }
        }
        return slots;
    }

    /** "KRW-BTC" → "BTC", "BTC" → "BTC" */
    private static String extractCoin(String symbol) {
        int idx = symbol.lastIndexOf('-');
        return (idx >= 0 ? symbol.substring(idx + 1) : symbol).toUpperCase();
    }
}
