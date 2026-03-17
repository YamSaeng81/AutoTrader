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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        throttle();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        throttle();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            // 존재하지 않는 마켓 코드가 포함된 경우 빈 목록 반환 (계좌 페이지 오류 방지)
            log.warn("Upbit ticker 마켓 코드 없음 (404): markets={}", markets);
            return Collections.emptyList();
        }
        if (response.statusCode() != 200) {
            log.error("Upbit ticker API 오류: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Upbit ticker API 호출 실패: " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
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
