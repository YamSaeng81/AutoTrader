package com.cryptoautotrader.strategy.orderbook;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Orderbook Imbalance (호가 불균형) 전략
 *
 * <p>S4-6 Delta 일관성 필터 (캔들 근사 모드):
 *   마지막 캔들의 압력 방향이 lookback 구간 전체 누적 방향과 반대인 경우
 *   신호 강도를 50% 할인한다. 급반전은 스푸핑 등의 허위 주문 패턴일 수 있기 때문이다.
 *   "bidVolume/askVolume" 파라미터가 제공되는 실시간 모드에서는 적용하지 않는다.
 *
 * 실시간 모드 (Phase 4 WebSocket 연동 시):
 *   매개변수 "bidVolume", "askVolume"이 제공될 경우 실제 호가 불균형을 계산한다.
 *   불균형비율 = bidVolume / (bidVolume + askVolume)
 *   불균형비율 > imbalanceThreshold       → BUY  (매수 압력 우세)
 *   불균형비율 < (1 - imbalanceThreshold) → SELL (매도 압력 우세)
 *
 * 백테스팅 모드 (캔들 데이터 기반 근사치):
 *   "bidVolume", "askVolume"이 없을 경우 캔들 데이터로 매수/매도 압력을 근사한다.
 *   캔들 볼륨 분해 방법:
 *     - 상승 캔들(close > open): 볼륨의 대부분이 매수 주도
 *       매수량 근사 = volume * (close - open) / (high - low + epsilon)
 *     - 하락 캔들(close < open): 볼륨의 대부분이 매도 주도
 *       매도량 근사 = volume * (open - close) / (high - low + epsilon)
 *   lookback 기간 동안의 누적 매수/매도량으로 불균형 계산
 *
 * 주의: 캔들 기반 근사치는 실제 호가창보다 정확도가 낮다.
 *       Phase 4 WebSocket 구현 후 실시간 호가 데이터로 대체 예정.
 */
public class OrderbookImbalanceStrategy implements Strategy {

    private static final int SCALE = 8;
    // 분모가 0이 되는 것을 방지하기 위한 최소값
    private static final BigDecimal EPSILON = BigDecimal.valueOf(1e-10);

