package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyParamUtils;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Weighted Voting 기반 복합 전략.
 *
 * <pre>
 * buyScore  = Σ(weight × confidence)  (BUY  신호 전략 합산)
 * sellScore = Σ(weight × confidence)  (SELL 신호 전략 합산)
 *
 * buyScore  ≥ 0.5                         → BUY  (strength ≈ score × 100)
 * sellScore ≥ 0.5                         → SELL
 * buyScore  ≥ 0.3                         → BUY  (weak)
 * sellScore ≥ 0.3                         → SELL (weak)
 * 양쪽 모두 ≥ 0.3 이고 강도 등급이 같음   → HOLD (상충 — 한쪽만 strong이면 그쪽 우세)
 * 그 외                                   → HOLD
 * </pre>
 */
public class CompositeStrategy implements Strategy {

    private static final double STRONG_THRESHOLD = 0.5;
    private static final double WEAK_THRESHOLD   = 0.3;

    /** EMA 방향 필터 기본 파라미터 */
    private static final int DEFAULT_EMA_SHORT = 20;
    private static final int DEFAULT_EMA_LONG  = 50;
    /**
     * EMA 역추세 감쇠 계수 기본값 — 0.0이면 기존과 동일하게 역추세 스코어를 완전히 0으로 만든다
     * (하드 차단과 동일 효과). params로 "emaFilterDampenFactor"(0.0~1.0)를 넘기면 완전 차단 대신
     * 점수를 비례 감쇠시켜, 강한 역추세 신호가 threshold를 넘을 여지를 남길 수 있다.
     * 2026-07-06 실전 로그 분석: 완전 차단 방식에서 STRONG_BUY(score 0.5+) 후보조차 하락추세
     * 판정 한 번으로 전부 취소됨 — A/B 백테스트로 완화 여지를 검증하기 위해 감쇠 방식을 추가.
     */
    private static final double DEFAULT_EMA_DAMPEN_FACTOR = 0.0;

    /** ADX 횡보장 필터 기본 파라미터 */
    private static final int    DEFAULT_ADX_PERIOD    = 14;
    private static final double DEFAULT_ADX_THRESHOLD = 20.0;
    /** 동적 ADX 임계값 계산용: 최근 N개 캔들의 ADX 분포에서 이 백분위수를 임계값으로 사용 */
    private static final int    ADX_DYNAMIC_WINDOW      = 60;   // 60캔들 ADX 분포
    private static final double ADX_DYNAMIC_PERCENTILE  = 0.30; // 30th percentile
    private static final double ADX_DYNAMIC_MIN         = 15.0; // 동적 임계 하한 (핫픽스 수준)
    private static final double ADX_DYNAMIC_MAX         = 25.0; // 동적 임계 상한

    private final String name;
    private final List<WeightedStrategy> strategies;
    private final double totalWeight;

    /**
     * EMA 방향 필터 활성화 여부.
     * true이면 단기 EMA > 장기 EMA(상승 추세) 구간에서 SELL 신호를,
     * 단기 EMA < 장기 EMA(하락 추세) 구간에서 BUY 신호를 HOLD로 억제한다.
     */
    private final boolean emaFilterEnabled;

    /**
     * ADX 횡보장 필터 활성화 여부.
     * true이면 ADX(14) < 20 구간에서 하위 전략 평가 없이 즉시 HOLD를 반환한다.
     * 추세가 없는 횡보장에서 ATR 기반 가짜 돌파 신호를 사전 차단한다.
     */
    private final boolean adxFilterEnabled;

    /** 기본 COMPOSITE 전략 (EMA 필터, ADX 필터 미적용) */
    public CompositeStrategy(List<WeightedStrategy> strategies) {
        this("COMPOSITE", strategies);
    }

    /** 이름을 명시적으로 지정하는 프리셋 복합 전략 (EMA 필터, ADX 필터 미적용) */
    public CompositeStrategy(String name, List<WeightedStrategy> strategies) {
        this(name, strategies, false, false);
    }

