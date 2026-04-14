package com.cryptoautotrader.strategy.macdstochbb;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.StatefulStrategy;
import com.cryptoautotrader.strategy.StrategyParamUtils;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MACD + StochRSI 복합 추세 전략 (1시간봉 최적화)
 *
 * <pre>
 * 추세  : MACD > 0 AND 히스토그램 확대
 * 타이밍: StochRSI %K < 20 (과매도) AND %K > %D (골든크로스)
 * 필터  : 거래량 증가
 * 횡보  : |MACD| < sidewaysThreshold → HOLD
 * 쿨다운: BUY 후 cooldownCandles 캔들 동안 재진입 금지
 *
 * 매수 조건 (모두 충족):
 *   1. MACD > 0                        (상승 추세)
 *   2. MACD 히스토그램 증가             (상승 힘 확대)
 *   3. StochRSI %K < oversoldLevel(20) (과매도)
 *   4. %K > %D                          (골든크로스)
 *   5. 거래량 >= 평균 거래량
 *
 * [개선 v2] 볼린저밴드 %B 조건 제거:
 *   기존 조건 "%B <= 0.35 (하단 지지선 근처)"는 MACD>0(상승추세)과 구조적으로 충돌.
 *   StochRSI 과매도가 이미 눌림목 타이밍을 포착하므로 %B 조건은 중복·과필터.
 *   → 3년 H1 백테스트 기준 BTC 5건 등 극희소 신호 해소 목적.
 *
 * 매도 조건 (하나라도 충족):
 *   1. MACD 히스토그램 감소
 *   2. OR StochRSI %K > overboughtLevel(80)
 *
 * 리스크 관리 (BUY 신호에 suggestedStopLoss/TakeProfit 포함):
 *   손절: -2% (stopLossPct)
 *   익절: +4% (takeProfitPct)
 *   쿨다운: 3캔들 (cooldownCandles)
 * </pre>
 */
public class MacdStochBbStrategy implements StatefulStrategy {

