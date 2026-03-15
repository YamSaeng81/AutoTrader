package com.cryptoautotrader.core.regime;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 시장 상태(MarketRegime)를 감지하는 Stateful 감지기.
 *
 * <p>판단 우선순위:
 * <ol>
 *   <li>ADX > 25 → TREND (강한 추세)</li>
 *   <li>ATR > ATR SMA × 1.5 + ADX < 25 → VOLATILITY (변동성 급등)</li>
 *   <li>ADX < 20 + BB Bandwidth < 하위 20% 퍼센타일 → RANGE (횡보)</li>
 *   <li>그 외 (ADX 20~25) → TRANSITIONAL (전환 중)</li>
 * </ol>
 *
 * <p>Hysteresis: 새 Regime이 HYSTERESIS_CANDLES 회 연속 감지되어야 전환한다.
 * 중간에 다른 Regime이 끼어들면 카운터가 리셋된다.
 */
public class MarketRegimeDetector {

    // ── 임계값 ──────────────────────────────────────────────────────────────
    private static final BigDecimal ADX_TREND_THRESHOLD = BigDecimal.valueOf(25);
    private static final BigDecimal ADX_RANGE_THRESHOLD = BigDecimal.valueOf(20);
    private static final double     ATR_SPIKE_MULTIPLIER = 1.5;
    private static final double     BB_PERCENTILE        = 0.20;  // 하위 20%

    // ── 지표 기간 ────────────────────────────────────────────────────────────
    private static final int ADX_PERIOD          = 14;
    private static final int BB_PERIOD           = 20;
    private static final double BB_MULTIPLIER    = 2.0;
    private static final int BB_LOOKBACK         = 30;  // bandwidth 퍼센타일 계산용
    private static final int ATR_PERIOD          = 14;
    private static final int ATR_SMA_PERIOD      = 20;

    /**
     * 최소 필요 캔들 수:
     * - ADX: ADX_PERIOD * 2 + 1 = 29
     * - ATR + SMA: ATR_PERIOD + ATR_SMA_PERIOD = 34
     * - BB Bandwidth: BB_PERIOD + BB_LOOKBACK - 1 = 49
     */
    public static final int MIN_CANDLE_COUNT = 50;

    // ── Hysteresis ───────────────────────────────────────────────────────────
    private static final int HYSTERESIS_CANDLES = 3;

    private MarketRegime previousRegime  = MarketRegime.RANGE;
    private MarketRegime candidateRegime = null;
    private int          holdCount       = 0;

    // ── 공개 API ─────────────────────────────────────────────────────────────

    public MarketRegime detect(List<Candle> candles) {
        if (candles.size() < MIN_CANDLE_COUNT) {
            return previousRegime;
        }
        MarketRegime detected = detectRaw(candles);
        return applyHysteresis(detected);
    }

    /** 테스트 등에서 내부 상태를 초기화할 때 사용한다. */
    public void resetState() {
        previousRegime  = MarketRegime.RANGE;
        candidateRegime = null;
        holdCount       = 0;
    }

    /** Hysteresis를 적용하지 않은 즉시 감지 결과 (테스트/디버깅용) */
    public MarketRegime detectRaw(List<Candle> candles) {
        BigDecimal adx = IndicatorUtils.adx(candles, ADX_PERIOD);

        // 1. TREND: ADX > 25
        if (adx.compareTo(ADX_TREND_THRESHOLD) > 0) {
            return MarketRegime.TREND;
        }

        // 2. VOLATILITY: ATR spike (ADX < 25 전제)
        List<BigDecimal> atrSeries = IndicatorUtils.atrList(candles, ATR_PERIOD);
        if (atrSeries.size() >= ATR_SMA_PERIOD) {
            BigDecimal currentATR = atrSeries.get(atrSeries.size() - 1);
            BigDecimal atrSma     = IndicatorUtils.sma(atrSeries, ATR_SMA_PERIOD);
            if (currentATR.compareTo(atrSma.multiply(BigDecimal.valueOf(ATR_SPIKE_MULTIPLIER))) > 0) {
                return MarketRegime.VOLATILITY;
            }
        }

        // 3. RANGE: ADX < 20 + BB Bandwidth 좁음
        if (adx.compareTo(ADX_RANGE_THRESHOLD) < 0) {
            List<BigDecimal> closes = closes(candles);
            if (closes.size() >= BB_PERIOD + BB_LOOKBACK - 1) {
                BigDecimal currentBW    = IndicatorUtils.bollingerBandwidth(closes, BB_PERIOD, BB_MULTIPLIER);
                List<BigDecimal> bwList = IndicatorUtils.bollingerBandwidths(closes, BB_PERIOD, BB_MULTIPLIER, BB_LOOKBACK);
                BigDecimal bwPercentile = percentile(bwList, BB_PERCENTILE);
                if (currentBW.compareTo(bwPercentile) <= 0) {
                    return MarketRegime.RANGE;
                }
            } else {
                // BB 데이터 부족: ADX만으로 RANGE 판단
                return MarketRegime.RANGE;
            }
        }

        // 4. TRANSITIONAL: ADX 20~25 구간 또는 BB 폭이 아직 넓은 경우
        return MarketRegime.TRANSITIONAL;
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * Hysteresis 적용: 새 Regime이 HYSTERESIS_CANDLES 회 연속 감지되어야 전환.
     */
    private MarketRegime applyHysteresis(MarketRegime detected) {
        if (detected == previousRegime) {
            candidateRegime = null;
            holdCount       = 0;
            return previousRegime;
        }

        if (detected != candidateRegime) {
            candidateRegime = detected;
            holdCount       = 1;
        } else {
            holdCount++;
        }

        if (holdCount >= HYSTERESIS_CANDLES) {
            previousRegime  = detected;
            candidateRegime = null;
            holdCount       = 0;
        }
        return previousRegime;
    }

    /** 캔들 리스트에서 종가 리스트 추출 */
    private static List<BigDecimal> closes(List<Candle> candles) {
        List<BigDecimal> result = new ArrayList<>(candles.size());
        for (Candle c : candles) {
            result.add(c.getClose());
        }
        return result;
    }

    /** 정렬 후 주어진 퍼센타일 인덱스의 값 반환 */
    private static BigDecimal percentile(List<BigDecimal> values, double p) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        List<BigDecimal> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.floor(sorted.size() * p);
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }
}
