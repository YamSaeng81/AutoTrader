package com.cryptoautotrader.core.metrics;

import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 성과 지표 계산 — 일별 equity curve 기반 연환산 통계.
 *
 * <p>Sharpe / Sortino 는 일별 simple return 시계열에서 계산하고 sqrt(365) 로 연환산한다.
 * Calmar 는 실제 CAGR 을 계산해 |MDD| 로 나눈다. 거래 빈도에 따라 annualization 이
 * 왜곡되던 per-trade 방식은 2026-04-15 감사(20260415_sharpe_audit.md) 결과 폐기.</p>
 */
public final class MetricsCalculator {

    private static final int SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 암호화폐는 24/7 거래이므로 365 (주식은 252). */
    private static final double TRADING_DAYS_PER_YEAR = 365.0;
    private static final double SQRT_YEAR = Math.sqrt(TRADING_DAYS_PER_YEAR);

    private MetricsCalculator() {}

    public static PerformanceReport calculate(List<TradeRecord> trades, BigDecimal initialCapital) {
        return calculate(trades, initialCapital, "FULL");
    }

    public static PerformanceReport calculate(List<TradeRecord> trades, BigDecimal initialCapital, String segment) {
        // 매도 거래만 필터 (매수→매도 쌍으로 PnL 계산) — 시간순 정렬 보장
        List<TradeRecord> sellTrades = trades.stream()
                .filter(t -> t.getSide() == OrderSide.SELL)
                .sorted(Comparator.comparing(TradeRecord::getExecutedAt))
                .toList();

        int totalTrades = sellTrades.size();
        if (totalTrades == 0) {
            return emptyReport(segment);
        }

        // 승/패 분류
        List<BigDecimal> profits = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();
        for (TradeRecord t : sellTrades) {
            if (t.getPnl().compareTo(BigDecimal.ZERO) > 0) {
                profits.add(t.getPnl());
            } else {
                losses.add(t.getPnl());
            }
        }

        int winningTrades = profits.size();
        int losingTrades = losses.size();

        // 총 수익률
        BigDecimal totalPnl = sellTrades.stream()
                .map(TradeRecord::getPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalReturnPct = totalPnl.divide(initialCapital, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED);

        // 승률
        BigDecimal winRatePct = BigDecimal.valueOf(winningTrades)
                .divide(BigDecimal.valueOf(totalTrades), SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED);

        // MDD (trade-by-trade 실현 equity 곡선 기반)
        BigDecimal mddPct = calculateMDD(trades, initialCapital);

        // 평균 수익/손실
        BigDecimal avgProfitPct = profits.isEmpty() ? BigDecimal.ZERO
                : profits.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(profits.size()), SCALE, RoundingMode.HALF_UP)
                .divide(initialCapital, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED);
        BigDecimal avgLossPct = losses.isEmpty() ? BigDecimal.ZERO
                : losses.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(losses.size()), SCALE, RoundingMode.HALF_UP)
                .divide(initialCapital, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED);

        // 승패비
        BigDecimal winLossRatio = avgLossPct.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : avgProfitPct.divide(avgLossPct.abs(), SCALE, RoundingMode.HALF_UP);

        // ── 일별 equity curve 기반 리스크 조정 지표 ───────────────
        double[] dailyReturns = buildDailyReturns(sellTrades, initialCapital);

        BigDecimal sharpeRatio = calculateSharpe(dailyReturns);
        BigDecimal sortinoRatio = calculateSortino(dailyReturns);
        BigDecimal calmarRatio = calculateCalmar(sellTrades, initialCapital, totalPnl, mddPct);

        // Recovery Factor = 총 수익률(%) / |MDD(%)|
        BigDecimal recoveryFactor = mddPct.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : totalReturnPct.divide(mddPct.abs(), SCALE, RoundingMode.HALF_UP);

        int maxConsecutiveLoss = calculateMaxConsecutiveLoss(sellTrades);
        Map<String, BigDecimal> monthlyReturns = calculateMonthlyReturns(sellTrades, initialCapital);

