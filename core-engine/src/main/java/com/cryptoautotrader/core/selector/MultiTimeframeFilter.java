package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.strategy.StrategySignal.Action;

import java.util.List;
import java.util.Map;

/**
 * Multi-Timeframe (다중 타임프레임) 필터
 *
 * <p>상위 TF(Higher TimeFrame, 예: 1h) 추세 방향을 확인하여
 * 하위 TF(Lower TimeFrame, 예: 5m) 신호가 역추세인 경우 억제한다.
 *
 * <pre>
 * HTF 추세 BUY  + LTF 신호 BUY  → BUY  (추세 순방향)
 * HTF 추세 SELL + LTF 신호 SELL → SELL (추세 순방향)
 * HTF 추세 BUY  + LTF 신호 SELL → HOLD (역추세 억제)
 * HTF 추세 SELL + LTF 신호 BUY  → HOLD (역추세 억제)
 * HTF 추세 HOLD                 → LTF 신호 그대로 통과 (중립 구간)
 * </pre>
 *
 * <p>HTF 전략으로는 추세 방향성이 명확한 Supertrend 사용을 권장한다.
 */
public class MultiTimeframeFilter {

    private final Strategy htfStrategy;
    private final Strategy ltfStrategy;

    public MultiTimeframeFilter(Strategy htfStrategy, Strategy ltfStrategy) {
        this.htfStrategy = htfStrategy;
        this.ltfStrategy = ltfStrategy;
    }

    /**
     * HTF + LTF 신호를 결합하여 최종 신호를 반환한다.
     *
     * @param htfCandles  상위 타임프레임 캔들 (예: 1h)
     * @param ltfCandles  하위 타임프레임 캔들 (예: 5m)
     * @param htfParams   HTF 전략 파라미터
     * @param ltfParams   LTF 전략 파라미터
     */
    public StrategySignal evaluate(
            List<Candle> htfCandles,
            List<Candle> ltfCandles,
            Map<String, Object> htfParams,
            Map<String, Object> ltfParams) {

        if (htfCandles.size() < htfStrategy.getMinimumCandleCount()) {
            return ltfStrategy.evaluate(ltfCandles, ltfParams);
        }

        StrategySignal htfSignal = htfStrategy.evaluate(htfCandles, htfParams);
        StrategySignal ltfSignal = ltfStrategy.evaluate(ltfCandles, ltfParams);

        Action htfAction = htfSignal.getAction();
        Action ltfAction = ltfSignal.getAction();

        // HTF가 HOLD이면 LTF 신호를 그대로 통과
        if (htfAction == Action.HOLD) {
            return ltfSignal;
        }

        // HTF와 LTF가 역방향이면 억제
        if (htfAction == Action.BUY && ltfAction == Action.SELL) {
            return StrategySignal.hold(String.format(
                    "MTF 역추세 억제: HTF=%s(%s) vs LTF=%s",
                    htfAction, htfSignal.getReason(), ltfSignal.getReason()));
        }
        if (htfAction == Action.SELL && ltfAction == Action.BUY) {
            return StrategySignal.hold(String.format(
                    "MTF 역추세 억제: HTF=%s(%s) vs LTF=%s",
                    htfAction, htfSignal.getReason(), ltfSignal.getReason()));
        }

        // HTF와 LTF가 같은 방향이면 LTF 신호 통과
        return ltfSignal;
    }
}
