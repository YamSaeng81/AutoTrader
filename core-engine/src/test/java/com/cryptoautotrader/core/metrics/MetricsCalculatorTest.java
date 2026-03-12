package com.cryptoautotrader.core.metrics;

import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

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

    private TradeRecord makeSellTrade(Instant time, BigDecimal pnl) {
        return TradeRecord.builder()
                .side(OrderSide.SELL).price(new BigDecimal("50000000"))
                .quantity(new BigDecimal("0.1")).fee(BigDecimal.ZERO).slippage(BigDecimal.ZERO)
                .pnl(pnl).cumulativePnl(pnl).executedAt(time).build();
    }
}
