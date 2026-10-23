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

/**
 * Upbit REST API 클라이언트
 */
@Slf4j
public class UpbitRestClient {

    /**
     * 공개 시세 API 주소 — 기본 업비트. 시스템 속성 {@code upbit.market-data.base-url} 로만 바꾼다(실제 프로세스 재시작 시험이 거래소
     * 대역의 시세·호가·캔들 응답을 쓰기 위함, 2026-10-15). 🔴 주문 주소와 같이 시험 전용 가드를 거친다 — 시험 빌드·프로필이 아니면 이
     * 속성이 설정된 것만으로 예외(기동 첫 단계 {@code TestHooks.verifyAtStartup} 과 이 사용 지점 양쪽, 2026-10-16 리뷰 지적).
     */
    private static final String DEFAULT_BASE_URL = "https://api.upbit.com/v1";
    private final String BASE_URL = java.util.Objects.requireNonNullElse(
            com.cryptoautotrader.exchange.TestHooks.require("upbit.market-data.base-url"), DEFAULT_BASE_URL);
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

        throttle();
        HttpResponse<String> response = httpClient.send(buildGetRequest(url), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            // 2026-09-15: url 을 함께 찍는다. 이전에는 status/body 만 남겨서 운영 로그에
            // `status=404, body={"error":{"name":404,"message":"Code not found"}}` 만 보였고
            // **어느 코인이 문제인지 알 수 없었다**(상장폐지 코드인지 오타인지 구분 불가).
            // 캔들 URL 은 market=KRW-XXX 를 쿼리로 갖고 인증은 헤더로 나가므로 로그에 안전하다.
            log.error("Upbit API 오류: status={}, url={}, body={}",
                    response.statusCode(), url, response.body());
            throw new RuntimeException("Upbit API 호출 실패: " + response.statusCode() + " (" + url + ")");
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

    /**
     * HTTP GET 요청 빌드 헬퍼.
     *
     * <p><b>요청 타임아웃 필수</b>(2026-08-25) — {@link HttpClient#connectTimeout}은 TCP 연결
     * 수립까지만 보장하고, 연결된 뒤 응답이 안 오면(서버/네트워크 정체) 영원히 블로킹된다.
     * DynamicTradingService.tick()이 이 경로(emergencyStop → closeOpenPositions)에서 멈춰
     * 29시간 넘게 전체 동적 세션이 무응답 상태였던 사고의 원인 — {@code fixedDelay} 스케줄은
     * 현재 실행이 끝나야 다음이 잡히므로, 이 호출 하나가 안 끝나면 스케줄 자체가 죽는다.</p>
     */
    private HttpRequest buildGetRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
    }

    /**
     * 업비트 요청 한도 준수 — 프로세스 전체 공용 예산({@link UpbitRequestLimiter#QUOTATION})에서 요청 시각을 원자적으로 예약한다.
     *
     * <p>🔴 2026-10-23 (리뷰): 종전에는 마지막 요청 시각을 {@code static} 으로 공유하면서 {@code synchronized} 는 <b>인스턴스 잠금</b>이라,
     * 서로 다른 인스턴스가 같은 시각을 동시에 읽고 함께 요청할 수 있었다(설명 주석의 "원자적 처리" 는 인스턴스 하나 안에서만 맞았다).
     * 이제 모든 인스턴스가 한 예산을 쓴다. 기본 간격 125ms(종전 110ms).</p>
     */
    // 시험에서 여러 인스턴스를 동시에 돌려 예산 공유를 확인할 수 있게 package-private
    void throttle() throws InterruptedException {
        UpbitRequestLimiter.QUOTATION.acquire();
    }
}
