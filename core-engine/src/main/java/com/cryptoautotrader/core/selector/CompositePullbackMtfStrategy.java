package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.strategy.supertrend.SupertrendStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * COMPOSITE_PULLBACK_MTF — 강한 추세 중 "눌림목 회복" 진입 전략.
 *
 * <p>기존 라이브 전략들이 돌파/모멘텀(COMPOSITE_BREAKOUT, CMI_V2)에 쏠려 있어, 성격이 직교하는
 * 눌림목 진입을 별도로 검증하기 위한 전략이다. 고점 추격 대비 체결가가 유리하다.
 *
 * <h3>BUY 조건 (모두 충족)</h3>
 * <ol>
 *   <li>H4 Supertrend 상승 (H1 캔들을 ×4 다운샘플 후 Supertrend BUY)</li>
 *   <li>H1 종가 &gt; EMA200 (장기 상승 레짐)</li>
 *   <li>H1 RSI(14)가 {@code [rsiLow, rsiHigh]} (기본 40~55) — 과열 아님, 눌림으로 식음</li>
 *   <li>최근 가격이 EMA20 또는 VWAP 부근까지 눌렸다가({@code pullbackLookback}봉 내 저가 근접)
 *       다시 회복(현재 종가 &gt; EMA20 &amp;&amp; 직전 종가 상회)</li>
 *   <li>ADX(14) ≥ {@code adxMin} (기본 18) — 횡보장 진입 금지</li>
 * </ol>
 *
 * <h3>SELL 조건 (둘 중 하나)</h3>
 * <ul>
 *   <li>H4 Supertrend 하락 전환</li>
 *   <li>H1 종가 &lt; EMA20 (눌림목 지지 이탈)</li>
 * </ul>
 * 손절/익절(SL/TP)은 LiveTradingService가 별도 처리하므로 이 전략은 신호 기반 청산만 담당한다.
 *
 * <p>상태를 보유하지 않는 stateless 전략 — H4 Supertrend는 매 호출 전체 시계열로 재계산한다.
 */
public class CompositePullbackMtfStrategy implements Strategy {

    private static final int SCALE = 8;

    private final SupertrendStrategy htfSupertrend = new SupertrendStrategy();

    @Override
    public String getName() {
        return "COMPOSITE_PULLBACK_MTF";
    }

