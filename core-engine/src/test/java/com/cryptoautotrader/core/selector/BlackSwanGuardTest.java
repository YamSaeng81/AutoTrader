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
 * BlackSwanGuard 회귀 테스트 (2026-07-02 종합분석 — 4/30 로드맵 ⭐⭐⭐ BLACK_SWAN_GUARD 구현).
 */
class BlackSwanGuardTest {

    /** H1 캔들 count개를 생성 — open=high=low=close=price, volume=1 (평탄) */
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

    private void appendCandle(List<Candle> candles, long offsetSecondsFromLast,
                               String close, String high, String volume) {
        Candle last = candles.get(candles.size() - 1);
        candles.add(Candle.builder()
                .time(last.getTime().plusSeconds(offsetSecondsFromLast))
                .open(new BigDecimal(close))
                .high(new BigDecimal(high))
                .low(new BigDecimal(close))
                .close(new BigDecimal(close))
                .volume(new BigDecimal(volume))
                .build());
    }

    @Test
    @DisplayName("평탄한 시장 — 발동하지 않음")
    void 평온한_시장은_미발동() {
        List<Candle> candles = flatH1(30, "100", "10");
        BlackSwanGuard.Result result = BlackSwanGuard.check(candles);
        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("1시간 내 고점 대비 5% 이상 급락 — 발동")
    void 급락시_발동() {
        List<Candle> candles = flatH1(25, "100", "10");
        // 30분 전 캔들의 고가 110 → 현재 종가 100은 (100-110)/110 = -9.09% 하락
        appendCandle(candles, 1800, "110", "110", "10"); // 30분 후, 고점 110
        appendCandle(candles, 1800, "100", "100", "10"); // 다시 30분 후(고점으로부터 30분 이내), 종가 100

        BlackSwanGuard.Result result = BlackSwanGuard.check(candles);
        assertThat(result.triggered()).isTrue();
        assertThat(result.reason()).contains("급락");
    }

    @Test
    @DisplayName("1시간 내 5% 미만 하락 — 미발동")
    void 경미한_하락은_미발동() {
        List<Candle> candles = flatH1(25, "100", "10");
        appendCandle(candles, 1800, "103", "103", "10");
        appendCandle(candles, 1800, "100", "100", "10"); // (100-103)/103 ≈ -2.9%

        BlackSwanGuard.Result result = BlackSwanGuard.check(candles);
        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("1시간보다 오래 전 고점은 낙폭 계산에서 제외됨")
    void 오래된_고점은_무시() {
        List<Candle> candles = flatH1(25, "100", "10");
        // 고점 200을 현재로부터 90분 전에 배치(lookback 60분 밖) → 윈도우에 포함 안 됨
        appendCandle(candles, 5400, "200", "200", "10"); // -90분
        appendCandle(candles, 1800, "100", "100", "10"); // -60분 (경계, 고점 미포함 구간 시작)
        appendCandle(candles, 1800, "100", "100", "10"); // -30분
        appendCandle(candles, 1800, "100", "100", "10"); // 현재

        BlackSwanGuard.Result result = BlackSwanGuard.check(candles);
        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("현재 캔들 거래량이 최근 평균의 5배 이상 — 발동")
    void 거래량_급증시_발동() {
        List<Candle> candles = flatH1(25, "100", "10"); // 평균 거래량 10
        appendCandle(candles, 3600, "100", "100", "55"); // 55 = 5.5배

        BlackSwanGuard.Result result = BlackSwanGuard.check(candles);
        assertThat(result.triggered()).isTrue();
        assertThat(result.reason()).contains("거래량");
    }

    @Test
    @DisplayName("거래량이 평균의 5배 미만 — 미발동")
    void 거래량_소폭증가는_미발동() {
        List<Candle> candles = flatH1(25, "100", "10");
        appendCandle(candles, 3600, "100", "100", "30"); // 3배

        BlackSwanGuard.Result result = BlackSwanGuard.check(candles);
        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("캔들이 비어있으면 미발동 (보수적)")
    void 빈_캔들은_미발동() {
        assertThat(BlackSwanGuard.check(List.of()).triggered()).isFalse();
        assertThat(BlackSwanGuard.check(null).triggered()).isFalse();
    }

    @Test
    @DisplayName("캔들 수가 거래량 기준선(20개) 이하면 거래량 체크 스킵 — NPE 없이 미발동")
    void 캔들_부족시_거래량체크_스킵() {
        List<Candle> candles = flatH1(5, "100", "10");
        assertThat(BlackSwanGuard.check(candles).triggered()).isFalse();
    }
}
