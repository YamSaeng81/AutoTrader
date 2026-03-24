package com.cryptoautotrader.strategy.volumedelta;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Volume Delta (누적 볼륨 델타) 전략
 *
 * <p>각 캔들의 매수/매도 주도 볼륨 차이(Delta)를 누적하여 시장 압력의 방향과 강도를 측정한다.
 *
 * <p>볼륨 분해 (Tick Rule 근사):
 *   상승비율 = (close - low) / (high - low + epsilon)
 *   매수 볼륨 = volume × 상승비율
 *   매도 볼륨 = volume × (1 - 상승비율)
 *   캔들 Delta = 매수 볼륨 - 매도 볼륨
 *
 * <p>신호 조건:
 *   누적Delta비율 = sum(Delta) / sum(volume) 로 정규화
 *   누적Delta비율 > +signalThreshold  → BUY  (매수 압력 우세)
 *   누적Delta비율 < -signalThreshold  → SELL (매도 압력 우세)
 *   그 외                             → HOLD
 *
 * <p>다이버전스 필터 (divergenceMode=true일 때):
 *   가격 방향과 누적 Delta 방향이 반대인 경우 반전 가능성을 감지한다.
 *   가격 상승 + 누적Delta 음수 → 약세 다이버전스 → BUY 신호를 HOLD로 격하
 *   가격 하락 + 누적Delta 양수 → 강세 다이버전스 → SELL 신호를 HOLD로 격하
 *   (Delta가 신호 임계값을 넘지 않은 경우에는 다이버전스 역방향 신호를 발생시키지 않는다)
 *
 * <p>Delta 추세 확인 필터:
 *   lookback 구간을 전반부/후반부로 나눠 평균 Delta를 비교한다.
 *   BUY  발동: 후반부 평균 Delta > 전반부 평균 Delta (매수 압력이 강화되는 추세)
 *   SELL 발동: 후반부 평균 Delta < 전반부 평균 Delta (매도 압력이 강화되는 추세)
 *   미충족 시 HOLD로 격하한다.
 */
public class VolumeDeltaStrategy implements Strategy {

    private static final int SCALE = 8;
    private static final BigDecimal EPSILON = BigDecimal.valueOf(1e-10);

