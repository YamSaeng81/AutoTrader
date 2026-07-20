package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.strategy.Candle;

import java.time.ZoneId;
import java.util.List;

/**
 * 야간 시간대·TRANSITIONAL 레짐 신호 감쇠 게이트 — 2026-07-20 신호품질 분석(최근 30일, 전 세션) 근거.
 *
 * <p>{@link Ema200RegimeGate}/{@link RangeRegimeGate}와 달리 신호를 완전 차단하지 않는다.
 * EMA 방향 필터({@link CompositeStrategy}의 emaFilterDampenFactor)와 동일하게 buy/sellScore를
 * threshold 비교 전에 비례 감쇠시켜, 강한 신호는 여전히 통과할 여지를 남긴다.</p>
 *
 * <h3>근거 (30일 신호품질 집계, 표본 규모 수백~수천 건)</h3>
 * <ul>
 *   <li>KST 20~23시: 4h 승률 31~38%·평균수익 -0.25%~-0.59% (전 시간대 중 최저).
 *       반대로 KST 06~09시는 승률 56~62%·평균 +0.36%~+0.58%.</li>
 *   <li>TRANSITIONAL 레짐: 24h 승률 17.4%·평균 -1.21% (TREND +1.05%, RANGE +0.05% 대비 최악).</li>
 * </ul>
 *
 * <p><b>주의</b>: 표본에 동적 세션의 SCANNING SELL 노이즈(청산 대상 없음에도 기록되는 로그)가
 * 다수 섞여 있어 완전한 실전 인과 검증은 아직 아니다. 하드 차단 대신 감쇠로 반영한 이유다.
 * 재평가 시점(2~4주 후 신호품질 재분석)에 표본을 노이즈 제외 후 재검증할 것.</p>
 */
public final class SignalQualityDampenGate {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 야간 감쇠 시작 시각(KST, 포함) — 20시부터 자정 전까지(20~23시) */
    private static final int NIGHT_DAMPEN_START_HOUR_KST = 20;

    /** 감쇠 계수 기본값 — params로 override 가능(백테스트/튜닝용), 1.0이면 무감쇠 */
    public static final double DEFAULT_NIGHT_DAMPEN_FACTOR        = 0.6;
    public static final double DEFAULT_TRANSITIONAL_DAMPEN_FACTOR = 0.5;

    private SignalQualityDampenGate() {}

    /**
     * 마지막(현재) 캔들 시각이 KST 20~23시이면 야간 감쇠 계수를, 아니면 1.0(무감쇠)을 반환한다.
     *
     * @param candles       시간 오름차순 캔들 (마지막 원소가 현재 평가 대상)
     * @param configuredFactor 감쇠 계수 (params override 값 또는 {@link #DEFAULT_NIGHT_DAMPEN_FACTOR})
     */
    public static double nightFactor(List<Candle> candles, double configuredFactor) {
        if (candles == null || candles.isEmpty()) {
            return 1.0;
        }
        int hourKst = candles.get(candles.size() - 1).getTime().atZone(KST).getHour();
        return hourKst >= NIGHT_DAMPEN_START_HOUR_KST ? configuredFactor : 1.0;
    }

    /** 현재 KST 시각(테스트/로깅용) */
    public static int hourKst(List<Candle> candles) {
        return candles.get(candles.size() - 1).getTime().atZone(KST).getHour();
    }

    /**
     * 레짐이 TRANSITIONAL이면 감쇠 계수를, 아니면 1.0(무감쇠)을 반환한다.
     *
     * @param regime        {@link com.cryptoautotrader.core.regime.MarketRegimeDetector#detectRaw}
     *                      등으로 감지한 현재 레짐. null 허용(감지 불가 시 무감쇠).
     * @param configuredFactor 감쇠 계수 (params override 값 또는 {@link #DEFAULT_TRANSITIONAL_DAMPEN_FACTOR})
     */
    public static double transitionalFactor(MarketRegime regime, double configuredFactor) {
        return regime == MarketRegime.TRANSITIONAL ? configuredFactor : 1.0;
    }
}
