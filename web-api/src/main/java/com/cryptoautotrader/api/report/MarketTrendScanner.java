package com.cryptoautotrader.api.report;

import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 대형/중형 코인 48시간 추세 스캐너.
 *
 * <p>Upbit 24h 거래대금 상위 N개(대형·중형 프록시)에 대해 다음을 계산해 아침 브리핑에 제공한다:
 * <ul>
 *   <li>48h / 24h 가격 변화율</li>
 *   <li>중기 추세: 시간봉 EMA200 대비 위치(상/하) + 이격률(추세 강도)</li>
 *   <li>변동성: ATR%</li>
 *   <li>거래량 급증: 최근 24h 시간봉 평균 거래량 vs 직전 24h (관심 급증 신호)</li>
 * </ul>
 *
 * <p>읽기 전용(REST 조회만) — 매매 로직과 무관.
 */
@Service
@RequiredArgsConstructor
public class MarketTrendScanner {

    private static final Logger log = LoggerFactory.getLogger(MarketTrendScanner.class);

    /** 추세 판정용 시간봉 EMA 기간 (데이터 부족 시 가용 개수로 축소) */
    private static final int EMA_PERIOD = 200;
    /** ATR 계산 봉 수 */
    private static final int ATR_PERIOD = 14;
    /** 48h 계산에 필요한 최소 시간봉 수 (48h + 여유) */
    private static final int MIN_CANDLES = 49;

    private final UpbitRestClient upbitRestClient;