    @Override
    public String getName() {
        return "ORDERBOOK_IMBALANCE";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        double imbalanceThreshold = getDouble(params, "imbalanceThreshold", 0.70);
        int lookback = getInt(params, "lookback", 15);

        if (candles.size() < lookback) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + lookback);
        }

        // 실시간 호가 데이터가 파라미터로 제공된 경우 (Phase 4 WebSocket 연동)
        Object bidVolumeParam = params.get("bidVolume");
        Object askVolumeParam = params.get("askVolume");

        if (bidVolumeParam instanceof Number && askVolumeParam instanceof Number) {
            return evaluateWithRealtimeOrderbook(
                    (Number) bidVolumeParam,
                    (Number) askVolumeParam,
                    imbalanceThreshold
            );
        }

        // 캔들 데이터 기반 근사치 계산 (백테스팅 모드)
        return evaluateWithCandleApproximation(candles, lookback, imbalanceThreshold);
    }

    @Override
    public int getMinimumCandleCount() {
        return 15;
    }

    /**
     * 실시간 호가 데이터로 불균형을 계산한다 (Phase 4 WebSocket 연동 시 사용).
     */
    private StrategySignal evaluateWithRealtimeOrderbook(
            Number bidVolume, Number askVolume, double imbalanceThreshold) {

        BigDecimal bid = BigDecimal.valueOf(bidVolume.doubleValue());
        BigDecimal ask = BigDecimal.valueOf(askVolume.doubleValue());
        BigDecimal total = bid.add(ask);

        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return StrategySignal.hold("호가 데이터 없음 (total=0)");
        }

        BigDecimal imbalanceRatio = bid.divide(total, SCALE, RoundingMode.HALF_UP);
        BigDecimal threshold = BigDecimal.valueOf(imbalanceThreshold);
        BigDecimal counterThreshold = BigDecimal.ONE.subtract(threshold);

        if (imbalanceRatio.compareTo(threshold) >= 0) {
            BigDecimal strength = imbalanceRatio.subtract(threshold)
                    .divide(BigDecimal.ONE.subtract(threshold), SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .min(BigDecimal.valueOf(100));
            return StrategySignal.buy(strength,
                    String.format("호가 매수 우세: 불균형=%.2f%% > 임계값=%.0f%% (매수량=%.2f, 매도량=%.2f)",
                            imbalanceRatio.multiply(BigDecimal.valueOf(100)),
                            imbalanceThreshold * 100, bid, ask));
        }

        if (imbalanceRatio.compareTo(counterThreshold) <= 0) {
            BigDecimal strength = counterThreshold.subtract(imbalanceRatio)
                    .divide(counterThreshold, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .min(BigDecimal.valueOf(100));
            return StrategySignal.sell(strength,
                    String.format("호가 매도 우세: 불균형=%.2f%% < 임계값=%.0f%% (매수량=%.2f, 매도량=%.2f)",
                            imbalanceRatio.multiply(BigDecimal.valueOf(100)),
                            (1 - imbalanceThreshold) * 100, bid, ask));
        }

        return StrategySignal.hold(String.format("호가 균형: 불균형=%.2f%% (임계값=±%.0f%%)",
                imbalanceRatio.multiply(BigDecimal.valueOf(100)), imbalanceThreshold * 100));
    }

    /**
     * 캔들 데이터로 매수/매도 압력을 근사하여 불균형을 계산한다 (백테스팅 모드).
     *
     * 볼륨 분해 공식 (Tick Rule 기반 근사):
     *   상승비율 = (close - low) / (high - low + epsilon)
     *   매수량  = volume * 상승비율
     *   매도량  = volume * (1 - 상승비율)
     *
     * S4-6 Delta 일관성 필터:
     *   마지막 캔들의 압력 방향이 lookback 구간 전체와 반대이면 신호 강도를 50% 할인.
     */
    private StrategySignal evaluateWithCandleApproximation(
            List<Candle> candles, int lookback, double imbalanceThreshold) {

        int start = candles.size() - lookback;
        BigDecimal totalBuyVolume  = BigDecimal.ZERO;
        BigDecimal totalSellVolume = BigDecimal.ZERO;

        // lookback-1개 캔들의 누적 압력 (마지막 캔들 제외)
        BigDecimal priorBuyVolume  = BigDecimal.ZERO;
        BigDecimal priorSellVolume = BigDecimal.ZERO;

        for (int i = start; i < candles.size(); i++) {
            Candle c = candles.get(i);
            BigDecimal hl = c.getHigh().subtract(c.getLow()).abs().add(EPSILON);
            BigDecimal buyRatio = c.getClose().subtract(c.getLow())
                    .divide(hl, SCALE, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO)
                    .min(BigDecimal.ONE);
            BigDecimal buyVolume  = c.getVolume().multiply(buyRatio);
            BigDecimal sellVolume = c.getVolume().multiply(BigDecimal.ONE.subtract(buyRatio));
            totalBuyVolume  = totalBuyVolume.add(buyVolume);
            totalSellVolume = totalSellVolume.add(sellVolume);
            if (i < candles.size() - 1) {   // 마지막 캔들은 제외
                priorBuyVolume  = priorBuyVolume.add(buyVolume);
                priorSellVolume = priorSellVolume.add(sellVolume);
            }
        }

        BigDecimal totalVolume = totalBuyVolume.add(totalSellVolume);
        if (totalVolume.compareTo(BigDecimal.ZERO) == 0) {
            return StrategySignal.hold("볼륨 데이터 없음");
        }

        // S4-6 Delta 일관성: 마지막 캔들 방향 vs 이전 누적 방향 비교
        boolean priorBuyDominant = priorBuyVolume.compareTo(priorSellVolume) >= 0;
        BigDecimal lastBuyDelta  = totalBuyVolume.subtract(priorBuyVolume);
        BigDecimal lastSellDelta = totalSellVolume.subtract(priorSellVolume);
        boolean lastBuyDominant  = lastBuyDelta.compareTo(lastSellDelta) >= 0;
        boolean deltaConsistent  = (priorBuyDominant == lastBuyDominant);

        BigDecimal imbalanceRatio    = totalBuyVolume.divide(totalVolume, SCALE, RoundingMode.HALF_UP);
        BigDecimal threshold         = BigDecimal.valueOf(imbalanceThreshold);
        BigDecimal counterThreshold  = BigDecimal.ONE.subtract(threshold);
        // Delta 불일치 시 강도 50% 할인
        BigDecimal deltaDiscount = deltaConsistent ? BigDecimal.ONE : BigDecimal.valueOf(0.5);
        String deltaTag = deltaConsistent ? "" : " [Delta반전↓50%]";

        if (imbalanceRatio.compareTo(threshold) >= 0) {
            BigDecimal strength = imbalanceRatio.subtract(threshold)
                    .divide(BigDecimal.ONE.subtract(threshold), SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .multiply(deltaDiscount)
                    .min(BigDecimal.valueOf(100));
            return StrategySignal.buy(strength,
                    String.format("캔들 매수 우세(근사): 불균형=%.2f%% > 임계값=%.0f%% (lookback=%d)%s",
                            imbalanceRatio.multiply(BigDecimal.valueOf(100)),
                            imbalanceThreshold * 100, lookback, deltaTag));
        }

        if (imbalanceRatio.compareTo(counterThreshold) <= 0) {
            BigDecimal strength = counterThreshold.subtract(imbalanceRatio)
                    .divide(counterThreshold, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .multiply(deltaDiscount)
                    .min(BigDecimal.valueOf(100));
            return StrategySignal.sell(strength,
                    String.format("캔들 매도 우세(근사): 불균형=%.2f%% < 임계값=%.0f%% (lookback=%d)%s",
                            imbalanceRatio.multiply(BigDecimal.valueOf(100)),
                            (1 - imbalanceThreshold) * 100, lookback, deltaTag));
        }

        return StrategySignal.hold(String.format("캔들 볼륨 균형(근사): 불균형=%.2f%% (임계값=±%.0f%%)%s",
                imbalanceRatio.multiply(BigDecimal.valueOf(100)), imbalanceThreshold * 100, deltaTag));
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
