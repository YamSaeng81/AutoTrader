package com.cryptoautotrader.api.service;

import com.cryptoautotrader.core.selector.WatchlistQualityGate;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.IndicatorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 실시간 코인 필터링 서비스.
 *
 * <h3>파이프라인</h3>
 * <ol>
 *   <li>KRW 마켓 전체 조회 → 24h 거래량 상위 {@code maxCandidates}개 추출</li>
 *   <li>호가 스프레드 필터: (최우선매도호가 - 최우선매수호가) / 최우선매도호가 &le; maxSpreadPct</li>
 *   <li>ATR 변동성 필터: ATR(14) / 현재가 &ge; minAtrPct</li>
 *   <li>최종 {@code targetSize}개 반환 (거래량 내림차순 유지)</li>
 * </ol>
 *
 * <p>UpbitRestClient Bean이 없으면(API 키 미설정 환경) 빈 목록을 반환하고 경고 로그를 남긴다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchlistFilterService {

    private static final int ATR_PERIOD = 14;
    /** ATR 계산에 필요한 최소 캔들 수 (period + 1) */
    private static final int ATR_MIN_CANDLES = ATR_PERIOD + 1;
    /** minAtrPct 정규화 기준 타임프레임 (분) — minAtrPct 파라미터는 이 타임프레임 기준값으로 해석한다 */
    private static final long BASELINE_TIMEFRAME_MIN = 60;
    /**
     * 품질 게이트용 캔들 조회 개수 — EMA200 산출에 200개가 필요하므로 여유분 포함 210개.
     * (Ema200RegimeGate는 200개 미만이면 보수적으로 상승추세를 허용하므로 반드시 200개 이상 확보한다.)
     */
    private static final int CANDLE_COUNT_FOR_FILTER = 210;

    private final UpbitRestClient upbitRestClient;

    /**
     * 워치리스트 품질 큐레이션 기준 (2026-07-24). 각 값이 null·false면 해당 검사를 건너뛴다.
     * {@link WatchlistQualityGate} 참조.
     *
     * @param minTradeValueKrw 24h 거래대금 유동성 하한 KRW (null·0이면 생략)
     * @param maxAtrPct        ATR(14)/현재가 변동성 상한 % (H1 기준값, null·0이면 생략)
     * @param requireUptrend   종가 &gt; EMA200 상승추세 코인만 포함
     * @param excludeCrashing  1시간 내 급락 중인 코인 제외
     */
    public record QualityCriteria(BigDecimal minTradeValueKrw, BigDecimal maxAtrPct,
                                  boolean requireUptrend, boolean excludeCrashing) {
        /** 모든 품질 검사를 끈 기준 — 기존 동작(거래대금+스프레드+ATR하한만) 유지용. */
        public static QualityCriteria disabled() {
            return new QualityCriteria(null, null, false, false);
        }
    }

    /**
     * 필터링된 감시 종목 목록을 반환한다.
     *
     * @param maxCandidates 거래량 기준 1차 후보 수 (예: 30)
     * @param targetSize    최종 감시 목록 크기 (예: 10)
     * @param minAtrPct     ATR(14)/현재가 최소 비율 % (예: 0.5)
     * @param maxSpreadPct  호가 스프레드 최대 비율 % (예: 0.1)
     * @param timeframe     ATR 계산 타임프레임 (예: "H1")
     * @return 거래량 내림차순으로 정렬된 KRW 마켓 코드 목록
     */
    public List<String> buildWatchlist(int maxCandidates, int targetSize,
                                       BigDecimal minAtrPct, BigDecimal maxSpreadPct,
                                       String timeframe) {
        return buildWatchlist(maxCandidates, targetSize, minAtrPct, maxSpreadPct, timeframe,
                QualityCriteria.disabled());
    }

    /**
     * 품질 큐레이션 기준을 적용한 감시 종목 목록을 반환한다. 기존 파이프라인(거래대금 상위 →
     * 스프레드 → ATR 하한) 뒤에 {@link WatchlistQualityGate}(유동성·변동성 상한·상승추세·비급락)를
     * 추가로 통과한 코인만 남긴다.
     *
     * @param criteria 품질 큐레이션 기준. {@link QualityCriteria#disabled()}면 기존 동작과 동일.
     */
    public List<String> buildWatchlist(int maxCandidates, int targetSize,
                                       BigDecimal minAtrPct, BigDecimal maxSpreadPct,
                                       String timeframe, QualityCriteria criteria) {
        try {
            // 1단계: KRW 마켓 목록 조회
            List<Map<String, Object>> markets = upbitRestClient.getMarkets();
            List<String> krwMarkets = markets.stream()
                    .map(m -> (String) m.get("market"))
                    .filter(m -> m != null && m.startsWith("KRW-"))
                    .toList();

            if (krwMarkets.isEmpty()) {
                log.warn("[Watchlist] KRW 마켓 목록 없음");
                return List.of();
            }

            // 2단계: 전체 티커 일괄 조회 → 24h 거래대금 기준 상위 maxCandidates개
            String marketsParam = String.join(",", krwMarkets);
            List<Map<String, Object>> tickers = upbitRestClient.getTicker(marketsParam);

            List<Map<String, Object>> topCandidates = tickers.stream()
                    .filter(t -> t.get("acc_trade_price_24h") != null)
                    .sorted(Comparator.comparingDouble(
                            t -> -toDouble(((Map<String, Object>) t).get("acc_trade_price_24h"))))
                    .limit(maxCandidates)
                    .toList();

            log.debug("[Watchlist] 거래대금 상위 {}개 후보 선정 (전체 {} KRW 마켓 중)",
                    topCandidates.size(), krwMarkets.size());

            // 3단계: 스프레드 + ATR 하한 + 품질 큐레이션 게이트
            List<String> passed = new ArrayList<>();
            for (Map<String, Object> ticker : topCandidates) {
                if (passed.size() >= targetSize) break;

                String market = (String) ticker.get("market");
                BigDecimal tradePrice = toBigDecimal(ticker.get("trade_price"));
                if (market == null || tradePrice == null || tradePrice.compareTo(BigDecimal.ZERO) <= 0) continue;

                // 스프레드 필터 (호가창)
                if (!passesSpreadFilter(market, tradePrice, maxSpreadPct)) continue;

                // 캔들 1회 조회 → ATR 하한 + EMA200 + 급락 판정에 공용 사용
                List<Candle> candles = fetchFilterCandles(market, timeframe);
                if (candles.size() < ATR_MIN_CANDLES) {
                    log.debug("[Watchlist] ATR 캔들 부족 ({}): {}개", market, candles.size());
                    continue;
                }

                BigDecimal atrPct = computeAtrPct(candles, tradePrice);
                if (atrPct == null) continue;

                // ATR 변동성 하한 (기존) — 너무 잔잔한 코인 배제
                BigDecimal effectiveMinAtrPct = normalizeAtrPct(minAtrPct, timeframe);
                if (atrPct.compareTo(effectiveMinAtrPct) < 0) {
                    log.debug("[Watchlist] ATR 하한 탈락: {} atrPct={}% < {}%",
                            market, atrPct.toPlainString(), effectiveMinAtrPct.toPlainString());
                    continue;
                }

                // 품질 큐레이션 게이트 — 유동성·변동성 상한·상승추세·비급락
                BigDecimal tradeValue24h = toBigDecimal(ticker.get("acc_trade_price_24h"));
                BigDecimal effectiveMaxAtrPct = criteria.maxAtrPct() != null
                        ? normalizeAtrPct(criteria.maxAtrPct(), timeframe) : null;
                WatchlistQualityGate.Decision decision = WatchlistQualityGate.evaluate(
                        tradeValue24h, atrPct, candles,
                        criteria.minTradeValueKrw(), effectiveMaxAtrPct,
                        criteria.requireUptrend(), criteria.excludeCrashing());
                if (!decision.accepted()) {
                    log.debug("[Watchlist] 품질 게이트 탈락: {} — {}", market, decision.reason());
                    continue;
                }

                passed.add(market);
            }

            log.info("[Watchlist] 최종 감시 목록 {}개: {}", passed.size(), passed);
            return passed;

        } catch (Exception e) {
            log.error("[Watchlist] 필터링 실패: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private boolean passesSpreadFilter(String market, BigDecimal tradePrice, BigDecimal maxSpreadPct) {
        try {
            List<Map<String, Object>> orderbooks = upbitRestClient.getOrderbook(market);
            if (orderbooks.isEmpty()) return false;

            Map<String, Object> ob = orderbooks.get(0);
            List<?> units = (List<?>) ob.get("orderbook_units");
            if (units == null || units.isEmpty()) return false;

            Map<?, ?> best = (Map<?, ?>) units.get(0);
            BigDecimal ask = toBigDecimal(best.get("ask_price"));
            BigDecimal bid = toBigDecimal(best.get("bid_price"));
            if (ask == null || bid == null || ask.compareTo(BigDecimal.ZERO) <= 0) return false;

            BigDecimal spreadPct = ask.subtract(bid)
                    .divide(ask, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            boolean pass = spreadPct.compareTo(maxSpreadPct) <= 0;
            if (!pass) {
                log.debug("[Watchlist] 스프레드 필터 탈락: {} spread={}% > {}%",
                        market, spreadPct.toPlainString(), maxSpreadPct.toPlainString());
            }
            return pass;
        } catch (Exception e) {
            log.debug("[Watchlist] 호가창 조회 실패 ({}): {}", market, e.getMessage());
            return false;
        }
    }

    /**
     * 품질 필터 공용 캔들 조회 — EMA200(200개) 산출이 가능하도록 {@value #CANDLE_COUNT_FOR_FILTER}개
     * 여유분까지 확보한다. 실패 시 빈 목록을 반환한다.
     */
    private List<Candle> fetchFilterCandles(String market, String timeframe) {
        try {
            Instant to = Instant.now();
            Instant from = to.minus((CANDLE_COUNT_FOR_FILTER + 5) * toMinutes(timeframe), ChronoUnit.MINUTES);
            UpbitCandleCollector collector = new UpbitCandleCollector(upbitRestClient);
            return collector.fetchCandles(market, timeframe, from, to);
        } catch (Exception e) {
            log.debug("[Watchlist] 캔들 조회 실패 ({}): {}", market, e.getMessage());
            return List.of();
        }
    }

    /** ATR(14)/현재가 % 계산 — 실패 시 null. */
    private BigDecimal computeAtrPct(List<Candle> candles, BigDecimal tradePrice) {
        try {
            BigDecimal atr = IndicatorUtils.atr(candles, ATR_PERIOD);
            return atr.divide(tradePrice, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ATR 임계값(%)은 {@value #BASELINE_TIMEFRAME_MIN}분(H1) 기준값으로 입력받는다고 가정하고,
     * 실제 평가 타임프레임에 맞춰 변동성 스케일링(sqrt-of-time 근사)을 적용한다. 하한(minAtrPct)과
     * 상한(maxAtrPct) 모두 동일 기준으로 정규화한다.
     *
     * <p>고정 임계값을 모든 타임프레임에 그대로 쓰면, 캔들 주기가 짧을수록(M5 등) ATR(14)이
     * 차지하는 가격 비율이 자연히 작아져 워치리스트가 텅 비는 문제가 있었다. 반대로 H4처럼 긴
     * 타임프레임에서는 동일 임계값이 지나치게 헐거워진다. sqrt(타임프레임비율) 스케일링은 변동성이
     * 시간의 제곱근에 비례한다는 통상적 근사치이며, 백테스트로 검증된 값은 아니므로 추정치로 사용한다.</p>
     */
    private BigDecimal normalizeAtrPct(BigDecimal atrPct, String timeframe) {
        long tfMinutes = toMinutes(timeframe);
        if (tfMinutes == BASELINE_TIMEFRAME_MIN) return atrPct;
        double ratio = Math.sqrt((double) tfMinutes / BASELINE_TIMEFRAME_MIN);
        return atrPct.multiply(BigDecimal.valueOf(ratio));
    }

    private static long toMinutes(String timeframe) {
        return switch (timeframe.toUpperCase()) {
            case "M1"  -> 1L;
            case "M3"  -> 3L;
            case "M5"  -> 5L;
            case "M15" -> 15L;
            case "M30" -> 30L;
            case "H1"  -> 60L;
            case "H4"  -> 240L;
            case "D1"  -> 1440L;
            default    -> 60L;
        };
    }

    private static double toDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(obj.toString()); } catch (Exception e) { return 0.0; }
    }

    private static BigDecimal toBigDecimal(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(obj.toString()); } catch (Exception e) { return null; }
    }
}
