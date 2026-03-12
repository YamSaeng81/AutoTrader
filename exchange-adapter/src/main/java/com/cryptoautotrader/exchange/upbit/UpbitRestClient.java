package com.cryptoautotrader.exchange.upbit;

import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Upbit REST API 클라이언트
 */
@Slf4j
public class UpbitRestClient {

    private static final String BASE_URL = "https://api.upbit.com/v1";
    private static final DateTimeFormatter UPBIT_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

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

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Upbit API 오류: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Upbit API 호출 실패: " + response.statusCode());
        }

        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }
}
