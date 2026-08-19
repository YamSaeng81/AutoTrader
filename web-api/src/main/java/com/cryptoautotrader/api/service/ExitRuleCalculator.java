package com.cryptoautotrader.api.service;

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
 * LIVE·DYNAMIC 두 트레이딩 서비스가 공유하는 청산 규칙 계산 — 신호 기대값 검증 게이트에
 * 이은 P1 두 번째 항목("LIVE/DYNAMIC 청산 엔진 통합")의 범위를 SL/TP 공식 + time stop
 * 판정으로 좁혀 추출한 것이다.
 *
 * <h3>배경</h3>
 * <p>2026-07-31 개편에서 동적 세션(DynamicTradingService)에 ATR 기반 SL/TP와 time stop을
 * 도입했지만, LIVE(LiveTradingService)는 계속 고정 {@code stopLossPct}만 쓰고 있었다.
 * 그 결과 LIVE 세션 194의 BTC 포지션이 136시간 동안 청산되지 못하고 물려 있었다 —
 * 가격 기반 청산(고정 5% SL)만 있고 시간 기반 탈출구가 아예 없었기 때문이다.
 * 같은 계산을 두 곳에 따로 구현해 두면 이런 이식 누락이 반복된다(07-31 개편도, 이후
 * 08-06의 블랙스완 조임 제거도 "한쪽 고치고 한쪽 나중에" 패턴이었다) — 이 클래스가
 * 그 반복을 없앤다: 이제 두 서비스가 같은 함수를 호출한다.</p>
 *
 * <p>기존 {@code DynamicTradingService}의 package-private static 메서드였던 것을 그대로
 * 옮겼다 — 상수·판정 로직 전부 동일, 계산 결과는 바뀌지 않는다. LIVE 쪽만 이 클래스를
 * 새로 호출하도록 바뀌므로, 동작이 바뀌는 쪽은 LIVE뿐이다.</p>
 */
final class ExitRuleCalculator {

    private ExitRuleCalculator() {}

    // ── 손절폭 (2026-07-31 전면 개편, 원래 DynamicTradingService 소재) ──────────
    //
    // 개편 전: 세션 고정 stopLossPct(5%) 또는 전략 제안값을 그대로 사용 + 블랙스완 발동 시
    //   현재가 기준 1×ATR로 **조임**. 07-29~31 실측 결과 **청산 6건이 전부 SL 강제청산**
    //   (전략 SELL 청산 0건)이었고, 실현률이 SL 폭보다 정확히 수수료(0.07~0.24%p)만큼만
    //   나빴다. 즉 손실은 전략 판단이 아니라 **청산 규칙**이 만들었다.
    //
    // 결정적 증거: 같은 신호들의 사후 4h 수익률은 평균 -0.17%(KAITO는 +1.23%)로 거의 중립.
    //   손절만 안 했으면 본전권이었다 = 교과서적 휩쏘. 워치리스트는 ATR 하한을 통과한
    //   고변동 알트인데 SL 3~5%는 1 ATR 에도 못 미쳐 정상 등락에 확실히 걸린다.
    //
    // 개편 후: SL 폭 = clamp(ATR(14)/가격 × SL_ATR_MULTIPLIER, floorPct, MAX).
    //   변동성이 큰 종목일수록 SL이 **넓어진다**. floorPct(세션 설정값)는 이제 상한이 아니라 **하한**이다.
    //
    // 재조정 (2026-08-05): 개편 자체는 옳았으나 **폭이 과했다**. 07-31~08-05 운영 실측에서
    //   동적 세션 청산 3건이 전부 손절이고 평균 −7.4%였다. 세부 분해:
    //   - pos 2386/2387 KRW-META2: ATR 3.48% → SL 폭 **6.96%**, 실현 −7.05%/−7.08%.
    //     초과분은 체결 오버슛 0.22% + 수수료 0.09%뿐 = **손실의 거의 전부가 SL 폭 자체**.
    //   - pos 2383 KRW-ELSA: ATR 2.85% → SL 폭 5.70%, 실현 −8.33% (나머지는 아래 감시 지연).
    //   사용자가 설정한 5%의 1.4~1.7배가 실제로 나갔다. 배수 2.0 → 1.5, 상한 12% → 8%로
    //   조인다. 하한(floorPct)은 그대로라 저변동 종목의 동작은 바뀌지 않는다.
    //
    // 2026-08-06: 이 상수·공식을 DynamicTradingService에서 이 클래스로 옮겨 LIVE와 공유한다.
    //   그 전까지 LIVE는 이 개편·재조정을 전혀 받지 못하고 고정 stopLossPct만 쓰고 있었다.
    private static final int SL_ATR_PERIOD = 14;
    /** ATR 배수 — 1.5 ATR 밖에 SL을 두어 정상 등락(1 ATR 내외)에 털리지 않게 한다. */
    private static final BigDecimal SL_ATR_MULTIPLIER = new BigDecimal("1.5");
    /** SL 폭 상한 % — 초저유동 종목의 비정상 ATR로 손실이 무한정 커지는 것을 막는 안전판. */
    private static final BigDecimal SL_PCT_MAX = new BigDecimal("8.0");
    /** 익절 = 손절폭 × 이 배수 (손익비 2:1 유지) */
    private static final BigDecimal TP_RR_MULTIPLIER = new BigDecimal("2.0");
    /**
     * TP 폭 상한 % — <b>손익비보다 도달 가능성이 우선</b>이다.
     *
     * <p><b>근거 (2026-08-05 실측)</b>: TP를 SL 폭의 2배로 따라 키우다 보니 KRW-META2는
     * TP가 <b>+14.10%</b>로 잡혔다. 넓은 SL은 반드시 맞고 넓은 TP는 사실상 안 맞는다 —
     * 07-31 개편 이후 5일간 <b>익절 0건 / 손절 3건</b>이 그 결과다. SL 상한(8%)과 짝을 맞춰
     * TP도 8%로 자른다. 이 구간에서는 손익비가 2:1 아래로 내려가지만, 도달하지 않는 TP의
     * 명목 손익비보다 실현되는 TP가 낫다.</p>
     */
    private static final BigDecimal TP_PCT_MAX = new BigDecimal("8.0");

