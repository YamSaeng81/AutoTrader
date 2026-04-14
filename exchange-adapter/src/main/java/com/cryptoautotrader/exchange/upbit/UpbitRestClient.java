package com.cryptoautotrader.exchange.upbit;

import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Upbit REST API 클라이언트
 */
@Slf4j
public class UpbitRestClient {

    private static final String BASE_URL = "https://api.upbit.com/v1";
    private static final DateTimeFormatter UPBIT_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    /** Upbit 캔들 API 제한: 초당 10회 → 110ms 간격으로 호출 (여유 10%) */
    private static final long MIN_INTERVAL_MS = 110;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong lastRequestTime = new AtomicLong(0);

    public UpbitRestClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 캔들 데이터 조회
     * @param timeframeUnit minutes, days, weeks, months
     * @param unitValue 분봉일 경우 1,3,5,15,30,60,240
     */
    public List<UpbitCandleResponse> getCandles(String market, String timeframeUnit, int unitValue,
                                                  Instant to, int count) throws Exception {
        String url;
        if ("minutes".equals(timeframeUnit)) {
            url = String.format("%s/candles/%s/%d?market=%s&count=%d",
                    BASE_URL, timeframeUnit, unitValue, market, count);
        } else {
            url = String.format("%s/candles/%s?market=%s&count=%d",
                    BASE_URL, timeframeUnit, market, count);
        }

        if (to != null) {
            url += "&to=" + UPBIT_FORMAT.format(to);
        }

        throttle();
        HttpResponse<String> response = httpClient.send(buildGetRequest(url), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Upbit API 오류: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Upbit API 호출 실패: " + response.statusCode());
        }

        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /**
     * 현재가(ticker) 조회 — 인증 불필요 (공개 API)
     * @param markets 마켓 코드 목록 (예: "KRW-BTC,KRW-ETH")
     * @return ticker 응답 목록
     */
    public List<Map<String, Object>> getTicker(String markets) throws Exception {
        if (markets == null || markets.isBlank()) return Collections.emptyList();
        String encoded = URLEncoder.encode(markets, StandardCharsets.UTF_8);
        String url = BASE_URL + "/ticker?markets=" + encoded;
        throttle();
        HttpResponse<String> response = httpClient.send(buildGetRequest(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            // 상장폐지 등 없는 코인이 포함된 경우 — 개별 조회로 폴백하여 유효한 코인만 반환
            log.warn("Upbit ticker 404 (상장폐지 코인 포함 가능성): markets={} — 개별 조회로 폴백", markets);
            return getTickerIndividually(markets.split(","));
        }
        if (response.statusCode() != 200) {
            log.error("Upbit ticker API 오류: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Upbit ticker API 호출 실패: " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** 404 발생 시 마켓을 1개씩 조회하여 유효한 것만 모아 반환 (상장폐지 코인 대응) */
    private List<Map<String, Object>> getTickerIndividually(String[] marketArr) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String market : marketArr) {
            String m = market.trim();
            if (m.isEmpty()) continue;
            try {
                String encoded = URLEncoder.encode(m, StandardCharsets.UTF_8);
                throttle();
                HttpResponse<String> res = httpClient.send(
                        buildGetRequest(BASE_URL + "/ticker?markets=" + encoded),
                        HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    List<Map<String, Object>> parsed = objectMapper.readValue(res.body(), new TypeReference<>() {});
                    result.addAll(parsed);
                } else {
                    log.debug("Upbit ticker 조회 실패 (상장폐지 추정): market={}, status={}", m, res.statusCode());
                }
            } catch (Exception e) {
                log.debug("Upbit ticker 개별 조회 오류: market={}, error={}", m, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 호가창(Orderbook) 조회 — 인증 불필요 (공개 API)
     * ORDERBOOK_IMBALANCE 전략에 real-time bidVolume/askVolume 제공용
     * @param market 마켓 코드 (예: "KRW-BTC")
     * @return 호가창 응답 목록 (total_bid_size, total_ask_size 포함)
     */
    public List<Map<String, Object>> getOrderbook(String market) throws Exception {
        if (market == null || market.isBlank()) return Collections.emptyList();
        String encoded = URLEncoder.encode(market, StandardCharsets.UTF_8);
        String url = BASE_URL + "/orderbook?markets=" + encoded;
        throttle();
        HttpResponse<String> response = httpClient.send(buildGetRequest(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("Upbit orderbook API 오류: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Upbit orderbook API 호출 실패: " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /**
     * KRW 마켓 전체 목록 조회 — 인증 불필요 (공개 API)
     * @return 마켓 목록 (market, korean_name, english_name 등 포함)
     */
    public List<Map<String, Object>> getMarkets() throws Exception {
        String url = BASE_URL + "/market/all";
        throttle();
        HttpResponse<String> response = httpClient.send(buildGetRequest(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("Upbit market/all API 오류: status={}", response.statusCode());
            throw new RuntimeException("Upbit market/all API 호출 실패: " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /** HTTP GET 요청 빌드 헬퍼 */
    private HttpRequest buildGetRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    /** Upbit API Rate Limit 준수 — 연속 호출 시 최소 110ms 간격 보장 (원자적 처리) */
    private synchronized void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime.get();
        if (elapsed < MIN_INTERVAL_MS) {
            Thread.sleep(MIN_INTERVAL_MS - elapsed);
        }
        lastRequestTime.set(System.currentTimeMillis());
    }
}
