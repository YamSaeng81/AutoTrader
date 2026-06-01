package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;

import java.math.BigDecimal;
import java.util.List;

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
 *   <li><b>DOGE 예외</b>: EMA200 아래에서도 수익 패턴이 확인되어 게이트를 면제(항상 허용).
 *       근거: PROGRESS.md EMA200 PoC — "DOGE는 EMA200 아래에서도 수익 패턴 존재".</li>
 * </ul>
 *
 * <p>SELL 신호에는 적용하지 않는다(롱 청산은 추세와 무관하게 항상 가능해야 함).</p>
 */
public final class Ema200RegimeGate {

    private static final int EMA_PERIOD = 200;

    private Ema200RegimeGate() {}

    /**
     * 해당 코인·캔들에서 BUY를 허용할지 판정한다.
     *
     * @param candles  시간 오름차순 캔들 (마지막 원소가 현재 캔들)
     * @param coinPair "KRW-DOGE" 등. null 허용(예외 미적용).
     * @return BUY 허용이면 true
     */
    public static boolean allowsBuy(List<Candle> candles, String coinPair) {
        // DOGE 예외: EMA200 아래에서도 수익 패턴 존재 → 게이트 면제
        if (coinPair != null && coinPair.contains("DOGE")) {
            return true;
        }
        if (candles.size() < EMA_PERIOD) {
            return true; // EMA200 산출 불가 — 보수적으로 허용
        }
        List<BigDecimal> closes = candles.stream()
                .map(Candle::getClose)
                .toList();
        BigDecimal ema200 = IndicatorUtils.ema(closes, EMA_PERIOD);
        return candles.get(candles.size() - 1).getClose().compareTo(ema200) > 0;
    }
}