        // §13 데이터 스냅샷 편향 감지 지표
        BigDecimal monthlyStdDev   = calculateMonthlyStdDev(monthlyReturns);
        BigDecimal monthlySkewness = calculateMonthlySkewness(monthlyReturns);
        BigDecimal topMonthConc    = calculateTopMonthConcentration(sellTrades, initialCapital);

        return PerformanceReport.builder()
                .totalReturnPct(totalReturnPct)
                .winRatePct(winRatePct)
                .mddPct(mddPct)
                .sharpeRatio(sharpeRatio)
                .sortinoRatio(sortinoRatio)
                .calmarRatio(calmarRatio)
                .winLossRatio(winLossRatio)
                .recoveryFactor(recoveryFactor)
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .avgProfitPct(avgProfitPct)
                .avgLossPct(avgLossPct)
                .maxConsecutiveLoss(maxConsecutiveLoss)
                .monthlyReturns(monthlyReturns)
                .monthlyReturnStdDev(monthlyStdDev)
                .monthlyReturnSkewness(monthlySkewness)
                .topMonthConcentrationPct(topMonthConc)
                .segment(segment)
                .build();
    }

    // ── 일별 equity curve → simple return 시계열 ──────────────────

    /**
     * 매도 거래 목록으로부터 일별 simple return 시계열을 생성한다.
     * <ul>
     *   <li>거래 발생일: 해당 일의 누적 realized PnL 만큼 equity 변경</li>
     *   <li>거래 없는 날: return = 0 (equity 유지)</li>
     *   <li>기간: 첫 매도 거래일 ~ 마지막 매도 거래일 (KST 기준)</li>
     * </ul>
     * 하루만 있는 경우 (returns 길이 < 2) 빈 배열 반환 → Sharpe/Sortino = 0.
     */
    private static double[] buildDailyReturns(List<TradeRecord> sellTrades, BigDecimal initialCapital) {
        if (sellTrades.isEmpty()) return new double[0];

        LocalDate startDay = sellTrades.get(0).getExecutedAt().atZone(KST).toLocalDate();
        LocalDate endDay = sellTrades.get(sellTrades.size() - 1).getExecutedAt().atZone(KST).toLocalDate();

        // 일별 realized pnl 집계
        Map<LocalDate, BigDecimal> dailyPnl = new TreeMap<>();
        for (TradeRecord t : sellTrades) {
            LocalDate day = t.getExecutedAt().atZone(KST).toLocalDate();
            dailyPnl.merge(day, t.getPnl(), BigDecimal::add);
        }

        // equity curve 구축 — equity[0] = initial (startDay 전날 종가)
        // equity[i] = startDay + (i-1)일의 종가 equity
        List<Double> equity = new ArrayList<>();
        double current = initialCapital.doubleValue();
        equity.add(current);

        LocalDate d = startDay;
        while (!d.isAfter(endDay)) {
            BigDecimal pnl = dailyPnl.getOrDefault(d, BigDecimal.ZERO);
            current += pnl.doubleValue();
            equity.add(current);
            d = d.plusDays(1);
        }

        if (equity.size() < 2) return new double[0];

        double[] returns = new double[equity.size() - 1];
        for (int i = 1; i < equity.size(); i++) {
            double prev = equity.get(i - 1);
            double curr = equity.get(i);
            returns[i - 1] = prev > 0 ? (curr - prev) / prev : 0.0;
        }
        return returns;
    }

    // ── 연환산 Sharpe ────────────────────────────────────────────

    /**
     * 일별 simple return 시계열 → 연환산 Sharpe ratio.
     * 표본 분산(N-1) 사용. MAR = 0 가정 (무위험 이자율 무시).
     */
    private static BigDecimal calculateSharpe(double[] returns) {
        if (returns.length < 2) return BigDecimal.ZERO;

        double mean = mean(returns);
        double variance = 0;
        for (double r : returns) {
            double d = r - mean;
            variance += d * d;
        }
        variance /= (returns.length - 1);   // 표본 분산
        double std = Math.sqrt(variance);
        if (std == 0) return BigDecimal.ZERO;

        double sharpeAnnual = (mean / std) * SQRT_YEAR;
        return BigDecimal.valueOf(sharpeAnnual).setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ── 연환산 Sortino ───────────────────────────────────────────

    /**
     * 일별 simple return 시계열 → 연환산 Sortino ratio.
     * Downside deviation = sqrt( Σ min(r - MAR, 0)² / N ) (MAR = 0).
     * 분모는 전체 표본 수 N 사용 — Frank Sortino 원본 정의 준수.
     */
    private static BigDecimal calculateSortino(double[] returns) {
        if (returns.length < 2) return BigDecimal.ZERO;

        double mean = mean(returns);
        double downsideSumSq = 0;
        for (double r : returns) {
            if (r < 0) downsideSumSq += r * r;
        }
        double downsideDev = Math.sqrt(downsideSumSq / returns.length);
        if (downsideDev == 0) return BigDecimal.ZERO;

        double sortinoAnnual = (mean / downsideDev) * SQRT_YEAR;
        return BigDecimal.valueOf(sortinoAnnual).setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ── Calmar (CAGR / |MDD|) ────────────────────────────────────

    /**
     * 실제 CAGR 기반 Calmar ratio.
     * <pre>
     *   CAGR = (finalEquity / initialCapital)^(1/years) - 1
     *   Calmar = CAGR(%) / |MDD(%)|
     * </pre>
     * 기간이 1일 미만인 경우 단순 total return 으로 대체 (연환산 외삽 금지).
     */
    private static BigDecimal calculateCalmar(List<TradeRecord> sellTrades, BigDecimal initialCapital,
                                               BigDecimal totalPnl, BigDecimal mddPct) {
        if (sellTrades.isEmpty() || mddPct.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        double initial = initialCapital.doubleValue();
        double finalEquity = initial + totalPnl.doubleValue();
        if (finalEquity <= 0 || initial <= 0) return BigDecimal.ZERO;

        Instant first = sellTrades.get(0).getExecutedAt();
        Instant last = sellTrades.get(sellTrades.size() - 1).getExecutedAt();
        long days = Duration.between(first, last).toDays();

        double totalReturnMult = finalEquity / initial;
        double cagrPct;
        if (days < 1) {
            // 하루 이내 — 연환산 외삽 금지, 단순 수익률 사용
            cagrPct = (totalReturnMult - 1) * 100;
        } else {
            double years = days / TRADING_DAYS_PER_YEAR;
            double cagr = Math.pow(totalReturnMult, 1.0 / years) - 1;
            cagrPct = cagr * 100;
        }

        double mddAbs = Math.abs(mddPct.doubleValue());
        return BigDecimal.valueOf(cagrPct / mddAbs).setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ── 공용 헬퍼 ────────────────────────────────────────────────

    private static double mean(double[] values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private static BigDecimal calculateMDD(List<TradeRecord> trades, BigDecimal initialCapital) {
        BigDecimal peak = initialCapital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal equity = initialCapital;

        for (TradeRecord trade : trades) {
            if (trade.getSide() == OrderSide.SELL) {
                equity = equity.add(trade.getPnl());
                peak = peak.max(equity);
                BigDecimal drawdown = equity.subtract(peak)
                        .divide(peak, SCALE, RoundingMode.HALF_UP)
                        .multiply(HUNDRED);
                maxDrawdown = maxDrawdown.min(drawdown);
            }
        }
        return maxDrawdown;
    }

    private static int calculateMaxConsecutiveLoss(List<TradeRecord> sellTrades) {
        int max = 0, current = 0;
        for (TradeRecord t : sellTrades) {
            if (t.getPnl().compareTo(BigDecimal.ZERO) < 0) {
                current++;
                max = Math.max(max, current);
            } else {
                current = 0;
            }
        }
        return max;
    }

    private static Map<String, BigDecimal> calculateMonthlyReturns(List<TradeRecord> sellTrades, BigDecimal initialCapital) {
        Map<String, BigDecimal> monthly = new TreeMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM").withZone(KST);

        for (TradeRecord t : sellTrades) {
            String month = fmt.format(t.getExecutedAt());
            monthly.merge(month, t.getPnl(), BigDecimal::add);
        }

        // PnL → 수익률(%)로 변환
        monthly.replaceAll((k, v) -> v.divide(initialCapital, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED));
        return monthly;
    }

    // ── §13 월별 수익률 분포 편향 감지 ───────────────────────────

    /**
     * 월별 수익률의 표준편차(%).
     * 월수가 2 미만이면 0 반환.
     */
    static BigDecimal calculateMonthlyStdDev(Map<String, BigDecimal> monthlyReturns) {
        if (monthlyReturns.size() < 2) return BigDecimal.ZERO;
        double[] vals = monthlyReturns.values().stream()
                .mapToDouble(BigDecimal::doubleValue).toArray();
        double mean = Arrays.stream(vals).average().orElse(0);
        double variance = Arrays.stream(vals).map(v -> (v - mean) * (v - mean)).average().orElse(0);
        return BigDecimal.valueOf(Math.sqrt(variance)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 월별 수익률의 왜도(skewness).
     * 양수 = 오른쪽 꼬리 (소수 달에 급등 집중), 음수 = 왼쪽 꼬리.
     * 월수가 3 미만이면 0 반환.
     */
    static BigDecimal calculateMonthlySkewness(Map<String, BigDecimal> monthlyReturns) {
        if (monthlyReturns.size() < 3) return BigDecimal.ZERO;
        double[] vals = monthlyReturns.values().stream()
                .mapToDouble(BigDecimal::doubleValue).toArray();
        double mean = Arrays.stream(vals).average().orElse(0);
        double stdDev = Math.sqrt(Arrays.stream(vals).map(v -> (v - mean) * (v - mean)).average().orElse(0));
        if (stdDev == 0) return BigDecimal.ZERO;
        int n = vals.length;
        double skew = Arrays.stream(vals)
                .map(v -> Math.pow((v - mean) / stdDev, 3))
                .sum() * n / ((double)(n - 1) * (n - 2));
        return BigDecimal.valueOf(skew).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 가장 수익이 높은 단일 달이 전체 양수 PnL 에서 차지하는 비율(%).
     * 80% 이상이면 수익이 특정 달에 집중되어 있음 — 스냅샷 편향 의심.
     * 전체 PnL 이 0 이하이면 0 반환.
     */
    static BigDecimal calculateTopMonthConcentration(
            List<TradeRecord> sellTrades, BigDecimal initialCapital) {
        if (sellTrades.isEmpty()) return BigDecimal.ZERO;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM").withZone(KST);
        Map<String, BigDecimal> monthly = new TreeMap<>();
        for (TradeRecord t : sellTrades) {
            String month = fmt.format(t.getExecutedAt());
            monthly.merge(month, t.getPnl(), BigDecimal::add);
        }
        // 양수 PnL 달만 합산
        BigDecimal totalPositive = monthly.values().stream()
                .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalPositive.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        BigDecimal topMonth = monthly.values().stream()
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (topMonth.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return topMonth.divide(totalPositive, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED);
    }

    private static PerformanceReport emptyReport(String segment) {
        return PerformanceReport.builder()
                .totalReturnPct(BigDecimal.ZERO).winRatePct(BigDecimal.ZERO)
                .mddPct(BigDecimal.ZERO).sharpeRatio(BigDecimal.ZERO)
                .sortinoRatio(BigDecimal.ZERO).calmarRatio(BigDecimal.ZERO)
                .winLossRatio(BigDecimal.ZERO).recoveryFactor(BigDecimal.ZERO)
                .totalTrades(0).winningTrades(0).losingTrades(0)
                .avgProfitPct(BigDecimal.ZERO).avgLossPct(BigDecimal.ZERO)
                .maxConsecutiveLoss(0).monthlyReturns(Map.of())
                .monthlyReturnStdDev(BigDecimal.ZERO)
                .monthlyReturnSkewness(BigDecimal.ZERO)
                .topMonthConcentrationPct(BigDecimal.ZERO)
                .segment(segment)
                .build();
    }
}
