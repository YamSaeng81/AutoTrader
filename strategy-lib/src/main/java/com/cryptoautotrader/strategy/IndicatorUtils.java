package com.cryptoautotrader.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 기술적 지표 계산 유틸리티 (전략에서 공통 사용)
 */
public final class IndicatorUtils {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final int SCALE = 8;

    private IndicatorUtils() {}

    public static BigDecimal sma(List<BigDecimal> values, int period) {
        if (values.size() < period) {
            throw new IllegalArgumentException("데이터 부족: " + values.size() + " < " + period);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = values.size() - period; i < values.size(); i++) {
            sum = sum.add(values.get(i));
        }
        return sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal ema(List<BigDecimal> values, int period) {
        if (values.size() < period) {
            throw new IllegalArgumentException("데이터 부족: " + values.size() + " < " + period);
        }
        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1));
        BigDecimal oneMinusMult = BigDecimal.ONE.subtract(multiplier);

        // 첫 EMA = SMA
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(values.get(i));
        }
        BigDecimal emaVal = sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);

        for (int i = period; i < values.size(); i++) {
            emaVal = values.get(i).multiply(multiplier, MC)
                    .add(emaVal.multiply(oneMinusMult, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
        return emaVal;
    }

    public static BigDecimal standardDeviation(List<BigDecimal> values, int period) {
        if (values.size() < period) {
            throw new IllegalArgumentException("데이터 부족");
        }
        BigDecimal mean = sma(values, period);
        BigDecimal sumSq = BigDecimal.ZERO;
        for (int i = values.size() - period; i < values.size(); i++) {
            BigDecimal diff = values.get(i).subtract(mean);
            sumSq = sumSq.add(diff.multiply(diff, MC));
        }
        BigDecimal variance = sumSq.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal atr(List<Candle> candles, int period) {
        if (candles.size() < period + 1) {
            throw new IllegalArgumentException("ATR 계산에 데이터 부족");
        }
        BigDecimal atrVal = BigDecimal.ZERO;

        // 초기 ATR = 첫 period개 TR의 평균
        for (int i = 1; i <= period; i++) {
            atrVal = atrVal.add(trueRange(candles.get(i), candles.get(i - 1)));
        }
        atrVal = atrVal.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);

        // 이후 EMA 방식
        for (int i = period + 1; i < candles.size(); i++) {
            BigDecimal tr = trueRange(candles.get(i), candles.get(i - 1));
            atrVal = atrVal.multiply(BigDecimal.valueOf(period - 1))
                    .add(tr)
                    .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        }
        return atrVal;
    }

    public static BigDecimal trueRange(Candle current, Candle previous) {
        BigDecimal hl = current.getHigh().subtract(current.getLow()).abs();
        BigDecimal hc = current.getHigh().subtract(previous.getClose()).abs();
        BigDecimal lc = current.getLow().subtract(previous.getClose()).abs();
        return hl.max(hc).max(lc);
    }

    /**
     * Bollinger Bandwidth: (2 × multiplier × stdDev) / sma × 100
     * 마지막 period개 closes를 기준으로 계산한다.
     */
    public static BigDecimal bollingerBandwidth(List<BigDecimal> closes, int period, double multiplier) {
        if (closes.size() < period) {
            throw new IllegalArgumentException("데이터 부족: " + closes.size() + " < " + period);
        }
        BigDecimal mean = sma(closes, period);
        if (mean.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal stdDev = standardDeviation(closes, period);
        return stdDev.multiply(BigDecimal.valueOf(multiplier * 2))
                .divide(mean, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * 최근 count개의 rolling Bollinger Bandwidth 값을 반환한다.
     * 각 값은 크기 period인 창에서 계산된다.
     */
    public static List<BigDecimal> bollingerBandwidths(List<BigDecimal> closes, int period,
                                                        double multiplier, int count) {
        List<BigDecimal> result = new ArrayList<>();
        int numWindows = closes.size() - period + 1;
        if (numWindows <= 0) return result;
        int startIdx = Math.max(0, numWindows - count);
        for (int i = startIdx; i < numWindows; i++) {
            result.add(bollingerBandwidth(closes.subList(i, i + period), period, multiplier));
        }
        return result;
    }

    /**
     * Wilder 평활 ATR 시계열을 반환한다. candles.size() - period 개의 값을 반환한다.
     * 인덱스 0 = period번째 캔들 기준 ATR, 마지막 = 최신 ATR.
     */
    public static List<BigDecimal> atrList(List<Candle> candles, int period) {
        List<BigDecimal> result = new ArrayList<>();
        if (candles.size() < period + 1) return result;

        BigDecimal atrVal = BigDecimal.ZERO;
        for (int i = 1; i <= period; i++) {
            atrVal = atrVal.add(trueRange(candles.get(i), candles.get(i - 1)));
        }
        atrVal = atrVal.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        result.add(atrVal);

        for (int i = period + 1; i < candles.size(); i++) {
            BigDecimal tr = trueRange(candles.get(i), candles.get(i - 1));
            atrVal = atrVal.multiply(BigDecimal.valueOf(period - 1))
                    .add(tr)
                    .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
            result.add(atrVal);
        }
        return result;
    }

    public static BigDecimal adx(List<Candle> candles, int period) {
        if (candles.size() < period * 2 + 1) {
            throw new IllegalArgumentException("ADX 계산에 데이터 부족");
        }

        BigDecimal smoothedPlusDM = BigDecimal.ZERO;
        BigDecimal smoothedMinusDM = BigDecimal.ZERO;
        BigDecimal smoothedTR = BigDecimal.ZERO;

        // 초기 합산
        for (int i = 1; i <= period; i++) {
            Candle curr = candles.get(i);
            Candle prev = candles.get(i - 1);
            BigDecimal plusDM = curr.getHigh().subtract(prev.getHigh());
            BigDecimal minusDM = prev.getLow().subtract(curr.getLow());

            if (plusDM.compareTo(minusDM) > 0 && plusDM.compareTo(BigDecimal.ZERO) > 0) {
                smoothedPlusDM = smoothedPlusDM.add(plusDM);
            }
            if (minusDM.compareTo(plusDM) > 0 && minusDM.compareTo(BigDecimal.ZERO) > 0) {
                smoothedMinusDM = smoothedMinusDM.add(minusDM);
            }
            smoothedTR = smoothedTR.add(trueRange(curr, prev));
        }

        BigDecimal periodBD = BigDecimal.valueOf(period);
        BigDecimal dxSum = BigDecimal.ZERO;
        int dxCount = 0;

        for (int i = period + 1; i < candles.size(); i++) {
            Candle curr = candles.get(i);
            Candle prev = candles.get(i - 1);

            BigDecimal tr = trueRange(curr, prev);
            BigDecimal plusDM = curr.getHigh().subtract(prev.getHigh());
            BigDecimal minusDM = prev.getLow().subtract(curr.getLow());

            BigDecimal actualPlusDM = BigDecimal.ZERO;
            BigDecimal actualMinusDM = BigDecimal.ZERO;
            if (plusDM.compareTo(minusDM) > 0 && plusDM.compareTo(BigDecimal.ZERO) > 0) {
                actualPlusDM = plusDM;
            }
            if (minusDM.compareTo(plusDM) > 0 && minusDM.compareTo(BigDecimal.ZERO) > 0) {
                actualMinusDM = minusDM;
            }

            smoothedTR = smoothedTR.subtract(smoothedTR.divide(periodBD, SCALE, RoundingMode.HALF_UP)).add(tr);
            smoothedPlusDM = smoothedPlusDM.subtract(smoothedPlusDM.divide(periodBD, SCALE, RoundingMode.HALF_UP)).add(actualPlusDM);
            smoothedMinusDM = smoothedMinusDM.subtract(smoothedMinusDM.divide(periodBD, SCALE, RoundingMode.HALF_UP)).add(actualMinusDM);

            if (smoothedTR.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal plusDI = smoothedPlusDM.divide(smoothedTR, SCALE, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            BigDecimal minusDI = smoothedMinusDM.divide(smoothedTR, SCALE, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

            BigDecimal diSum = plusDI.add(minusDI);
            if (diSum.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal dx = plusDI.subtract(minusDI).abs()
                    .divide(diSum, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            dxSum = dxSum.add(dx);
            dxCount++;
        }

        if (dxCount == 0) return BigDecimal.ZERO;
        return dxSum.divide(BigDecimal.valueOf(dxCount), SCALE, RoundingMode.HALF_UP);
    }
}