    /**
     * 24h 거래대금 상위 {@code topN} 코인의 추세 스냅샷을 반환한다.
     * 조회 실패 코인은 결과에서 제외한다. 전체 실패 시 빈 리스트.
     */
    public List<CoinTrend> scanTopCoins(int topN) {
        try {
            List<Map<String, Object>> markets = upbitRestClient.getMarkets();
            Map<String, String> nameByMarket = new HashMap<>();
            List<String> krwMarkets = new ArrayList<>();
            for (Map<String, Object> m : markets) {
                String mk = (String) m.get("market");
                if (mk == null || !mk.startsWith("KRW-")) continue;
                krwMarkets.add(mk);
                Object kn = m.get("korean_name");
                if (kn != null) nameByMarket.put(mk, kn.toString());
            }
            if (krwMarkets.isEmpty()) {
                log.warn("[TrendScanner] KRW 마켓 없음");
                return List.of();
            }

            List<Map<String, Object>> tickers = upbitRestClient.getTicker(String.join(",", krwMarkets));
            List<Map<String, Object>> top = tickers.stream()
                    .filter(t -> t.get("acc_trade_price_24h") != null)
                    .sorted(Comparator.comparingDouble(t -> -toDouble(t.get("acc_trade_price_24h"))))
                    .limit(Math.max(1, topN))
                    .toList();

            List<CoinTrend> result = new ArrayList<>();
            for (Map<String, Object> t : top) {
                String mk = (String) t.get("market");
                if (mk == null) continue;
                String name = nameByMarket.getOrDefault(mk, mk.replace("KRW-", ""));
                CoinTrend ct = buildTrend(mk, name, toBigDecimal(t.get("acc_trade_price_24h")));
                if (ct != null) result.add(ct);
            }
            log.info("[TrendScanner] 상위 {}개 요청 → {}개 추세 산출", topN, result.size());
            return result;
        } catch (Exception e) {
            log.warn("[TrendScanner] 스캔 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private CoinTrend buildTrend(String market, String name, BigDecimal tradeValue24h) {
        try {
            // 시간봉 newest-first: index 0 = 현재, index 24 = 24h 전, index 48 = 48h 전
            List<UpbitCandleResponse> c = upbitRestClient.getCandles(market, "minutes", 60, Instant.now(), EMA_PERIOD);
            if (c.size() < MIN_CANDLES) {
                log.debug("[TrendScanner] {} 캔들 부족: {}개", market, c.size());
                return null;
            }
            BigDecimal now   = c.get(0).getTradePrice();
            BigDecimal ago24 = c.get(24).getTradePrice();
            BigDecimal ago48 = c.get(48).getTradePrice();
            if (now == null) return null;

            BigDecimal ch24    = pctChange(now, ago24);
            BigDecimal ch48    = pctChange(now, ago48);
            BigDecimal atrPct  = atrPct(c);
            BigDecimal volSurge = volSurgePct(c);
            Double ema         = computeEma(c);
            Boolean uptrend    = ema == null ? null : now.doubleValue() > ema;
            BigDecimal emaGap  = ema == null || ema == 0.0 ? null
                    : BigDecimal.valueOf((now.doubleValue() - ema) / ema * 100).setScale(2, RoundingMode.HALF_UP);

            return new CoinTrend(market, name, ch48, ch24, atrPct, uptrend, emaGap, volSurge, tradeValue24h);
        } catch (Exception e) {
            log.debug("[TrendScanner] {} 추세 계산 실패: {}", market, e.getMessage());
            return null;
        }
    }

    /** (now - past) / past * 100, 소수 2자리 */
    private static BigDecimal pctChange(BigDecimal now, BigDecimal past) {
        if (now == null || past == null || past.compareTo(BigDecimal.ZERO) == 0) return null;
        return now.subtract(past)
                .divide(past, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 최근 ATR_PERIOD 봉 ATR을 현재가 대비 %로. 데이터 부족 시 null */
    private static BigDecimal atrPct(List<UpbitCandleResponse> candles) {
        if (candles.size() < ATR_PERIOD + 1) return null;
        BigDecimal current = candles.get(0).getTradePrice();
        if (current == null || current.compareTo(BigDecimal.ZERO) == 0) return null;

        BigDecimal trSum = BigDecimal.ZERO;
        for (int i = 0; i < ATR_PERIOD; i++) {
            UpbitCandleResponse cur = candles.get(i);
            BigDecimal prevClose = candles.get(i + 1).getTradePrice();
            BigDecimal high = cur.getHighPrice();
            BigDecimal low  = cur.getLowPrice();
            if (high == null || low == null || prevClose == null) continue;
            BigDecimal tr = high.subtract(low).abs()
                    .max(high.subtract(prevClose).abs())
                    .max(low.subtract(prevClose).abs());
            trSum = trSum.add(tr);
        }
        BigDecimal atr = trSum.divide(BigDecimal.valueOf(ATR_PERIOD), 8, RoundingMode.HALF_UP);
        return atr.divide(current, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 거래량 급증률 — 최근 24h 시간봉 평균 거래량 대비 직전 24h 평균의 변화율(%).
     * +면 관심 급증, 데이터 부족·직전 0이면 null.
     */
    private static BigDecimal volSurgePct(List<UpbitCandleResponse> candles) {
        if (candles.size() < 48) return null;
        double recent = 0, prior = 0;
        for (int i = 0; i < 24; i++) {
            BigDecimal v = candles.get(i).getCandleAccTradeVolume();
            if (v != null) recent += v.doubleValue();
        }
        for (int i = 24; i < 48; i++) {
            BigDecimal v = candles.get(i).getCandleAccTradeVolume();
            if (v != null) prior += v.doubleValue();
        }
        if (prior <= 0) return null;
        return BigDecimal.valueOf((recent - prior) / prior * 100).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 시간봉 EMA(EMA_PERIOD, 부족 시 가용 개수) 값. 계산 불가 시 null.
     */
    private static Double computeEma(List<UpbitCandleResponse> candles) {
        int n = candles.size();
        if (n < 20) return null;
        int period = Math.min(EMA_PERIOD, n);
        double k = 2.0 / (period + 1);
        double ema = candles.get(n - 1).getTradePrice().doubleValue(); // oldest
        for (int i = n - 2; i >= 0; i--) {
            BigDecimal close = candles.get(i).getTradePrice();
            if (close == null) continue;
            ema = close.doubleValue() * k + ema * (1 - k);
        }
        return ema;
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return 0.0; }
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * 코인 48h 추세 스냅샷.
     *
     * @param uptrend  시간봉 EMA 대비 현재가 위(상승추세)면 true, 아래면 false, 판정불가면 null
     * @param emaGapPct EMA 이격률(%) — 추세 강도. +면 EMA 위로 강함, 계산불가면 null
     * @param volSurgePct 거래량 급증률(%) — 최근24h vs 직전24h 평균 거래량, 계산불가면 null
     */
    public record CoinTrend(
            String market,
            String koreanName,
            BigDecimal change48hPct,
            BigDecimal change24hPct,
            BigDecimal atrPct,
            Boolean uptrend,
            BigDecimal emaGapPct,
            BigDecimal volSurgePct,
            BigDecimal tradeValue24h) {}
}
