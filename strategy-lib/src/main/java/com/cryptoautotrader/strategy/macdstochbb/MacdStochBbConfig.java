package com.cryptoautotrader.strategy.macdstochbb;

/**
 * MACD + StochRSI + 볼린저밴드 복합 추세 전략 기본 파라미터 상수
 *
 * <p>StrategyConfig JSON 작성 시 참조용. 모든 값은 MacdStochBbStrategy의 기본값과 동일.
 *
 * <pre>
 * {
 *   "fastPeriod":        12,
 *   "slowPeriod":        26,
 *   "signalPeriod":       9,
 *   "rsiPeriod":         14,
 *   "stochPeriod":       14,
 *   "stochSignalPeriod":  3,
 *   "oversoldLevel":     20.0,
 *   "overboughtLevel":   80.0,
 *   "bbPeriod":          20,
 *   "bbMultiplier":       2.0,
 *   "volumePeriod":      20,
 *   "cooldownCandles":    3,
 *   "sidewaysThreshold":  0.0005,
 *   "supportPercentB":    0.35,
 *   "stopLossPct":        0.02,
 *   "takeProfitPct":      0.04
 * }
 * </pre>
 */
public final class MacdStochBbConfig {

    // MACD
    public static final int    FAST_PERIOD         = 12;
    public static final int    SLOW_PERIOD         = 26;
    public static final int    SIGNAL_PERIOD       = 9;

    // StochRSI
    public static final int    RSI_PERIOD          = 14;
    public static final int    STOCH_PERIOD        = 14;
    public static final int    STOCH_SIGNAL_PERIOD = 3;
    public static final double OVERSOLD_LEVEL      = 20.0;
    public static final double OVERBOUGHT_LEVEL    = 80.0;

    // 볼린저밴드
    public static final int    BB_PERIOD           = 20;
    public static final double BB_MULTIPLIER       = 2.0;

    // 거래량 필터
    public static final int    VOLUME_PERIOD       = 20;

    // 리스크 관리
    public static final int    COOLDOWN_CANDLES    = 3;
    public static final double SIDEWAYS_THRESHOLD  = 0.0005;
    public static final double SUPPORT_PERCENT_B   = 0.35;  // %B ≤ 0.35 → 지지선 근처
    // ⚠️ 2026-09-22 (Wave 4-M) — 단위를 **비율 → 퍼센트**로 통일했다.
    //
    //    이전: STOP_LOSS_PCT = 0.02 (비율). 그런데 같은 이름 stopLossPct 를
    //          HeikinAshiStochStrategy 는 1.5(퍼센트)로 읽고 /100 해서 썼다.
    //          **같은 params 맵이 두 전략에 100배 다른 뜻으로 읽혔다.**
    //          세션 strategy_params 가 전략 params 를 그대로 시드하므로
    //          stopLossPct: 5.0 (5% 의도)을 저장하면 여기서는 500% 가 됐다 —
    //          손절이 사실상 사라지는데 예외도 경고도 나지 않는다.
    //
    //    지금: 프로젝트 규약(ExitRuleConfig 5.0, 전 DTO·엔티티)과 같은 퍼센트다.
    //          사용처에서 StrategyParamUtils.getPercentAsRatio 로 읽어 /100 하므로
    //          **기본값 거동은 이전과 완전히 같다**(0.02 비율 ≡ 2.0 퍼센트).
    //          바뀌는 것은 호출자가 값을 명시로 넘길 때의 해석뿐이다.
    public static final double STOP_LOSS_PCT       = 2.0;   // -2%
    public static final double TAKE_PROFIT_PCT     = 4.0;   // +4%

    private MacdStochBbConfig() {}
}
