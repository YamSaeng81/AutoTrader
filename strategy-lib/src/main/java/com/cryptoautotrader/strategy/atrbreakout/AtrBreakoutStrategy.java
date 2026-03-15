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
 *
 * <p>S4-5 거래량 필터 (volumeFilterEnabled=true):
 *   돌파 시 현재 거래량 > 최근 atrPeriod개 평균 거래량 × volumeMultiplier(기본 1.5)이어야 신호 인정.
 *   거래량 미달 시 HOLD를 반환하여 저유동성 돌파를 필터링한다.
 */
public class AtrBreakoutStrategy implements Strategy {

    private static final int SCALE = 8;

    @Override
    public String getName() {
        return "ATR_BREAKOUT";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int atrPeriod              = getInt(params, "atrPeriod", 14);
        double multiplier          = getDouble(params, "multiplier", 1.5);
        boolean useStopLoss        = getBoolean(params, "useStopLoss", true);
        boolean volumeFilter       = getBoolean(params, "volumeFilterEnabled", true);
        double volumeMultiplier    = getDouble(params, "volumeMultiplier", 1.5);

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

        // S4-5 거래량 필터: 평균 거래량 × volumeMultiplier 미만이면 돌파 무효
        if (volumeFilter && candles.size() >= atrPeriod + 1) {
            BigDecimal sumVol = BigDecimal.ZERO;
            int volStart = candles.size() - 1 - atrPeriod;
            for (int i = Math.max(0, volStart); i < candles.size() - 1; i++) {
                sumVol = sumVol.add(candles.get(i).getVolume());
            }
            BigDecimal avgVol = sumVol.divide(BigDecimal.valueOf(atrPeriod), SCALE, RoundingMode.HALF_UP);
            BigDecimal curVol = currentCandle.getVolume();

            if (curVol.compareTo(avgVol.multiply(BigDecimal.valueOf(volumeMultiplier))) < 0) {
                return StrategySignal.hold(String.format(
                        "ATR 돌파 감지됐으나 거래량 부족: %.2f < avgVol=%.2f × %.1f",
                        curVol, avgVol, volumeMultiplier));
            }
        }

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
