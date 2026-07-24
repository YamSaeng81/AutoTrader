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
 * WatchlistQualityGate 단위 테스트 (2026-07-24 동적 세션 무거래 근본 원인 대응).
 *
 * <p>유동성/변동성 상한/상승추세/급락 4개 큐레이션 기준을 네트워크·DB 없이 격리 검증한다.
 */
class WatchlistQualityGateTest {

    private static final BigDecimal MIN_TRADE_VALUE = new BigDecimal("5000000000");   // 50억
    private static final BigDecimal MAX_ATR_PCT     = new BigDecimal("4.0");
    private static final BigDecimal BIG_TRADE_VALUE = new BigDecimal("50000000000");  // 500억
    private static final BigDecimal LOW_ATR_PCT     = new BigDecimal("1.0");

    /** 종가·고가를 지정값으로 갖는 5분 간격 캔들 N개 */
    private List<Candle> flat(int count, String price) {
        List<Candle> candles = new ArrayList<>();
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        BigDecimal p = new BigDecimal(price);
        for (int i = 0; i < count; i++) {
            candles.add(Candle.builder()
                    .time(t.plusSeconds(i * 300L))
                    .open(p).high(p).low(p).close(p)
                    .volume(BigDecimal.ONE)
                    .build());
        }
        return candles;
    }

    private void setCandle(List<Candle> candles, int idx, String high, String close) {
        Candle c = candles.get(idx);
        candles.set(idx, Candle.builder()
                .time(c.getTime())
                .open(new BigDecimal(close)).high(new BigDecimal(high))
                .low(new BigDecimal(close)).close(new BigDecimal(close))
                .volume(BigDecimal.ONE)
                .build());
    }

    @Test
    @DisplayName("유동성·변동성·상승추세·비급락 모두 충족하면 통과")
    void 정상코인_통과() {
        // 200개 100원 평탄(EMA200≈100), 마지막 종가 105 → 상승추세 + 급락 아님
        List<Candle> candles = flat(200, "100");
        setCandle(candles, 199, "105", "105");

        WatchlistQualityGate.Decision d = WatchlistQualityGate.evaluate(
                BIG_TRADE_VALUE, LOW_ATR_PCT, candles,
                MIN_TRADE_VALUE, MAX_ATR_PCT, true, true);

        assertThat(d.accepted()).isTrue();
        assertThat(d.reason()).isNull();
    }

    @Test
    @DisplayName("24h 거래대금이 유동성 하한 미만이면 탈락")
    void 유동성미달_탈락() {
        List<Candle> candles = flat(200, "100");
        setCandle(candles, 199, "105", "105");

        WatchlistQualityGate.Decision d = WatchlistQualityGate.evaluate(
                new BigDecimal("1000000000"), LOW_ATR_PCT, candles,  // 10억 < 50억
                MIN_TRADE_VALUE, MAX_ATR_PCT, true, true);

        assertThat(d.accepted()).isFalse();
        assertThat(d.reason()).contains("유동성 미달");
    }

    @Test
    @DisplayName("ATR%가 변동성 상한을 초과하면 탈락")
    void 변동성과다_탈락() {
        List<Candle> candles = flat(200, "100");
        setCandle(candles, 199, "105", "105");

        WatchlistQualityGate.Decision d = WatchlistQualityGate.evaluate(
                BIG_TRADE_VALUE, new BigDecimal("6.5"), candles,     // 6.5% > 4.0%
                MIN_TRADE_VALUE, MAX_ATR_PCT, true, true);

        assertThat(d.accepted()).isFalse();
        assertThat(d.reason()).contains("변동성 과다");
    }

    @Test
    @DisplayName("종가가 EMA200 아래(하락추세)면 탈락")
    void 하락추세_탈락() {
        List<Candle> candles = flat(200, "100");
        setCandle(candles, 199, "50", "50");   // 종가 50 < EMA200≈100

        WatchlistQualityGate.Decision d = WatchlistQualityGate.evaluate(
                BIG_TRADE_VALUE, LOW_ATR_PCT, candles,
                MIN_TRADE_VALUE, MAX_ATR_PCT, true, true);

        assertThat(d.accepted()).isFalse();
        assertThat(d.reason()).contains("하락 추세");
    }

    @Test
    @DisplayName("1시간 내 급락(-5% 초과) 중이면 탈락 — 상승추세여도 급락 배제")
    void 급락중_탈락() {
        // 199개 50원 + 마지막 직전 고가 110 + 마지막 종가 100 → EMA200≈50 대비 상승추세지만
        // 최근 1시간 고가(110) 대비 -9% 급락 → BlackSwanGuard 발동
        List<Candle> candles = flat(200, "50");
        setCandle(candles, 198, "110", "110");
        setCandle(candles, 199, "100", "100");

        WatchlistQualityGate.Decision d = WatchlistQualityGate.evaluate(
                BIG_TRADE_VALUE, LOW_ATR_PCT, candles,
                MIN_TRADE_VALUE, MAX_ATR_PCT, true, true);

        assertThat(d.accepted()).isFalse();
        assertThat(d.reason()).contains("급락 중");
    }

    @Test
    @DisplayName("모든 기준을 끄면(null/false) 급락·하락추세 코인도 통과")
    void 기준_비활성화시_통과() {
        List<Candle> candles = flat(200, "100");
        setCandle(candles, 198, "110", "110");
        setCandle(candles, 199, "50", "50");   // 급락 + 하락추세

        WatchlistQualityGate.Decision d = WatchlistQualityGate.evaluate(
                new BigDecimal("1"), new BigDecimal("99"), candles,
                null, null, false, false);

        assertThat(d.accepted()).isTrue();
    }

    @Test
    @DisplayName("유동성 → 변동성 → 추세 → 급락 순으로 첫 위반 사유를 반환")
    void 위반_우선순위() {
        List<Candle> candles = flat(200, "100");
        setCandle(candles, 199, "50", "50");   // 하락추세이기도 함

        // 유동성부터 위반 → 유동성 사유가 먼저
        WatchlistQualityGate.Decision d = WatchlistQualityGate.evaluate(
                new BigDecimal("1000000000"), new BigDecimal("99"), candles,
                MIN_TRADE_VALUE, MAX_ATR_PCT, true, true);

        assertThat(d.reason()).contains("유동성 미달");
    }
}
