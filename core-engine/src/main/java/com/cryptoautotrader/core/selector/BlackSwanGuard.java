package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;

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
 *   <li>현재 캔들 거래량이 최근 {@value #VOLUME_BASELINE_CANDLES}개 평균의 5배 이상</li>
 * </ul>
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

    /** 발동 시 보유 포지션에 적용할 강화 트레일링 SL 마진 (고점/저점 대비 0.3%) */
    public static final BigDecimal TIGHTENED_TRAILING_SL_MARGIN = new BigDecimal("0.003");

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

        Result dropResult = checkDrop(candles, current, currentClose);
        if (dropResult.triggered()) {
            return dropResult;
        }

        return checkVolumeSpike(candles, current);
    }

    private static Result checkDrop(List<Candle> candles, Candle current, BigDecimal currentClose) {
        Instant cutoff = current.getTime().minus(LOOKBACK_MINUTES, ChronoUnit.MINUTES);
        BigDecimal windowHigh = candles.stream()
                .filter(c -> !c.getTime().isBefore(cutoff))
                .map(Candle::getHigh)
                .max(Comparator.naturalOrder())
                .orElse(currentClose);

        if (windowHigh.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.none();
        }

        BigDecimal dropPct = currentClose.subtract(windowHigh)
                .divide(windowHigh, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (dropPct.compareTo(DROP_THRESHOLD_PCT) <= 0) {
            return Result.triggered(String.format(
                    "1시간 내 급락 %.2f%% (고점 %s → 현재 %s)", dropPct, windowHigh, currentClose));
        }
        return Result.none();
    }

    private static Result checkVolumeSpike(List<Candle> candles, Candle current) {
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
                    "거래량 급증 %.1f배 (최근 %d캔들 평균 %s → 현재 %s)",
                    ratio, VOLUME_BASELINE_CANDLES, avgVolume, current.getVolume()));
        }
        return Result.none();
    }
}
