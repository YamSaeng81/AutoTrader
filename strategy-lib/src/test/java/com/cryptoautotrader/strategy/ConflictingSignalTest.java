package com.cryptoautotrader.strategy;

import com.cryptoautotrader.strategy.bollinger.BollingerStrategy;
import com.cryptoautotrader.strategy.supertrend.SupertrendStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상충 신호(Conflicting Signal) 테스트
 *
 * 목적: 동일한 시장 데이터에서 서로 반대 방향의 신호가 발생할 수 있음을 명세화한다.
 * 이는 현재 시스템에 Market Regime Detector + CompositeStrategy(Weighted Voting)가
 * 없기 때문에 발생하는 구조적 문제이며, Phase S2~S3 구현의 근거가 된다.
 *
 * 시나리오: 강한 상승 추세 (Supertrend = BUY) + 상단 밴드 이탈 (Bollinger = SELL)
 *   - Supertrend는 추세 추종 전략 → 강한 상승 = BUY
 *   - Bollinger는 평균 회귀 전략 → 상단 밴드 이탈 = SELL (과매수로 판단)
 *   → 같은 데이터에서 BUY와 SELL이 동시에 발생 → 현재 시스템은 이를 조율하지 못함
 */
class ConflictingSignalTest {

    private final SupertrendStrategy supertrend = new SupertrendStrategy();
    private final BollingerStrategy bollinger = new BollingerStrategy();

    /**
     * 강한 상승장에서 Supertrend=BUY, Bollinger=SELL 동시 발생 확인
     *
     * 구성:
     * 1) 횡보 안정 구간(20캔들) → Bollinger 밴드 좁게 형성
     * 2) 강한 상승 구간(30캔들, 캔들당 1.2% 상승) → Supertrend 상승 추세 확립
     * 3) 상승 가속(5캔들, 캔들당 3%) → 상단 밴드 이탈 → Bollinger SELL 유발
     */
    @Test
    void 강한_상승장에서_Supertrend_BUY_Bollinger_SELL_동시발생() {
        List<Candle> candles = buildStrongUpTrendCandles();

        StrategySignal supertrendSignal = supertrend.evaluate(candles, Map.of(
                "atrPeriod", 10,
                "multiplier", 3.0
        ));
        StrategySignal bollingerSignal = bollinger.evaluate(candles, Map.of(
                "period", 20,
                "multiplier", 2.0,
                "adxMaxThreshold", 0   // ADX 필터 비활성화: 상충 신호 시나리오 테스트
        ));

        // Supertrend는 지속 상승 추세 → BUY
        assertThat(supertrendSignal.getAction())
                .as("강한 상승장: Supertrend는 BUY 신호를 내야 한다")
                .isEqualTo(StrategySignal.Action.BUY);

        // Bollinger는 상단 밴드 이탈(과매수) → SELL
        assertThat(bollingerSignal.getAction())
                .as("강한 상승장: Bollinger는 상단 밴드 이탈로 SELL 신호를 내야 한다")
                .isEqualTo(StrategySignal.Action.SELL);

        // 핵심 명세: 동일 캔들 데이터에서 두 전략이 반대 방향 신호 발생
        assertThat(supertrendSignal.getAction())
                .as("두 전략의 신호가 상충(BUY vs SELL)해야 한다 — 현재 시스템에 조율 로직 없음")
                .isNotEqualTo(bollingerSignal.getAction());
    }

    /**
     * 강한 하락장에서 Supertrend=SELL, Bollinger=BUY 동시 발생 확인
     *
     * 하락 추세에서도 동일한 구조적 상충이 발생한다.
     */
    @Test
    void 강한_하락장에서_Supertrend_SELL_Bollinger_BUY_동시발생() {
        List<Candle> candles = buildStrongDownTrendCandles();

        StrategySignal supertrendSignal = supertrend.evaluate(candles, Map.of(
                "atrPeriod", 10,
                "multiplier", 3.0
        ));
        StrategySignal bollingerSignal = bollinger.evaluate(candles, Map.of(
                "period", 20,
                "multiplier", 2.0,
                "adxMaxThreshold", 0   // ADX 필터 비활성화: 상충 신호 시나리오 테스트
        ));

        // Supertrend는 지속 하락 추세 → SELL
        assertThat(supertrendSignal.getAction())
                .as("강한 하락장: Supertrend는 SELL 신호를 내야 한다")
                .isEqualTo(StrategySignal.Action.SELL);

        // Bollinger는 하단 밴드 이탈(과매도) → BUY
        assertThat(bollingerSignal.getAction())
                .as("강한 하락장: Bollinger는 하단 밴드 이탈로 BUY 신호를 내야 한다")
                .isEqualTo(StrategySignal.Action.BUY);

        assertThat(supertrendSignal.getAction())
                .as("두 전략의 신호가 상충(SELL vs BUY)해야 한다 — 현재 시스템에 조율 로직 없음")
                .isNotEqualTo(bollingerSignal.getAction());
    }

