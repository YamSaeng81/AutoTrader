package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Weighted Voting 기반 복합 전략.
 *
 * <pre>
 * buyScore  = Σ(weight × confidence)  (BUY  신호 전략 합산)
 * sellScore = Σ(weight × confidence)  (SELL 신호 전략 합산)
 *
 * buyScore  > 0.6                         → BUY  (strength ≈ score × 100)
 * sellScore > 0.6                         → SELL
 * buyScore  > 0.4                         → BUY  (weak)
 * sellScore > 0.4                         → SELL (weak)
 * 양쪽 모두 > 0.4 (상충)                  → HOLD
 * 그 외                                   → HOLD
 * </pre>
 */
public class CompositeStrategy implements Strategy {

    private static final double STRONG_THRESHOLD = 0.6;
    private static final double WEAK_THRESHOLD   = 0.4;

    /** EMA 방향 필터 기본 파라미터 */
    private static final int DEFAULT_EMA_SHORT = 20;
    private static final int DEFAULT_EMA_LONG  = 50;

    /** ADX 횡보장 필터 기본 파라미터 */
    private static final int    DEFAULT_ADX_PERIOD    = 14;
    private static final double DEFAULT_ADX_THRESHOLD = 20.0;

    private final String name;
    private final List<WeightedStrategy> strategies;
    private final double totalWeight;

    /**
     * EMA 방향 필터 활성화 여부.
     * true이면 단기 EMA > 장기 EMA(상승 추세) 구간에서 SELL 신호를,
     * 단기 EMA < 장기 EMA(하락 추세) 구간에서 BUY 신호를 HOLD로 억제한다.
     */
    private final boolean emaFilterEnabled;

    /**
     * ADX 횡보장 필터 활성화 여부.
     * true이면 ADX(14) < 20 구간에서 하위 전략 평가 없이 즉시 HOLD를 반환한다.
     * 추세가 없는 횡보장에서 ATR 기반 가짜 돌파 신호를 사전 차단한다.
     */
    private final boolean adxFilterEnabled;

    /** 기본 COMPOSITE 전략 (EMA 필터, ADX 필터 미적용) */
    public CompositeStrategy(List<WeightedStrategy> strategies) {
        this("COMPOSITE", strategies);
    }

    /** 이름을 명시적으로 지정하는 프리셋 복합 전략 (EMA 필터, ADX 필터 미적용) */
    public CompositeStrategy(String name, List<WeightedStrategy> strategies) {
        this(name, strategies, false, false);
    }

    /** EMA 방향 필터 활성화 여부를 명시하는 생성자 (ADX 필터 미적용) */
    public CompositeStrategy(String name, List<WeightedStrategy> strategies, boolean emaFilterEnabled) {
        this(name, strategies, emaFilterEnabled, false);
    }

