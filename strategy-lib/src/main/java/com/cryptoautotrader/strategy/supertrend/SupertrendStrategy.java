package com.cryptoautotrader.strategy.supertrend;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Supertrend 전략
 * ATR 기반 동적 지지/저항선을 계산하고 가격과의 위치로 추세 방향을 판단한다.
 *
 * 계산 방식:
 *   HL2 = (고가 + 저가) / 2                      (중간가)
 *   상단 밴드(기본) = HL2 + multiplier * ATR(period)
 *   하단 밴드(기본) = HL2 - multiplier * ATR(period)
 *
 * 최종 밴드 조정 규칙 (Supertrend 핵심):
 *   - 이전 Supertrend가 하단 밴드(상승 추세)였다면:
 *       현재 하단 밴드 = max(현재 기본 하단, 이전 최종 하단)  ← 밴드가 뒤로 내려가지 않도록
 *   - 이전 Supertrend가 상단 밴드(하락 추세)였다면:
 *       현재 상단 밴드 = min(현재 기본 상단, 이전 최종 상단)  ← 밴드가 앞으로 올라가지 않도록
 *
 * 신호:
 *   - 종가 > 하단 Supertrend 밴드 → 상승 추세 → BUY  (포지션 유지 또는 진입)
 *   - 종가 < 상단 Supertrend 밴드 → 하락 추세 → SELL (포지션 청산 또는 공매도)
 *   - 추세 전환 시 크로스 발생 → 더 강한 신호로 처리
 */
public class SupertrendStrategy implements Strategy {

    private static final int SCALE = 8;

