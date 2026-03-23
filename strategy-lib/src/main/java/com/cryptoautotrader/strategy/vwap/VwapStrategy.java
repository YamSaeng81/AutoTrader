package com.cryptoautotrader.strategy.vwap;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyParamUtils;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * VWAP 역추세 전략
 * - 현재가가 VWAP 대비 N% 이상 할인 → BUY
 * - 현재가가 VWAP 대비 N% 이상 프리미엄 → SELL
 */
public class VwapStrategy implements Strategy {

    private static final int SCALE = 8;

    @Override
    public String getName() {
        return "VWAP";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        double  thresholdPct    = StrategyParamUtils.getDouble(params,  "thresholdPct",    1.5);
        int     period          = StrategyParamUtils.getInt(params,     "period",          20);
        int     adxPeriod       = StrategyParamUtils.getInt(params,     "adxPeriod",       14);
        double  adxMaxThreshold = StrategyParamUtils.getDouble(params,  "adxMaxThreshold", 35.0);
        boolean anchorSession   = StrategyParamUtils.getBoolean(params, "anchorSession",   true);

        if (candles.size() < period) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + period);
        }

        // ADX 상한선 필터: VWAP는 역추세 전략, 레인지 구간(낮은 ADX)에서만 유효
        if (adxMaxThreshold > 0 && candles.size() >= adxPeriod * 2 + 1) {
            BigDecimal adx = IndicatorUtils.adx(candles, adxPeriod);
            if (adx.compareTo(BigDecimal.valueOf(adxMaxThreshold)) >= 0) {
                return StrategySignal.hold(String.format(
                        "ADX 필터: 추세장 역추세 매매 억제 ADX=%.2f >= %.0f", adx, adxMaxThreshold));
            }
        }

        BigDecimal vwap = anchorSession
                ? calculateSessionVwap(candles, period)
                : calculateRollingVwap(candles, period);
        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();
        BigDecimal deviationPct = currentPrice.subtract(vwap)
                .divide(vwap, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        BigDecimal threshold = BigDecimal.valueOf(thresholdPct);
        BigDecimal strength = deviationPct.abs()
                .divide(threshold, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(50))
                .min(BigDecimal.valueOf(100));

        if (deviationPct.compareTo(threshold.negate()) <= 0) {
            return StrategySignal.buy(strength,
                    String.format("VWAP 할인 %.2f%% (임계값 -%.1f%%)", deviationPct.doubleValue(), thresholdPct));
        }
        if (deviationPct.compareTo(threshold) >= 0) {
            return StrategySignal.sell(strength,
                    String.format("VWAP 프리미엄 %.2f%% (임계값 +%.1f%%)", deviationPct.doubleValue(), thresholdPct));
        }

        return StrategySignal.hold(String.format("VWAP 편차 %.2f%% (임계값 ±%.1f%%)", deviationPct.doubleValue(), thresholdPct));
    }

    @Override
    public int getMinimumCandleCount() {
        return 20;
    }

    /**
     * 세션 앵커 VWAP: 오늘 UTC 00:00 기점의 캔들부터 누적 계산.
     * 당일 캔들이 3개 미만이면 rolling VWAP 으로 fallback.
     */
    private BigDecimal calculateSessionVwap(List<Candle> candles, int period) {
        LocalDate today = candles.get(candles.size() - 1).getTime()
                .atZone(ZoneOffset.UTC).toLocalDate();

        int sessionStart = candles.size(); // 당일 첫 캔들 인덱스
        for (int i = candles.size() - 1; i >= 0; i--) {
            LocalDate d = candles.get(i).getTime().atZone(ZoneOffset.UTC).toLocalDate();
            if (!d.equals(today)) break;
            sessionStart = i;
        }

        int sessionLen = candles.size() - sessionStart;
        if (sessionLen < 3) {
            // 당일 데이터가 너무 적으면 rolling VWAP 으로 대체
            return calculateRollingVwap(candles, period);
        }

        return accumulateVwap(candles, sessionStart, candles.size());
    }

    /** Rolling VWAP: 최근 period 개 캔들 기준 */
    private BigDecimal calculateRollingVwap(List<Candle> candles, int period) {
        int start = Math.max(0, candles.size() - period);
        return accumulateVwap(candles, start, candles.size());
    }

    private BigDecimal accumulateVwap(List<Candle> candles, int from, int to) {
        BigDecimal sumPV = BigDecimal.ZERO;
        BigDecimal sumV  = BigDecimal.ZERO;
        for (int i = from; i < to; i++) {
            Candle c = candles.get(i);
            BigDecimal tp = c.getHigh().add(c.getLow()).add(c.getClose())
                    .divide(BigDecimal.valueOf(3), SCALE, RoundingMode.HALF_UP);
            sumPV = sumPV.add(tp.multiply(c.getVolume()));
            sumV  = sumV.add(c.getVolume());
        }
        if (sumV.compareTo(BigDecimal.ZERO) == 0) {
            return candles.get(to - 1).getClose();
        }
        return sumPV.divide(sumV, SCALE, RoundingMode.HALF_UP);
    }

}

