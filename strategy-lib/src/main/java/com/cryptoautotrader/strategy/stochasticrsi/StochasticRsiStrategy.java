package com.cryptoautotrader.strategy.stochasticrsi;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stochastic RSI 전략 (Phase 3 — 6번째 추가 전략)
 *
 * <p>개요: RSI 값 자체에 Stochastic 공식을 적용하여 RSI 의 과매수/과매도를 더 민감하게 감지한다.
 * 일반 RSI 보다 반응이 빠르고 RANGE / VOLATILE 시장에 효과적이다.
 *
 * <p>계산 방식:
 * <pre>
 * 1. RSI(rsiPeriod) 시계열 계산  — Wilder's Smoothing
 * 2. StochRSI(%K) = (RSI - RSI_최저) / (RSI_최고 - RSI_최저) * 100
 *    - RSI_최고/최저: 최근 stochPeriod 개의 RSI 값 중 최고·최저
 *    - 분모 == 0 이면 %K = 50 (중립)
 * 3. %D = %K 의 SMA(signalPeriod)   ← 시그널 선
 * </pre>
 *
 * <p>매매 신호:
 * <pre>
 * 이전 %K <= oversoldLevel  AND 현재 %K > oversoldLevel  AND 현재 %K > 현재 %D → BUY
 * 이전 %K >= overboughtLevel AND 현재 %K < overboughtLevel AND 현재 %K < 현재 %D → SELL
 * 그 외 → HOLD
 * </pre>
 *
 * <p>신호 강도: %K 와 oversoldLevel/overboughtLevel 의 거리로 산출 (0~100)
 */
public class StochasticRsiStrategy implements Strategy {

    private static final int SCALE = 8;
    private static final BigDecimal FIFTY = BigDecimal.valueOf(50);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String getName() {
        return "STOCHASTIC_RSI";
    }

