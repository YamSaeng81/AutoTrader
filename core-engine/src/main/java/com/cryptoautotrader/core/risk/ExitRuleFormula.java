package com.cryptoautotrader.core.risk;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SL/TP·time stop 공식의 <b>단일 출처</b> — LIVE·DYNAMIC·PAPER·BACKTEST 네 경로가 모두 여기를 쓴다.
 *
 * <h3>왜 core-engine 으로 올렸나 (2026-09-08)</h3>
 * <p>이 공식은 {@code web-api} 의 {@code ExitRuleCalculator} 에 있었다. 그런데
 * {@code BacktestEngine} 은 {@code core-engine} 에 있고 <b>모듈 의존 방향이 web-api → core-engine</b>
 * 이라, 백테스트는 실전 공식을 <b>호출할 수 없었다.</b> 그래서 백테스트만 다른 공식을 쓰게 됐다:</p>
 *
 * <pre>
 *                    BACKTEST(수정 전)          LIVE · DYNAMIC · PAPER
 *   SL 폭            항상 5.0% 고정              clamp(ATR/가격 × 1.5, floor, 8%)
 *   ATR 반영         안 함                       함
 *   TP 폭            SL × 2 = 항상 10%           min(SL × 2, 8%)
 *   시간 초과 청산    없음                        maxHoldHours (운영 24h)
 * </pre>
 *
 * <p>결함은 "누가 잊었다" 가 아니라 <b>패키지 배치가 공유를 물리적으로 막고 있었다</b> 는 것이다.
 * 검사를 추가해도 못 고친다 — 배치를 바꿔야 사라진다. 그래서 옮겼다.</p>
 *
 * <h3>영향 (판정을 양방향으로 왜곡했다)</h3>
 * <ul>
 *   <li>워치리스트는 ATR 하한을 통과한 고변동 알트다. 실전 SL 은 8% 까지 넓어지는데 백테스트는
 *       5% 고정 → <b>백테스트가 실전보다 훨씬 자주 손절됐다.</b></li>
 *   <li>백테스트 TP 10% 는 실전이 결코 설정하지 않는 값이다. {@link #TP_PCT_MAX} 주석 그대로 —
 *       <i>넓은 SL 은 반드시 맞고 넓은 TP 는 사실상 안 맞는다</i> (07-31 개편 후 5일간 익절 0건/손절 3건).</li>
 *   <li>운영 동적 세션 청산 83건 중 <b>TIME_STOP 이 39건(47%)</b> 인데 백테스트에는 그 경로가
 *       아예 없었다 — 거래 모집단 자체가 달랐다.</li>
 * </ul>
 *
 * <h3>이름이 비슷한 셋을 구분할 것</h3>
 * <ul>
 *   <li><b>{@code ExitRuleFormula}</b>(여기) — SL/TP 폭과 time stop 을 정하는 <b>공식</b>. 단일 출처.</li>
 *   <li>{@link ExitRuleChecker} — 정해진 SL/TP 에 <b>도달했는지</b> 판정 + 트레일링 + 사이징.</li>
 *   <li>{@code ExitRuleCalculator}(web-api) — 이제 이 클래스로의 <b>얇은 위임</b>일 뿐이다.
 *       세션 오버라이드({@code ExitRuleOverrides}) 해석만 담당한다.</li>
 * </ul>
 *
 * <p>상태가 없다. 상수를 바꾸면 {@link #EXIT_RULES_VERSION} 을 함께 올릴 것 —
 * 올리지 않으면 <b>다른 규칙의 백테스트가 한 표본에 섞이고</b>, 수정 전 Walk Forward 결과가
 * 그대로 실자본 승인 근거로 쓰인다.</p>
 */
public final class ExitRuleFormula {

    private ExitRuleFormula() {}

    /**
     * 청산 규칙 버전 — <b>이 클래스의 공식이나 상수를 바꾸면 반드시 올린다.</b>
     *
     * <p>{@code backtest_run.exit_rules_version} 에 기록되고 {@code WalkForwardValidationGate} 가
     * 이 값보다 낮은 실행을 <b>근거로 인정하지 않는다.</b> 게이트는 조합별 <b>최신</b> 실행만 보므로,
     * 이 장치가 없으면 <b>재실행되지 않은 조합은 수정 전 판정을 영구히 유지한다</b> —
     * 조용히 낡은 근거로 실자본이 승인된다.</p>
     *
     * <p>이력:</p>
     * <ul>
     *   <li>1 — 2026-09-08 이전. 백테스트가 SL 5% 고정 · TP 10% · time stop 없음으로 돌던 시기.
     *       그리고 {@code updateTrailingStops} 의 손실 구간 SL 조임(= 실질 0.3% 손절)이 살아 있던 시기.</li>
     *   <li>2 — 2026-09-08. 네 경로가 이 공식을 공유. 백테스트에 ATR 기반 SL·TP 상한·time stop 적용.</li>
     * </ul>
     */
    public static final int EXIT_RULES_VERSION = 2;

    // ── 손절폭 (2026-07-31 전면 개편 → 08-05 재조정, 원래 DynamicTradingService 소재) ──────
    //
    // 개편 전: 세션 고정 stopLossPct(5%) 또는 전략 제안값을 그대로 사용. 07-29~31 실측에서
    //   청산 6건이 전부 SL 강제청산(전략 SELL 청산 0건)이었고, 실현률이 SL 폭보다 정확히
    //   수수료(0.07~0.24%p)만큼만 나빴다. 즉 손실은 전략 판단이 아니라 **청산 규칙**이 만들었다.
    //   같은 신호들의 사후 4h 수익률은 평균 −0.17%(KAITO +1.23%)로 거의 중립 = 교과서적 휩쏘.
    //   워치리스트는 ATR 하한을 통과한 고변동 알트인데 SL 3~5%는 1 ATR 에도 못 미친다.
    //
    // 개편 후: SL 폭 = clamp(ATR(14)/가격 × SL_ATR_MULTIPLIER, floorPct, SL_PCT_MAX).
    //   floorPct(세션 설정값)는 이제 상한이 아니라 **하한**이다.
    //
    // 재조정(2026-08-05): 방향은 옳았으나 **폭이 과했다**. META2 는 ATR 3.48% → SL 폭 6.96%,
    //   실현 −7.05%/−7.08% 로 초과분이 체결 오버슛 0.22% + 수수료 0.09% 뿐이었다
    //   = 손실의 거의 전부가 SL 폭 자체. 배수 2.0 → 1.5, 상한 12% → 8%.
    private static final int SL_ATR_PERIOD = 14;

    /** ATR 배수 — 1.5 ATR 밖에 SL 을 두어 정상 등락(1 ATR 내외)에 털리지 않게 한다. */
    private static final BigDecimal SL_ATR_MULTIPLIER = new BigDecimal("1.5");

    /** SL 폭 상한 % — 초저유동 종목의 비정상 ATR 로 손실이 무한정 커지는 것을 막는 안전판. */
    private static final BigDecimal SL_PCT_MAX = new BigDecimal("8.0");

    /** 익절 = 손절폭 × 이 배수 (손익비 2:1 유지) */
    private static final BigDecimal TP_RR_MULTIPLIER = new BigDecimal("2.0");

    /**
     * TP 폭 상한 % — <b>손익비보다 도달 가능성이 우선</b>이다.
     *
     * <p>2026-08-05 실측: TP 를 SL 폭의 2배로 따라 키우다 보니 KRW-META2 는 TP 가 <b>+14.10%</b>
     * 로 잡혔다. 넓은 SL 은 반드시 맞고 넓은 TP 는 사실상 안 맞는다 — 07-31 개편 이후 5일간
     * <b>익절 0건 / 손절 3건</b>이 그 결과다. SL 상한(8%)과 짝을 맞춰 TP 도 8% 로 자른다.
     * 이 구간에서는 손익비가 2:1 아래로 내려가지만, 도달하지 않는 TP 의 명목 손익비보다
     * 실현되는 TP 가 낫다.</p>
     */
    private static final BigDecimal TP_PCT_MAX = new BigDecimal("8.0");

    /**
     * 이 클래스가 매매 거동에 쓰는 상수 전체 — 규칙 지문({@code RulesetRegistry})에 담긴다.
     *
     * <p>지문의 {@code exit.*} 키는 {@code ExitRuleConfig}(DB 설정)에서 나오는데,
     * <b>SL/TP 를 실제로 계산하는 곳은 여기다.</b> 이 상수들이 지문 밖에 있으면
     * {@code SL_ATR_MULTIPLIER} 를 1.5 → 2.0 으로 바꿔 손절폭이 33% 넓어져도 지문이 그대로다
     * — <b>서로 다른 규칙의 거래가 한 표본에 섞인다.</b></p>
     *
     * <p>새 상수를 추가하면 여기에도 넣을 것. 빠뜨리면
     * {@code RulesetFingerprintTest.everyExitCalculatorConstantIsFingerprinted} 가 깨진다.</p>
     */
    public static Map<String, String> behaviorParams() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("slAtrPeriod", Integer.toString(SL_ATR_PERIOD));
        m.put("slAtrMultiplier", plain(SL_ATR_MULTIPLIER));
        m.put("slPctMax", plain(SL_PCT_MAX));
        m.put("tpRrMultiplier", plain(TP_RR_MULTIPLIER));
        m.put("tpPctMax", plain(TP_PCT_MAX));
        return m;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** 기본 배수 — 호출부가 오버라이드를 갖고 있지 않을 때 쓴다. */
    public static BigDecimal defaultSlAtrMultiplier() {
        return SL_ATR_MULTIPLIER;
    }

    /** 기본 배수 — 호출부가 오버라이드를 갖고 있지 않을 때 쓴다. */
    public static BigDecimal defaultTpRrMultiplier() {
        return TP_RR_MULTIPLIER;
    }

    /**
     * 손절폭(%) 결정 — {@code clamp(ATR(14)/가격 × 배수, floorPct, SL_PCT_MAX)}.
     *
     * <p>{@code floorPct}(세션 설정값)는 <b>하한</b>이다. 변동성이 큰 종목일수록 SL 이 넓어져,
     * 정상 등락(1 ATR 내외)에 강제청산되는 휩쏘를 막는다. ATR 계산이 불가능하면(캔들 부족 등)
     * floorPct 그대로 폴백한다.</p>
     *
     * @param slAtrMultiplier null 이면 {@link #SL_ATR_MULTIPLIER} 기본값
     */
    public static BigDecimal resolveStopLossPct(BigDecimal floorPct, List<Candle> candles,
                                                 BigDecimal currentPrice, BigDecimal slAtrMultiplier) {
        if (floorPct == null) {
            floorPct = BigDecimal.ZERO;
        }
        if (candles == null || currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return floorPct;
        }
        try {
            BigDecimal atr = IndicatorUtils.atr(candles, SL_ATR_PERIOD);
            if (atr == null || atr.compareTo(BigDecimal.ZERO) <= 0) {
                return floorPct;
            }
            BigDecimal atrPct = atr.divide(currentPrice, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            return atrPct.multiply(slAtrMultiplier != null ? slAtrMultiplier : SL_ATR_MULTIPLIER)
                    .max(floorPct).min(SL_PCT_MAX);
        } catch (Exception e) {
            // ATR 계산 데이터 부족 등 — 진입을 막을 사유는 아니므로 floorPct 로 진행
            return floorPct;
        }
    }

    /**
     * 익절가 결정 — {@code min(진입가 × (1 + SL폭 × 2), 진입가 × (1 + TP_PCT_MAX))}.
     *
     * <p>기본은 실제 채택된 SL 폭의 {@link #TP_RR_MULTIPLIER}배(손익비 2:1)지만
     * {@link #TP_PCT_MAX} 로 자른다. SL 만 넓히고 TP 를 그대로 두면 손익비가 무너지고,
     * TP 까지 따라 키우면 영영 도달하지 않는다.</p>
     *
     * @param tpRrMultiplier null 이면 {@link #TP_RR_MULTIPLIER} 기본값
     */
    public static BigDecimal resolveTakeProfitPrice(BigDecimal currentPrice, BigDecimal stopLossPrice,
                                                     BigDecimal suggestedTakeProfit,
                                                     BigDecimal tpRrMultiplier) {
        BigDecimal effectiveSlPct = BigDecimal.ONE
                .subtract(stopLossPrice.divide(currentPrice, 8, RoundingMode.HALF_UP))
                .multiply(BigDecimal.valueOf(100));
        BigDecimal targetTpPct = effectiveSlPct
                .multiply(tpRrMultiplier != null ? tpRrMultiplier : TP_RR_MULTIPLIER)
                .min(TP_PCT_MAX);
        BigDecimal atrTakeProfitPrice = currentPrice.multiply(BigDecimal.ONE.add(
                        targetTpPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                .setScale(8, RoundingMode.HALF_UP);
        if (suggestedTakeProfit == null) {
            return atrTakeProfitPrice;
        }
        BigDecimal tpCeilingPrice = currentPrice.multiply(BigDecimal.ONE.add(
                        TP_PCT_MAX.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                .setScale(8, RoundingMode.HALF_UP);
        return suggestedTakeProfit.max(atrTakeProfitPrice).min(tpCeilingPrice);
    }

    /**
     * 시간 초과 청산(time stop) 판정 — 손익과 무관하게, 보유시간이 {@code maxHoldHours} 를
     * 넘으면 청산 대상이다. {@code maxHoldHours} 가 null 이거나 0 이하면 비활성(항상 false).
     *
     * <p>가격 기반 SL/TP 만 있으면 저변동 종목(스테이블코인 등)은 어느 쪽에도 영원히 도달하지
     * 못해 자본이 무기한 묶인다 — DYNAMIC 세션 38 KRW-RLUSD 42시간 고착(2026-07-31),
     * LIVE 세션 194 BTC 136시간 고착(2026-08-06)이 같은 원인이다.</p>
     */
    public static boolean shouldTimeStop(Integer maxHoldHours, Instant openedAt, Instant now) {
        if (maxHoldHours == null || maxHoldHours <= 0 || openedAt == null) {
            return false;
        }
        return Duration.between(openedAt, now).toHours() >= maxHoldHours;
    }
}
