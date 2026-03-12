package com.cryptoautotrader.strategy.rsi;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RSI (Relative Strength Index) 전략
 * - RSI < oversoldLevel  → BUY  (과매도: 반등 기대)
 * - RSI > overboughtLevel → SELL (과매수: 하락 기대)
 * - 그 외 → HOLD
 *
 * 고급 옵션: RSI 다이버전스 감지
 *   - 가격이 신저점을 기록하는데 RSI는 이전 저점보다 높음 → 강한 BUY (강세 다이버전스)
 *   - 가격이 신고점을 기록하는데 RSI는 이전 고점보다 낮음 → 강한 SELL (약세 다이버전스)
 */
public class RsiStrategy implements Strategy {

    private static final int SCALE = 8;

    @Override
    public String getName() {
        return "RSI";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int period = getInt(params, "period", 14);
        double oversoldLevel = getDouble(params, "oversoldLevel", 30.0);
        double overboughtLevel = getDouble(params, "overboughtLevel", 70.0);
        boolean useDivergence = getBoolean(params, "useDivergence", true);
        // 다이버전스 감지에 사용할 이전 RSI 기간 (현재 RSI 계산 시점보다 lookback 이전)
        int divergenceLookback = getInt(params, "divergenceLookback", 5);

        // RSI 계산에는 period+1개 가격 차이가 필요하므로 최소 period+1개 캔들 필요
        // 다이버전스 감지를 위해 divergenceLookback만큼 추가 데이터 필요
        int minRequired = period + 1 + (useDivergence ? divergenceLookback : 0);
        if (candles.size() < minRequired) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + minRequired);
        }

        List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();

        // 현재 RSI 계산
        BigDecimal currentRsi = calculateRsi(closes, period);
        BigDecimal oversold = BigDecimal.valueOf(oversoldLevel);
        BigDecimal overbought = BigDecimal.valueOf(overboughtLevel);

        // 다이버전스 감지
        if (useDivergence && closes.size() >= period + 1 + divergenceLookback) {
            List<BigDecimal> prevCloses = closes.subList(0, closes.size() - divergenceLookback);
            BigDecimal prevRsi = calculateRsi(prevCloses, period);

            BigDecimal currentPrice = closes.get(closes.size() - 1);
            BigDecimal prevPrice = closes.get(closes.size() - 1 - divergenceLookback);

            // 강세 다이버전스: 가격 신저점 & RSI 신저점 아님 → 강한 BUY
            boolean priceMakesLowerLow = currentPrice.compareTo(prevPrice) < 0;
            boolean rsiMakesHigherLow = currentRsi.compareTo(prevRsi) > 0;
            if (priceMakesLowerLow && rsiMakesHigherLow && currentRsi.compareTo(oversold.add(BigDecimal.TEN)) < 0) {
                BigDecimal strength = BigDecimal.valueOf(100).subtract(currentRsi)
                        .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .min(BigDecimal.valueOf(100));
                return StrategySignal.buy(strength,
                        String.format("강세 다이버전스: 가격=%.2f(↓) RSI=%.2f(↑ from %.2f)",
                                currentPrice, currentRsi, prevRsi));
            }

            // 약세 다이버전스: 가격 신고점 & RSI 신고점 아님 → 강한 SELL
            boolean priceMakesHigherHigh = currentPrice.compareTo(prevPrice) > 0;
            boolean rsiMakesLowerHigh = currentRsi.compareTo(prevRsi) < 0;
            if (priceMakesHigherHigh && rsiMakesLowerHigh && currentRsi.compareTo(overbought.subtract(BigDecimal.TEN)) > 0) {
                BigDecimal strength = currentRsi
                        .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .min(BigDecimal.valueOf(100));
                return StrategySignal.sell(strength,
                        String.format("약세 다이버전스: 가격=%.2f(↑) RSI=%.2f(↓ from %.2f)",
                                currentPrice, currentRsi, prevRsi));
            }
        }

        // 기본 과매도/과매수 신호
        if (currentRsi.compareTo(oversold) < 0) {
            // 신호 강도: 과매도 수준이 낮을수록 강도 증가 (0에 가까울수록 100)
            BigDecimal strength = oversold.subtract(currentRsi)
                    .divide(oversold, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .min(BigDecimal.valueOf(100));
            return StrategySignal.buy(strength,
                    String.format("RSI 과매도: %.2f < %.1f", currentRsi, oversoldLevel));
        }

        if (currentRsi.compareTo(overbought) > 0) {
            // 신호 강도: 과매수 수준이 높을수록 강도 증가 (100에 가까울수록 100)
            BigDecimal strength = currentRsi.subtract(overbought)
                    .divide(BigDecimal.valueOf(100).subtract(overbought), SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .min(BigDecimal.valueOf(100));
            return StrategySignal.sell(strength,
                    String.format("RSI 과매수: %.2f > %.1f", currentRsi, overboughtLevel));
        }

        return StrategySignal.hold(String.format("RSI 중립: %.2f (과매도=%.1f, 과매수=%.1f)",
                currentRsi, oversoldLevel, overboughtLevel));
    }

    @Override
    public int getMinimumCandleCount() {
        // period(14) + 1 + divergenceLookback(5) = 20
        return 20;
    }

    /**
     * RSI 계산 (Wilder's Smoothing 방식)
     * RS = 평균 상승폭 / 평균 하락폭
     * RSI = 100 - (100 / (1 + RS))
     */
    private BigDecimal calculateRsi(List<BigDecimal> closes, int period) {
        // 가격 변화량 계산
        List<BigDecimal> gains = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();

        for (int i = 1; i < closes.size(); i++) {
            BigDecimal change = closes.get(i).subtract(closes.get(i - 1));
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                gains.add(change);
                losses.add(BigDecimal.ZERO);
            } else {
                gains.add(BigDecimal.ZERO);
                losses.add(change.abs());
            }
        }

        if (gains.size() < period) {
            return BigDecimal.valueOf(50); // 데이터 부족 시 중립값 반환
        }

        // 초기 평균 상승/하락 (단순 평균)
        BigDecimal avgGain = BigDecimal.ZERO;
        BigDecimal avgLoss = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            avgGain = avgGain.add(gains.get(i));
            avgLoss = avgLoss.add(losses.get(i));
        }
        avgGain = avgGain.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        avgLoss = avgLoss.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);

        // Wilder's Smoothing으로 이후 값 계산
        BigDecimal periodBD = BigDecimal.valueOf(period);
        for (int i = period; i < gains.size(); i++) {
            avgGain = avgGain.multiply(periodBD.subtract(BigDecimal.ONE))
                    .add(gains.get(i))
                    .divide(periodBD, SCALE, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(periodBD.subtract(BigDecimal.ONE))
                    .add(losses.get(i))
                    .divide(periodBD, SCALE, RoundingMode.HALF_UP);
        }

        // avgLoss가 0이면 RSI = 100 (완전 상승)
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(100);
        }

        BigDecimal rs = avgGain.divide(avgLoss, SCALE, RoundingMode.HALF_UP);
        // RSI = 100 - (100 / (1 + RS))
        BigDecimal rsi = BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100)
                        .divide(BigDecimal.ONE.add(rs), SCALE, RoundingMode.HALF_UP));
        return rsi.setScale(2, RoundingMode.HALF_UP);
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : defaultVal;
    }

    private boolean getBoolean(Map<String, Object> params, String key, boolean defaultVal) {
        Object v = params.get(key);
        return v instanceof Boolean ? (Boolean) v : defaultVal;
    }
}