    @Override
    public String getName() {
        return "VOLUME_DELTA";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int lookback        = getInt(params,    "lookback",        20);
        double threshold    = getDouble(params, "signalThreshold", 0.10);
        boolean divMode     = getBool(params,   "divergenceMode",  true);

        if (candles.size() < lookback) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + lookback);
        }

        int start = candles.size() - lookback;
        List<Candle> window = candles.subList(start, candles.size());

        // 각 캔들 Delta 계산
        BigDecimal[] deltas     = new BigDecimal[lookback];
        BigDecimal totalVolume  = BigDecimal.ZERO;
        BigDecimal cumDelta     = BigDecimal.ZERO;

        for (int i = 0; i < lookback; i++) {
            Candle c  = window.get(i);
            BigDecimal hl = c.getHigh().subtract(c.getLow()).abs().add(EPSILON);
            BigDecimal buyRatio = c.getClose().subtract(c.getLow())
                    .divide(hl, SCALE, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO)
                    .min(BigDecimal.ONE);
            BigDecimal buyVol  = c.getVolume().multiply(buyRatio);
            BigDecimal sellVol = c.getVolume().multiply(BigDecimal.ONE.subtract(buyRatio));
            deltas[i]   = buyVol.subtract(sellVol);
            cumDelta    = cumDelta.add(deltas[i]);
            totalVolume = totalVolume.add(c.getVolume());
        }

        if (totalVolume.compareTo(BigDecimal.ZERO) == 0) {
            return StrategySignal.hold("볼륨 데이터 없음");
        }

        // 누적 Delta 정규화 비율 (-1.0 ~ +1.0)
        BigDecimal cumDeltaRatio = cumDelta.divide(totalVolume, SCALE, RoundingMode.HALF_UP);

        // Delta 추세 확인 필터: 전반부 vs 후반부 평균 Delta 비교
        int halfPoint       = lookback / 2;
        int secondHalfCount = lookback - halfPoint;
        BigDecimal firstSum  = BigDecimal.ZERO;
        BigDecimal secondSum = BigDecimal.ZERO;
        for (int i = 0; i < lookback; i++) {
            if (i < halfPoint) firstSum  = firstSum.add(deltas[i]);
            else               secondSum = secondSum.add(deltas[i]);
        }
        BigDecimal firstAvg  = halfPoint > 0
                ? firstSum.divide(BigDecimal.valueOf(halfPoint),  SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal secondAvg = secondHalfCount > 0
                ? secondSum.divide(BigDecimal.valueOf(secondHalfCount), SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        boolean deltaStrengthening = secondAvg.compareTo(firstAvg) > 0;   // 매수 압력 강화
        boolean deltaWeakening     = secondAvg.compareTo(firstAvg) < 0;   // 매도 압력 강화

        // 가격 변화 방향 (다이버전스 감지용)
        BigDecimal firstClose = window.get(0).getClose();
        BigDecimal lastClose  = window.get(lookback - 1).getClose();
        boolean priceUp   = lastClose.compareTo(firstClose) > 0;
        boolean priceDown = lastClose.compareTo(firstClose) < 0;

        BigDecimal thresholdBd = BigDecimal.valueOf(threshold);
        BigDecimal negThreshold = thresholdBd.negate();

        // BUY 신호 평가
        if (cumDeltaRatio.compareTo(thresholdBd) >= 0) {
            // 추세 확인: 매수 압력이 강화되는 추세여야 함
            if (!deltaStrengthening) {
                return StrategySignal.hold(String.format(
                        "누적Delta 매수 우세이나 Delta 약화: ratio=%.4f (전반부Δavg=%.4f, 후반부Δavg=%.4f)",
                        cumDeltaRatio, firstAvg, secondAvg));
            }
            // 다이버전스 필터: 가격이 하락 중인데 Delta가 양수이면 HOLD
            if (divMode && priceDown) {
                return StrategySignal.hold(String.format(
                        "강세 다이버전스 감지(매수 보류): 가격↓ / 누적Delta=%.4f > 0 [lookback=%d]",
                        cumDeltaRatio, lookback));
            }
            BigDecimal strength = cumDeltaRatio.subtract(thresholdBd)
                    .divide(BigDecimal.ONE.subtract(thresholdBd), SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .min(BigDecimal.valueOf(100));
            return StrategySignal.buy(strength, String.format(
                    "누적Delta 매수 우세: ratio=%.4f > %.2f (전반부Δavg=%.4f, 후반부Δavg=%.4f, lookback=%d)",
                    cumDeltaRatio, threshold, firstAvg, secondAvg, lookback));
        }

        // SELL 신호 평가
        if (cumDeltaRatio.compareTo(negThreshold) <= 0) {
            // 추세 확인: 매도 압력이 강화되는 추세여야 함
            if (!deltaWeakening) {
                return StrategySignal.hold(String.format(
                        "누적Delta 매도 우세이나 Delta 약화: ratio=%.4f (전반부Δavg=%.4f, 후반부Δavg=%.4f)",
                        cumDeltaRatio, firstAvg, secondAvg));
            }
            // 다이버전스 필터: 가격이 상승 중인데 Delta가 음수이면 HOLD
            if (divMode && priceUp) {
                return StrategySignal.hold(String.format(
                        "약세 다이버전스 감지(매도 보류): 가격↑ / 누적Delta=%.4f < 0 [lookback=%d]",
                        cumDeltaRatio, lookback));
            }
            BigDecimal strength = negThreshold.subtract(cumDeltaRatio)
                    .divide(BigDecimal.ONE.subtract(thresholdBd), SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .min(BigDecimal.valueOf(100));
            return StrategySignal.sell(strength, String.format(
                    "누적Delta 매도 우세: ratio=%.4f < -%.2f (전반부Δavg=%.4f, 후반부Δavg=%.4f, lookback=%d)",
                    cumDeltaRatio, threshold, firstAvg, secondAvg, lookback));
        }

        return StrategySignal.hold(String.format(
                "누적Delta 중립: ratio=%.4f (임계값=±%.2f, lookback=%d)",
                cumDeltaRatio, threshold, lookback));
    }

    @Override
    public int getMinimumCandleCount() {
        return 20;
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : defaultVal;
    }

    private boolean getBool(Map<String, Object> params, String key, boolean defaultVal) {
        Object v = params.get(key);
        return v instanceof Boolean ? (Boolean) v : defaultVal;
    }
}
