package com.cryptoautotrader.api.util;

import com.cryptoautotrader.strategy.IndicatorUtils;
import com.cryptoautotrader.strategy.Candle;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * {@code strategy_log.indicators_json} 스냅샷 생성기 (2026-08-19).
 *
 * <p><b>왜 필요했나</b>: 페이퍼 함대가 왜 거래하지 않는지 분석할 때 지표 원값을 볼 수 없어
 * {@code reason} 자유 텍스트를 정규식으로 파싱해야 했다. PAPER 로그 7,238건의
 * {@code indicators_json} 이 전부 NULL 이었기 때문이다 — LIVE 만 기록하고 있었고
 * DYNAMIC·PAPER 는 기록 경로 자체가 없었다.</p>
 *
 * <p>세 엔진이 같은 스냅샷을 남기게 해서 사후 분석이 문자열 파싱에 의존하지 않게 한다.
 * 실패해도 매매를 막지 않는다 — 로그 보조 정보이지 거래 판단의 입력이 아니다.</p>
 */
@Slf4j
public final class IndicatorSnapshot {

    private IndicatorSnapshot() {}

    /**
     * 지표 스냅샷 JSON. 계산에 실패하면 {@code null} — 호출부는 그대로 넘기면 된다.
     *
     * @param ema200Pass EMA200 게이트 통과 여부. 이 게이트가 없는 엔진은 {@code null}.
     */
    public static String of(List<Candle> candles, boolean lastCandleClosed, Object closedCandleTime,
                            Boolean ema200Pass) {
        if (candles == null || candles.isEmpty()) return null;
        try {
            BigDecimal adx = IndicatorUtils.adx(candles, 14);
            BigDecimal atr = IndicatorUtils.atr(candles, 14);
            BigDecimal close = candles.get(candles.size() - 1).getClose();
            BigDecimal ema20 = IndicatorUtils.ema(closes(candles), 20);
            BigDecimal ema50 = IndicatorUtils.ema(closes(candles), 50);

            return String.format(Locale.ROOT,
                    "{\"closedCandleBased\":true,\"lastCandleClosed\":%b,\"closedCandleTime\":%s,"
                            + "\"adx14\":%s,\"atr14\":%s,\"ema20\":%s,\"ema50\":%s,"
                            + "\"close\":%s,\"candleCount\":%d,\"ema200Pass\":%s}",
                    lastCandleClosed,
                    closedCandleTime != null ? "\"" + closedCandleTime + "\"" : "null",
                    plain(adx), plain(atr), plain(ema20), plain(ema50), plain(close),
                    candles.size(),
                    ema200Pass != null ? ema200Pass.toString() : "null");
        } catch (Exception e) {
            log.debug("지표 스냅샷 생성 실패 — 로그만 비운다: {}", e.toString());
            return null;
        }
    }

    private static List<BigDecimal> closes(List<Candle> candles) {
        return candles.stream().map(Candle::getClose).toList();
    }

    private static String plain(BigDecimal v) {
        return v != null ? v.toPlainString() : "null";
    }
}
