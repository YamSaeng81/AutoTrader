package com.cryptoautotrader.strategy.macd;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyParamUtils;
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

    /**
     * 코인별 기본 파라미터: BTC (14,22) / ETH (10,26) / 그외 (12,24)
     * 그리드 서치 결과 기반 (2024~2025 H1)
     */
    private static int[] coinDefaults(Map<String, Object> params) {
        Object cp = params != null ? params.get("coinPair") : null;
        if (cp instanceof String coinPair) {
            if (coinPair.contains("BTC")) return new int[]{14, 22};
            if (coinPair.contains("ETH")) return new int[]{10, 26};
        }
        return new int[]{12, 24};
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int[] defaults   = coinDefaults(params);
        int fastPeriod   = StrategyParamUtils.getInt(params, "fastPeriod",   defaults[0]);
        int slowPeriod   = StrategyParamUtils.getInt(params, "slowPeriod",   defaults[1]);
        int signalPeriod = StrategyParamUtils.getInt(params, "signalPeriod", 9);
        int adxPeriod    = StrategyParamUtils.getInt(params,    "adxPeriod",     14);
        double adxThreshold = StrategyParamUtils.getDouble(params, "adxThreshold", 25.0);

        // MACD 계산에 필요한 최소 캔들 수:
        // slowPeriod개로 첫 EMA(slow) 계산, 이후 signalPeriod개의 MACD값으로 Signal EMA 계산
        // 크로스 감지를 위해 이전 시점 Signal도 필요하므로 +1
        int minRequired = slowPeriod + signalPeriod + 1;
        if (candles.size() < minRequired) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + minRequired);
        }

        // ADX 필터: MACD는 추세 추종 전략, 추세가 충분히 강한 경우에만 진입
        if (adxThreshold > 0 && candles.size() >= adxPeriod * 2 + 1) {
            BigDecimal adx = IndicatorUtils.adx(candles, adxPeriod);
            if (adx.compareTo(BigDecimal.valueOf(adxThreshold)) < 0) {
                return StrategySignal.hold(String.format(
                        "ADX 필터: 추세 약함 ADX=%.2f < %.0f (횡보장 크로스 억제)", adx, adxThreshold));
            }
        }

        List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();

        // 단일 패스로 현재·이전 MACD 값 동시 계산 (이중 재계산 방지)
        MacdValues[] pair = calculateMacdPair(closes, fastPeriod, slowPeriod, signalPeriod);
        MacdValues prev    = pair[0];
        MacdValues current = pair[1];

        BigDecimal currentHistogram = current.macdLine.subtract(current.signalLine);
        BigDecimal prevHistogram    = prev.macdLine.subtract(prev.signalLine);

        // 크로스 감지
        boolean currentAbove = current.macdLine.compareTo(current.signalLine) > 0;
        boolean prevAbove    = prev.macdLine.compareTo(prev.signalLine) > 0;

        if (currentAbove && !prevAbove) {
            // 골든크로스: MACD선이 Signal선을 상향 돌파

            // 제로라인 필터: MACD선이 0선 위에 있을 때만 BUY (약세 구간 매수 방지)
            if (current.macdLine.compareTo(BigDecimal.ZERO) <= 0) {
                return StrategySignal.hold(String.format(
                        "MACD 골든크로스 필터: 0선 아래 크로스 무시 MACD=%.6f", current.macdLine));
            }
            // 히스토그램 확대 필터: 크로스 직후 히스토그램이 확대 중일 때만 BUY (가짜 크로스 방지)
            if (currentHistogram.compareTo(prevHistogram) <= 0) {
                return StrategySignal.hold(String.format(
                        "MACD 골든크로스 필터: 히스토그램 미확대 현재=%.6f 이전=%.6f", currentHistogram, prevHistogram));
            }

            BigDecimal strength = calculateStrength(currentHistogram, current.signalLine);
            return StrategySignal.buy(strength,
                    String.format("MACD 골든크로스: MACD=%.6f, Signal=%.6f, Histogram=%.6f(확대)",
                            current.macdLine, current.signalLine, currentHistogram));
        }

        if (!currentAbove && prevAbove) {
            // 데드크로스: MACD선이 Signal선을 하향 돌파

            // 제로라인 필터: MACD선이 0선 아래에 있을 때만 SELL (강세 구간 매도 방지)
            if (current.macdLine.compareTo(BigDecimal.ZERO) >= 0) {
                return StrategySignal.hold(String.format(
                        "MACD 데드크로스 필터: 0선 위 크로스 무시 MACD=%.6f", current.macdLine));
            }
            // 히스토그램 확대 필터: 히스토그램이 더 음수 방향으로 확대 중일 때만 SELL
            if (currentHistogram.compareTo(prevHistogram) >= 0) {
                return StrategySignal.hold(String.format(
                        "MACD 데드크로스 필터: 히스토그램 미확대 현재=%.6f 이전=%.6f", currentHistogram, prevHistogram));
            }

            BigDecimal strength = calculateStrength(currentHistogram.abs(), current.signalLine.abs());
            return StrategySignal.sell(strength,
                    String.format("MACD 데드크로스: MACD=%.6f, Signal=%.6f, Histogram=%.6f(확대)",
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
     * 단일 패스로 직전·현재 MACD 값을 계산한다.
     * [0] = prev, [1] = current
     */
    private MacdValues[] calculateMacdPair(List<BigDecimal> closes, int fastPeriod, int slowPeriod, int signalPeriod) {
        List<BigDecimal> macdLines = new ArrayList<>();

        BigDecimal fastMultiplier = BigDecimal.valueOf(2.0 / (fastPeriod + 1));
        BigDecimal slowMultiplier = BigDecimal.valueOf(2.0 / (slowPeriod + 1));
        BigDecimal oneMinusFast   = BigDecimal.ONE.subtract(fastMultiplier);
        BigDecimal oneMinusSlow   = BigDecimal.ONE.subtract(slowMultiplier);

        // 초기 EMA(fast) = 첫 fastPeriod개의 SMA
        BigDecimal fastEma = BigDecimal.ZERO;
        for (int i = 0; i < fastPeriod; i++) fastEma = fastEma.add(closes.get(i));
        fastEma = fastEma.divide(BigDecimal.valueOf(fastPeriod), SCALE, RoundingMode.HALF_UP);

        // 초기 EMA(slow) = 첫 slowPeriod개의 SMA
        BigDecimal slowEma = BigDecimal.ZERO;
        for (int i = 0; i < slowPeriod; i++) slowEma = slowEma.add(closes.get(i));
        slowEma = slowEma.divide(BigDecimal.valueOf(slowPeriod), SCALE, RoundingMode.HALF_UP);

        for (int i = fastPeriod; i < slowPeriod; i++) {
            fastEma = closes.get(i).multiply(fastMultiplier, MC)
                    .add(fastEma.multiply(oneMinusFast, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
        macdLines.add(fastEma.subtract(slowEma));

        for (int i = slowPeriod; i < closes.size(); i++) {
            fastEma = closes.get(i).multiply(fastMultiplier, MC)
                    .add(fastEma.multiply(oneMinusFast, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            slowEma = closes.get(i).multiply(slowMultiplier, MC)
                    .add(slowEma.multiply(oneMinusSlow, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            macdLines.add(fastEma.subtract(slowEma));
        }

        // prev signal = macdLines[0..n-2]의 EMA, current signal = prev signal에 한 스텝 더
        BigDecimal prevSignal = IndicatorUtils.ema(
                macdLines.subList(0, macdLines.size() - 1), signalPeriod);
        BigDecimal sigMult    = BigDecimal.valueOf(2.0 / (signalPeriod + 1));
        BigDecimal currentSignal = macdLines.get(macdLines.size() - 1)
                .multiply(sigMult, MC)
                .add(prevSignal.multiply(BigDecimal.ONE.subtract(sigMult), MC))
                .setScale(SCALE, RoundingMode.HALF_UP);

        return new MacdValues[]{
            new MacdValues(macdLines.get(macdLines.size() - 2), prevSignal),
            new MacdValues(macdLines.get(macdLines.size() - 1), currentSignal)
        };
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
            this.macdLine   = macdLine;
            this.signalLine = signalLine;
        }
    }
}