    /**
     * 이 클래스가 매매 거동에 쓰는 상수 전체 — 규칙 지문({@code RulesetRegistry})에 담긴다.
     *
     * <h3>왜 별도로 노출하는가</h3>
     * <p>지문의 {@code exit.*} 키는 {@code ExitRuleConfig}(DB 설정)에서 나오는데,
     * <b>세 엔진이 SL/TP 를 실제로 계산하는 곳은 여기다.</b> LIVE·DYNAMIC·PAPER 모두
     * {@link #resolveStopLossPct}/{@link #resolveTakeProfitPrice} 를 호출하고,
     * DYNAMIC 은 {@code ExitRuleConfig} 를 아예 참조하지 않는다.</p>
     *
     * <p>따라서 이 상수들이 지문 밖에 있으면 {@code SL_ATR_MULTIPLIER} 를 1.5 → 2.0 으로
     * 바꿔 손절폭이 33% 넓어져도 지문이 그대로다 — <b>서로 다른 규칙의 거래가 한 표본에
     * 섞인다.</b> 08-07 워치리스트 회귀와 정확히 같은 유형의 사고다.</p>
     *
     * <p>새 상수를 추가하면 여기에도 넣을 것. 빠뜨리면
     * {@code RulesetFingerprintTest.everyExitCalculatorConstantIsFingerprinted} 가 깨진다.</p>
     */
    static Map<String, String> behaviorParams() {
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

    /**
     * 손절폭(%) 결정 — {@code clamp(ATR(14)/가격 × 배수, floorPct, SL_PCT_MAX)}.
     *
     * <p>{@code floorPct}(세션 설정값)는 <b>하한</b>이다. 변동성이 큰 종목일수록 SL이 넓어져,
     * 정상 등락(1 ATR 내외)에 강제청산되는 휩쏘를 막는다. ATR 계산이 불가능하면(캔들 부족 등)
     * floorPct 그대로 폴백한다.</p>
     */
    static BigDecimal resolveStopLossPct(BigDecimal floorPct, List<Candle> candles, BigDecimal currentPrice) {
        if (floorPct == null) {
            floorPct = BigDecimal.ZERO;
        }
        if (candles == null || currentPrice == null
                || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return floorPct;
        }
        try {
            BigDecimal atr = IndicatorUtils.atr(candles, SL_ATR_PERIOD);
            if (atr == null || atr.compareTo(BigDecimal.ZERO) <= 0) {
                return floorPct;
            }
            BigDecimal atrPct = atr.divide(currentPrice, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            return atrPct.multiply(SL_ATR_MULTIPLIER).max(floorPct).min(SL_PCT_MAX);
        } catch (Exception e) {
            // ATR 계산 데이터 부족 등 — 진입을 막을 사유는 아니므로 floorPct로 진행
            return floorPct;
        }
    }

    /**
     * 익절가 결정 — {@code min(진입가 × (1 + SL폭 × 2), 진입가 × (1 + TP_PCT_MAX))}.
     *
     * <p>기본은 실제 채택된 SL 폭의 {@link #TP_RR_MULTIPLIER}배(손익비 2:1)지만
     * {@link #TP_PCT_MAX}로 자른다. SL만 넓히고 TP를 그대로 두면 손익비가 무너지고,
     * TP까지 따라 키우면 영영 도달하지 않는다.</p>
     */
    static BigDecimal resolveTakeProfitPrice(BigDecimal currentPrice, BigDecimal stopLossPrice,
                                              BigDecimal suggestedTakeProfit) {
        BigDecimal effectiveSlPct = BigDecimal.ONE
                .subtract(stopLossPrice.divide(currentPrice, 8, RoundingMode.HALF_UP))
                .multiply(BigDecimal.valueOf(100));
        BigDecimal targetTpPct = effectiveSlPct.multiply(TP_RR_MULTIPLIER).min(TP_PCT_MAX);
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
     * 시간 초과 청산(time stop) 판정 — 손익과 무관하게, 보유시간이 {@code maxHoldHours}를
     * 넘으면 청산 대상이다. {@code maxHoldHours}가 null이거나 0 이하면 비활성(항상 false).
     *
     * <p>가격 기반 SL/TP만 있으면 저변동 종목(스테이블코인 등)은 어느 쪽에도 영원히
     * 도달하지 못해 자본이 무기한 묶인다 — DYNAMIC 세션 38 KRW-RLUSD 42시간 고착(2026-07-31),
     * LIVE 세션 194 BTC 136시간 고착(2026-08-06)이 같은 원인으로 발생한 사례다.</p>
     */
    static boolean shouldTimeStop(Integer maxHoldHours, Instant openedAt, Instant now) {
        if (maxHoldHours == null || maxHoldHours <= 0 || openedAt == null) {
            return false;
        }
        return Duration.between(openedAt, now).toHours() >= maxHoldHours;
    }
}
