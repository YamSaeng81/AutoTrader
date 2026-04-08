package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *   <li>RANGE       — BOLLINGER(0.4)  + VWAP(0.4) + GRID(0.2)</li>
 *   <li>VOLATILITY  — ATR_BREAKOUT(0.6) + VOLUME_DELTA(0.4)</li>
 *   <li>TRANSITIONAL — 직전 국면 전략 × 0.5, 신규 BUY 금지</li>
 *   <li>ADX 게이트  — ADX &lt; 20 이면 BUY 차단 (횡보장 진입 방지, DMI 원칙)</li>
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

    private static final BigDecimal ADX_GATE_THRESHOLD = BigDecimal.valueOf(20);
    private static final int        ADX_PERIOD         = 14;

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

        // ADX 게이트: ADX < 20 횡보장 BUY 차단 (DMI 원칙 — 추세 미확인 시 진입 금지)
        // ADX 계산에 period*2+1 캔들 필요 — 부족 시 게이트 스킵 (보수적 허용)
        if (signal.getAction() == StrategySignal.Action.BUY
                && candles.size() >= ADX_PERIOD * 2 + 1) {
            BigDecimal adx = IndicatorUtils.adx(candles, ADX_PERIOD);
            if (adx.compareTo(ADX_GATE_THRESHOLD) < 0) {
                return StrategySignal.hold(
                        "ADX " + adx.setScale(1, RoundingMode.HALF_UP) + " < 20 횡보장 매수 차단 [원신호: " + signal.getReason() + "]");
            }
        }

        return signal;
    }
}