    /** EMA 방향 필터 + ADX 횡보장 필터 활성화 여부를 모두 명시하는 생성자 */
    public CompositeStrategy(String name, List<WeightedStrategy> strategies,
                             boolean emaFilterEnabled, boolean adxFilterEnabled) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("전략 목록이 비어 있습니다.");
        }
        double sum = strategies.stream().mapToDouble(WeightedStrategy::getWeight).sum();
        if (sum <= 0) {
            throw new IllegalArgumentException("전략 가중치 합계가 0 이하입니다: " + sum);
        }
        this.name             = name;
        this.strategies       = strategies;
        this.totalWeight      = sum;
        this.emaFilterEnabled = emaFilterEnabled;
        this.adxFilterEnabled = adxFilterEnabled;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getMinimumCandleCount() {
        int strategyMin = strategies.stream()
                .mapToInt(ws -> ws.getStrategy().getMinimumCandleCount())
                .max()
                .orElse(0);
        // EMA 필터: 장기 EMA(50) 계산에 필요한 최소 캔들 수 보장
        if (emaFilterEnabled) {
            strategyMin = Math.max(strategyMin, DEFAULT_EMA_LONG);
        }
        // ADX 필터: ADX(14) 계산에 period*2+1 = 29개 캔들 필요
        if (adxFilterEnabled) {
            strategyMin = Math.max(strategyMin, DEFAULT_ADX_PERIOD * 2 + 1);
        }
        return strategyMin;
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        // ADX 횡보장 필터: 추세 없는 구간에서 하위 전략 평가 전 즉시 HOLD 반환
        if (adxFilterEnabled && candles.size() >= DEFAULT_ADX_PERIOD * 2 + 1) {
            BigDecimal adx = IndicatorUtils.adx(candles, DEFAULT_ADX_PERIOD);
            if (adx.doubleValue() < DEFAULT_ADX_THRESHOLD) {
                return StrategySignal.hold(String.format(
                        "ADX필터 횡보장 차단: ADX(%.1f) < %.0f (추세 없음)",
                        adx.doubleValue(), DEFAULT_ADX_THRESHOLD));
            }
        }

        double buyScore  = 0.0;
        double sellScore = 0.0;
        StringBuilder reasons = new StringBuilder();

        for (WeightedStrategy ws : strategies) {
            StrategySignal signal = ws.evaluate(candles, params);
            double w    = ws.getWeight();
            double conf = signal.getConfidence().doubleValue();

            switch (signal.getAction()) {
                case BUY  -> buyScore  += w * conf;
                case SELL -> sellScore += w * conf;
                case HOLD -> {}
            }
            reasons.append(ws.getStrategy().getName())
                   .append(':').append(signal.getAction())
                   .append('(').append(String.format("%.0f", signal.getStrength().doubleValue())).append(')')
                   .append(' ');
        }

        String detail = reasons.toString().trim();

        // 가중치 합계로 정규화 (총합이 1.0 초과 시 임계값 왜곡 방지)
        buyScore  /= totalWeight;
        sellScore /= totalWeight;

        return applyEmaFilter(candles, finalSignal(buyScore, sellScore, detail));
    }

    private StrategySignal finalSignal(double buyScore, double sellScore, String detail) {
        if (buyScore > WEAK_THRESHOLD && sellScore > WEAK_THRESHOLD) {
            return StrategySignal.hold(String.format("상충 신호 buy=%.2f sell=%.2f [%s]",
                    buyScore, sellScore, detail));
        }
        if (buyScore > STRONG_THRESHOLD) {
            return StrategySignal.buy(BigDecimal.valueOf(buyScore * 100),
                    String.format("STRONG_BUY score=%.2f [%s]", buyScore, detail));
        }
        if (sellScore > STRONG_THRESHOLD) {
            return StrategySignal.sell(BigDecimal.valueOf(sellScore * 100),
                    String.format("STRONG_SELL score=%.2f [%s]", sellScore, detail));
        }
        if (buyScore > WEAK_THRESHOLD) {
            return StrategySignal.buy(BigDecimal.valueOf(buyScore * 100),
                    String.format("BUY score=%.2f [%s]", buyScore, detail));
        }
        if (sellScore > WEAK_THRESHOLD) {
            return StrategySignal.sell(BigDecimal.valueOf(sellScore * 100),
                    String.format("SELL score=%.2f [%s]", sellScore, detail));
        }
        return StrategySignal.hold(String.format("점수 미달 buy=%.2f sell=%.2f [%s]",
                buyScore, sellScore, detail));
    }

    /**
     * EMA 방향 필터: 추세 방향에 역행하는 신호를 HOLD로 억제한다.
     * - 상승 추세(EMA20 > EMA50): SELL 신호 억제
     * - 하락 추세(EMA20 < EMA50): BUY 신호 억제
     * emaFilterEnabled=false 또는 캔들 부족 시 원본 신호 그대로 반환.
     */
    private StrategySignal applyEmaFilter(List<Candle> candles, StrategySignal signal) {
        if (!emaFilterEnabled || candles.size() < DEFAULT_EMA_LONG
                || signal.getAction() == StrategySignal.Action.HOLD) {
            return signal;
        }

        List<BigDecimal> closes = candles.stream()
                .map(Candle::getClose)
                .toList();

        BigDecimal emaShort = IndicatorUtils.ema(closes, DEFAULT_EMA_SHORT);
        BigDecimal emaLong  = IndicatorUtils.ema(closes, DEFAULT_EMA_LONG);
        boolean uptrend = emaShort.compareTo(emaLong) > 0;

        if (uptrend && signal.getAction() == StrategySignal.Action.SELL) {
            return StrategySignal.hold(String.format(
                    "EMA필터 SELL억제 (EMA%d=%.0f > EMA%d=%.0f 상승추세) [%s]",
                    DEFAULT_EMA_SHORT, emaShort.doubleValue(),
                    DEFAULT_EMA_LONG,  emaLong.doubleValue(), signal.getReason()));
        }
        if (!uptrend && signal.getAction() == StrategySignal.Action.BUY) {
            return StrategySignal.hold(String.format(
                    "EMA필터 BUY억제 (EMA%d=%.0f < EMA%d=%.0f 하락추세) [%s]",
                    DEFAULT_EMA_SHORT, emaShort.doubleValue(),
                    DEFAULT_EMA_LONG,  emaLong.doubleValue(), signal.getReason()));
        }
        return signal;
    }
}
