package com.cryptoautotrader.strategy.heikinashi;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyParamUtils;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Heikin-Ashi + 200 EMA + Stochastic RSI 추세추종 전략.
 *
 * <p>출처 유튜브 룰을 코드화한 전략. 단기 소음을 줄인 하이키나시 캔들로 추세를 읽고,
 * 200 EMA 로 장기 추세 방향을 고정한 뒤, Stochastic RSI 의 K/D 크로스로 진입 타이밍을 잡는다.
 * 진입과 동시에 고정 손익비(기본 손절 -1.5% / 익절 +3.0% = 1:2)를 신호에 함께 제안한다.
 *
 * <p>롱(BUY) 진입 — 3가지 동시 충족:
 * <pre>
 * 1. 장기 추세 : 종가가 200 EMA 위
 * 2. 지표 교차 : StochRSI 골든크로스 (이전 K ≤ 이전 D, 현재 K > 현재 D)
 * 3. 캔들 확인 : 직전보다 몸통이 길어진 아래꼬리 없는 양봉 하이키나시 캔들
 * </pre>
 *
 * <p>숏(SELL) 진입 — 대칭 조건:
 * <pre>
 * 1. 장기 추세 : 종가가 200 EMA 아래
 * 2. 지표 교차 : StochRSI 데드크로스 (이전 K ≥ 이전 D, 현재 K < 현재 D)
 * 3. 캔들 확인 : 직전보다 몸통이 길어진 위꼬리 없는 음봉 하이키나시 캔들
 * </pre>
 *
 * <p>EMA / StochRSI 는 원본(일반) 캔들 종가 기준으로 계산하고, 캔들 모양 확인만
 * 하이키나시 캔들로 수행한다.
 */
public class HeikinAshiStochStrategy implements Strategy {

    private static final int SCALE = 8;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String getName() {
        return "HEIKIN_ASHI_STOCH";
    }

