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
 * <p>롱(BUY) 진입 — 동시 충족:
 * <pre>
 * 1. 장기 추세 : 종가가 200 EMA 위
 * 2. 지표 교차 : StochRSI 골든크로스 (이전 K ≤ 이전 D, 현재 K > 현재 D)
 * 3. 캔들 확인 : 아래꼬리 비율이 maxWickRatio 이하인 양봉 하이키나시 캔들
 * 4. 거래량   : 현재 거래량 ≥ 직전 N캔들 평균 × volumeFilterRatio (보완안 8)
 * </pre>
 *
 * <p>숏(SELL) 진입 — 대칭 조건(거래량 필터 제외):
 * <pre>
 * 1. 장기 추세 : 종가가 200 EMA 아래
 * 2. 지표 교차 : StochRSI 데드크로스 (이전 K ≥ 이전 D, 현재 K < 현재 D)
 * 3. 캔들 확인 : 위꼬리 비율이 maxWickRatio 이하인 음봉 하이키나시 캔들
 * </pre>
 *
 * <p>신호 희소성 완화(보완안 1·4·8): 기본 maxWickRatio 0.0→0.25, "직전보다 몸통이 길어짐"은
 * 진입 필수가 아니라 strength 가산점(requireBodyGrowth=true 로 원작 룰 복원), 거래량 1차 필터 추가.
 * 모두 파라미터 토글로 A/B 비교가 가능하다.
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
        double maxWickRatio  = StrategyParamUtils.getDouble(params, "maxWickRatio",  0.25);
        double stopLossPct   = StrategyParamUtils.getDouble(params, "stopLossPct",   1.5);
        double takeProfitPct = StrategyParamUtils.getDouble(params, "takeProfitPct", 3.0);
        // 신호 희소성 완화 (보완안 1·4·8) — 전부 파라미터 토글로 A/B 비교 가능.
        //  · maxWickRatio 0.0→0.25 (보완안 1): "꼬리 0" 초과잉 필터 완화 → 채택
        //  · volumeFilterRatio=0.8 (보완안 8): 완화로 늘어난 잡신호를 거래량으로 1차 거름 → 채택
        //  · requireBodyGrowth (보완안 4): 몸통 증가를 가산점으로 풀면(false) 4코인 전부 수익 악화
        //    (2026-06 H1 100일 백테스트) → 기본 true(원작 필수 유지)로 기각. false 로 실험 가능.
        boolean requireBodyGrowth = StrategyParamUtils.getBoolean(params, "requireBodyGrowth", true);
        double bodyGrowthBonus    = StrategyParamUtils.getDouble(params, "bodyGrowthBonus", 10.0);
        double volumeFilterRatio  = StrategyParamUtils.getDouble(params, "volumeFilterRatio", 0.8);
        int    volumeAvgPeriod    = StrategyParamUtils.getInt(params,    "volumeAvgPeriod", 20);

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

        // 보완안 4: 몸통 증가를 진입 "필수"에서 "가산점"으로. requireBodyGrowth=true면 원작(필수) 복원.
        boolean bodyOk      = !requireBodyGrowth || bodyGrew;
        boolean longCandle  = bullish && bodyOk && noLowerWick;
        boolean shortCandle = bearish && bodyOk && noUpperWick;

        // 보완안 8: 거래량 필터 — 현재 거래량이 직전 N캔들 평균의 volumeFilterRatio배 이상일 때만 BUY 허용.
        //  (완화로 늘어난 잡신호 1차 거름. 청산(SELL)에는 적용하지 않는다 — 빠져나갈 길을 막지 않기 위함.)
        BigDecimal avgVolume     = averageVolume(candles, volumeAvgPeriod);
        BigDecimal currentVolume = candles.get(candles.size() - 1).getVolume();
        boolean volumeOk = volumeFilterRatio <= 0.0
                || avgVolume.signum() == 0
                || currentVolume.compareTo(avgVolume.multiply(BigDecimal.valueOf(volumeFilterRatio))) >= 0;

        // ── 신호 판정 ──
        if (aboveEma && goldenCross && longCandle && volumeOk) {
            BigDecimal entry = lastClose;
            BigDecimal sl = entry.multiply(BigDecimal.ONE.subtract(pct(stopLossPct)))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal tp = entry.multiply(BigDecimal.ONE.add(pct(takeProfitPct)))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal strength = withBodyBonus(crossStrength(currentK, currentD), bodyGrew, bodyGrowthBonus);
            return StrategySignal.buy(strength,
                    String.format("HA 롱: 200EMA 위(종가=%.2f>EMA=%.2f), StochRSI 골든크로스(K=%.2f>D=%.2f), "
                                    + "아래꼬리없는 양봉(몸통%s). 손절=%.2f 익절=%.2f",
                            lastClose, ema, currentK, currentD, bodyGrew ? "↑" : "→", sl, tp),
                    sl, tp);
        }

        if (belowEma && deadCross && shortCandle) {
            BigDecimal entry = lastClose;
            BigDecimal sl = entry.multiply(BigDecimal.ONE.add(pct(stopLossPct)))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal tp = entry.multiply(BigDecimal.ONE.subtract(pct(takeProfitPct)))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal strength = withBodyBonus(crossStrength(currentK, currentD), bodyGrew, bodyGrowthBonus);
            return StrategySignal.sell(strength,
                    String.format("HA 숏: 200EMA 아래(종가=%.2f<EMA=%.2f), StochRSI 데드크로스(K=%.2f<D=%.2f), "
                                    + "위꼬리없는 음봉(몸통%s). 손절=%.2f 익절=%.2f",
                            lastClose, ema, currentK, currentD, bodyGrew ? "↑" : "→", sl, tp),
                    sl, tp);
        }

        return StrategySignal.hold(String.format(
                "신호 없음: EMA위=%b/아래=%b, 골든=%b/데드=%b, 롱캔들=%b/숏캔들=%b, 거래량OK=%b (K=%.2f, D=%.2f)",
                aboveEma, belowEma, goldenCross, deadCross, longCandle, shortCandle, volumeOk, currentK, currentD));
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

    /** 보완안 4: 직전보다 몸통이 길어진 캔들이면 strength 가산점 (100 상한). */
    private static BigDecimal withBodyBonus(BigDecimal base, boolean bodyGrew, double bonus) {
        if (!bodyGrew || bonus <= 0.0) {
            return base;
        }
        return base.add(BigDecimal.valueOf(bonus)).min(HUNDRED);
    }

    /** 현재(마지막) 캔들 직전 {@code period}개 캔들의 평균 거래량 — 현재 캔들 제외. */
    private static BigDecimal averageVolume(List<Candle> candles, int period) {
        int n = candles.size();
        int count = Math.min(period, n - 1); // 마지막(현재) 캔들 제외
        if (count <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = n - 1 - count; i < n - 1; i++) {
            sum = sum.add(candles.get(i).getVolume());
        }
        return sum.divide(BigDecimal.valueOf(count), SCALE, RoundingMode.HALF_UP);
    }
}