    @Override
    public int getMinimumCandleCount() {
        // rsiPeriod(14) + stochPeriod(14) + signalPeriod(3) + 여유 = 40
        return 40;
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        // 파라미터 파싱
        int    rsiPeriod       = parseIntParam(params,    "rsiPeriod",       14);
        int    stochPeriod     = parseIntParam(params,    "stochPeriod",     14);
        int    signalPeriod    = parseIntParam(params,    "signalPeriod",    3);
        double oversoldLevel   = parseDoubleParam(params, "oversoldLevel",   20.0);
        double overboughtLevel = parseDoubleParam(params, "overboughtLevel", 80.0);

        // %K 이전 값과 현재 값, %D 현재 값을 구하려면
        // RSI 시계열 최소: rsiPeriod + stochPeriod 개 (stochPeriod 개의 RSI 값을 얻으려면)
        // %K 시계열 최소: stochPeriod 개 → %D 계산에 signalPeriod 개 필요 → 크로스 감지에 +1
        int required = rsiPeriod + stochPeriod + signalPeriod;
        if (candles.size() < required) {
            return StrategySignal.hold("캔들 수 부족: " + candles.size() + "/" + required);
        }

        // Step 1: 전체 캔들에 대한 RSI 시계열 계산
        List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();
        List<BigDecimal> rsiSeries = computeRsiSeries(closes, rsiPeriod);

        // RSI 시계열 값이 stochPeriod + signalPeriod 개 이상 있어야 %D 까지 계산 가능
        if (rsiSeries.size() < stochPeriod + signalPeriod) {
            return StrategySignal.hold("RSI 시계열 부족: " + rsiSeries.size());
        }

        // Step 2: RSI 시계열에 Stochastic 공식 적용 → %K 시계열
        List<BigDecimal> kSeries = computeStochasticK(rsiSeries, stochPeriod);

        // %K 시계열이 signalPeriod + 1 개 이상 있어야 %D 와 이전 %K 를 모두 구할 수 있음
        if (kSeries.size() < signalPeriod + 1) {
            return StrategySignal.hold("%K 시계열 부족: " + kSeries.size());
        }

        // Step 3: %K 의 SMA → %D 시계열
        List<BigDecimal> dSeries = computeSma(kSeries, signalPeriod);

        if (dSeries.isEmpty()) {
            return StrategySignal.hold("%D 시계열 계산 실패");
        }

        // Step 4: 신호 판단
        BigDecimal currentK = kSeries.get(kSeries.size() - 1);
        BigDecimal prevK    = kSeries.get(kSeries.size() - 2);
        BigDecimal currentD = dSeries.get(dSeries.size() - 1);

        BigDecimal oversold   = BigDecimal.valueOf(oversoldLevel);
        BigDecimal overbought = BigDecimal.valueOf(overboughtLevel);

        // BUY: 이전 %K <= oversoldLevel AND 현재 %K > oversoldLevel AND 현재 %K > 현재 %D
        boolean buySignal =
                prevK.compareTo(oversold)   <= 0
                && currentK.compareTo(oversold) >  0
                && currentK.compareTo(currentD) >  0;

        // SELL: 이전 %K >= overboughtLevel AND 현재 %K < overboughtLevel AND 현재 %K < 현재 %D
        boolean sellSignal =
                prevK.compareTo(overbought)   >= 0
                && currentK.compareTo(overbought) <  0
                && currentK.compareTo(currentD)   <  0;

        if (buySignal) {
            // 강도: oversoldLevel 에서 멀어질수록 강해짐 (0~100 클램프)
            BigDecimal strength = currentK.subtract(oversold)
                    .divide(HUNDRED.subtract(oversold).max(BigDecimal.ONE), SCALE, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
                    .min(HUNDRED)
                    .max(BigDecimal.ZERO);
            return StrategySignal.buy(strength,
                    String.format("StochRSI 과매도 탈출: K=%.2f, D=%.2f, 과매도기준=%.1f",
                            currentK, currentD, oversoldLevel));
        }

        if (sellSignal) {
            // 강도: overboughtLevel 에서 멀어질수록 강해짐 (0~100 클램프)
            BigDecimal strength = overbought.subtract(currentK)
                    .divide(overbought.max(BigDecimal.ONE), SCALE, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
                    .min(HUNDRED)
                    .max(BigDecimal.ZERO);
            return StrategySignal.sell(strength,
                    String.format("StochRSI 과매수 탈출: K=%.2f, D=%.2f, 과매수기준=%.1f",
                            currentK, currentD, overboughtLevel));
        }

        return StrategySignal.hold(String.format(
                "StochRSI 신호 없음: K=%.2f, prevK=%.2f, D=%.2f (과매도=%.1f, 과매수=%.1f)",
                currentK, prevK, currentD, oversoldLevel, overboughtLevel));
    }

    // ── 지표 계산 메서드 ───────────────────────────────────────────────────────

    /**
     * 전체 종가 리스트에 대한 RSI 시계열을 반환한다. (Wilder's Smoothing 방식)
     *
     * <p>RSI 계산에 period+1 개의 종가 차이가 필요하므로 첫 RSI 값은
     * closes 인덱스 period 지점부터 계산된다.
     * 반환 크기 = closes.size() - period
     *
     * @param closes  종가 리스트
     * @param period  RSI 기간
     * @return RSI 시계열 (비어 있을 수 있음)
     */
    private List<BigDecimal> computeRsiSeries(List<BigDecimal> closes, int period) {
        List<BigDecimal> result = new ArrayList<>();
        if (closes.size() <= period) {
            return result;
        }

        // 가격 변화량 계산
        List<BigDecimal> gains  = new ArrayList<>();
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
            return result;
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

        // 첫 번째 RSI 기록
        result.add(rsiFromAvg(avgGain, avgLoss));

        // Wilder's Smoothing 으로 나머지 RSI 값 계산
        BigDecimal periodBD       = BigDecimal.valueOf(period);
        BigDecimal periodMinusOne = periodBD.subtract(BigDecimal.ONE);
        for (int i = period; i < gains.size(); i++) {
            avgGain = avgGain.multiply(periodMinusOne)
                    .add(gains.get(i))
                    .divide(periodBD, SCALE, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(periodMinusOne)
                    .add(losses.get(i))
                    .divide(periodBD, SCALE, RoundingMode.HALF_UP);
            result.add(rsiFromAvg(avgGain, avgLoss));
        }

        return result;
    }

    /**
     * 평균 상승/하락으로 RSI 값을 산출한다.
     * avgLoss == 0 이면 RSI = 100 (완전 상승)
     */
    private BigDecimal rsiFromAvg(BigDecimal avgGain, BigDecimal avgLoss) {
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return HUNDRED;
        }
        BigDecimal rs  = avgGain.divide(avgLoss, SCALE, RoundingMode.HALF_UP);
        BigDecimal rsi = HUNDRED.subtract(
                HUNDRED.divide(BigDecimal.ONE.add(rs), SCALE, RoundingMode.HALF_UP));
        return rsi.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * RSI 시계열에 Stochastic 공식을 적용하여 %K 시계열을 반환한다.
     *
     * <pre>
     * %K[i] = (rsiSeries[i] - min(rsiSeries[i-stochPeriod+1..i]))
     *       / (max - min) * 100
     * 분모 == 0 이면 %K = 50
     * </pre>
     *
     * @param rsiSeries  RSI 시계열
     * @param stochPeriod  Stochastic 계산 기간
     * @return %K 시계열 (비어 있을 수 있음)
     */
    private List<BigDecimal> computeStochasticK(List<BigDecimal> rsiSeries, int stochPeriod) {
        List<BigDecimal> kSeries = new ArrayList<>();
        // stochPeriod 개의 RSI 값이 있어야 첫 %K 계산 가능
        for (int i = stochPeriod - 1; i < rsiSeries.size(); i++) {
            // 윈도우: [i - stochPeriod + 1, i]
            BigDecimal high = rsiSeries.get(i - stochPeriod + 1);
            BigDecimal low  = rsiSeries.get(i - stochPeriod + 1);
            for (int j = i - stochPeriod + 2; j <= i; j++) {
                BigDecimal v = rsiSeries.get(j);
                if (v.compareTo(high) > 0) high = v;
                if (v.compareTo(low)  < 0) low  = v;
            }

            BigDecimal range = high.subtract(low);
            BigDecimal k;
            if (range.compareTo(BigDecimal.ZERO) == 0) {
                // RSI 최고 == 최저 → 중립값 처리
                k = FIFTY;
            } else {
                k = rsiSeries.get(i).subtract(low)
                        .divide(range, SCALE, RoundingMode.HALF_UP)
                        .multiply(HUNDRED)
                        .setScale(2, RoundingMode.HALF_UP);
            }
            kSeries.add(k);
        }
        return kSeries;
    }

    /**
     * 리스트의 각 위치에서 최근 period 개의 단순 평균을 계산한 시계열을 반환한다.
     * 반환 크기 = values.size() - period + 1
     *
     * @param values  원본 값 리스트
     * @param period  이동평균 기간
     * @return SMA 시계열 (비어 있을 수 있음)
     */
    private List<BigDecimal> computeSma(List<BigDecimal> values, int period) {
        List<BigDecimal> smaList = new ArrayList<>();
        for (int i = period - 1; i < values.size(); i++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = i - period + 1; j <= i; j++) {
                sum = sum.add(values.get(j));
            }
            smaList.add(sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP));
        }
        return smaList;
    }

    // ── 파라미터 파싱 헬퍼 ────────────────────────────────────────────────────

    private int parseIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private double parseDoubleParam(Map<String, Object> params, String key, double defaultValue) {
        Object val = params.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }
}
