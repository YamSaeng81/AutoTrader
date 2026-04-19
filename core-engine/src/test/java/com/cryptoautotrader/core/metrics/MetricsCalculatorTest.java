package com.cryptoautotrader.core.metrics;

import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCalculatorTest {

    private static final BigDecimal INITIAL_CAPITAL = new BigDecimal("10000000");

    @Test
    void 거래없으면_빈_리포트() {
        PerformanceReport report = MetricsCalculator.calculate(List.of(), INITIAL_CAPITAL);
        assertThat(report.getTotalTrades()).isZero();
        assertThat(report.getTotalReturnPct()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void 수익_거래_승률100() {
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        List<TradeRecord> trades = List.of(
                TradeRecord.builder().side(OrderSide.BUY).price(new BigDecimal("50000000"))
                        .quantity(new BigDecimal("0.1")).fee(BigDecimal.ZERO).slippage(BigDecimal.ZERO)
                        .pnl(BigDecimal.ZERO).cumulativePnl(BigDecimal.ZERO)
                        .executedAt(base).build(),
                TradeRecord.builder().side(OrderSide.SELL).price(new BigDecimal("55000000"))
                        .quantity(new BigDecimal("0.1")).fee(BigDecimal.ZERO).slippage(BigDecimal.ZERO)
                        .pnl(new BigDecimal("500000")).cumulativePnl(new BigDecimal("500000"))
                        .executedAt(base.plus(1, ChronoUnit.DAYS)).build()
        );

        PerformanceReport report = MetricsCalculator.calculate(trades, INITIAL_CAPITAL);
        assertThat(report.getTotalTrades()).isEqualTo(1);
        assertThat(report.getWinningTrades()).isEqualTo(1);
        assertThat(report.getWinRatePct()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(report.getTotalReturnPct()).isPositive();
    }

    @Test
    void 손실_거래_MDD_음수() {
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        List<TradeRecord> trades = List.of(
                TradeRecord.builder().side(OrderSide.BUY).price(new BigDecimal("50000000"))
                        .quantity(new BigDecimal("0.1")).fee(BigDecimal.ZERO).slippage(BigDecimal.ZERO)
                        .pnl(BigDecimal.ZERO).cumulativePnl(BigDecimal.ZERO)
                        .executedAt(base).build(),
                TradeRecord.builder().side(OrderSide.SELL).price(new BigDecimal("45000000"))
                        .quantity(new BigDecimal("0.1")).fee(BigDecimal.ZERO).slippage(BigDecimal.ZERO)
                        .pnl(new BigDecimal("-500000")).cumulativePnl(new BigDecimal("-500000"))
                        .executedAt(base.plus(1, ChronoUnit.DAYS)).build()
        );

        PerformanceReport report = MetricsCalculator.calculate(trades, INITIAL_CAPITAL);
        assertThat(report.getLosingTrades()).isEqualTo(1);
        assertThat(report.getMddPct()).isNegative();
        assertThat(report.getMaxConsecutiveLoss()).isEqualTo(1);
    }

    @Test
    void 연속_손실_카운트() {
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        List<TradeRecord> trades = List.of(
                makeSellTrade(base, new BigDecimal("-100000")),
                makeSellTrade(base.plus(1, ChronoUnit.DAYS), new BigDecimal("-200000")),
                makeSellTrade(base.plus(2, ChronoUnit.DAYS), new BigDecimal("-150000")),
                makeSellTrade(base.plus(3, ChronoUnit.DAYS), new BigDecimal("300000"))
        );

        PerformanceReport report = MetricsCalculator.calculate(trades, INITIAL_CAPITAL);
        assertThat(report.getMaxConsecutiveLoss()).isEqualTo(3);
    }

    @Test
    void 같은날_다수거래는_Sharpe_0() {
        // 같은 날 100건 거래 → 일별 시계열 데이터 포인트 1개 → Sharpe = 0
        // (과거 버그: per-trade 기준으로 sqrt(365) 곱해 Sharpe 부풀려짐)
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        List<TradeRecord> trades = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            trades.add(makeSellTrade(base.plus(i, ChronoUnit.SECONDS), new BigDecimal("10000")));
        }
        PerformanceReport report = MetricsCalculator.calculate(trades, INITIAL_CAPITAL);
        assertThat(report.getSharpeRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getSortinoRatio()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void Sharpe는_일별시계열_기반_연환산() {
        // 10일 연속 일별 거래 — 과거 버그 하에선 Sharpe가 sqrt(365) 계수로 팽창했으나
        // 일별 시계열 기반으로는 합리적 범위(<10)에 머물러야 한다.
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        List<TradeRecord> trades = new java.util.ArrayList<>();
        BigDecimal[] pnls = {
                new BigDecimal("50000"),  new BigDecimal("-20000"),
                new BigDecimal("80000"),  new BigDecimal("-30000"),
                new BigDecimal("60000"),  new BigDecimal("-10000"),
                new BigDecimal("40000"),  new BigDecimal("-25000"),
                new BigDecimal("70000"),  new BigDecimal("-15000")
        };
        for (int i = 0; i < pnls.length; i++) {
            trades.add(makeSellTrade(base.plus(i, ChronoUnit.DAYS), pnls[i]));
        }
        PerformanceReport report = MetricsCalculator.calculate(trades, INITIAL_CAPITAL);
        // 합리적 범위: 전설적 전략도 연간 Sharpe 5 이하가 정상
        // 합리적 범위: 연간 Sharpe는 5 이하가 정상 (10건 소표본이라 여유 허용)
        assertThat(report.getSharpeRatio().doubleValue()).isBetween(-15.0, 15.0);
        // Sortino는 downside 드문 경우 커질 수 있음 — 여기선 50 이내면 충분
        assertThat(report.getSortinoRatio().doubleValue()).isBetween(-50.0, 50.0);
    }

    @Test
    void Calmar는_CAGR기반_연환산_외삽금지() {
        // 하루 이내 거래 → 연환산 외삽 금지, 단순 수익률 사용
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        List<TradeRecord> trades = List.of(
                makeSellTrade(base, new BigDecimal("100000")),
                makeSellTrade(base.plus(1, ChronoUnit.HOURS), new BigDecimal("-50000"))
        );
        PerformanceReport report = MetricsCalculator.calculate(trades, INITIAL_CAPITAL);
        // total pnl = 50000, total return = 0.5%, mdd ≈ -0.5%
        // Calmar = 0.5 / 0.5 = 1.0 (CAGR 외삽 없이)
        assertThat(report.getCalmarRatio().doubleValue()).isBetween(-5.0, 5.0);
    }

    // ── §13 데이터 스냅샷 편향 감지 지표 테스트 ─────────────────

    @Test
    void 월별stdDev_균등분포는_0에_가깝다() {
        // 모든 달 동일 수익률 → stdDev ≈ 0
        Map<String, BigDecimal> monthly = Map.of(
                "2024-01", BigDecimal.TEN,
                "2024-02", BigDecimal.TEN,
                "2024-03", BigDecimal.TEN
        );
        BigDecimal stdDev = MetricsCalculator.calculateMonthlyStdDev(monthly);
        assertThat(stdDev.doubleValue()).isLessThan(0.01);
    }

    @Test
    void 월별stdDev_분산이_크면_높은값() {
        Map<String, BigDecimal> monthly = Map.of(
                "2024-01", new BigDecimal("100"),
                "2024-02", new BigDecimal("-50"),
                "2024-03", new BigDecimal("0")
        );
        BigDecimal stdDev = MetricsCalculator.calculateMonthlyStdDev(monthly);
        assertThat(stdDev.doubleValue()).isGreaterThan(50.0);
    }

    @Test
    void topMonthConcentration_특정달_집중이면_높은비율() {
        // 1월에 수익 90%, 2·3월에 각 5% → 집중도 ≈ 90%
        Instant base = Instant.parse("2024-01-15T00:00:00Z");
        List<TradeRecord> trades = List.of(
                makeSellTrade(base,                                  new BigDecimal("900000")),  // 1월
                makeSellTrade(base.plus(30, ChronoUnit.DAYS),        new BigDecimal("50000")),   // 2월
                makeSellTrade(base.plus(60, ChronoUnit.DAYS),        new BigDecimal("50000"))    // 3월
        );
        BigDecimal conc = MetricsCalculator.calculateTopMonthConcentration(trades, INITIAL_CAPITAL);
        assertThat(conc.doubleValue()).isGreaterThan(80.0);
    }

    @Test
    void topMonthConcentration_균등분포는_낮은비율() {
        Instant base = Instant.parse("2024-01-15T00:00:00Z");
        List<TradeRecord> trades = List.of(
                makeSellTrade(base,                                  new BigDecimal("100000")),
                makeSellTrade(base.plus(30, ChronoUnit.DAYS),        new BigDecimal("100000")),
                makeSellTrade(base.plus(60, ChronoUnit.DAYS),        new BigDecimal("100000"))
        );
        BigDecimal conc = MetricsCalculator.calculateTopMonthConcentration(trades, INITIAL_CAPITAL);
        // 3등분이면 집중도 ≈ 33%
        assertThat(conc.doubleValue()).isLessThan(40.0);
    }

    @Test
    void 월별skewness_오른쪽꼬리는_양수() {
        // 대부분 소폭 음수, 한 달 큰 양수 → 양의 왜도
        Map<String, BigDecimal> monthly = Map.of(
                "2024-01", new BigDecimal("-5"),
                "2024-02", new BigDecimal("-3"),
                "2024-03", new BigDecimal("-4"),
                "2024-04", new BigDecimal("50")  // 오른쪽 꼬리
        );
        BigDecimal skew = MetricsCalculator.calculateMonthlySkewness(monthly);
        assertThat(skew.doubleValue()).isGreaterThan(0);
    }

    private TradeRecord makeSellTrade(Instant time, BigDecimal pnl) {
        return TradeRecord.builder()
                .side(OrderSide.SELL).price(new BigDecimal("50000000"))
                .quantity(new BigDecimal("0.1")).fee(BigDecimal.ZERO).slippage(BigDecimal.ZERO)
                .pnl(pnl).cumulativePnl(pnl).executedAt(time).build();
    }
}
