package com.cryptoautotrader.core.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FillSimulatorTest {

    private final FillSimulator simulator = new FillSimulator(
            new BigDecimal("0.1"), new BigDecimal("0.3"));

    @Test
    void MarketImpact_계산() {
        BigDecimal impact = simulator.calculateMarketImpact(
                new BigDecimal("10"), new BigDecimal("100"));
        // 10/100 * 0.1 = 0.01
        assertThat(impact).isEqualByComparingTo(new BigDecimal("0.01"));
    }

    @Test
    void MarketImpact_거래량_0이면_0() {
        BigDecimal impact = simulator.calculateMarketImpact(
                new BigDecimal("10"), BigDecimal.ZERO);
        assertThat(impact).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void PartialFill_주문량이_크면_true() {
        // maxFill = 100 * 0.3 = 30
        boolean partial = simulator.isPartialFill(new BigDecimal("50"), new BigDecimal("100"));
        assertThat(partial).isTrue();
    }

    @Test
    void PartialFill_주문량이_작으면_false() {
        boolean partial = simulator.isPartialFill(new BigDecimal("20"), new BigDecimal("100"));
        assertThat(partial).isFalse();
    }

    @Test
    void 최대체결수량_계산() {
        BigDecimal maxFill = simulator.calculateMaxFillQuantity(new BigDecimal("100"));
        assertThat(maxFill).isEqualByComparingTo(new BigDecimal("30"));
    }
}
