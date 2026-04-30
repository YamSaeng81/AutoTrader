package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 기존 전략 위에 RSI 과매수/과매도 Veto 게이트를 추가하는 래퍼 전략.
 *
 * <h3>설계 배경</h3>
 * <p>RSI를 가중 투표의 하위 전략(0.2 가중치)으로 쓰면 수학적으로 단독 신호 차단이
 * 불가능하다 (confidence > 2.0 이 필요). 대신 래퍼로 분리하면 임계값을 초과한 순간
 * 어떤 강도의 BUY/SELL 신호도 강제로 차단할 수 있다.</p>
 *
 * <h3>필터 규칙</h3>
 * <ul>
 *   <li>RSI > vetoOverbought (기본 75) 이고 내부 전략이 BUY  → HOLD (과매수 구간 매수 차단)</li>
 *   <li>RSI < vetoOversold  (기본 25) 이고 내부 전략이 SELL → HOLD (과매도 구간 매도 차단)</li>
 *   <li>그 외 → 내부 전략 신호 통과</li>
 * </ul>
 *
 * <h3>파라미터</h3>
 * <ul>
 *   <li>rsiPeriod       : RSI 계산 기간 (기본 14)</li>
 *   <li>vetoOverbought  : 매수 차단 RSI 임계값 (기본 75, 표준 70보다 완화)</li>
 *   <li>vetoOversold    : 매도 차단 RSI 임계값 (기본 25, 표준 30보다 완화)</li>
 *   <li>skipRsiVeto     : true이면 이 필터를 비활성화 (기본 false)</li>
 * </ul>
 */
public class RsiVetoStrategy implements Strategy {

    private static final int    DEFAULT_RSI_PERIOD      = 14;
    private static final double DEFAULT_VETO_OVERBOUGHT = 75.0;
    private static final double DEFAULT_VETO_OVERSOLD   = 25.0;

    private final String   name;
    private final Strategy delegate;

    public RsiVetoStrategy(String name, Strategy delegate) {
        this.name     = name;
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getMinimumCandleCount() {
        return Math.max(delegate.getMinimumCandleCount(), DEFAULT_RSI_PERIOD + 1);
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        StrategySignal base = delegate.evaluate(candles, params);

        if (base.getAction() == StrategySignal.Action.HOLD) {
            return base;
        }

        boolean skip = params != null && Boolean.TRUE.equals(params.get("skipRsiVeto"));
        if (skip || candles.size() < DEFAULT_RSI_PERIOD + 1) {
            return base;
        }

        int    period         = getParam(params, "rsiPeriod",      DEFAULT_RSI_PERIOD);
        double vetoOverbought = getParam(params, "vetoOverbought", DEFAULT_VETO_OVERBOUGHT);
        double vetoOversold   = getParam(params, "vetoOversold",   DEFAULT_VETO_OVERSOLD);

        BigDecimal rsi = computeCurrentRsi(candles, period);

        if (base.getAction() == StrategySignal.Action.BUY
                && rsi.doubleValue() > vetoOverbought) {
            return StrategySignal.hold(String.format(
                    "RSIVeto BUY차단: RSI(%.1f) > 과매수임계(%.0f) [%s]",
                    rsi.doubleValue(), vetoOverbought, base.getReason()));
        }

        if (base.getAction() == StrategySignal.Action.SELL
                && rsi.doubleValue() < vetoOversold) {
            return StrategySignal.hold(String.format(
                    "RSIVeto SELL차단: RSI(%.1f) < 과매도임계(%.0f) [%s]",
                    rsi.doubleValue(), vetoOversold, base.getReason()));
        }

        return base;
    }

    private BigDecimal computeCurrentRsi(List<Candle> candles, int period) {
        List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();
        List<BigDecimal> series = IndicatorUtils.rsiSeries(closes, period);
        if (series.isEmpty()) return BigDecimal.valueOf(50.0);
        return series.get(series.size() - 1);
    }

    private int getParam(Map<String, Object> params, String key, int defaultVal) {
        if (params == null) return defaultVal;
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return defaultVal;
    }

    private double getParam(Map<String, Object> params, String key, double defaultVal) {
        if (params == null) return defaultVal;
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return defaultVal;
    }
}
