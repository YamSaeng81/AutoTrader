package com.cryptoautotrader.strategy.stochasticrsi;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyParamUtils;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Stochastic RSI 전략 (Phase 3 — 6번째 추가 전략)
 *
 * <p>개요: RSI 값 자체에 Stochastic 공식을 적용하여 RSI 의 과매수/과매도를 더 민감하게 감지한다.
 * 일반 RSI 보다 반응이 빠르고 RANGE / VOLATILITY 시장에 효과적이다.
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
    private static final BigDecimal FIFTY   = BigDecimal.valueOf(50);
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
        int    rsiPeriod       = StrategyParamUtils.getInt(params,    "rsiPeriod",       14);
        int    stochPeriod     = StrategyParamUtils.getInt(params,    "stochPeriod",     14);
        int    signalPeriod    = StrategyParamUtils.getInt(params,    "signalPeriod",    3);
        double oversoldLevel   = StrategyParamUtils.getDouble(params, "oversoldLevel",   20.0);
        double overboughtLevel = StrategyParamUtils.getDouble(params, "overboughtLevel", 80.0);
        int    adxPeriod       = StrategyParamUtils.getInt(params,    "adxPeriod",       14);
        double adxMaxThreshold = StrategyParamUtils.getDouble(params, "adxMaxThreshold", 30.0);
        int    volumePeriod    = StrategyParamUtils.getInt(params,    "volumePeriod",    20);

        // %K 이전 값과 현재 값, %D 현재 값을 구하려면
        // RSI 시계열 최소: rsiPeriod + stochPeriod 개 (stochPeriod 개의 RSI 값을 얻으려면)
        // %K 시계열 최소: stochPeriod 개 → %D 계산에 signalPeriod 개 필요 → 크로스 감지에 +1
        int required = rsiPeriod + stochPeriod + signalPeriod;
        if (candles.size() < required) {
            return StrategySignal.hold("캔들 수 부족: " + candles.size() + "/" + required);
        }

        // ADX 상한선 필터: StochRSI는 레인지 구간(낮은 ADX)에서 효과적, 강한 추세장 회피
        if (adxMaxThreshold > 0 && candles.size() >= adxPeriod * 2 + 1) {
            BigDecimal adx = IndicatorUtils.adx(candles, adxPeriod);
            if (adx.compareTo(BigDecimal.valueOf(adxMaxThreshold)) >= 0) {
                return StrategySignal.hold(String.format(
                        "ADX 필터: 강한 추세 구간 매매 회피 ADX=%.2f >= %.0f", adx, adxMaxThreshold));
            }
        }

        // 거래량 필터
        List<BigDecimal> volumes = candles.stream().map(Candle::getVolume).toList();
        BigDecimal avgVolume = volumePeriod > 0 && volumes.size() >= volumePeriod
                ? IndicatorUtils.sma(volumes, volumePeriod)
                : BigDecimal.ZERO;
        BigDecimal currentVolume = volumes.get(volumes.size() - 1);
        boolean volumeOk = avgVolume.compareTo(BigDecimal.ZERO) == 0
                || currentVolume.compareTo(avgVolume) >= 0;

        List<BigDecimal> closes    = candles.stream().map(Candle::getClose).toList();
        List<BigDecimal> rsiSeries = IndicatorUtils.rsiSeries(closes, rsiPeriod);

        if (rsiSeries.size() < stochPeriod + signalPeriod) {
            return StrategySignal.hold("RSI 시계열 부족: " + rsiSeries.size());
        }

        List<BigDecimal> kSeries = IndicatorUtils.stochasticKSeries(rsiSeries, stochPeriod);

        if (kSeries.size() < signalPeriod + 1) {
            return StrategySignal.hold("%K 시계열 부족: " + kSeries.size());
        }

        List<BigDecimal> dSeries = IndicatorUtils.smaList(kSeries, signalPeriod);

        if (dSeries.isEmpty()) {
            return StrategySignal.hold("%D 시계열 계산 실패");
        }

        BigDecimal currentK = kSeries.get(kSeries.size() - 1);
        BigDecimal prevK    = kSeries.get(kSeries.size() - 2);
        BigDecimal currentD = dSeries.get(dSeries.size() - 1);
        BigDecimal prevD    = dSeries.size() >= 2 ? dSeries.get(dSeries.size() - 2) : currentD;

        BigDecimal oversold   = BigDecimal.valueOf(oversoldLevel);
        BigDecimal overbought = BigDecimal.valueOf(overboughtLevel);

        // BUY: 이전 %K <= oversoldLevel AND 현재 %K > oversoldLevel
        //      AND 2캔들 연속 %K > %D (현재 + 이전 캔들 모두 %K가 %D 위)
        boolean buySignal =
                prevK.compareTo(oversold)   <= 0
                && currentK.compareTo(oversold) >  0
                && currentK.compareTo(currentD) >  0
                && prevK.compareTo(prevD)       >  0;

        // SELL: 이전 %K >= overboughtLevel AND 현재 %K < overboughtLevel
        //       AND 2캔들 연속 %K < %D (현재 + 이전 캔들 모두 %K가 %D 아래)
        boolean sellSignal =
                prevK.compareTo(overbought)   >= 0
                && currentK.compareTo(overbought) <  0
                && currentK.compareTo(currentD)   <  0
                && prevK.compareTo(prevD)         <  0;

        if (buySignal) {
            if (!volumeOk) {
                return StrategySignal.hold(String.format(
                        "StochRSI BUY 필터: 거래량 부족 현재=%.2f 평균=%.2f", currentVolume, avgVolume));
            }
            BigDecimal strength = currentK.subtract(oversold)
                    .divide(HUNDRED.subtract(oversold).max(BigDecimal.ONE), SCALE, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
                    .min(HUNDRED)
                    .max(BigDecimal.ZERO);
            return StrategySignal.buy(strength,
                    String.format("StochRSI 과매도 탈출: K=%.2f, D=%.2f, 과매도기준=%.1f (거래량확인)",
                            currentK, currentD, oversoldLevel));
        }

        if (sellSignal) {
            if (!volumeOk) {
                return StrategySignal.hold(String.format(
                        "StochRSI SELL 필터: 거래량 부족 현재=%.2f 평균=%.2f", currentVolume, avgVolume));
            }
            BigDecimal strength = overbought.subtract(currentK)
                    .divide(overbought.max(BigDecimal.ONE), SCALE, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
                    .min(HUNDRED)
                    .max(BigDecimal.ZERO);
            return StrategySignal.sell(strength,
                    String.format("StochRSI 과매수 탈출: K=%.2f, D=%.2f, 과매수기준=%.1f (거래량확인)",
                            currentK, currentD, overboughtLevel));
        }

        return StrategySignal.hold(String.format(
                "StochRSI 신호 없음: K=%.2f, prevK=%.2f, D=%.2f (과매도=%.1f, 과매수=%.1f)",
                currentK, prevK, currentD, oversoldLevel, overboughtLevel));
    }
}