    @Override
    public int getMinimumCandleCount() {
        // EMA200 산출에 H1 캔들 200개 필요 (가장 큰 요구량).
        return 200;
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int    ema200Period = getInt(params, "ema200Period", 200);
        int    ema20Period  = getInt(params, "ema20Period", 20);
        int    rsiPeriod    = getInt(params, "rsiPeriod", 14);
        int    adxPeriod    = getInt(params, "adxPeriod", 14);
        int    vwapPeriod   = getInt(params, "vwapPeriod", 20);
        int    atrPeriod    = getInt(params, "atrPeriod", 14);
        int    htfFactor    = getInt(params, "htfFactor", 4);
        int    pullbackLookback   = getInt(params, "pullbackLookback", 4);
        double rsiLow       = getDouble(params, "rsiLow", 40.0);
        double rsiHigh      = getDouble(params, "rsiHigh", 55.0);
        double adxMin       = getDouble(params, "adxMin", 18.0);
        double pullbackTolPct = getDouble(params, "pullbackTolerancePct", 0.6);
        // 청산/리스크 — 휩쏘 방지를 위해 EMA20 이탈은 ATR 마진 + 2봉 확정으로만 인정.
        double slAtrMult      = getDouble(params, "slAtrMult", 2.0);   // 손절 = 진입가 − 2×ATR
        double tpAtrMult      = getDouble(params, "tpAtrMult", 4.0);   // 익절 = 진입가 + 4×ATR (1:2 R:R)
        double ema20ExitAtrMult = getDouble(params, "ema20ExitAtrMult", 0.5); // EMA20 이탈 마진

        int n = candles.size();
        if (n < Math.max(ema200Period + 1, rsiPeriod + 2)) {
            return StrategySignal.hold("데이터 부족: " + n + " < " + (ema200Period + 1));
        }

        List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();
        BigDecimal currentClose = closes.get(n - 1);
        BigDecimal prevClose    = closes.get(n - 2);
        BigDecimal ema200 = IndicatorUtils.ema(closes, ema200Period);
        BigDecimal ema20  = IndicatorUtils.ema(closes, ema20Period);
        BigDecimal vwap   = rollingVwap(candles, vwapPeriod);

        List<BigDecimal> rsiList = IndicatorUtils.rsiSeries(closes, rsiPeriod);
        if (rsiList.isEmpty()) {
            return StrategySignal.hold("RSI 산출 불가");
        }
        double rsi = rsiList.get(rsiList.size() - 1).doubleValue();
        double adx = IndicatorUtils.adx(candles, adxPeriod).doubleValue();
        BigDecimal atr = IndicatorUtils.atr(candles, atrPeriod);

        // ── H4 추세 판정 (H1 ×htfFactor 다운샘플) ──────────────────────────────
        List<Candle> htfCandles = CandleDownsampler.downsample(candles, htfFactor);
        StrategySignal.Action htfAction = StrategySignal.Action.HOLD;
        if (htfCandles.size() >= htfSupertrend.getMinimumCandleCount()) {
            htfAction = htfSupertrend.evaluate(htfCandles, Map.of()).getAction();
        }

        // ── SELL (신호 기반 청산) — 보유 시 LiveTradingService가 실행 ───────────
        // 1순위: H4 Supertrend 하락 전환.
        if (htfAction == StrategySignal.Action.SELL) {
            return StrategySignal.sell(BigDecimal.valueOf(70),
                    String.format("H4 Supertrend 하락 전환 (RSI=%.1f, ADX=%.1f)", rsi, adx));
        }
        // 2순위: EMA20 "확정" 이탈 — 휩쏘 방지를 위해 ATR 마진 + 2봉 연속 종가로만 인정.
        //  (기존엔 단일 종가 < EMA20 즉시 청산이라 눌림목 진입 직후 반복 청산 → 승률 급락)
        BigDecimal ema20ExitLine = ema20.subtract(atr.multiply(BigDecimal.valueOf(ema20ExitAtrMult)));
        boolean ema20BreakConfirmed = currentClose.compareTo(ema20ExitLine) < 0
                && prevClose.compareTo(ema20) < 0;     // 직전 봉도 EMA20 아래 → 일시 흔들림 배제
        if (ema20BreakConfirmed) {
            return StrategySignal.sell(BigDecimal.valueOf(60),
                    String.format("H1 EMA20 확정 이탈(2봉+ATR마진): 종가=%.4f < %.4f", currentClose, ema20ExitLine));
        }

        // ── BUY 게이트 ─────────────────────────────────────────────────────────
        if (adx < adxMin) {
            return StrategySignal.hold(String.format("횡보장 진입 금지: ADX=%.1f < %.1f", adx, adxMin));
        }
        if (currentClose.compareTo(ema200) <= 0) {
            return StrategySignal.hold(String.format("EMA200 아래: 종가=%.4f ≤ EMA200=%.4f", currentClose, ema200));
        }
        if (rsi < rsiLow || rsi > rsiHigh) {
            return StrategySignal.hold(String.format("RSI 범위 밖: %.1f ∉ [%.0f, %.0f]", rsi, rsiLow, rsiHigh));
        }
        if (htfAction != StrategySignal.Action.BUY) {
            return StrategySignal.hold("H4 Supertrend 상승 미확인 (중립/데이터부족)");
        }

        // 눌림목 → 회복 판정: 최근 pullbackLookback봉(현재 제외) 저가가 EMA20/VWAP 부근까지 근접했고,
        // 현재 종가가 직전 종가를 상회하며 EMA20 위로 회복.
        BigDecimal anchor = ema20.max(vwap);                       // 더 높은 지지선 기준 (보수적)
        BigDecimal tolBand = anchor.multiply(BigDecimal.valueOf(1.0 + pullbackTolPct / 100.0));
        BigDecimal minRecentLow = null;
        int from = Math.max(0, n - 1 - pullbackLookback);
        for (int i = from; i < n - 1; i++) {
            BigDecimal low = candles.get(i).getLow();
            if (minRecentLow == null || low.compareTo(minRecentLow) < 0) {
                minRecentLow = low;
            }
        }
        boolean pulledToAnchor = minRecentLow != null
                && minRecentLow.compareTo(tolBand) <= 0          // 저가가 지지선 부근까지 눌림
                && minRecentLow.compareTo(currentClose) < 0;     // 그 저가는 현재가보다 아래(되돌림 발생)
        boolean recovered = currentClose.compareTo(prevClose) > 0  // 위로 반등
                && currentClose.compareTo(ema20) > 0;              // EMA20 위로 회복

        if (pulledToAnchor && recovered) {
            BigDecimal strength = BigDecimal.valueOf(Math.min(90.0, 50.0 + (adx - adxMin)));
            // ATR 기반 SL/TP(1:2 R:R) — 엔진이 추세 추종/트레일링으로 관리. 신호 청산만으로
            // 휩쏘 나던 문제를 보완한다. (현재 종가 기준 근사 — 실제 체결가는 다음 봉 open)
            BigDecimal stopLoss   = currentClose.subtract(atr.multiply(BigDecimal.valueOf(slAtrMult)));
            BigDecimal takeProfit = currentClose.add(atr.multiply(BigDecimal.valueOf(tpAtrMult)));
            return StrategySignal.buy(strength, String.format(
                    "눌림목 회복 BUY: 종가=%.4f, EMA20=%.4f, VWAP=%.4f, 저점=%.4f, RSI=%.1f, ADX=%.1f, "
                    + "SL=%.4f TP=%.4f [H4:상승]",
                    currentClose, ema20, vwap, minRecentLow, rsi, adx, stopLoss, takeProfit),
                    stopLoss, takeProfit);
        }

        return StrategySignal.hold(String.format(
                "눌림목 미충족: pulled=%b recovered=%b (종가=%.4f, EMA20=%.4f, RSI=%.1f)",
                pulledToAnchor, recovered, currentClose, ema20, rsi));
    }

    /** 최근 period 캔들 기준 rolling VWAP — typical=(H+L+C)/3 가중. */
    private static BigDecimal rollingVwap(List<Candle> candles, int period) {
        int n = candles.size();
        int p = Math.min(period, n);
        BigDecimal pv = BigDecimal.ZERO;
        BigDecimal vol = BigDecimal.ZERO;
        for (int i = n - p; i < n; i++) {
            Candle c = candles.get(i);
            BigDecimal typical = c.getHigh().add(c.getLow()).add(c.getClose())
                    .divide(BigDecimal.valueOf(3), SCALE, RoundingMode.HALF_UP);
            pv = pv.add(typical.multiply(c.getVolume()));
            vol = vol.add(c.getVolume());
        }
        if (vol.compareTo(BigDecimal.ZERO) == 0) {
            return candles.get(n - 1).getClose();
        }
        return pv.divide(vol, SCALE, RoundingMode.HALF_UP);
    }

    private static int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }

    private static double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : defaultVal;
    }
}
