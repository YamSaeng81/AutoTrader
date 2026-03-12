package com.cryptoautotrader.exchange.upbit;

import com.cryptoautotrader.exchange.adapter.ExchangeAdapter;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import com.cryptoautotrader.strategy.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Upbit 과거 캔들 데이터 수집기
 * Rate Limit: 초당 10회 제한 준수
 */
@Slf4j
@RequiredArgsConstructor
public class UpbitCandleCollector implements ExchangeAdapter {

    private static final int MAX_CANDLES_PER_REQUEST = 200;
    private static final long RATE_LIMIT_DELAY_MS = 110; // 초당 10회 → 100ms + 여유

    private final UpbitRestClient restClient;

    @Override
    public String getExchangeName() {
        return "UPBIT";
    }

    @Override
    public List<Candle> fetchCandles(String coinPair, String timeframe, Instant from, Instant to) {
        String unit = resolveTimeframeUnit(timeframe);
        int unitValue = resolveUnitValue(timeframe);

        List<Candle> allCandles = new ArrayList<>();
        Instant cursor = to;

        try {
            while (cursor.isAfter(from)) {
                List<UpbitCandleResponse> responses = restClient.getCandles(
                        coinPair, unit, unitValue, cursor, MAX_CANDLES_PER_REQUEST);

                if (responses.isEmpty()) break;

                for (UpbitCandleResponse r : responses) {
                    Instant candleTime = parseCandleTime(r.getCandleDateTimeUtc());
                    if (candleTime.isBefore(from)) continue;

                    allCandles.add(Candle.builder()
                            .time(candleTime)
                            .open(r.getOpeningPrice())
                            .high(r.getHighPrice())
                            .low(r.getLowPrice())
                            .close(r.getTradePrice())
                            .volume(r.getCandleAccTradeVolume())
                            .build());
                }

                // 마지막 캔들의 시간을 다음 커서로
                UpbitCandleResponse lastCandle = responses.get(responses.size() - 1);
                Instant lastTime = parseCandleTime(lastCandle.getCandleDateTimeUtc());
                if (!lastTime.isBefore(cursor)) break; // 무한 루프 방지
                cursor = lastTime;

                Thread.sleep(RATE_LIMIT_DELAY_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("캔들 수집 중단됨");
        } catch (Exception e) {
            log.error("캔들 수집 실패: {}", e.getMessage(), e);
            throw new RuntimeException("캔들 데이터 수집 실패", e);
        }

        Collections.sort(allCandles, (a, b) -> a.getTime().compareTo(b.getTime()));
        log.info("캔들 수집 완료: {} {} {} 건", coinPair, timeframe, allCandles.size());
        return allCandles;
    }

    @Override
    public List<String> getSupportedCoins() {
        return List.of("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-DOGE",
                "KRW-ADA", "KRW-AVAX", "KRW-DOT", "KRW-MATIC", "KRW-LINK");
    }

    private String resolveTimeframeUnit(String timeframe) {
        return switch (timeframe.toUpperCase()) {
            case "M1", "M5", "M15", "M30", "H1", "H4" -> "minutes";
            case "D1" -> "days";
            default -> throw new IllegalArgumentException("지원하지 않는 타임프레임: " + timeframe);
        };
    }

    private int resolveUnitValue(String timeframe) {
        return switch (timeframe.toUpperCase()) {
            case "M1" -> 1;
            case "M5" -> 5;
            case "M15" -> 15;
            case "M30" -> 30;
            case "H1" -> 60;
            case "H4" -> 240;
            case "D1" -> 1;
            default -> throw new IllegalArgumentException("지원하지 않는 타임프레임: " + timeframe);
        };
    }

    private Instant parseCandleTime(String utcDateTimeStr) {
        return LocalDateTime.parse(utcDateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC);
    }
}
