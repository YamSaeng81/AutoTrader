package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * EMA200 레짐 게이트 — BUY 신호를 장기 추세(EMA200) 위에서만 허용하는 단일 진실 소스.
 *
 * <p>이전에는 동일 로직이 {@code LiveTradingService.isAboveEma200Live()}와
 * {@code BacktestEngine.isAboveEma200()} 두 곳에 중복 구현되어 있었고,
 * 라이브에만 DOGE 예외가 있고 백테스트에는 없어 두 경로의 동작이 어긋났다
 * (2026-06-01 전략 전체 분석 P1-B). 이 클래스로 통합해 백테스트↔라이브 정합을 보장한다.
 *
 * <h3>규칙</h3>
 * <ul>
 *   <li>현재 종가 &gt; EMA200 → BUY 허용 (상승 레짐)</li>
 *   <li>캔들 200개 미만 → EMA200 산출 불가 → 보수적으로 허용(true), 차단하지 않음</li>
 * </ul>
 *
 * <p><b>DOGE 예외 제거 (2026-07-15)</b>: "EMA200 아래에서도 수익 패턴 존재"(PoC) 근거로
 * DOGE만 게이트를 면제했으나, 2026-07 실전에서 반증됨 — 라이브 세션 189(DOGE, CMI_V2)가
 * 하락장에서 유일하게 면제 덕에 계속 진입해 5연속 손절(-591원, -5.9%) 후 비상 정지.
 * 게이트가 지켜준 타 코인 세션들과 달리 면제가 곧 손실 경로였다.</p>
 *
 * <p>SELL 신호에는 적용하지 않는다(롱 청산은 추세와 무관하게 항상 가능해야 함).</p>
 */
public final class Ema200RegimeGate {

    private static final int EMA_PERIOD = 200;

    /**
     * EMA200 게이트 면제 전략 — 평균회귀 계열.
     *
     * <p>평균회귀 전략은 "EMA200 아래 과매도 구간에서 하단 이탈을 매수"하는 것이 전제라
     * 이 게이트와 논리적으로 상충한다 (게이트 적용 시 전략이 사실상 무력화됨 —
     * 2026-07-20 운영 DB 분석: 동적 세션 EMA200 차단 15건 전부 추세추종 전략의 역추세 신호였고,
     * 추세추종 계열엔 게이트가 옳았다. 면제는 평균회귀 계열에만 한정한다).</p>
     *
     * <p>면제되어도 BLACK_SWAN_GUARD(코인별 급락)·BTC_MARKET_GUARD(시장 전체 급락)·
     * 손실 쿨다운·SL/TP는 그대로 적용된다 — 나이프 캐칭은 별도 경로가 방어한다.</p>
     */
    private static final Set<String> EXEMPT_STRATEGIES = Set.of("COMPOSITE_MEANREV_BB");

    private Ema200RegimeGate() {}

    /**
     * 해당 전략이 EMA200 게이트 면제 대상(평균회귀 계열)인지 반환한다.
     * 호출부는 면제 전략에 대해 {@link #allowsBuy} 판정을 건너뛴다.
     */
    public static boolean isExempt(String strategyType) {
        return strategyType != null && EXEMPT_STRATEGIES.contains(strategyType);
    }

    /**
     * 해당 코인·캔들에서 BUY를 허용할지 판정한다.
     *
     * @param candles  시간 오름차순 캔들 (마지막 원소가 현재 캔들)
     * @param coinPair "KRW-BTC" 등. null 허용. (DOGE 예외 제거 후 판정에 미사용 —
     *                 호출부 호환성을 위해 시그니처 유지)
     * @return BUY 허용이면 true
     */
    public static boolean allowsBuy(List<Candle> candles, String coinPair) {
        return allowsBuy(candles, coinPair, BigDecimal.ZERO);
    }

