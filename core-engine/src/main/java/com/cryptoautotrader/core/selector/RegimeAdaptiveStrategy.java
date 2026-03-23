package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.util.List;
import java.util.Map;

/**
 * 시장 국면(MarketRegime)에 따라 전략 구성을 동적으로 선택하는 적응형 복합 전략 (COMPOSITE).
 *
 * <p>StrategyRegistry에 stateful로 등록되어 세션별 독립 인스턴스를 사용한다.
 * MarketRegimeDetector의 Hysteresis 상태가 세션 단위로 격리된다.
 *
 * <ul>
 *   <li>TREND       — SUPERTREND(0.5) + EMA_CROSS(0.3) + ATR_BREAKOUT(0.2)</li>
 *   <li>RANGE       — BOLLINGER(0.4)  + RSI(0.4) + GRID(0.2)</li>
 *   <li>VOLATILITY  — ATR_BREAKOUT(0.6) + STOCHASTIC_RSI(0.4)</li>
 *   <li>TRANSITIONAL — 직전 국면 전략 × 0.5, 신규 BUY 금지</li>
 * </ul>
 */
public class RegimeAdaptiveStrategy implements Strategy {

    private final MarketRegimeDetector detector = new MarketRegimeDetector();

    @Override
    public String getName() {
        return "COMPOSITE";
    }

    @Override
    public int getMinimumCandleCount() {
        // StrategySelector 전략 중 최대 요구 캔들 수 (Supertrend 기준)
        return 50;
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        MarketRegime regime = detector.detect(candles);
        List<WeightedStrategy> weighted = StrategySelector.select(regime);
        StrategySignal signal = new CompositeStrategy(weighted).evaluate(candles, params);

        // TRANSITIONAL 국면: 신규 진입 금지 — 기존 포지션 유지(SELL)만 허용
        if (regime == MarketRegime.TRANSITIONAL
                && signal.getAction() == StrategySignal.Action.BUY) {
            return StrategySignal.hold(
                    "TRANSITIONAL 국면 신규 진입 금지 [원신호: " + signal.getReason() + "]");
        }
        return signal;
    }
}
