package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.util.List;
import java.util.Map;

/**
 * 상위 타임프레임(HTF) 추세 방향이 하위 타임프레임(LTF) 신호와 일치할 때만 진입을 허용하는 래퍼 전략.
 *
 * <h3>동작 원리</h3>
 * <ol>
 *   <li>입력 캔들(LTF, 예: H1)로 LTF 신호 획득.</li>
 *   <li>LTF 캔들을 {@code htfFactor}배로 다운샘플(예: ×4 → H4).</li>
 *   <li>HTF 캔들로 추세 방향 판정(예: Supertrend BUY/SELL).</li>
 *   <li>HTF·LTF 방향이 다르면 HOLD — 역추세 진입 차단.</li>
 *   <li>HTF가 HOLD(중립)이거나 방향 일치 → LTF 신호 통과.</li>
 * </ol>
 *
 * <h3>신호이유 태그</h3>
 * <ul>
 *   <li>{@code [H4:BUY]}  — HTF 상승 추세 확인 후 통과</li>
 *   <li>{@code [H4:SELL]} — HTF 하락 추세 확인 후 통과</li>
 *   <li>{@code [H4:중립]} — HTF HOLD, LTF 신호 그대로 통과</li>
 * </ul>
 *
 * <h3>strictHtf 모드</h3>
 * 기본(false)은 위 설명대로 HTF HOLD·데이터부족 시 LTF 신호를 통과시키는 보수적 허용이다.
 * {@code strictHtf=true}면 HTF가 명시적으로 방향(BUY/SELL)을 확인해 주지 않는 한 진입을 막는다:
 * <ul>
 *   <li>HTF 데이터 부족 → 통과 대신 HOLD ({@code [H4:데이터부족·strict]})</li>
 *   <li>HTF HOLD(중립) → 통과 대신 HOLD ({@code [H4:중립·strict]})</li>
 * </ul>
 * 운영 초반의 과공격 진입(데이터부족 통과)과 추세 미확인 진입을 차단하려는 용도.
 *
 * <h3>최소 캔들 수</h3>
 * {@code max(ltfDelegate.min, htfFactor × htfStrategy.min)} — HTF 계산에 필요한 LTF 캔들 포함.
 */
public class MtfConfirmedStrategy implements Strategy {

    private final String   name;
    private final Strategy ltfDelegate;  // H1 신호 전략
    private final Strategy htfStrategy;  // HTF 추세 확인 전략 (다운샘플 캔들에 적용)
    private final int      htfFactor;    // 다운샘플 배율 (H1→H4 = 4)
    private final boolean  strictHtf;    // true: HTF 미확인(HOLD/데이터부족) 시 진입 차단

    /** 기본 생성자 — 보수적 허용 모드(strictHtf=false). */
    public MtfConfirmedStrategy(String name, Strategy ltfDelegate,
                                Strategy htfStrategy, int htfFactor) {
        this(name, ltfDelegate, htfStrategy, htfFactor, false);
    }

    public MtfConfirmedStrategy(String name, Strategy ltfDelegate,
                                Strategy htfStrategy, int htfFactor, boolean strictHtf) {
        if (htfFactor < 2) throw new IllegalArgumentException("htfFactor must be >= 2: " + htfFactor);
        this.name        = name;
        this.ltfDelegate = ltfDelegate;
        this.htfStrategy = htfStrategy;
        this.htfFactor   = htfFactor;
        this.strictHtf   = strictHtf;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getMinimumCandleCount() {
        // LTF 최소 요구량 vs HTF 전략을 돌리기 위한 LTF 캔들 수 중 큰 값
        return Math.max(
                ltfDelegate.getMinimumCandleCount(),
                htfFactor * htfStrategy.getMinimumCandleCount());
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        // 1. LTF(H1) 신호 먼저 계산 — HOLD이면 HTF 계산 불필요
        StrategySignal ltfSignal = ltfDelegate.evaluate(candles, params);
        if (ltfSignal.getAction() == StrategySignal.Action.HOLD) {
            return ltfSignal;
        }

        // 2. H1 → HTF 다운샘플
        List<Candle> htfCandles = CandleDownsampler.downsample(candles, htfFactor);

        // 3. HTF 캔들 부족 — strict면 차단, 아니면 LTF 신호 통과 (보수적 허용)
        if (htfCandles.size() < htfStrategy.getMinimumCandleCount()) {
            if (strictHtf) {
                return StrategySignal.hold(String.format(
                        "[H4:데이터부족·strict] HTF 미확인 진입 차단 [%s]", ltfSignal.getReason()));
            }
            return tag(ltfSignal, "[H4:데이터부족] ");
        }

        // 4. HTF 추세 방향 판정
        StrategySignal htfSignal = htfStrategy.evaluate(htfCandles, params);

        // 5. HTF HOLD(중립) — strict면 차단, 아니면 LTF 신호 통과
        if (htfSignal.getAction() == StrategySignal.Action.HOLD) {
            if (strictHtf) {
                return StrategySignal.hold(String.format(
                        "[H4:중립·strict] HTF 추세 미확인 진입 차단 [%s]", ltfSignal.getReason()));
            }
            return tag(ltfSignal, "[H4:중립] ");
        }

        // 6. 방향 불일치 → 역추세 차단
        if (htfSignal.getAction() != ltfSignal.getAction()) {
            return StrategySignal.hold(String.format(
                    "MTF불일치: H4=%s vs H1=%s [%s]",
                    htfSignal.getAction(), ltfSignal.getAction(), ltfSignal.getReason()));
        }

        // 7. 방향 일치 → HTF 태그 붙여 통과
        return tag(ltfSignal, String.format("[H4:%s] ", htfSignal.getAction()));
    }

    /**
     * 신호에 HTF 태그를 prefix로 붙여 반환한다.
     * 하위(LTF) 전략이 제안한 suggestedStopLoss/takeProfit은 toBuilder로 보존한다.
     */
    private static StrategySignal tag(StrategySignal signal, String prefix) {
        return signal.toBuilder()
                .reason(prefix + signal.getReason())
                .build();
    }
}
