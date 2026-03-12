package com.cryptoautotrader.core.portfolio;

import com.cryptoautotrader.core.model.CoinPair;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioManagerTest {

    @Test
    void 자본할당_가능여부_확인() {
        PortfolioManager pm = new PortfolioManager(new BigDecimal("10000000"));
        assertThat(pm.canAllocate("s1", new BigDecimal("5000000"))).isTrue();
        assertThat(pm.canAllocate("s1", new BigDecimal("15000000"))).isFalse();
    }

    @Test
    void 자본할당_후_가용자본_감소() {
        PortfolioManager pm = new PortfolioManager(new BigDecimal("10000000"));
        pm.allocate("s1", new BigDecimal("3000000"));
        assertThat(pm.getAvailableCapital()).isEqualByComparingTo(new BigDecimal("7000000"));
    }

    @Test
    void 자본반환_후_가용자본_증가() {
        PortfolioManager pm = new PortfolioManager(new BigDecimal("10000000"));
        pm.allocate("s1", new BigDecimal("3000000"));
        pm.release("s1", new BigDecimal("3000000"));
        assertThat(pm.getAvailableCapital()).isEqualByComparingTo(new BigDecimal("10000000"));
    }

    @Test
    void 초과할당시_예외() {
        PortfolioManager pm = new PortfolioManager(new BigDecimal("10000000"));
        pm.allocate("s1", new BigDecimal("8000000"));
        assertThatThrownBy(() -> pm.allocate("s2", new BigDecimal("5000000")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 코인방향_충돌감지() {
        PortfolioManager pm = new PortfolioManager(new BigDecimal("10000000"));
        CoinPair btc = CoinPair.of("KRW-BTC");

        pm.setDirection(btc, PortfolioManager.PositionDirection.LONG);
        assertThat(pm.hasConflict(btc, PortfolioManager.PositionDirection.SHORT)).isTrue();
        assertThat(pm.hasConflict(btc, PortfolioManager.PositionDirection.LONG)).isFalse();
    }

    @Test
    void FLAT_방향은_충돌없음() {
        PortfolioManager pm = new PortfolioManager(new BigDecimal("10000000"));
        CoinPair btc = CoinPair.of("KRW-BTC");

        pm.setDirection(btc, PortfolioManager.PositionDirection.LONG);
        pm.setDirection(btc, PortfolioManager.PositionDirection.FLAT);
        assertThat(pm.hasConflict(btc, PortfolioManager.PositionDirection.SHORT)).isFalse();
    }
}
