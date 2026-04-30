package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 기존 전략 위에 Ichimoku Kinko Hyo 5요소 필터를 추가하는 래퍼 전략.
 *
 * <h3>필터 규칙 (적용 순서)</h3>
 * <ol>
 *   <li><b>구름 내부 차단</b>: 가격이 Kumo 안(전환 구간) → HOLD (방향 불확실)</li>
 *   <li><b>Chikou Span 충돌 차단</b>: 후행스팬이 26캔들 전 가격과 역행 → HOLD
 *       (예: BUY 신호인데 Chikou < 26봉전 가격 → 추세 미확립)</li>
 *   <li><b>Tenkan/Kijun 크로스 충돌 차단</b>: 현재 Tenkan/Kijun 방향이 신호와 역행 → HOLD
 *       (예: BUY 신호인데 Tenkan < Kijun → 단기 추세 하락)</li>
 * </ol>
 *
 * <p>기존(구름 내부만 차단)에서 Chikou Span + Tenkan/Kijun 확장(P1-4).
 * EMA 방향 필터와 독립적인 조건으로, 진짜 일목균형표 신호를 활용.</p>
 *
 * <h3>파라미터 (기본값)</h3>
 * <ul>
 *   <li>Tenkan-sen: 9 (전환선)</li>
 *   <li>Kijun-sen: 26 (기준선)</li>
 *   <li>Senkou Span B: 52 (선행스팬 B)</li>
 *   <li>Chikou Span 후행: 26캔들 (기준선과 동일)</li>
 * </ul>
 * 최소 캔들 수: 52 + 26 = 78 (Chikou 비교를 위해 기존 대비 +26)
 */
public class IchimokuFilteredStrategy implements Strategy {

    private static final int TENKAN_PERIOD   = 9;
    private static final int KIJUN_PERIOD    = 26;
    private static final int SENKOU_B_PERIOD = 52;
    /** Chikou Span: 현재 종가를 KIJUN_PERIOD 캔들 전 가격과 비교 */
    private static final int CHIKOU_SHIFT    = KIJUN_PERIOD;

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
        // Chikou 비교를 위해 SENKOU_B_PERIOD + CHIKOU_SHIFT 필요
        return Math.max(delegate.getMinimumCandleCount(), SENKOU_B_PERIOD + CHIKOU_SHIFT);
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        StrategySignal base = delegate.evaluate(candles, params);

        if (base.getAction() == StrategySignal.Action.HOLD) {
            return base;
        }

        if (candles.size() < SENKOU_B_PERIOD) {
            return base;
        }

        // [필터 1] 구름 내부 차단 (기존)
        StrategySignal afterCloud = applyCloudFilter(candles, base);
        if (afterCloud.getAction() == StrategySignal.Action.HOLD) {
            return afterCloud;
        }

        // [필터 2] Chikou Span 충돌 차단
        if (candles.size() >= SENKOU_B_PERIOD + CHIKOU_SHIFT) {
            StrategySignal afterChikou = applyChikouFilter(candles, afterCloud);
            if (afterChikou.getAction() == StrategySignal.Action.HOLD) {
                return afterChikou;
            }
        }

