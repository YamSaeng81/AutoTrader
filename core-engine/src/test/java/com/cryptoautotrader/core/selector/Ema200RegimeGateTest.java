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
 * Ema200RegimeGate 단일 진실 소스 회귀 테스트 (2026-06-01 P1-B).
 *
 * <p>이전에 LiveTradingService/BacktestEngine 두 곳에 중복 구현되어 DOGE 예외가
 * 한쪽에만 있던 불일치를 게이트로 통합. 핵심 계약을 고정한다.
 */
class Ema200RegimeGateTest {

    /** 종가를 일정 값으로 갖는 캔들 N개 생성 */
    private List<Candle> flatCandles(int count, String close) {
        List<Candle> candles = new ArrayList<>();
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        BigDecimal c = new BigDecimal(close);
        for (int i = 0; i < count; i++) {
            candles.add(Candle.builder()
                    .time(t.plusSeconds(i * 3600L))
                    .open(c).high(c).low(c).close(c)
                    .volume(BigDecimal.ONE)
                    .build());
        }
        return candles;
    }

    /** 마지막 캔들 종가를 덮어쓴다 */
    private List<Candle> withLastClose(List<Candle> candles, String close) {
        BigDecimal c = new BigDecimal(close);
        Candle last = candles.get(candles.size() - 1);
        candles.set(candles.size() - 1, Candle.builder()
                .time(last.getTime())
                .open(c).high(c).low(c).close(c)
                .volume(BigDecimal.ONE)
                .build());
        return candles;
    }

    @Test
    @DisplayName("현재가가 EMA200 위면 BUY 허용")
    void 상승레짐이면_허용() {
        // 200개 100원 평탄 → EMA200≈100. 마지막을 200원으로 → 현재가 > EMA200
        List<Candle> candles = withLastClose(flatCandles(200, "100"), "200");
        assertThat(Ema200RegimeGate.allowsBuy(candles, "KRW-BTC")).isTrue();
    }

    @Test
    @DisplayName("현재가가 EMA200 아래면 BUY 차단")
    void 하락레짐이면_차단() {
        List<Candle> candles = withLastClose(flatCandles(200, "100"), "50");
        assertThat(Ema200RegimeGate.allowsBuy(candles, "KRW-BTC")).isFalse();
    }

    @Test
    @DisplayName("DOGE는 EMA200 아래여도 BUY 허용 (예외)")
    void doge_예외는_항상_허용() {
        List<Candle> candles = withLastClose(flatCandles(200, "100"), "50");
        assertThat(Ema200RegimeGate.allowsBuy(candles, "KRW-DOGE")).isTrue();
    }

    @Test
    @DisplayName("캔들 200개 미만이면 EMA200 산출 불가 → 보수적으로 허용")
    void 캔들부족시_허용() {
        List<Candle> candles = withLastClose(flatCandles(199, "100"), "50");
        assertThat(Ema200RegimeGate.allowsBuy(candles, "KRW-BTC")).isTrue();
    }

    @Test
    @DisplayName("coinPair가 null이어도 NPE 없이 EMA200 규칙 적용")
    void coinPair_null_허용() {
        List<Candle> candles = withLastClose(flatCandles(200, "100"), "50");
        assertThat(Ema200RegimeGate.allowsBuy(candles, null)).isFalse();
    }
}