    /** EMA 방향 필터 활성화 여부를 명시하는 생성자 (ADX 필터 미적용) */
    public CompositeStrategy(String name, List<WeightedStrategy> strategies, boolean emaFilterEnabled) {
        this(name, strategies, emaFilterEnabled, false);
    }

    /** EMA 방향 필터 + ADX 횡보장 필터 활성화 여부를 모두 명시하는 생성자 */
    public CompositeStrategy(String name, List<WeightedStrategy> strategies,
                             boolean emaFilterEnabled, boolean adxFilterEnabled) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("전략 목록이 비어 있습니다.");
        }
        double sum = strategies.stream().mapToDouble(WeightedStrategy::getWeight).sum();
        if (sum <= 0) {
            throw new IllegalArgumentException("전략 가중치 합계가 0 이하입니다: " + sum);
        }
        this.name             = name;
        this.strategies       = strategies;
        this.totalWeight      = sum;
        this.emaFilterEnabled = emaFilterEnabled;
        this.adxFilterEnabled = adxFilterEnabled;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getMinimumCandleCount() {
        int strategyMin = strategies.stream()
                .mapToInt(ws -> ws.getStrategy().getMinimumCandleCount())
                .max()
                .orElse(0);
        // EMA 필터: 장기 EMA(50) 계산에 필요한 최소 캔들 수 보장
        if (emaFilterEnabled) {
            strategyMin = Math.max(strategyMin, DEFAULT_EMA_LONG);
        }
        // ADX 필터: ADX(14) 계산에 period*2+1 = 29개 캔들 필요
        if (adxFilterEnabled) {
            strategyMin = Math.max(strategyMin, DEFAULT_ADX_PERIOD * 2 + 1);
        }
        return strategyMin;
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        // ADX 횡보장 필터: 추세 없는 구간에서 하위 전략 평가 전 즉시 HOLD 반환
        // 임계값/주기는 params로 override 가능 ("adxThreshold", "adxPeriod"),
        // "skipAdxFilter"=true 시 호출자(레짐 인지 등)가 필터를 비활성화할 수 있다.
        boolean skipAdx = params != null && StrategyParamUtils.getBoolean(params, "skipAdxFilter", false);
        if (adxFilterEnabled && !skipAdx) {
            int adxPeriod = params != null
                    ? StrategyParamUtils.getInt(params, "adxPeriod", DEFAULT_ADX_PERIOD)
                    : DEFAULT_ADX_PERIOD;

            // 동적 ADX 임계값: 최근 60캔들 ADX 분포의 30th percentile
            // params에 adxThreshold가 명시된 경우 그 값을 우선 사용 (수동 override)
            // 분포 샘플 부족(<10) 시 DEFAULT_ADX_THRESHOLD(20.0)로 폴백
            double adxThreshold;
            if (params != null && params.containsKey("adxThreshold")) {
                adxThreshold = StrategyParamUtils.getDouble(params, "adxThreshold", DEFAULT_ADX_THRESHOLD);
            } else {
                double dynamic = IndicatorUtils.adxPercentileThreshold(
                        candles, adxPeriod, ADX_DYNAMIC_WINDOW,
                        ADX_DYNAMIC_PERCENTILE, DEFAULT_ADX_THRESHOLD);
                adxThreshold = Math.max(ADX_DYNAMIC_MIN, Math.min(ADX_DYNAMIC_MAX, dynamic));
            }

            if (candles.size() >= adxPeriod * 2 + 1) {
                BigDecimal adx = IndicatorUtils.adx(candles, adxPeriod);
                if (adx.doubleValue() < adxThreshold) {
                    return StrategySignal.hold(String.format(
                            "ADX필터 횡보장 차단: ADX(%.1f) < %.1f (동적임계, period=%d)",
                            adx.doubleValue(), adxThreshold, adxPeriod));
                }
            }
        }

        double buyScore  = 0.0;
        double sellScore = 0.0;
        StringBuilder reasons = new StringBuilder();

        for (WeightedStrategy ws : strategies) {
            StrategySignal signal = ws.evaluate(candles, params);
            double w    = ws.getWeight();
            double conf = signal.getConfidence().doubleValue();

            switch (signal.getAction()) {
                case BUY  -> buyScore  += w * conf;
                case SELL -> sellScore += w * conf;
                case HOLD -> {}
            }
            reasons.append(ws.getStrategy().getName())
                   .append(':').append(signal.getAction())
                   .append('(').append(String.format("%.0f", signal.getStrength().doubleValue())).append(')')
                   .append(' ');
        }

        String detail = reasons.toString().trim();

        // 가중치 합계로 정규화 (총합이 1.0 초과 시만 — 총합 < 1.0이면 raw score 유지)
        double normalizer = Math.max(totalWeight, 1.0);
        buyScore  /= normalizer;
        sellScore /= normalizer;

        // ── 야간(KST 20~23시) 신호 감쇠 ──────────────────────────────────────
        // 근거: SignalQualityDampenGate 참조 (2026-07-20 30일 신호품질 분석).
        // EMA 필터와 달리 방향 무관 양쪽 스코어를 감쇠 — 야간엔 BUY/SELL 모두 승률이 낮았다.
        if (buyScore > 0 || sellScore > 0) {
            double nightFactorParam = params != null
                    ? StrategyParamUtils.getDouble(params, "nightDampenFactor",
                            SignalQualityDampenGate.DEFAULT_NIGHT_DAMPEN_FACTOR)
                    : SignalQualityDampenGate.DEFAULT_NIGHT_DAMPEN_FACTOR;
            double nightFactor = SignalQualityDampenGate.nightFactor(candles, nightFactorParam);
            if (nightFactor < 1.0) {
                double rawBuy = buyScore, rawSell = sellScore;
                buyScore  *= nightFactor;
                sellScore *= nightFactor;
                detail += String.format(" [야간감쇠(KST %02d시): buy %.2f→%.2f sell %.2f→%.2f]",
                        SignalQualityDampenGate.hourKst(candles), rawBuy, buyScore, rawSell, sellScore);
            }
        }

        // ── TRANSITIONAL 레짐 신호 감쇠 ──────────────────────────────────────
        // 레짐 감지는 RangeRegimeGate와 동일하게 MarketRegimeDetector.detectRaw(stateless)를 사용.
        if ((buyScore > 0 || sellScore > 0) && candles.size() >= MarketRegimeDetector.MIN_CANDLE_COUNT) {
            double transitionalFactorParam = params != null
                    ? StrategyParamUtils.getDouble(params, "transitionalDampenFactor",
                            SignalQualityDampenGate.DEFAULT_TRANSITIONAL_DAMPEN_FACTOR)
                    : SignalQualityDampenGate.DEFAULT_TRANSITIONAL_DAMPEN_FACTOR;
            MarketRegime regime = new MarketRegimeDetector().detectRaw(candles);
            double transitionalFactor = SignalQualityDampenGate.transitionalFactor(regime, transitionalFactorParam);
            if (transitionalFactor < 1.0) {
                double rawBuy = buyScore, rawSell = sellScore;
                buyScore  *= transitionalFactor;
                sellScore *= transitionalFactor;
                detail += String.format(" [TRANSITIONAL감쇠: buy %.2f→%.2f sell %.2f→%.2f]",
                        rawBuy, buyScore, rawSell, sellScore);
            }
        }

        // EMA 방향 필터: 역추세 스코어를 threshold 비교 전에 감쇠시킨다.
        // dampenFactor 기본값(0.0)은 역추세 스코어를 0으로 만들어 기존의 "완전 차단" 동작과
        // 결과가 동일하다 — params로 "emaFilterDampenFactor"(0.0~1.0)를 넘기면 완전 차단 대신
        // 비례 감쇠로 완화해 백테스트로 효과를 검증할 수 있다.
        if (emaFilterEnabled && candles.size() >= DEFAULT_EMA_LONG) {
            List<BigDecimal> closes = candles.stream().map(Candle::getClose).toList();
            BigDecimal emaShort = IndicatorUtils.ema(closes, DEFAULT_EMA_SHORT);
            BigDecimal emaLong  = IndicatorUtils.ema(closes, DEFAULT_EMA_LONG);
            boolean uptrend = emaShort.compareTo(emaLong) > 0;

            double dampenFactor = params != null
                    ? StrategyParamUtils.getDouble(params, "emaFilterDampenFactor", DEFAULT_EMA_DAMPEN_FACTOR)
                    : DEFAULT_EMA_DAMPEN_FACTOR;
            dampenFactor = Math.max(0.0, Math.min(1.0, dampenFactor));

            if (uptrend && sellScore > 0) {
                double raw = sellScore;
                sellScore *= dampenFactor;
                detail += String.format(" [EMA필터: 상승추세(EMA%d=%.0f>EMA%d=%.0f) SELL %.2f→%.2f]",
                        DEFAULT_EMA_SHORT, emaShort.doubleValue(), DEFAULT_EMA_LONG, emaLong.doubleValue(),
                        raw, sellScore);
            } else if (!uptrend && buyScore > 0) {
                double raw = buyScore;
                buyScore *= dampenFactor;
                detail += String.format(" [EMA필터: 하락추세(EMA%d=%.0f<EMA%d=%.0f) BUY %.2f→%.2f]",
                        DEFAULT_EMA_SHORT, emaShort.doubleValue(), DEFAULT_EMA_LONG, emaLong.doubleValue(),
                        raw, buyScore);
            }
        }

        // WEAK_THRESHOLD/STRONG_THRESHOLD는 params로 override 가능 ("weakThreshold"/"strongThreshold")
        // — 2026-07-02 S-1: WEAK 0.4→0.3 하향의 A/B 백테스트 검증용. 미지정 시 기본값(0.3/0.5) 사용.
        double weakThreshold = params != null
                ? StrategyParamUtils.getDouble(params, "weakThreshold", WEAK_THRESHOLD)
                : WEAK_THRESHOLD;
        double strongThreshold = params != null
                ? StrategyParamUtils.getDouble(params, "strongThreshold", STRONG_THRESHOLD)
                : STRONG_THRESHOLD;

        return finalSignal(buyScore, sellScore, detail, weakThreshold, strongThreshold);
    }

    private StrategySignal finalSignal(double buyScore, double sellScore, String detail,
                                        double weakThreshold, double strongThreshold) {
        // 임계 비교는 >= — MACD 단독 BUY(100) 등이 정확히 임계값(예: 0.20)에 떨어지는 경우가
        // 실전에서 가장 흔한 후보 패턴이라 strict 비교(>)는 이들을 전부 탈락시킨다 (2026-07-16 운영 분석)
        boolean buyStrong  = buyScore  >= strongThreshold;
        boolean sellStrong = sellScore >= strongThreshold;
        // 상충 판정은 양쪽 강도 등급이 같을 때만 — 한쪽만 strong이면 weak 반대 신호가
        // 강신호를 무효화하지 못한다 (weak 임계 인하 시 STRONG_BUY가 감쇠 SELL에 사살되는 역효과 방지)
        if (buyScore >= weakThreshold && sellScore >= weakThreshold && buyStrong == sellStrong) {
            return StrategySignal.hold(String.format("상충 신호 buy=%.2f sell=%.2f [%s]",
                    buyScore, sellScore, detail));
        }
        if (buyStrong) {
            return StrategySignal.buy(BigDecimal.valueOf(buyScore * 100),
                    String.format("STRONG_BUY score=%.2f [%s]", buyScore, detail));
        }
        if (sellStrong) {
            return StrategySignal.sell(BigDecimal.valueOf(sellScore * 100),
                    String.format("STRONG_SELL score=%.2f [%s]", sellScore, detail));
        }
        if (buyScore >= weakThreshold) {
            return StrategySignal.buy(BigDecimal.valueOf(buyScore * 100),
                    String.format("BUY score=%.2f [%s]", buyScore, detail));
        }
        if (sellScore >= weakThreshold) {
            return StrategySignal.sell(BigDecimal.valueOf(sellScore * 100),
                    String.format("SELL score=%.2f [%s]", sellScore, detail));
        }
        return StrategySignal.hold(String.format("점수 미달 buy=%.2f sell=%.2f [%s]",
                buyScore, sellScore, detail));
    }
}