    // ─── 헬퍼: 강한 상승장 캔들 데이터 생성 ──────────────────────────────────

    /**
     * 횡보(20) → 지속 상승(30, +1.2%/캔들) → 가속 상승(5, +3%/캔들)
     * 마지막 5캔들이 Bollinger 상단 밴드를 이탈하도록 설계
     */
    private List<Candle> buildStrongUpTrendCandles() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");
        int idx = 0;

        // 1단계: 횡보 안정 구간 (Bollinger 밴드 폭 좁게 형성)
        for (int i = 0; i < 20; i++) {
            BigDecimal high = price.add(price.multiply(new BigDecimal("0.002")));
            BigDecimal low  = price.subtract(price.multiply(new BigDecimal("0.002")));
            candles.add(candle(base, idx++, price, high, low, price));
        }

        // 2단계: 지속 상승 (Supertrend 상승 추세 확립)
        for (int i = 0; i < 30; i++) {
            BigDecimal close = price.add(price.multiply(new BigDecimal("0.012")));
            BigDecimal high  = close.add(price.multiply(new BigDecimal("0.003")));
            BigDecimal low   = price.subtract(price.multiply(new BigDecimal("0.001")));
            candles.add(candle(base, idx++, price, high, low, close));
            price = close;
        }

        // 3단계: 가속 상승 (Bollinger 상단 밴드 이탈 유발)
        for (int i = 0; i < 5; i++) {
            BigDecimal close = price.add(price.multiply(new BigDecimal("0.030")));
            BigDecimal high  = close.add(price.multiply(new BigDecimal("0.005")));
            BigDecimal low   = price.subtract(price.multiply(new BigDecimal("0.002")));
            candles.add(candle(base, idx++, price, high, low, close));
            price = close;
        }

        return candles;
    }

    /**
     * 횡보(20) → 지속 하락(30, -1.2%/캔들) → 가속 하락(5, -3%/캔들)
     */
    private List<Candle> buildStrongDownTrendCandles() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("50000000");
        int idx = 0;

        // 1단계: 횡보 안정 구간
        for (int i = 0; i < 20; i++) {
            BigDecimal high = price.add(price.multiply(new BigDecimal("0.002")));
            BigDecimal low  = price.subtract(price.multiply(new BigDecimal("0.002")));
            candles.add(candle(base, idx++, price, high, low, price));
        }

        // 2단계: 지속 하락 (Supertrend 하락 추세 확립)
        for (int i = 0; i < 30; i++) {
            BigDecimal close = price.subtract(price.multiply(new BigDecimal("0.012")));
            BigDecimal high  = price.add(price.multiply(new BigDecimal("0.001")));
            BigDecimal low   = close.subtract(price.multiply(new BigDecimal("0.003")));
            candles.add(candle(base, idx++, price, high, low, close));
            price = close;
        }

        // 3단계: 가속 하락 (Bollinger 하단 밴드 이탈 유발)
        for (int i = 0; i < 5; i++) {
            BigDecimal close = price.subtract(price.multiply(new BigDecimal("0.030")));
            BigDecimal high  = price.add(price.multiply(new BigDecimal("0.002")));
            BigDecimal low   = close.subtract(price.multiply(new BigDecimal("0.005")));
            candles.add(candle(base, idx++, price, high, low, close));
            price = close;
        }

        return candles;
    }

    private Candle candle(Instant base, int idx,
                          BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        return Candle.builder()
                .time(base.plus(idx, ChronoUnit.HOURS))
                .open(open).high(high).low(low).close(close)
                .volume(BigDecimal.valueOf(200))
                .build();
    }
}
