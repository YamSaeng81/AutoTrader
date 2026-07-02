package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BtcMarketGuard 회귀 테스트 (2026-07-02 codex 분석 §6 — BTC 시장 전체 서킷 브레이커).
 */
class BtcMarketGuardTest {

    private List<Candle> flatH1(int count, String price, String volume) {
        List<Candle> candles = new ArrayList<>();
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        BigDecimal p = new BigDecimal(price);
        BigDecimal v = new BigDecimal(volume);
        for (int i = 0; i < count; i++) {
            candles.add(Candle.builder()
                    .time(t.plusSeconds(i * 3600L))
                    .open(p).high(p).low(p).close(p)
                    .volume(v)
                    .build());
        }
        return candles;
    }

    private void appendCandle(List<Candle> candles, long offsetSecondsFromLast, String close, String high) {
        Candle last = candles.get(candles.size() - 1);
        candles.add(Candle.builder()
                .time(last.getTime().plusSeconds(offsetSecondsFromLast))
                .open(new BigDecimal(close))
                .high(new BigDecimal(high))
                .low(new BigDecimal(close))
                .close(new BigDecimal(close))
                .volume(BigDecimal.TEN)
                .build());
    }

    @Test
    @DisplayName("평온한 BTC 시장 — 미발동")
    void 평온한_시장은_미발동() {
        List<Candle> candles = flatH1(30, "100000000", "10");
        assertThat(BtcMarketGuard.check(candles).triggered()).isFalse();
    }

    @Test
    @DisplayName("BTC 1시간 내 고점 대비 -1.5% 이상 급락 — 발동")
    void 급락시_발동() {
        List<Candle> candles = flatH1(25, "100000000", "10");
        appendCandle(candles, 1800, "102000000", "102000000"); // 고점 1.02억
        appendCandle(candles, 1800, "100000000", "100000000"); // (100-102)/102 ≈ -1.96%

        BtcMarketGuard.Result result = BtcMarketGuard.check(candles);
        assertThat(result.triggered()).isTrue();
        assertThat(result.reason()).contains("BTC");
    }

    @Test
    @DisplayName("BTC 1시간 내 -1.5% 미만 하락 — 미발동")
    void 경미한_하락은_미발동() {
        List<Candle> candles = flatH1(25, "100000000", "10");
        appendCandle(candles, 1800, "100500000", "100500000");
        appendCandle(candles, 1800, "100000000", "100000000"); // ≈ -0.5%

        assertThat(BtcMarketGuard.check(candles).triggered()).isFalse();
    }

    @Test
    @DisplayName("1시간보다 오래 전 고점은 낙폭 계산에서 제외됨")
    void 오래된_고점은_무시() {
        List<Candle> candles = flatH1(25, "100000000", "10");
        appendCandle(candles, 5400, "200000000", "200000000"); // -90분, 윈도우 밖
        appendCandle(candles, 1800, "100000000", "100000000"); // -60분
        appendCandle(candles, 1800, "100000000", "100000000"); // -30분
        appendCandle(candles, 1800, "100000000", "100000000"); // 현재

        assertThat(BtcMarketGuard.check(candles).triggered()).isFalse();
    }

    @Test
    @DisplayName("캔들이 비어있으면 미발동 (보수적)")
    void 빈_캔들은_미발동() {
        assertThat(BtcMarketGuard.check(List.of()).triggered()).isFalse();
        assertThat(BtcMarketGuard.check(null).triggered()).isFalse();
    }
}
