package com.cryptoautotrader.strategy.rsi;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * RSI (Relative Strength Index) 전략
 * - RSI < oversoldLevel  → BUY  (과매도: 반등 기대)
 * - RSI > overboughtLevel → SELL (과매수: 하락 기대)
 * - 그 외 → HOLD
 *
 * <p>S4-4 피봇 기반 다이버전스 감지 (기존 고정-lookback → 스윙 고점/저점 탐색):
 *   - 가격 신저점(lower low) & RSI 고점(higher low) → 강세 다이버전스 BUY
 *   - 가격 신고점(higher high) & RSI 저점(lower high) → 약세 다이버전스 SELL
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
        int pivotWindow = getInt(params, "pivotWindow", 10);  // S4-4: 스윙 탐색 범위

        // RSI 계산에는 period+1개 가격 차이 필요
        if (candles.size() < period + 1) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + (period + 1));
        }

        List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();

        // RSI 시리즈 1회 계산 (이후 인덱스 룩업으로 재사용 — 중복 계산 방지)
        List<BigDecimal> rsiSeries = calculateRsiSeries(closes, period);
        BigDecimal currentRsi = rsiSeries.get(closes.size() - 1);
        BigDecimal oversold   = BigDecimal.valueOf(oversoldLevel);
        BigDecimal overbought = BigDecimal.valueOf(overboughtLevel);

        // S4-4 피봇 기반 다이버전스 감지
        if (useDivergence) {
            int currentIdx = closes.size() - 1;

            // 강세 다이버전스: 최근 스윙 저점 탐색 → 가격 Lower Low & RSI Higher Low
            int swingLowIdx = findRecentSwingLow(closes, currentIdx, pivotWindow);
            if (swingLowIdx >= period
                    && closes.get(currentIdx).compareTo(closes.get(swingLowIdx)) < 0) {
                BigDecimal swingLowRsi = rsiSeries.get(swingLowIdx);
                if (currentRsi.compareTo(swingLowRsi) > 0
                        && currentRsi.compareTo(oversold.add(BigDecimal.TEN)) < 0) {
                    BigDecimal strength = BigDecimal.valueOf(100).subtract(currentRsi)
                            .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .min(BigDecimal.valueOf(100));
                    return StrategySignal.buy(strength,
                            String.format("피봇 강세 다이버전스: 가격=%.2f(↓) RSI=%.2f(↑ from %.2f)",
                                    closes.get(currentIdx), currentRsi, swingLowRsi));
                }
            }

            // 약세 다이버전스: 최근 스윙 고점 탐색 → 가격 Higher High & RSI Lower High
            int swingHighIdx = findRecentSwingHigh(closes, currentIdx, pivotWindow);
            if (swingHighIdx >= period
                    && closes.get(currentIdx).compareTo(closes.get(swingHighIdx)) > 0) {
                BigDecimal swingHighRsi = rsiSeries.get(swingHighIdx);
                if (currentRsi.compareTo(swingHighRsi) < 0
                        && currentRsi.compareTo(overbought.subtract(BigDecimal.TEN)) > 0) {
                    BigDecimal strength = currentRsi
                            .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .min(BigDecimal.valueOf(100));
                    return StrategySignal.sell(strength,
                            String.format("피봇 약세 다이버전스: 가격=%.2f(↑) RSI=%.2f(↓ from %.2f)",
                                    closes.get(currentIdx), currentRsi, swingHighRsi));
                }
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
     * 전체 RSI 시리즈를 1-pass로 계산 (Wilder's Smoothing).
     * 반환 리스트는 closes와 동일한 크기이며, 유효 RSI가 없는 앞부분(0~period-1)은 BigDecimal.valueOf(50)으로 채운다.
     * evaluate()에서 인덱스 룩업으로 재사용해 중복 계산을 방지한다.
     */
    private List<BigDecimal> calculateRsiSeries(List<BigDecimal> closes, int period) {
        // 🔴 2026-09-22 (Wave 4) — RSI 계산을 IndicatorUtils 단일 출처로 모았다.
        //
        // 이전에는 이 클래스가 Wilder 평활과 rsiFromAvgs 를 **자체 구현**으로 들고 있었고,
        // IndicatorUtils.rsiSeries 와 수학이 완전히 같았다(SCALE=8, 최종 setScale(2)).
        // 그 복제의 대가가 Wave 3-H 에서 드러났다 — "무변동 구간에서 RSI 가 100(과매수)"
        // 이라는 **하나의 결함을 두 곳에 각각** 고쳐야 했다. 한쪽만 고쳤다면 전략에 따라
        // 같은 입력이 다른 RSI 를 보는 상태가 남았을 것이고, 그건 아무 신호도 주지 않는다.
        //
        // 정렬만 다르다: IndicatorUtils 는 첫 유효값부터 시작하는 compact 리스트를 주고,
        // 여기서는 closes 와 인덱스가 맞는 리스트가 필요하다(evaluate 가 인덱스 룩업을 한다).
        // 그 변환만 남긴다.
        int n = closes.size();
        List<BigDecimal> aligned =
                new ArrayList<>(java.util.Collections.nCopies(n, BigDecimal.valueOf(50)));

        // compact[k] 는 closes[period + k] 의 RSI 다.
        // 크기 검증: compact.size() = n - period, 채우는 인덱스는 period..n-1 로 정확히 일치한다.
        // closes.size() <= period 이면 compact 가 비어 있어 전부 50 으로 남는다 — 구 구현과 같다.
        List<BigDecimal> compact = IndicatorUtils.rsiSeries(closes, period);
        for (int k = 0; k < compact.size(); k++) {
            aligned.set(period + k, compact.get(k));
        }
        return aligned;
    }

    /**
     * fromIdx 이전에서 가장 최근 스윙 저점(local minimum) 인덱스를 반환한다.
     * 조건: prices[i] < prices[i-1] && prices[i] < prices[i+1]
     */
    private int findRecentSwingLow(List<BigDecimal> prices, int fromIdx, int windowSize) {
        for (int i = fromIdx - 1; i >= Math.max(1, fromIdx - windowSize); i--) {
            if (i + 1 < prices.size()
                    && prices.get(i).compareTo(prices.get(i - 1)) < 0
                    && prices.get(i).compareTo(prices.get(i + 1)) < 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * fromIdx 이전에서 가장 최근 스윙 고점(local maximum) 인덱스를 반환한다.
     * 조건: prices[i] > prices[i-1] && prices[i] > prices[i+1]
     */
    private int findRecentSwingHigh(List<BigDecimal> prices, int fromIdx, int windowSize) {
        for (int i = fromIdx - 1; i >= Math.max(1, fromIdx - windowSize); i--) {
            if (i + 1 < prices.size()
                    && prices.get(i).compareTo(prices.get(i - 1)) > 0
                    && prices.get(i).compareTo(prices.get(i + 1)) > 0) {
                return i;
            }
        }
        return -1;
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
