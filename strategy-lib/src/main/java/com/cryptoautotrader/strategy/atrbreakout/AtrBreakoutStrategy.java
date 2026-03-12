package com.cryptoautotrader.strategy.atrbreakout;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * ATR Breakout (변동성 돌파) 전략
 * 래리 윌리엄스의 변동성 돌파 전략을 ATR 기반으로 구현한다.
 *
 * 핵심 로직:
 *   매수 기준선 = 현재 캔들 시가 + ATR(period) * multiplier
 *   매도 기준선 = 현재 캔들 시가 - ATR(period) * multiplier
 *
 *   현재 종가 > 매수 기준선 → BUY  (상방 변동성 돌파)
 *   현재 종가 < 매도 기준선 → SELL (하방 변동성 돌파)
 *   그 외 → HOLD
 *
 * GridStrategy와의 차별점:
 *   - GridStrategy: 고정 가격 격자 기반 분할 매수/매도 (횡보장 특화)
 *   - AtrBreakout:  ATR 기반 동적 돌파 레벨 계산 (모멘텀 진입, 트렌딩 장 특화)
 *
 * 손절 옵션 (useStopLoss=true):
 *   매수 진입 후 종가가 매도 기준선 아래로 떨어지면 SELL 신호 발생
 */
public class AtrBreakoutStrategy implements Strategy {

    private static final int SCALE = 8;

    @Override
    public String getName() {
        return "ATR_BREAKOUT";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int atrPeriod = getInt(params, "atrPeriod", 14);
        double multiplier = getDouble(params, "multiplier", 1.5);
        boolean useStopLoss = getBoolean(params, "useStopLoss", true);

        // ATR 계산에 atrPeriod+1개 캔들 필요
        if (candles.size() < atrPeriod + 1) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + (atrPeriod + 1));
        }

        BigDecimal atr = IndicatorUtils.atr(candles, atrPeriod);
        BigDecimal multBD = BigDecimal.valueOf(multiplier);
        BigDecimal atrMultiplied = atr.multiply(multBD).setScale(SCALE, RoundingMode.HALF_UP);

        Candle currentCandle = candles.get(candles.size() - 1);
        BigDecimal currentOpen = currentCandle.getOpen();
        BigDecimal currentClose = currentCandle.getClose();

        // 돌파 기준선 계산
        BigDecimal buyThreshold = currentOpen.add(atrMultiplied);
        BigDecimal sellThreshold = currentOpen.subtract(atrMultiplied);

        // 현재가가 매수/매도 기준선을 돌파했는지 확인
        if (currentClose.compareTo(buyThreshold) > 0) {
            // 상방 돌파: 돌파 폭이 클수록 강한 신호
            BigDecimal breakoutPct = currentClose.subtract(buyThreshold)
                    .divide(atr, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .min(BigDecimal.valueOf(100));
            BigDecimal strength = BigDecimal.valueOf(50).add(breakoutPct.divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP))
                    .min(BigDecimal.valueOf(100));
            return StrategySignal.buy(strength,
                    String.format("ATR 상방 돌파: 종가=%.2f > 기준=%.2f (시가=%.2f, ATR=%.2f * %.1f)",
                            currentClose, buyThreshold, currentOpen, atr, multiplier));
        }

        if (currentClose.compareTo(sellThreshold) < 0) {
            // 하방 돌파: 돌파 폭이 클수록 강한 신호
            BigDecimal breakoutPct = sellThreshold.subtract(currentClose)
                    .divide(atr, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .min(BigDecimal.valueOf(100));
            BigDecimal strength = BigDecimal.valueOf(50).add(breakoutPct.divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP))
                    .min(BigDecimal.valueOf(100));

            if (useStopLoss) {
                return StrategySignal.sell(strength,
                        String.format("ATR 하방 돌파(손절): 종가=%.2f < 기준=%.2f (시가=%.2f, ATR=%.2f * %.1f)",
                                currentClose, sellThreshold, currentOpen, atr, multiplier));
            } else {
                return StrategySignal.hold(
                        String.format("ATR 하방 돌파(손절 비활성): 종가=%.2f < 기준=%.2f",
                                currentClose, sellThreshold));
            }
        }

        // 비돌파 구간: 현재가의 기준선 내 위치 정보 포함
        BigDecimal rangePosition = currentClose.subtract(sellThreshold)
                .divide(buyThreshold.subtract(sellThreshold), SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return StrategySignal.hold(String.format(
                "ATR 돌파 없음: 매수기준=%.2f, 현재=%.2f, 매도기준=%.2f (위치=%.1f%%)",
                buyThreshold, currentClose, sellThreshold, rangePosition));
    }

    @Override
    public int getMinimumCandleCount() {
        // atrPeriod(14) + 1 = 15
        return 15;
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
