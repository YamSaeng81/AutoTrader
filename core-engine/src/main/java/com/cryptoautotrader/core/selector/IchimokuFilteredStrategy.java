package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 기존 전략 위에 Ichimoku Kinko Hyo 필터를 추가하는 래퍼 전략.
 *
 * <h3>필터 규칙</h3>
 * <ul>
 *   <li>가격이 구름(Kumo) 안(전환 구간) → 신호 억제 (HOLD): 방향 불확실 구간 진입 차단</li>
 *   <li>가격이 구름 위 또는 아래 → 신호 통과 (추세 확립 구간)</li>
 * </ul>
 *
 * <p>EMA 방향 필터(상승추세 SELL 억제 / 하락추세 BUY 억제)와 중복되지 않는
 * 독립 조건으로, 추세 전환 중인 불확실 구간에서의 진입을 차단한다.</p>
 *
 * <h3>파라미터 (기본값)</h3>
 * <ul>
 *   <li>Tenkan-sen: 9</li>
 *   <li>Kijun-sen: 26</li>
 *   <li>Senkou Span B: 52</li>
 * </ul>
 * 최소 캔들 수: 52 (Senkou Span B 계산에 필요)
 */
public class IchimokuFilteredStrategy implements Strategy {

    private static final int TENKAN_PERIOD  = 9;
    private static final int KIJUN_PERIOD   = 26;
    private static final int SENKOU_B_PERIOD = 52;

    private final String   name;
    private final Strategy delegate;

    public IchimokuFilteredStrategy(String name, Strategy delegate) {
        this.name     = name;
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getMinimumCandleCount() {
        return Math.max(delegate.getMinimumCandleCount(), SENKOU_B_PERIOD);
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        StrategySignal base = delegate.evaluate(candles, params);

        // HOLD 신호는 그대로 통과
        if (base.getAction() == StrategySignal.Action.HOLD) {
            return base;
        }

        // 캔들 부족 시 필터 없이 통과
        if (candles.size() < SENKOU_B_PERIOD) {
            return base;
        }

        return applyIchimokuFilter(candles, base);
    }

    /**
     * Ichimoku 구름 필터 적용.
     * 가격이 구름(Kumo) 안에 있는 전환 구간에서 신호를 억제한다.
     * 구름 위/아래(추세 확립 구간)에서는 신호를 그대로 통과시킨다.
     */
    private StrategySignal applyIchimokuFilter(List<Candle> candles, StrategySignal signal) {
        BigDecimal currentClose = candles.get(candles.size() - 1).getClose();

        BigDecimal tenkan  = IndicatorUtils.ichimokuTenkan(candles, TENKAN_PERIOD);
        BigDecimal kijun   = IndicatorUtils.ichimokuKijun(candles, KIJUN_PERIOD);
        BigDecimal senkouA = IndicatorUtils.ichimokuSenkouA(tenkan, kijun);
        BigDecimal senkouB = IndicatorUtils.ichimokuSenkouB(candles, SENKOU_B_PERIOD);

        BigDecimal cloudTop    = senkouA.max(senkouB);
        BigDecimal cloudBottom = senkouA.min(senkouB);

        boolean priceInsideCloud = currentClose.compareTo(cloudBottom) >= 0
                                && currentClose.compareTo(cloudTop)    <= 0;

        // 가격이 구름 안(전환 구간): 방향 불확실 → 신호 억제
        if (priceInsideCloud) {
            return StrategySignal.hold(String.format(
                    "Ichimoku필터 구름내부 차단: 가격(%.0f) ∈ [구름하단(%.0f), 구름상단(%.0f)] [%s]",
                    currentClose.doubleValue(), cloudBottom.doubleValue(),
                    cloudTop.doubleValue(), signal.getReason()));
        }

        return signal;
    }
}
