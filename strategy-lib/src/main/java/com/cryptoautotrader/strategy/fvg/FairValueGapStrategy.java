package com.cryptoautotrader.strategy.fvg;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fair Value Gap (FVG, 유동성 공백) 전략 — A단계 (모멘텀 방식)
 *
 * <p><b>핵심 로직</b>: 3개 캔들 패턴으로 급격한 가격 불균형 구간을 감지하여
 * 그 방향으로 모멘텀 진입한다.
 * <ul>
 *   <li><b>상승 FVG (BUY)</b>: {@code Candle[n-2].high < Candle[n].low}
 *       — 중간 임펄스 캔들이 공백을 만들며 급등. 추세 지속 베팅.</li>
 *   <li><b>하락 FVG (SELL)</b>: {@code Candle[n-2].low > Candle[n].high}
 *       — 중간 임펄스 캔들이 공백을 만들며 급락. 하락 지속 베팅.</li>
 * </ul>
 *
 * <p><b>EMA 필터 (선택)</b>: FVG 방향이 EMA 추세와 일치할 때만 신호 인정.
 * 역추세 FVG는 노이즈 확률이 높아 필터링한다.
 *
 * <p><b>최소 공백 크기 필터</b>: 공백 크기가 {@code Candle[n-2].high}의
 * {@code minGapPct}% 미만이면 HOLD. 미세 노이즈 FVG 제거.
 *
 * <p><b>신호 강도</b>: 공백 크기 / 임펄스 캔들 몸통 비율로 산출.
 * 비율이 클수록 강한 불균형 = 강한 신호.
 */
public class FairValueGapStrategy implements Strategy {

    private static final int SCALE = 8;

    @Override
    public String getName() {
        return "FAIR_VALUE_GAP";
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int emaPeriod           = getInt(params, "emaPeriod", 20);
        boolean emaFilter       = getBoolean(params, "emaFilterEnabled", true);
        double minGapPct        = getDouble(params, "minGapPct", 0.1);

        // EMA 필터 활성 시 emaPeriod + 3개 캔들 필요, 비활성 시 3개
        int required = emaFilter ? emaPeriod + 3 : 3;
        if (candles.size() < required) {
            return StrategySignal.hold("데이터 부족: " + candles.size() + " < " + required);
        }

        // 최근 3개 캔들: c0=n-2, c1=n-1(임펄스), c2=n(현재)
        Candle c0 = candles.get(candles.size() - 3);
        Candle c1 = candles.get(candles.size() - 2);
        Candle c2 = candles.get(candles.size() - 1);

        boolean bullishFvg = c0.getHigh().compareTo(c2.getLow()) < 0;
        boolean bearishFvg = c0.getLow().compareTo(c2.getHigh()) > 0;

        if (!bullishFvg && !bearishFvg) {
            return StrategySignal.hold(String.format(
                    "FVG 없음: c0.high=%.2f, c2.low=%.2f, c0.low=%.2f, c2.high=%.2f",
                    c0.getHigh(), c2.getLow(), c0.getLow(), c2.getHigh()));
        }

        // EMA 계산
        BigDecimal ema = null;
        if (emaFilter) {
            List<BigDecimal> closes = candles.stream()
                    .map(Candle::getClose)
                    .collect(Collectors.toList());
            ema = IndicatorUtils.ema(closes, emaPeriod);
        }

        if (bullishFvg) {
            BigDecimal gapSize = c2.getLow().subtract(c0.getHigh());
            BigDecimal refPrice = c0.getHigh();

            // 최소 공백 크기 필터
            if (refPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal gapPct = gapSize.divide(refPrice, SCALE, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                if (gapPct.compareTo(BigDecimal.valueOf(minGapPct)) < 0) {
                    return StrategySignal.hold(String.format(
                            "상승 FVG 감지됐으나 공백 크기 미달: %.4f%% < %.4f%%", gapPct, minGapPct));
                }
            }

            // EMA 필터: 현재 종가가 EMA 위에 있어야 상승 추세 확인
            if (emaFilter && ema != null && c2.getClose().compareTo(ema) < 0) {
                return StrategySignal.hold(String.format(
                        "상승 FVG 감지됐으나 EMA 필터 차단: 종가=%.2f < EMA=%.2f (역추세)", c2.getClose(), ema));
            }

            BigDecimal strength = calcStrength(gapSize, c1);
            return StrategySignal.buy(strength, String.format(
                    "상승 FVG: c0.high=%.2f < c2.low=%.2f (공백=%.2f, 임펄스 캔들 몸통=%.2f)%s",
                    c0.getHigh(), c2.getLow(), gapSize,
                    c1.getClose().subtract(c1.getOpen()).abs(),
                    ema != null ? String.format(", EMA=%.2f", ema) : ""));
        }

        // bearishFvg
        BigDecimal gapSize = c0.getLow().subtract(c2.getHigh());
        BigDecimal refPrice = c0.getLow();

        // 최소 공백 크기 필터
        if (refPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal gapPct = gapSize.divide(refPrice, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            if (gapPct.compareTo(BigDecimal.valueOf(minGapPct)) < 0) {
                return StrategySignal.hold(String.format(
                        "하락 FVG 감지됐으나 공백 크기 미달: %.4f%% < %.4f%%", gapPct, minGapPct));
            }
        }

        // EMA 필터: 현재 종가가 EMA 아래에 있어야 하락 추세 확인
        if (emaFilter && ema != null && c2.getClose().compareTo(ema) > 0) {
            return StrategySignal.hold(String.format(
                    "하락 FVG 감지됐으나 EMA 필터 차단: 종가=%.2f > EMA=%.2f (역추세)", c2.getClose(), ema));
        }

        BigDecimal strength = calcStrength(gapSize, c1);
        return StrategySignal.sell(strength, String.format(
                "하락 FVG: c0.low=%.2f > c2.high=%.2f (공백=%.2f, 임펄스 캔들 몸통=%.2f)%s",
                c0.getLow(), c2.getHigh(), gapSize,
                c1.getClose().subtract(c1.getOpen()).abs(),
                ema != null ? String.format(", EMA=%.2f", ema) : ""));
    }

    /**
     * 신호 강도 계산: 공백 크기 / 임펄스 캔들 몸통 크기 비율.
     * 몸통이 없으면(도지 캔들) 공백 크기만으로 50~100 범위 산출.
     */
    private BigDecimal calcStrength(BigDecimal gapSize, Candle impulse) {
        BigDecimal body = impulse.getClose().subtract(impulse.getOpen()).abs();
        if (body.compareTo(BigDecimal.ZERO) == 0) {
            // 도지 캔들인 경우 기본 강도 60
            return BigDecimal.valueOf(60);
        }
        // 공백/몸통 비율이 0.5 이상이면 강한 FVG
        BigDecimal ratio = gapSize.divide(body, SCALE, RoundingMode.HALF_UP);
        BigDecimal strength = BigDecimal.valueOf(50)
                .add(ratio.multiply(BigDecimal.valueOf(50)))
                .min(BigDecimal.valueOf(100))
                .max(BigDecimal.valueOf(50));
        return strength.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public int getMinimumCandleCount() {
        // EMA(20) 기본값 기준: 20 + 3 = 23
        return 23;
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : defaultVal;
    }

    private boolean getBoolean(Map<String, Object> params, String key, boolean defaultVal) {
        Object v = params.get(key);
        return v instanceof Boolean ? (Boolean) v : defaultVal;
    }
}