        // [필터 3] Tenkan/Kijun 방향 충돌 차단
        StrategySignal afterTK = applyTenkanKijunFilter(candles, afterCloud);
        return afterTK;
    }

    /**
     * [필터 1] 구름 내부 차단.
     * 가격이 Kumo(Senkou A~B 사이) 안에 있으면 방향 불확실 → HOLD.
     */
    private StrategySignal applyCloudFilter(List<Candle> candles, StrategySignal signal) {
        BigDecimal currentClose = candles.get(candles.size() - 1).getClose();

        BigDecimal tenkan  = IndicatorUtils.ichimokuTenkan(candles, TENKAN_PERIOD);
        BigDecimal kijun   = IndicatorUtils.ichimokuKijun(candles, KIJUN_PERIOD);
        BigDecimal senkouA = IndicatorUtils.ichimokuSenkouA(tenkan, kijun);
        BigDecimal senkouB = IndicatorUtils.ichimokuSenkouB(candles, SENKOU_B_PERIOD);

        BigDecimal cloudTop    = senkouA.max(senkouB);
        BigDecimal cloudBottom = senkouA.min(senkouB);

        boolean insideCloud = currentClose.compareTo(cloudBottom) >= 0
                           && currentClose.compareTo(cloudTop)    <= 0;

        if (insideCloud) {
            return StrategySignal.hold(String.format(
                    "Ichimoku[구름내부] 차단: 가격(%.0f) ∈ [하단(%.0f), 상단(%.0f)] [%s]",
                    currentClose.doubleValue(), cloudBottom.doubleValue(),
                    cloudTop.doubleValue(), signal.getReason()));
        }
        return signal;
    }

    /**
     * [필터 2] Chikou Span(후행스팬) 충돌 차단.
     * 후행스팬 = 현재 종가를 KIJUN_PERIOD(26)캔들 과거에 표시.
     * 현재 종가와 26캔들 전 종가를 비교하여 신호 방향과 역행하면 차단.
     * - BUY 신호 + Chikou(현재 close) < 26봉전 close → 과거보다 낮음 = 하락 미확립 → HOLD
     * - SELL 신호 + Chikou(현재 close) > 26봉전 close → 과거보다 높음 = 상승 미확립 → HOLD
     */
    private StrategySignal applyChikouFilter(List<Candle> candles, StrategySignal signal) {
        BigDecimal currentClose  = candles.get(candles.size() - 1).getClose();
        BigDecimal chikouRefClose = candles.get(candles.size() - 1 - CHIKOU_SHIFT).getClose();

        boolean chikouAbove = currentClose.compareTo(chikouRefClose) > 0; // 현재 > 26봉전

        if (signal.getAction() == StrategySignal.Action.BUY && !chikouAbove) {
            return StrategySignal.hold(String.format(
                    "Ichimoku[Chikou] BUY차단: 후행스팬(%.0f) < 26봉전가격(%.0f) [%s]",
                    currentClose.doubleValue(), chikouRefClose.doubleValue(), signal.getReason()));
        }
        if (signal.getAction() == StrategySignal.Action.SELL && chikouAbove) {
            return StrategySignal.hold(String.format(
                    "Ichimoku[Chikou] SELL차단: 후행스팬(%.0f) > 26봉전가격(%.0f) [%s]",
                    currentClose.doubleValue(), chikouRefClose.doubleValue(), signal.getReason()));
        }
        return signal;
    }

    /**
     * [필터 3] Tenkan/Kijun 방향 충돌 차단.
     * Tenkan-sen(전환선) vs Kijun-sen(기준선)의 위치로 단기 추세 방향 판단.
     * - BUY 신호 + Tenkan < Kijun (단기 하락 추세) → HOLD
     * - SELL 신호 + Tenkan > Kijun (단기 상승 추세) → HOLD
     */
    private StrategySignal applyTenkanKijunFilter(List<Candle> candles, StrategySignal signal) {
        BigDecimal tenkan = IndicatorUtils.ichimokuTenkan(candles, TENKAN_PERIOD);
        BigDecimal kijun  = IndicatorUtils.ichimokuKijun(candles, KIJUN_PERIOD);

        boolean tenkanAboveKijun = tenkan.compareTo(kijun) > 0;

        if (signal.getAction() == StrategySignal.Action.BUY && !tenkanAboveKijun) {
            return StrategySignal.hold(String.format(
                    "Ichimoku[TK] BUY차단: Tenkan(%.0f) < Kijun(%.0f) (단기하락추세) [%s]",
                    tenkan.doubleValue(), kijun.doubleValue(), signal.getReason()));
        }
        if (signal.getAction() == StrategySignal.Action.SELL && tenkanAboveKijun) {
            return StrategySignal.hold(String.format(
                    "Ichimoku[TK] SELL차단: Tenkan(%.0f) > Kijun(%.0f) (단기상승추세) [%s]",
                    tenkan.doubleValue(), kijun.doubleValue(), signal.getReason()));
        }
        return signal;
    }
}
