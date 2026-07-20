package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SignalQualityDampenGate — 야간·TRANSITIONAL 감쇠 계수")
class SignalQualityDampenGateTest {

    private static List<Candle> candleAt(Instant time) {
        BigDecimal c = BigDecimal.valueOf(100);
        return List.of(Candle.builder()
                .time(time).open(c).high(c).low(c).close(c)
                .volume(BigDecimal.ONE)
                .build());
    }

    // ── nightFactor ───────────────────────────────────────────────────────

    @Test
    @DisplayName("KST 20시 정각부터 감쇠 계수 적용")
    void kst_20시부터_감쇠() {
        Instant t = Instant.parse("2026-01-01T11:00:00Z"); // KST 20:00
        assertThat(SignalQualityDampenGate.nightFactor(candleAt(t), 0.6)).isEqualTo(0.6);
    }

    @Test
    @DisplayName("KST 19시 59분(직전)은 무감쇠")
    void kst_19시대는_무감쇠() {
        Instant t = Instant.parse("2026-01-01T10:59:00Z"); // KST 19:59
        assertThat(SignalQualityDampenGate.nightFactor(candleAt(t), 0.6)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("KST 23시대는 감쇠, 자정 이후(0시)는 무감쇠")
    void kst_23시_감쇠_자정이후_무감쇠() {
        Instant t23 = Instant.parse("2026-01-01T14:00:00Z"); // KST 23:00
        Instant t00 = Instant.parse("2026-01-01T15:00:00Z"); // KST 00:00 (다음날)
        assertThat(SignalQualityDampenGate.nightFactor(candleAt(t23), 0.6)).isEqualTo(0.6);
        assertThat(SignalQualityDampenGate.nightFactor(candleAt(t00), 0.6)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("빈 캔들 리스트는 무감쇠(예외 없이 1.0)")
    void 빈캔들_무감쇠() {
        assertThat(SignalQualityDampenGate.nightFactor(List.of(), 0.6)).isEqualTo(1.0);
    }

    // ── transitionalFactor ───────────────────────────────────────────────

    @Test
    @DisplayName("TRANSITIONAL 레짐이면 감쇠 계수 적용")
    void transitional_감쇠() {
        assertThat(SignalQualityDampenGate.transitionalFactor(MarketRegime.TRANSITIONAL, 0.5)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("TREND/RANGE/VOLATILITY/null 레짐은 무감쇠")
    void 기타_레짐_무감쇠() {
        assertThat(SignalQualityDampenGate.transitionalFactor(MarketRegime.TREND, 0.5)).isEqualTo(1.0);
        assertThat(SignalQualityDampenGate.transitionalFactor(MarketRegime.RANGE, 0.5)).isEqualTo(1.0);
        assertThat(SignalQualityDampenGate.transitionalFactor(MarketRegime.VOLATILITY, 0.5)).isEqualTo(1.0);
        assertThat(SignalQualityDampenGate.transitionalFactor(null, 0.5)).isEqualTo(1.0);
    }
}
