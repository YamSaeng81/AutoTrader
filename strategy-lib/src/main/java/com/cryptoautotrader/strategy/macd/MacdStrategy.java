package com.cryptoautotrader.strategy.macd;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MACD (Moving Average Convergence Divergence) 전략
 * - MACD선이 Signal선을 상향 돌파 → BUY  (골든크로스)
 * - MACD선이 Signal선을 하향 돌파 → SELL (데드크로스)
 * - 히스토그램(MACD - Signal) 방향으로 신호 강도 보정
 *
 * MACD선  = EMA(fast) - EMA(slow)
 * Signal선 = MACD선의 EMA(signalPeriod)
 * 히스토그램 = MACD선 - Signal선
 */
public class MacdStrategy implements Strategy {

    private static final int SCALE = 8;
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    @Override
    public String getName() {
        return "MACD";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int fastPeriod = getInt(params, "fastPeriod", 12);
        int slowPeriod = getInt(params, "slowPeriod", 26);
        int signalPeriod = getInt(params, "signalPeriod", 9);

        // MACD 계산에 필요한 최소 캔들 수:
        // slowPeriod개로 첫 EMA(slow) 계산, 이후 signalPeriod개의 MACD값으로 Signal EMA 계산
        // 크로스 감지를 위해 이전 시점 Signal도 필요하므로 +1
        int minRequired = slowPeriod + signalPeriod + 1;
        if (candles.size() < minRequired) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + minRequired);
        }

        List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();

        // 현재 시점과 이전 시점의 MACD 값 계산 (크로스 감지용)
        MacdValues current = calculateMacd(closes, fastPeriod, slowPeriod, signalPeriod);
        MacdValues prev = calculateMacd(closes.subList(0, closes.size() - 1), fastPeriod, slowPeriod, signalPeriod);

        BigDecimal currentHistogram = current.macdLine.subtract(current.signalLine);
        BigDecimal prevHistogram = prev.macdLine.subtract(prev.signalLine);

        // 크로스 감지
        boolean currentAbove = current.macdLine.compareTo(current.signalLine) > 0;
        boolean prevAbove = prev.macdLine.compareTo(prev.signalLine) > 0;

        if (currentAbove && !prevAbove) {
            // 골든크로스: MACD선이 Signal선을 상향 돌파
            // 히스토그램이 커질수록 신호 강도 증가
            BigDecimal strength = calculateStrength(currentHistogram, current.signalLine);
            return StrategySignal.buy(strength,
                    String.format("MACD 골든크로스: MACD=%.6f, Signal=%.6f, Histogram=%.6f",
                            current.macdLine, current.signalLine, currentHistogram));
        }

        if (!currentAbove && prevAbove) {
            // 데드크로스: MACD선이 Signal선을 하향 돌파
            BigDecimal strength = calculateStrength(currentHistogram.abs(), current.signalLine.abs());
            return StrategySignal.sell(strength,
                    String.format("MACD 데드크로스: MACD=%.6f, Signal=%.6f, Histogram=%.6f",
                            current.macdLine, current.signalLine, currentHistogram));
        }

        // 크로스 없음: 히스토그램 방향 언급
        String histDir = currentHistogram.compareTo(prevHistogram) > 0 ? "확대" : "축소";
        return StrategySignal.hold(String.format("MACD 크로스 없음: MACD=%.6f, Signal=%.6f, Histogram=%s(%.6f)",
                current.macdLine, current.signalLine, histDir, currentHistogram));
    }

    @Override
    public int getMinimumCandleCount() {
        // slowPeriod(26) + signalPeriod(9) + 1 = 36
        return 36;
    }

    /**
     * MACD선과 Signal선을 계산하여 반환
     * MACD는 전체 데이터를 순차 스캔하여 정확한 EMA를 계산한다.
     */
    private MacdValues calculateMacd(List<BigDecimal> closes, int fastPeriod, int slowPeriod, int signalPeriod) {
        // 각 시점의 MACD선 값 계산 (slowPeriod 이후부터 계산 가능)
        List<BigDecimal> macdLines = new ArrayList<>();

        // EMA 계산: 초기 SMA 이후 EMA 방식으로 누적
        BigDecimal fastMultiplier = BigDecimal.valueOf(2.0 / (fastPeriod + 1));
        BigDecimal slowMultiplier = BigDecimal.valueOf(2.0 / (slowPeriod + 1));
        BigDecimal oneMinusFast = BigDecimal.ONE.subtract(fastMultiplier);
        BigDecimal oneMinusSlow = BigDecimal.ONE.subtract(slowMultiplier);

        // 초기 EMA(fast) = 첫 fastPeriod개의 SMA
        BigDecimal fastEma = BigDecimal.ZERO;
        for (int i = 0; i < fastPeriod; i++) {
            fastEma = fastEma.add(closes.get(i));
        }
        fastEma = fastEma.divide(BigDecimal.valueOf(fastPeriod), SCALE, RoundingMode.HALF_UP);

        // 초기 EMA(slow) = 첫 slowPeriod개의 SMA
        BigDecimal slowEma = BigDecimal.ZERO;
        for (int i = 0; i < slowPeriod; i++) {
            slowEma = slowEma.add(closes.get(i));
        }
        slowEma = slowEma.divide(BigDecimal.valueOf(slowPeriod), SCALE, RoundingMode.HALF_UP);

        // fastPeriod 이후부터 fast EMA 갱신, slowPeriod 이후부터 MACD 계산 시작
        for (int i = fastPeriod; i < slowPeriod; i++) {
            fastEma = closes.get(i).multiply(fastMultiplier, MC)
                    .add(fastEma.multiply(oneMinusFast, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }

        // slowPeriod 도달: 첫 MACD 기록
        macdLines.add(fastEma.subtract(slowEma));

        // slowPeriod 이후: 두 EMA 모두 갱신하며 MACD 계산
        for (int i = slowPeriod; i < closes.size(); i++) {
            fastEma = closes.get(i).multiply(fastMultiplier, MC)
                    .add(fastEma.multiply(oneMinusFast, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            slowEma = closes.get(i).multiply(slowMultiplier, MC)
                    .add(slowEma.multiply(oneMinusSlow, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            macdLines.add(fastEma.subtract(slowEma));
        }

        // Signal선 = MACD선의 EMA(signalPeriod)
        BigDecimal signalLine = calculateEmaFromList(macdLines, signalPeriod);
        BigDecimal currentMacdLine = macdLines.get(macdLines.size() - 1);

        return new MacdValues(currentMacdLine, signalLine);
    }

    /**
     * 리스트의 마지막 값을 기준으로 EMA를 계산한다.
     */
    private BigDecimal calculateEmaFromList(List<BigDecimal> values, int period) {
        if (values.size() < period) {
            // 데이터 부족 시 단순 평균 반환
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal v : values) sum = sum.add(v);
            return sum.divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1));
        BigDecimal oneMinusMult = BigDecimal.ONE.subtract(multiplier);

        // 초기 EMA = 첫 period개의 SMA
        BigDecimal ema = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            ema = ema.add(values.get(i));
        }
        ema = ema.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);

        for (int i = period; i < values.size(); i++) {
            ema = values.get(i).multiply(multiplier, MC)
                    .add(ema.multiply(oneMinusMult, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
        return ema;
    }

    /**
     * 히스토그램 절댓값 대비 Signal선 크기로 신호 강도(0~100) 계산
     */
    private BigDecimal calculateStrength(BigDecimal histogramAbs, BigDecimal referenceAbs) {
        if (referenceAbs.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(50);
        }
        return histogramAbs
                .divide(referenceAbs.abs(), SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(1000))
                .min(BigDecimal.valueOf(100));
    }

    /** MACD 계산 결과를 담는 내부 레코드 */
    private static class MacdValues {
        final BigDecimal macdLine;
        final BigDecimal signalLine;

        MacdValues(BigDecimal macdLine, BigDecimal signalLine) {
            this.macdLine = macdLine;
            this.signalLine = signalLine;
        }
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }
}
