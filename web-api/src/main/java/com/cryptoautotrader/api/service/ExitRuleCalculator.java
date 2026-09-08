package com.cryptoautotrader.api.service;

import com.cryptoautotrader.core.risk.ExitRuleFormula;
import com.cryptoautotrader.strategy.Candle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 세션 오버라이드를 적용해 청산 공식을 호출하는 <b>얇은 위임층</b>.
 * 실제 공식과 상수는 전부 {@link ExitRuleFormula}(core-engine)에 있다.
 *
 * <h3>배경</h3>
 * <p>2026-07-31 개편에서 동적 세션에 ATR 기반 SL/TP 와 time stop 을 도입했지만 LIVE 는 계속
 * 고정 {@code stopLossPct} 만 쓰고 있었다. 그 결과 LIVE 세션 194 의 BTC 포지션이 136시간 동안
 * 청산되지 못했다 — 가격 기반 청산만 있고 시간 기반 탈출구가 없었기 때문이다. 같은 계산을 두 곳에
 * 구현해 두면 이런 이식 누락이 반복된다. 08-06 에 이 클래스를 만들어 LIVE·DYNAMIC 이 같은 함수를
 * 호출하게 했고, PAPER 도 뒤따라 붙었다.</p>
 *
 * <h3>그래도 백테스트가 빠져 있었다 (2026-09-08)</h3>
 * <p>세 매매 엔진을 통합해도 {@code BacktestEngine} 만은 다른 공식을 썼다 — <b>이 클래스가
 * web-api 에 있고 백테스트는 core-engine 에 있어, 모듈 의존 방향상 호출이 물리적으로
 * 불가능했기 때문이다.</b> "잊었다" 가 아니라 배치가 공유를 막고 있었다.</p>
 *
 * <p>그래서 공식과 상수를 {@link ExitRuleFormula}(core-engine)로 옮기고, 이 클래스는
 * <b>{@link ExitRuleOverrides} 해석만</b> 남겼다. 계산 결과는 이전과 완전히 동일하다 —
 * 상수도 로직도 그대로 옮겼을 뿐이다. 달라지는 것은 백테스트 쪽뿐이다.</p>
 *
 * <p>새 상수·공식 변경은 {@link ExitRuleFormula} 에 한다. 여기에 상수를 다시 만들면
 * 백테스트가 또 갈라진다.</p>
 */
final class ExitRuleCalculator {

    private ExitRuleCalculator() {}

    /**
     * 규칙 지문에 담기는 상수 — {@link ExitRuleFormula#behaviorParams()} 그대로다.
     *
     * <p>{@code RulesetRegistry} 가 {@code exitcalc.*} 접두어로 담는다. 접두어를 바꾸면
     * 기존 지문이 전부 달라져 표본이 갈리므로 그대로 둔다(상수가 옮겨간 것은 구현 세부사항이고,
     * <b>값이 바뀌지 않았으므로 지문도 바뀌면 안 된다</b>).</p>
     */
    static Map<String, String> behaviorParams() {
        return ExitRuleFormula.behaviorParams();
    }

    /** {@link #resolveStopLossPct(BigDecimal, List, BigDecimal, ExitRuleOverrides)} 의 오버라이드 없는 형태. */
    static BigDecimal resolveStopLossPct(BigDecimal floorPct, List<Candle> candles, BigDecimal currentPrice) {
        return resolveStopLossPct(floorPct, candles, currentPrice, ExitRuleOverrides.NONE);
    }

    /**
     * 손절폭(%) 결정 — {@code clamp(ATR(14)/가격 × 배수, floorPct, 상한)}.
     * 공식은 {@link ExitRuleFormula#resolveStopLossPct}, 여기서는 세션 오버라이드만 얹는다.
     *
     * <p>{@code overrides} 가 {@link ExitRuleOverrides#NONE} 이면 기본 동작과 완전히 같다.
     * 오버라이드 값은 {@code strategy_params} 로 들어오고 {@code RulesetRegistry} 가
     * {@code strategy.params} 키로 지문에 담으므로, arm 별 거래가 자동으로 다른 표본이 된다
     * (2026-08-24 손절폭 A/B).</p>
     */
    static BigDecimal resolveStopLossPct(BigDecimal floorPct, List<Candle> candles,
                                          BigDecimal currentPrice, ExitRuleOverrides overrides) {
        return ExitRuleFormula.resolveStopLossPct(floorPct, candles, currentPrice,
                overrides.slAtrMultiplierOr(ExitRuleFormula.defaultSlAtrMultiplier()));
    }

    /** {@link #resolveTakeProfitPrice(BigDecimal, BigDecimal, BigDecimal, ExitRuleOverrides)} 의 오버라이드 없는 형태. */
    static BigDecimal resolveTakeProfitPrice(BigDecimal currentPrice, BigDecimal stopLossPrice,
                                              BigDecimal suggestedTakeProfit) {
        return resolveTakeProfitPrice(currentPrice, stopLossPrice, suggestedTakeProfit,
                ExitRuleOverrides.NONE);
    }

    /**
     * 익절가 결정 — 공식은 {@link ExitRuleFormula#resolveTakeProfitPrice}.
     *
     * <p><b>SL 을 넓히는 실험군은 반드시 {@code tpRrMultiplier} 를 함께 낮춰야 한다.</b>
     * TP 가 SL 에 연동돼 있어 SL 만 넓히면 TP 도 멀어져 도달 불가가 된다 — 07-31 개편이
     * 그렇게 실패했다({@code ExitRuleFormula.TP_PCT_MAX} javadoc 참조).</p>
     */
    static BigDecimal resolveTakeProfitPrice(BigDecimal currentPrice, BigDecimal stopLossPrice,
                                              BigDecimal suggestedTakeProfit,
                                              ExitRuleOverrides overrides) {
        return ExitRuleFormula.resolveTakeProfitPrice(currentPrice, stopLossPrice, suggestedTakeProfit,
                overrides.tpRrMultiplierOr(ExitRuleFormula.defaultTpRrMultiplier()));
    }

    /**
     * 시간 초과 청산(time stop) 판정 — {@link ExitRuleFormula#shouldTimeStop} 그대로.
     *
     * <p>가격 기반 SL/TP 만 있으면 저변동 종목은 어느 쪽에도 영원히 도달하지 못해 자본이 무기한
     * 묶인다 — DYNAMIC 세션 38 KRW-RLUSD 42시간 고착(2026-07-31), LIVE 세션 194 BTC 136시간
     * 고착(2026-08-06)이 같은 원인이다.</p>
     */
    static boolean shouldTimeStop(Integer maxHoldHours, Instant openedAt, Instant now) {
        return ExitRuleFormula.shouldTimeStop(maxHoldHours, openedAt, now);
    }
}
