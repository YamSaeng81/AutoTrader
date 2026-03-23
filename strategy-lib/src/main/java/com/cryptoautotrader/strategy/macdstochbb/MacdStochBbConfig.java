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
    public static final double STOP_LOSS_PCT       = 0.02;  // -2%
    public static final double TAKE_PROFIT_PCT     = 0.04;  // +4%

    private MacdStochBbConfig() {}
}