    private static final int SCALE = 8;
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);


    /** 마지막 BUY 신호 발생 시점의 candles.size() (-1 = 없음) */
    private int lastBuyCandleCount = -1;

    @Override
    public String getName() {
        return "MACD_STOCH_BB";
    }

    @Override
    public void resetState() {
        lastBuyCandleCount = -1;
    }

    @Override
    public int getMinimumCandleCount() {
        // slowPeriod(26) + signalPeriod(9) + rsiPeriod(14) + stochPeriod(14) + stochSignal(3) + 여유
        return 70;
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int    fastPeriod      = StrategyParamUtils.getInt(params,    "fastPeriod",        12);
        int    slowPeriod      = StrategyParamUtils.getInt(params,    "slowPeriod",        26);
        int    signalPeriod    = StrategyParamUtils.getInt(params,    "signalPeriod",      9);
        int    rsiPeriod       = StrategyParamUtils.getInt(params,    "rsiPeriod",         14);
        int    stochPeriod     = StrategyParamUtils.getInt(params,    "stochPeriod",       14);
        int    stochSignal     = StrategyParamUtils.getInt(params,    "stochSignalPeriod", 3);
        double oversoldLevel   = StrategyParamUtils.getDouble(params, "oversoldLevel",     20.0);
        double overboughtLevel = StrategyParamUtils.getDouble(params, "overboughtLevel",   80.0);
        int    volumePeriod    = StrategyParamUtils.getInt(params,    "volumePeriod",      20);
        int    cooldown        = StrategyParamUtils.getInt(params,    "cooldownCandles",   3);
        double sidewaysThr     = StrategyParamUtils.getDouble(params, "sidewaysThreshold", 0.0005);
        double stopLossPct     = StrategyParamUtils.getDouble(params, "stopLossPct",       0.02);
        double takeProfitPct   = StrategyParamUtils.getDouble(params, "takeProfitPct",     0.04);

        int minRequired = slowPeriod + signalPeriod + rsiPeriod + stochPeriod + stochSignal + 1;
        if (candles.size() < minRequired) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + minRequired);
        }

        List<BigDecimal> closes  = candles.stream().map(Candle::getClose).toList();
        List<BigDecimal> volumes = candles.stream().map(Candle::getVolume).toList();
        BigDecimal currentPrice  = closes.get(closes.size() - 1);

        // 단일 패스로 직전·현재 MACD 계산
        MacdResult[] pair   = calcMacdPair(closes, fastPeriod, slowPeriod, signalPeriod);
        MacdResult prev     = pair[0];
        MacdResult current  = pair[1];
        BigDecimal curHist  = current.macd.subtract(current.signal);
        BigDecimal preHist  = prev.macd.subtract(prev.signal);

        // 횡보 필터
        if (current.macd.abs().compareTo(BigDecimal.valueOf(sidewaysThr)) < 0) {
            return StrategySignal.hold(String.format(
                    "횡보 필터: |MACD|=%.6f < %.4f (횡보장 진입 금지)",
                    current.macd, sidewaysThr));
        }

        // StochRSI 계산
        List<BigDecimal> rsiSeries = IndicatorUtils.rsiSeries(closes, rsiPeriod);
        if (rsiSeries.size() < stochPeriod + stochSignal) {
            return StrategySignal.hold("RSI 시계열 부족: " + rsiSeries.size());
        }
        List<BigDecimal> kSeries = IndicatorUtils.stochasticKSeries(rsiSeries, stochPeriod);
        if (kSeries.size() < stochSignal + 1) {
            return StrategySignal.hold("%K 시계열 부족: " + kSeries.size());
        }
        List<BigDecimal> dSeries = IndicatorUtils.smaList(kSeries, stochSignal);
        if (dSeries.isEmpty()) {
            return StrategySignal.hold("%D 계산 실패");
        }
        BigDecimal currentK = kSeries.get(kSeries.size() - 1);
        BigDecimal currentD = dSeries.get(dSeries.size() - 1);

        // 거래량 필터
        BigDecimal avgVolume = IndicatorUtils.sma(volumes, volumePeriod);
        BigDecimal curVolume = volumes.get(volumes.size() - 1);
        boolean volumeOk = curVolume.compareTo(avgVolume) >= 0;

        // 신호 판단
        boolean macdPositive    = current.macd.compareTo(BigDecimal.ZERO) > 0;
        boolean histIncreasing  = curHist.compareTo(preHist) > 0;
        boolean histDecreasing  = curHist.compareTo(preHist) < 0;
        boolean stochOversold   = currentK.compareTo(BigDecimal.valueOf(oversoldLevel))  < 0;
        boolean stochOverbought = currentK.compareTo(BigDecimal.valueOf(overboughtLevel)) > 0;
        boolean kAboveD         = currentK.compareTo(currentD) > 0;

        if (macdPositive && histIncreasing && stochOversold && kAboveD && volumeOk) {
            // 쿨다운 체크
            if (lastBuyCandleCount >= 0 && (candles.size() - lastBuyCandleCount) < cooldown) {
                int remaining = cooldown - (candles.size() - lastBuyCandleCount);
                return StrategySignal.hold(String.format(
                        "BUY 쿨다운: %d캔들 후 재진입 가능 (마지막 진입 후 %d캔들 경과)",
                        remaining, candles.size() - lastBuyCandleCount));
            }

            lastBuyCandleCount = candles.size();

            BigDecimal stopLoss   = currentPrice.multiply(BigDecimal.valueOf(1 - stopLossPct),   MC).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal takeProfit = currentPrice.multiply(BigDecimal.valueOf(1 + takeProfitPct), MC).setScale(SCALE, RoundingMode.HALF_UP);

            BigDecimal strength = BigDecimal.valueOf(oversoldLevel)
                    .subtract(currentK)
                    .divide(BigDecimal.valueOf(oversoldLevel).max(BigDecimal.ONE), SCALE, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
                    .abs()
                    .min(HUNDRED)
                    .max(BigDecimal.ZERO);

            return StrategySignal.buy(strength,
                    String.format("MACD_STOCH_BB 매수: MACD=%.6f(>0), Hist=%.6f↑, K=%.1f<%.0f, K>D(%.1f), 거래량OK",
                            current.macd, curHist, currentK, oversoldLevel, currentD),
                    stopLoss, takeProfit);
        }

        if (histDecreasing || stochOverbought) {
            String trigger;
            BigDecimal strength;

            if (histDecreasing && stochOverbought) {
                trigger = String.format("Hist감소(%.6f→%.6f) AND StochRSI과매수(K=%.1f>%.0f)",
                        preHist, curHist, currentK, overboughtLevel);
                strength = currentK.subtract(BigDecimal.valueOf(overboughtLevel))
                        .divide(HUNDRED.subtract(BigDecimal.valueOf(overboughtLevel)).max(BigDecimal.ONE), SCALE, RoundingMode.HALF_UP)
                        .multiply(HUNDRED).min(HUNDRED).max(BigDecimal.ZERO);
            } else if (stochOverbought) {
                trigger = String.format("StochRSI 과매수: K=%.1f > %.0f", currentK, overboughtLevel);
                strength = currentK.subtract(BigDecimal.valueOf(overboughtLevel))
                        .divide(HUNDRED.subtract(BigDecimal.valueOf(overboughtLevel)).max(BigDecimal.ONE), SCALE, RoundingMode.HALF_UP)
                        .multiply(HUNDRED).min(HUNDRED).max(BigDecimal.ZERO);
            } else {
                trigger = String.format("MACD Hist감소: %.6f → %.6f", preHist, curHist);
                BigDecimal base = preHist.abs().max(BigDecimal.valueOf(0.000001));
                strength = preHist.subtract(curHist).abs()
                        .divide(base, SCALE, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(50))
                        .min(HUNDRED).max(BigDecimal.ZERO);
            }

            return StrategySignal.sell(strength, "MACD_STOCH_BB 매도: " + trigger);
        }

        return StrategySignal.hold(String.format(
                "신호 없음: MACD=%.6f, Hist=%.6f, K=%.1f, D=%.1f",
                current.macd, curHist, currentK, currentD));
    }

    /**
     * 단일 패스로 직전·현재 MACD 값을 계산한다.
     * [0] = prev, [1] = current
     */
    private MacdResult[] calcMacdPair(List<BigDecimal> closes, int fast, int slow, int signal) {
        BigDecimal fastMult = BigDecimal.valueOf(2.0 / (fast + 1));
        BigDecimal slowMult = BigDecimal.valueOf(2.0 / (slow + 1));
        BigDecimal oneMFast = BigDecimal.ONE.subtract(fastMult);
        BigDecimal oneMSlow = BigDecimal.ONE.subtract(slowMult);

        BigDecimal fastEma = BigDecimal.ZERO;
        for (int i = 0; i < fast; i++) fastEma = fastEma.add(closes.get(i));
        fastEma = fastEma.divide(BigDecimal.valueOf(fast), SCALE, RoundingMode.HALF_UP);

        BigDecimal slowEma = BigDecimal.ZERO;
        for (int i = 0; i < slow; i++) slowEma = slowEma.add(closes.get(i));
        slowEma = slowEma.divide(BigDecimal.valueOf(slow), SCALE, RoundingMode.HALF_UP);

        for (int i = fast; i < slow; i++) {
            fastEma = closes.get(i).multiply(fastMult, MC)
                    .add(fastEma.multiply(oneMFast, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }

        List<BigDecimal> macdLine = new ArrayList<>();
        macdLine.add(fastEma.subtract(slowEma));
        for (int i = slow; i < closes.size(); i++) {
            fastEma = closes.get(i).multiply(fastMult, MC)
                    .add(fastEma.multiply(oneMFast, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            slowEma = closes.get(i).multiply(slowMult, MC)
                    .add(slowEma.multiply(oneMSlow, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            macdLine.add(fastEma.subtract(slowEma));
        }

        // prev signal = macdLine[0..n-2]의 EMA, current signal = 한 스텝 더
        BigDecimal prevSignal = IndicatorUtils.ema(
                macdLine.subList(0, macdLine.size() - 1), signal);
        BigDecimal sigMult    = BigDecimal.valueOf(2.0 / (signal + 1));
        BigDecimal curSignal  = macdLine.get(macdLine.size() - 1)
                .multiply(sigMult, MC)
                .add(prevSignal.multiply(BigDecimal.ONE.subtract(sigMult), MC))
                .setScale(SCALE, RoundingMode.HALF_UP);

        return new MacdResult[]{
            new MacdResult(macdLine.get(macdLine.size() - 2), prevSignal),
            new MacdResult(macdLine.get(macdLine.size() - 1), curSignal)
        };
    }

    /** MACD 계산 결과 */
    private static class MacdResult {
        final BigDecimal macd;
        final BigDecimal signal;

        MacdResult(BigDecimal macd, BigDecimal signal) {
            this.macd   = macd;
            this.signal = signal;
        }
    }
}
