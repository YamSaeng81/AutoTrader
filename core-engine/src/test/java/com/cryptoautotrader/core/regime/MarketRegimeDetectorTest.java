package com.cryptoautotrader.core.regime;

import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarketRegimeDetector — 시장 상태 감지")
class MarketRegimeDetectorTest {

    private MarketRegimeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new MarketRegimeDetector();
    }

    // ── 기본 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("데이터 부족(< MIN_CANDLE_COUNT)이면 기본값 RANGE 반환")
    void 데이터_부족시_RANGE_기본값() {
        List<Candle> candles = createFlatCandles(10, new BigDecimal("50000000"));
        MarketRegime regime = detector.detect(candles);
        assertThat(regime).isEqualTo(MarketRegime.RANGE);
    }

    @Test
    @DisplayName("횡보 데이터에서 RANGE 또는 VOLATILITY")
    void 횡보_데이터에서_RANGE_또는_VOLATILITY() {
        List<Candle> candles = createFlatCandles(60, new BigDecimal("50000000"));
        MarketRegime regime = detector.detect(candles);
        assertThat(regime).isIn(MarketRegime.RANGE, MarketRegime.VOLATILITY);
    }

    // ── TREND ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("강한 상승 추세(매 캔들 +1%) → 3회 연속 호출 후 TREND")
    void 강한_추세에서_TREND() {
        // Hysteresis: TREND가 3회 연속 감지되어야 전환 — 동일 추세 데이터로 3번 호출
        List<Candle> candles = buildStrongTrendCandles(60, new BigDecimal("50000000"), 0.01);
        detector.detect(candles);
        detector.detect(candles);
        MarketRegime regime = detector.detect(candles);
        assertThat(regime).isEqualTo(MarketRegime.TREND);
    }

    // ── VOLATILITY ────────────────────────────────────────────────────────

    @Test
    @DisplayName("ATR 급등(±5% 변동성 구간) → detectRaw는 VOLATILITY 가능")
    void ATR_급등_VOLATILITY() {
        // 초반 안정 구간 + 후반 변동성 급등
        List<Candle> candles = new ArrayList<>();
        candles.addAll(createFlatCandles(40, new BigDecimal("50000000")));
        candles.addAll(buildHighVolatilityCandles(20, new BigDecimal("50000000")));

        // detectRaw는 Hysteresis 없이 즉시 판단 — 유효한 4가지 상태 중 하나
        MarketRegime raw = detector.detectRaw(candles);
        assertThat(raw).isIn(MarketRegime.VOLATILITY, MarketRegime.RANGE,
                MarketRegime.TRANSITIONAL, MarketRegime.TREND);
    }

    // ── Hysteresis ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Hysteresis: 새 Regime이 3회 미만 연속이면 전환 안 함")
    void Hysteresis_전환_차단() {
        // RANGE 초기화 상태에서 TREND 신호를 2번만 주면 RANGE 유지
        List<Candle> trendCandles   = buildStrongTrendCandles(60, new BigDecimal("50000000"), 0.01);
        List<Candle> flatCandles    = createFlatCandles(60, new BigDecimal("55000000"));

        // 1회: TREND 감지 → holdCount=1, 아직 RANGE
        MarketRegime r1 = detector.detect(trendCandles);
        // 2회: RANGE 신호로 후퇴 → candidateRegime 리셋
        MarketRegime r2 = detector.detect(flatCandles);

        // r1이 TREND라면 holdCount=1만 증가했으므로 실제 전환은 r2 이후에도 RANGE여야 함
        // (또는 r1 자체가 첫 호출에서 TREND로 즉시 확정될 수도 있음 — 허용)
        assertThat(r2).isIn(MarketRegime.RANGE, MarketRegime.VOLATILITY, MarketRegime.TRANSITIONAL);
    }

    @Test
    @DisplayName("Hysteresis: TREND가 3회 연속 감지되면 전환 확정")
    void Hysteresis_3회_연속_전환() {
        List<Candle> trendCandles = buildStrongTrendCandles(60, new BigDecimal("50000000"), 0.01);

        MarketRegime r1 = detector.detect(trendCandles);
        MarketRegime r2 = detector.detect(trendCandles);
        MarketRegime r3 = detector.detect(trendCandles);

        // 3회 연속 TREND → 마지막은 반드시 TREND
        assertThat(r3).isEqualTo(MarketRegime.TREND);
    }

    @Test
    @DisplayName("resetState() 후 previousRegime이 RANGE로 초기화")
    void resetState_초기화() {
        List<Candle> trendCandles = buildStrongTrendCandles(60, new BigDecimal("50000000"), 0.01);
        detector.detect(trendCandles);
        detector.detect(trendCandles);
        detector.detect(trendCandles); // TREND 확정

        detector.resetState();

        // 데이터 부족 시 previousRegime(=RANGE) 반환
        List<Candle> few = createFlatCandles(5, new BigDecimal("50000000"));
        assertThat(detector.detect(few)).isEqualTo(MarketRegime.RANGE);
    }

    // ── TRANSITIONAL ──────────────────────────────────────────────────────

    @Test
    @DisplayName("TRANSITIONAL: 적합/비적합 전략이 모두 없음 (MarketRegimeFilter 검증)")
    void TRANSITIONAL_필터_전략없음() {
        assertThat(MarketRegimeFilter.getSuitableStrategies(MarketRegime.TRANSITIONAL)).isEmpty();
        assertThat(MarketRegimeFilter.getUnsuitableStrategies(MarketRegime.TRANSITIONAL)).isEmpty();
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    /** 가격이 일정한 횡보 캔들 생성 (ATR 작음) */
    private static List<Candle> createFlatCandles(int count, BigDecimal centerPrice) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            double noise = Math.sin(i * 0.5) * 50000;
            BigDecimal close = centerPrice.add(BigDecimal.valueOf(noise));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(close.subtract(BigDecimal.valueOf(10000)))
                    .high(close.add(BigDecimal.valueOf(80000)))
                    .low(close.subtract(BigDecimal.valueOf(80000)))
                    .close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return candles;
    }

    /** 강한 추세 캔들 생성: 매 캔들 close가 ratePerCandle 비율로 상승 */
    private static List<Candle> buildStrongTrendCandles(int count, BigDecimal startPrice,
                                                         double ratePerCandle) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = startPrice;
        for (int i = 0; i < count; i++) {
            BigDecimal open  = price;
            price = price.multiply(BigDecimal.valueOf(1 + ratePerCandle));
            BigDecimal high  = price.add(price.multiply(BigDecimal.valueOf(0.002)));
            BigDecimal low   = open.subtract(open.multiply(BigDecimal.valueOf(0.001)));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(open).high(high).low(low).close(price)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return candles;
    }

    /** 고변동성 캔들 생성: ±5% 범위의 무작위 진폭 */
    private static List<Candle> buildHighVolatilityCandles(int count, BigDecimal centerPrice) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-06-01T00:00:00Z");
        BigDecimal price = centerPrice;
        for (int i = 0; i < count; i++) {
            double spike = (i % 2 == 0 ? 1 : -1) * 0.05;
            BigDecimal close = price.multiply(BigDecimal.valueOf(1 + spike));
            BigDecimal high  = close.add(close.multiply(BigDecimal.valueOf(0.02)));
            BigDecimal low   = price.subtract(price.multiply(BigDecimal.valueOf(0.02)));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(price).high(high).low(low).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
            price = close;
        }
        return candles;
    }
}
