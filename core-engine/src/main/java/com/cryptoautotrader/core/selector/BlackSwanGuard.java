package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * 코인별 서킷 브레이커 — 급락/거래량 급증 구간에서 신규 진입을 차단하는 단일 진실 소스.
 *
 * <p>2026-04-30 신규 전략 제안 ★3 {@code BLACK_SWAN_GUARD}의 구현. LUNA/FTX류 사건에서는
 * 어떤 모멘텀/돌파 전략도 단독으로 방어할 수 없다는 문제의식에서 출발한, 전략과 무관한
 * 공통 안전망이다.</p>
 *
 * <h3>발동 조건 (OR)</h3>
 * <ul>
 *   <li>최근 1시간(wall-clock, 타임프레임 무관) 구간 고가 대비 현재 종가가 −5% 이상 하락</li>
 *   <li>현재 캔들 거래량이 최근 {@value #VOLUME_BASELINE_CANDLES}개 평균의 5배 이상
 *       <b>이면서</b> 1시간 고가 대비 −2% 이상 하락 동반 (조기경보)</li>
 * </ul>
 *
 * <p>거래량 조건에 하락 동반을 요구하는 이유(2026-07-08 운영 관찰): M5에서 거래량 5배 단독은
 * BTC/ETH 평상시에도 코인당 하루 1.5~2.8회 발동하는 노이즈였고(같은 기간 −2% 하락 동반은 0~3회),
 * 상승 돌파의 거래량 버스트에도 발동해 보유 포지션 SL을 오조임 → −0.6~−0.8% 조기 손절 2건을
 * 유발했다. 급락 없는 거래량 급증은 블랙스완 판별력이 없다.</p>
 *
 * <h3>적용 범위 (코인별, 시스템 전체 아님)</h3>
 * <p>해당 코인 자체의 신규 진입(BUY)만 차단한다. 다른 코인·세션에는 영향을 주지 않는다 —
 * {@link Ema200RegimeGate}/{@link RangeRegimeGate}와 동일하게 코인 단위 게이트로 설계했다.
 * 임의의 한 코인이 급락했다고 시스템 전체 세션을 멈추는 "글로벌 서킷 브레이커"는 더 큰 설계
 * (기준자산 선정, 세션 간 상태 공유)가 필요해 이번 범위에서 제외했다 — 필요 시 후속 과제.</p>
 *
 * <p>청산(SELL)에는 적용하지 않는다 — 보유 포지션은 SL/TP가 항상 별도로 방어한다.</p>
 */
public final class BlackSwanGuard {

    private static final long LOOKBACK_MINUTES = 60;
    private static final BigDecimal DROP_THRESHOLD_PCT = new BigDecimal("-5.0");
    private static final int VOLUME_BASELINE_CANDLES = 20;
    private static final BigDecimal VOLUME_SPIKE_MULTIPLIER = new BigDecimal("5.0");
    /** 거래량 급증 발동에 요구하는 동반 하락폭 (1시간 고가 대비 %) — 방향성 없는 버스트 오탐 차단 */
    private static final BigDecimal VOLUME_SPIKE_DROP_CONFIRM_PCT = new BigDecimal("-2.0");

    // ── 발동 시 보유 포지션 SL 강화 마진 (현재가 대비 비율) ──
    // 구 상수 0.3%는 M5 캔들 하나의 일상 등락폭보다 좁아 발동 즉시 청산 예약이나 다름없었다
    // (2026-07-08 운영 손절 2건). ATR(14)% 기반으로 코인 변동성에 맞추되, ExitRuleConfig의
    // ATR 손절 클램프(minAtrStopLossPct 1.2% / maxAtrStopLossPct 5.0%)와 같은 범위로 제한한다.
    private static final int TIGHTENED_SL_ATR_PERIOD = 14;
    private static final BigDecimal TIGHTENED_SL_MARGIN_MIN = new BigDecimal("0.012");
    private static final BigDecimal TIGHTENED_SL_MARGIN_MAX = new BigDecimal("0.05");

    private BlackSwanGuard() {}

    public record Result(boolean triggered, String reason) {
        private static final Result NONE = new Result(false, null);

        public static Result none() {
            return NONE;
        }

        public static Result triggered(String reason) {
            return new Result(true, reason);
        }
    }

    /**
     * 해당 코인의 최근 캔들을 기준으로 서킷 브레이커 발동 여부를 판정한다.
     *
     * @param candles 시간 오름차순 캔들 (마지막 원소가 현재/최신 캔들)
     * @return 발동 시 사유 포함, 미발동 시 {@link Result#none()}
     */
    public static Result check(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return Result.none();
        }

        Candle current = candles.get(candles.size() - 1);
        BigDecimal currentClose = current.getClose();

        BigDecimal dropPct = dropFromWindowHighPct(candles, current, currentClose);

        if (dropPct.compareTo(DROP_THRESHOLD_PCT) <= 0) {
            return Result.triggered(String.format(
                    "1시간 내 급락 %.2f%% (현재 %s)", dropPct, currentClose));
        }

        return checkVolumeSpike(candles, current, dropPct);
    }

    /** 최근 1시간(wall-clock) 구간 고가 대비 현재 종가 등락률(%) — 고가가 없거나 0 이하면 0 반환 */
    private static BigDecimal dropFromWindowHighPct(List<Candle> candles, Candle current,
                                                    BigDecimal currentClose) {
        Instant cutoff = current.getTime().minus(LOOKBACK_MINUTES, ChronoUnit.MINUTES);
        BigDecimal windowHigh = candles.stream()
                .filter(c -> !c.getTime().isBefore(cutoff))
                .map(Candle::getHigh)
                .max(Comparator.naturalOrder())
                .orElse(currentClose);

        if (windowHigh.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return currentClose.subtract(windowHigh)
                .divide(windowHigh, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private static Result checkVolumeSpike(List<Candle> candles, Candle current, BigDecimal dropPct) {
        // 하락 미동반 거래량 버스트는 오탐 — 상승 돌파·평상시 체결 몰림에도 5배는 흔하다 (클래스 주석 참조)
        if (dropPct.compareTo(VOLUME_SPIKE_DROP_CONFIRM_PCT) > 0) {
            return Result.none();
        }
        if (candles.size() <= VOLUME_BASELINE_CANDLES) {
            return Result.none();
        }
        List<Candle> baseline = candles.subList(
                candles.size() - 1 - VOLUME_BASELINE_CANDLES, candles.size() - 1);
        BigDecimal avgVolume = baseline.stream()
                .map(Candle::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(baseline.size()), 8, RoundingMode.HALF_UP);

        if (avgVolume.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.none();
        }

        BigDecimal ratio = current.getVolume().divide(avgVolume, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(VOLUME_SPIKE_MULTIPLIER) >= 0) {
            return Result.triggered(String.format(
                    "거래량 급증 %.1f배 + 1시간 내 하락 %.2f%% (최근 %d캔들 평균 %s → 현재 %s)",
                    ratio, dropPct, VOLUME_BASELINE_CANDLES, avgVolume, current.getVolume()));
        }
        return Result.none();
    }

    /**
     * 가드 발동 시 보유 포지션에 적용할 강화 트레일링 SL 마진 (현재가 대비 비율, 예: 0.015 = 1.5%).
     *
     * <p>ATR({@value #TIGHTENED_SL_ATR_PERIOD}) ÷ 현재 종가를 [1.2%, 5%]로 클램프한다.
     * 캔들이 부족하거나 계산 불가 시 하한(1.2%)으로 폴백 — 어떤 경우에도 구 0.3%처럼
     * 캔들 노이즈보다 좁아지지 않는다.</p>
     */
    public static BigDecimal tightenedSlMargin(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return TIGHTENED_SL_MARGIN_MIN;
        }
        try {
            BigDecimal atr = IndicatorUtils.atr(candles, TIGHTENED_SL_ATR_PERIOD);
            BigDecimal close = candles.get(candles.size() - 1).getClose();
            if (atr == null || close == null || close.compareTo(BigDecimal.ZERO) <= 0) {
                return TIGHTENED_SL_MARGIN_MIN;
            }
            BigDecimal margin = atr.divide(close, 6, RoundingMode.HALF_UP);
            return margin.max(TIGHTENED_SL_MARGIN_MIN).min(TIGHTENED_SL_MARGIN_MAX);
        } catch (Exception e) {
            return TIGHTENED_SL_MARGIN_MIN;
        }
    }
}