    /**
     * 허용 마진이 있는 판정 — 종가가 EMA200 × (1 - marginPct/100) 초과이면 BUY 허용.
     *
     * <p>2026-07-09 운영 DB 분석: 동적 세션의 EMA200 차단 사례 전부가 0.2~0.7% 차이의
     * 근소 차단(예: 종가 2,598,000 ≤ EMA200 2,602,011)이었다. 추세 전환 초입을 마진 없이
     * 전부 걸러내는 문제를 완화하기 위해 동적 세션 한정으로 마진을 준다. 라이브·백테스트
     * 경로는 기존 시그니처(마진 0)를 그대로 사용한다.</p>
     *
     * @param marginPct EMA200 대비 허용 하회폭 % (예: 1.0 → EMA200의 -1%까지 허용)
     */
    public static boolean allowsBuy(List<Candle> candles, String coinPair, BigDecimal marginPct) {
        if (candles.size() < EMA_PERIOD) {
            return true; // EMA200 산출 불가 — 보수적으로 허용
        }
        List<BigDecimal> closes = candles.stream()
                .map(Candle::getClose)
                .toList();
        BigDecimal ema200 = IndicatorUtils.ema(closes, EMA_PERIOD);
        BigDecimal threshold = ema200.multiply(
                BigDecimal.ONE.subtract(marginPct.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)));
        return candles.get(candles.size() - 1).getClose().compareTo(threshold) > 0;
    }

    /** EMA200 아래 근접 구간(감액 진입 밴드)에 적용하는 포지션 사이즈 배수. */
    public static final BigDecimal REDUCED_SIZE_MULTIPLIER = new BigDecimal("0.5");

    /**
     * BUY를 "허용/감액/차단" 3단계로 판정해 포지션 사이즈 배수를 반환한다 —
     * 하드 차단(0/1) 대신 EMA200 아래 근접 구간을 감액 진입으로 살려, 관망만 하던
     * 하락·횡보 국면에서도 리스크를 사이즈로 통제하며 거래·데이터를 확보한다
     * (2026-07-21 사용자 결정 — "너무 보수적이지 않은 거래" + 소액 데이터 수집).
     *
     * <p>밴드는 기존 {@code marginPct} 하나로 파생돼 별도 설정 없이 튜닝된다:</p>
     * <ul>
     *   <li>종가 &gt; EMA200 × (1 - margin%) → {@code 1.0} (정상 사이즈, 기존 허용 구간)</li>
     *   <li>EMA200 × (1 - 2·margin%) &lt; 종가 ≤ EMA200 × (1 - margin%) →
     *       {@code REDUCED_SIZE_MULTIPLIER} (감액 진입, 신규)</li>
     *   <li>종가 ≤ EMA200 × (1 - 2·margin%) → {@code 0.0} (딥 하락 — 나이프 캐칭 차단)</li>
     * </ul>
     *
     * <p>기존 {@link #allowsBuy}(하드 차단) 대비 <b>단조 완화</b>다 — margin~2·margin 구간이
     * 차단에서 감액 허용으로 바뀔 뿐, 정상 허용 구간과 딥 차단 구간은 그대로다. 라이브·백테스트
     * 경로는 {@code allowsBuy}를 계속 쓰므로 영향받지 않는다. 되돌리려면 호출부에서 다시
     * {@code allowsBuy}로 교체하거나 marginPct를 0으로 두면 된다.</p>
     *
     * @return 1.0(정상) / REDUCED_SIZE_MULTIPLIER(감액) / 0.0(차단)
     */
    public static BigDecimal buySizeMultiplier(List<Candle> candles, BigDecimal marginPct) {
        if (candles.size() < EMA_PERIOD) {
            return BigDecimal.ONE; // EMA200 산출 불가 — 보수적으로 정상 허용
        }
        List<BigDecimal> closes = candles.stream()
                .map(Candle::getClose)
                .toList();
        BigDecimal ema200 = IndicatorUtils.ema(closes, EMA_PERIOD);
        BigDecimal close = candles.get(candles.size() - 1).getClose();
        BigDecimal marginFrac = marginPct.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal fullThreshold = ema200.multiply(BigDecimal.ONE.subtract(marginFrac));
        if (close.compareTo(fullThreshold) > 0) {
            return BigDecimal.ONE;
        }
        BigDecimal reducedThreshold = ema200.multiply(
                BigDecimal.ONE.subtract(marginFrac.multiply(BigDecimal.valueOf(2))));
        if (close.compareTo(reducedThreshold) > 0) {
            return REDUCED_SIZE_MULTIPLIER;
        }
        return BigDecimal.ZERO;
    }
}
