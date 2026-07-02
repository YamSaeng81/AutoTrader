package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * BTC 시장 전체 급락 서킷 브레이커 — 알트 상관관계가 높은 업비트 KRW 마켓에서
 * {@link BlackSwanGuard}(코인별)만으로는 "BTC가 무너지면 전체 알트가 같이 무너지는" 리스크를
 * 방어하지 못한다는 문제의식에서 출발한 시장 전체 게이트다.
 *
 * <p>2026-07-02 codex 분석 §6 "BTC 1시간 수익률 &lt; -1.5%면 신규 매수 금지" 제안의 구현.
 * {@link BlackSwanGuard}가 명시적으로 범위 밖으로 남겨둔 "글로벌 서킷 브레이커"에 해당한다.</p>
 *
 * <h3>적용 범위</h3>
 * <p>BTC 캔들 하나만으로 전체 세션(코인 무관)의 신규 진입(BUY)을 차단한다. 청산(SELL)에는
 * 적용하지 않는다 — 보유 포지션은 SL/TP가 항상 별도로 방어한다.</p>
 */
public final class BtcMarketGuard {

    private static final long LOOKBACK_MINUTES = 60;
    private static final BigDecimal DROP_THRESHOLD_PCT = new BigDecimal("-1.5");

    private BtcMarketGuard() {}

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
     * BTC 캔들을 기준으로 시장 전체 서킷 브레이커 발동 여부를 판정한다.
     *
     * @param btcCandles BTC 캔들 (시간 오름차순, 마지막 원소가 현재/최신 캔들). null/empty면 판단 불가 → 미발동.
     * @return 발동 시 사유 포함, 미발동 시 {@link Result#none()}
     */
    public static Result check(List<Candle> btcCandles) {
        if (btcCandles == null || btcCandles.isEmpty()) {
            return Result.none();
        }

        Candle current = btcCandles.get(btcCandles.size() - 1);
        BigDecimal currentClose = current.getClose();

        Instant cutoff = current.getTime().minus(LOOKBACK_MINUTES, ChronoUnit.MINUTES);
        BigDecimal windowHigh = btcCandles.stream()
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
                    "BTC 1시간 급락 %.2f%% (고점 %s → 현재 %s) — 전체 신규 진입 차단",
                    dropPct, windowHigh, currentClose));
        }
        return Result.none();
    }
}