    @Override
    public String getName() {
        return "SUPERTREND";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int atrPeriod = getInt(params, "atrPeriod", 10);
        double multiplier = getDouble(params, "multiplier", 3.0);

        // ATR 계산에 atrPeriod+1개 캔들 필요, 추세 전환 감지를 위해 +1
        int minRequired = atrPeriod + 2;
        if (candles.size() < minRequired) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + minRequired);
        }

        BigDecimal multBD = BigDecimal.valueOf(multiplier);
        SupertrendResult result = calculateSupertrend(candles, atrPeriod, multBD);

        BigDecimal currentClose = candles.get(candles.size() - 1).getClose();
        boolean currentUptrend = result.isUptrend;
        boolean prevUptrend = result.wasPrevUptrend;

        // 신호 강도: 가격과 Supertrend 밴드의 거리 비율
        BigDecimal band = currentUptrend ? result.supertrendLine : result.supertrendLine;
        BigDecimal distance = currentClose.subtract(band).abs();
        BigDecimal strength = distance.divide(currentClose, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(10000))
                .min(BigDecimal.valueOf(100));

        // 추세 전환 감지 (크로스)
        if (currentUptrend && !prevUptrend) {
            // 하락 추세에서 상승 추세로 전환 → 강한 BUY
            return StrategySignal.buy(strength.max(BigDecimal.valueOf(70)),
                    String.format("Supertrend 상승 전환: 종가=%.2f, 밴드=%.2f (period=%d, mult=%.1f)",
                            currentClose, result.supertrendLine, atrPeriod, multiplier));
        }

        if (!currentUptrend && prevUptrend) {
            // 상승 추세에서 하락 추세로 전환 → 강한 SELL
            return StrategySignal.sell(strength.max(BigDecimal.valueOf(70)),
                    String.format("Supertrend 하락 전환: 종가=%.2f, 밴드=%.2f (period=%d, mult=%.1f)",
                            currentClose, result.supertrendLine, atrPeriod, multiplier));
        }

        // 추세 유지 중: 현재 추세 방향으로 신호 (약한 강도)
        BigDecimal holdStrength = strength.divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
        if (currentUptrend) {
            return StrategySignal.buy(holdStrength,
                    String.format("Supertrend 상승 추세 유지: 종가=%.2f > 밴드=%.2f",
                            currentClose, result.supertrendLine));
        } else {
            return StrategySignal.sell(holdStrength,
                    String.format("Supertrend 하락 추세 유지: 종가=%.2f < 밴드=%.2f",
                            currentClose, result.supertrendLine));
        }
    }

    @Override
    public int getMinimumCandleCount() {
        // atrPeriod(10) + 2 = 12
        return 12;
    }

    /**
     * Supertrend를 전체 캔들 시계열로 계산하여 최종 상태를 반환한다.
     * 전체 시계열 스캔이 필요한 이유: 이전 밴드 조정이 현재 밴드에 영향을 미치기 때문.
     */
    private SupertrendResult calculateSupertrend(List<Candle> candles, int atrPeriod, BigDecimal multiplier) {
        // 각 캔들의 ATR과 밴드를 순차적으로 계산
        // ATR 계산 시작점: atrPeriod 번째 인덱스부터 (0-based)
        List<BigDecimal> finalLowerBands = new ArrayList<>();
        List<BigDecimal> finalUpperBands = new ArrayList<>();
        List<Boolean> trends = new ArrayList<>();

        // 첫 캔들(atrPeriod 인덱스)의 ATR과 밴드 계산
        for (int i = atrPeriod; i < candles.size(); i++) {
            // i번째 캔들까지의 ATR 계산
            List<Candle> subCandles = candles.subList(0, i + 1);
            BigDecimal atr = IndicatorUtils.atr(subCandles, atrPeriod);

            Candle current = candles.get(i);
            BigDecimal hl2 = current.getHigh().add(current.getLow())
                    .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);

            // 기본 밴드 계산
            BigDecimal basicUpper = hl2.add(multiplier.multiply(atr));
            BigDecimal basicLower = hl2.subtract(multiplier.multiply(atr));

            BigDecimal finalUpper;
            BigDecimal finalLower;

            if (finalUpperBands.isEmpty()) {
                // 최초 밴드: 기본값 그대로 사용
                finalUpper = basicUpper;
                finalLower = basicLower;
                // 최초 추세: 종가가 상단 밴드 아래면 하락 추세로 시작
                boolean uptrend = current.getClose().compareTo(basicLower) >= 0;
                finalUpperBands.add(finalUpper);
                finalLowerBands.add(finalLower);
                trends.add(uptrend);
            } else {
                BigDecimal prevFinalUpper = finalUpperBands.get(finalUpperBands.size() - 1);
                BigDecimal prevFinalLower = finalLowerBands.get(finalLowerBands.size() - 1);
                boolean prevUptrend = trends.get(trends.size() - 1);
                BigDecimal prevClose = candles.get(i - 1).getClose();

                // 상단 밴드 조정: 이전 종가가 이전 상단보다 위에 있었으면 연속성 유지
                if (basicUpper.compareTo(prevFinalUpper) < 0
                        || prevClose.compareTo(prevFinalUpper) > 0) {
                    finalUpper = basicUpper;
                } else {
                    finalUpper = prevFinalUpper;
                }

                // 하단 밴드 조정: 이전 종가가 이전 하단보다 아래에 있었으면 연속성 유지
                if (basicLower.compareTo(prevFinalLower) > 0
                        || prevClose.compareTo(prevFinalLower) < 0) {
                    finalLower = basicLower;
                } else {
                    finalLower = prevFinalLower;
                }

                // 현재 추세 판단
                boolean currentUptrend;
                if (prevUptrend) {
                    // 이전에 상승 추세였으면: 종가가 하단 밴드 아래로 이탈하면 하락 전환
                    currentUptrend = current.getClose().compareTo(finalLower) >= 0;
                } else {
                    // 이전에 하락 추세였으면: 종가가 상단 밴드 위로 이탈하면 상승 전환
                    currentUptrend = current.getClose().compareTo(finalUpper) > 0;
                }

                finalUpperBands.add(finalUpper);
                finalLowerBands.add(finalLower);
                trends.add(currentUptrend);
            }
        }

        if (trends.isEmpty()) {
            return new SupertrendResult(BigDecimal.ZERO, true, true);
        }

        boolean isUptrend = trends.get(trends.size() - 1);
        boolean wasPrevUptrend = trends.size() >= 2 ? trends.get(trends.size() - 2) : isUptrend;

        // 현재 추세에 해당하는 Supertrend 밴드선 반환
        BigDecimal supertrendLine = isUptrend
                ? finalLowerBands.get(finalLowerBands.size() - 1)
                : finalUpperBands.get(finalUpperBands.size() - 1);

        return new SupertrendResult(supertrendLine, isUptrend, wasPrevUptrend);
    }

    /** Supertrend 계산 결과를 담는 내부 클래스 */
    private static class SupertrendResult {
        final BigDecimal supertrendLine; // 현재 Supertrend 밴드선 (상승 시 하단, 하락 시 상단)
        final boolean isUptrend;         // 현재 상승 추세 여부
        final boolean wasPrevUptrend;    // 이전 시점 상승 추세 여부 (전환 감지용)

        SupertrendResult(BigDecimal supertrendLine, boolean isUptrend, boolean wasPrevUptrend) {
            this.supertrendLine = supertrendLine;
            this.isUptrend = isUptrend;
            this.wasPrevUptrend = wasPrevUptrend;
        }
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : defaultVal;
    }
}