    @Override
    public int getMinimumCandleCount() {
        // 기본 emaPeriod(200) 가 지배적. EMA 안정화 여유 포함.
        return 205;
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int    emaPeriod     = StrategyParamUtils.getInt(params,    "emaPeriod",     200);
        int    rsiPeriod     = StrategyParamUtils.getInt(params,    "rsiPeriod",     14);
        int    stochPeriod   = StrategyParamUtils.getInt(params,    "stochPeriod",   14);
        int    signalPeriod  = StrategyParamUtils.getInt(params,    "signalPeriod",  3);
        double maxWickRatio  = StrategyParamUtils.getDouble(params, "maxWickRatio",  0.0);
        double stopLossPct   = StrategyParamUtils.getDouble(params, "stopLossPct",   1.5);
        double takeProfitPct = StrategyParamUtils.getDouble(params, "takeProfitPct", 3.0);

        int required = Math.max(emaPeriod, rsiPeriod + stochPeriod + signalPeriod) + 1;
        if (candles.size() < required) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + "/" + required);
        }

        List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();

        // 1. 장기 추세 — 200 EMA
        BigDecimal ema       = IndicatorUtils.ema(closes, emaPeriod);
        BigDecimal lastClose = closes.get(closes.size() - 1);
        boolean aboveEma = lastClose.compareTo(ema) > 0;
        boolean belowEma = lastClose.compareTo(ema) < 0;

        // 2. 지표 교차 — Stochastic RSI K/D
        List<BigDecimal> rsiSeries = IndicatorUtils.rsiSeries(closes, rsiPeriod);
        if (rsiSeries.size() < stochPeriod + signalPeriod + 1) {
            return StrategySignal.hold("RSI 시계열 부족: " + rsiSeries.size());
        }
        List<BigDecimal> kSeries = IndicatorUtils.stochasticKSeries(rsiSeries, stochPeriod);
        if (kSeries.size() < signalPeriod + 1) {
            return StrategySignal.hold("%K 시계열 부족: " + kSeries.size());
        }
        List<BigDecimal> dSeries = IndicatorUtils.smaList(kSeries, signalPeriod);
        if (dSeries.size() < 2) {
            return StrategySignal.hold("%D 시계열 부족: " + dSeries.size());
        }

        BigDecimal currentK = kSeries.get(kSeries.size() - 1);
        BigDecimal currentD = dSeries.get(dSeries.size() - 1);
        BigDecimal prevK    = kSeries.get(kSeries.size() - 2);
        BigDecimal prevD    = dSeries.get(dSeries.size() - 2);

        boolean goldenCross = prevK.compareTo(prevD) <= 0 && currentK.compareTo(currentD) > 0;
        boolean deadCross   = prevK.compareTo(prevD) >= 0 && currentK.compareTo(currentD) < 0;

        // 3. 캔들 확인 — 하이키나시 모양
        List<Candle> ha = IndicatorUtils.heikinAshi(candles);
        Candle lastHa = ha.get(ha.size() - 1);
        Candle prevHa = ha.get(ha.size() - 2);

        BigDecimal body     = lastHa.getClose().subtract(lastHa.getOpen()).abs();
        BigDecimal prevBody = prevHa.getClose().subtract(prevHa.getOpen()).abs();
        boolean bodyGrew = body.compareTo(prevBody) > 0;
        boolean bullish  = lastHa.getClose().compareTo(lastHa.getOpen()) > 0;
        boolean bearish  = lastHa.getClose().compareTo(lastHa.getOpen()) < 0;

        // 꼬리 길이: 아래꼬리 = min(시,종) - 저, 위꼬리 = 고 - max(시,종)
        BigDecimal lowerWick = lastHa.getOpen().min(lastHa.getClose()).subtract(lastHa.getLow());
        BigDecimal upperWick = lastHa.getHigh().subtract(lastHa.getOpen().max(lastHa.getClose()));
        BigDecimal maxWick   = body.multiply(BigDecimal.valueOf(maxWickRatio));
        boolean noLowerWick = lowerWick.compareTo(maxWick) <= 0;
        boolean noUpperWick = upperWick.compareTo(maxWick) <= 0;

        boolean longCandle  = bullish && bodyGrew && noLowerWick;
        boolean shortCandle = bearish && bodyGrew && noUpperWick;

        // ── 신호 판정 ──
        if (aboveEma && goldenCross && longCandle) {
            BigDecimal entry = lastClose;
            BigDecimal sl = entry.multiply(BigDecimal.ONE.subtract(pct(stopLossPct)))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal tp = entry.multiply(BigDecimal.ONE.add(pct(takeProfitPct)))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal strength = crossStrength(currentK, currentD);
            return StrategySignal.buy(strength,
                    String.format("HA 롱: 200EMA 위(종가=%.2f>EMA=%.2f), StochRSI 골든크로스(K=%.2f>D=%.2f), "
                                    + "아래꼬리없는 양봉(몸통↑). 손절=%.2f 익절=%.2f",
                            lastClose, ema, currentK, currentD, sl, tp),
                    sl, tp);
        }

        if (belowEma && deadCross && shortCandle) {
            BigDecimal entry = lastClose;
            BigDecimal sl = entry.multiply(BigDecimal.ONE.add(pct(stopLossPct)))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal tp = entry.multiply(BigDecimal.ONE.subtract(pct(takeProfitPct)))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal strength = crossStrength(currentK, currentD);
            return StrategySignal.sell(strength,
                    String.format("HA 숏: 200EMA 아래(종가=%.2f<EMA=%.2f), StochRSI 데드크로스(K=%.2f<D=%.2f), "
                                    + "위꼬리없는 음봉(몸통↑). 손절=%.2f 익절=%.2f",
                            lastClose, ema, currentK, currentD, sl, tp),
                    sl, tp);
        }

        return StrategySignal.hold(String.format(
                "신호 없음: EMA위=%b/아래=%b, 골든=%b/데드=%b, 롱캔들=%b/숏캔들=%b (K=%.2f, D=%.2f)",
                aboveEma, belowEma, goldenCross, deadCross, longCandle, shortCandle, currentK, currentD));
    }

    /** 퍼센트 → 소수 (예: 1.5 → 0.015) */
    private static BigDecimal pct(double percent) {
        return BigDecimal.valueOf(percent).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    }

    /** K/D 이격을 0~100 강도로 환산 (50 기준 + 이격 가산) */
    private static BigDecimal crossStrength(BigDecimal k, BigDecimal d) {
        BigDecimal gap = k.subtract(d).abs();
        return BigDecimal.valueOf(50).add(gap.multiply(BigDecimal.valueOf(2)))
                .min(HUNDRED).max(BigDecimal.ZERO);
    }
}
