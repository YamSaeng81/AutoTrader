package com.cryptoautotrader.exchange.upbit;

import com.cryptoautotrader.exchange.upbit.dto.AccountResponse;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * Upbit 주문 REST API 클라이언트
 *
 * JWT 인증 기반으로 주문 생성/조회/취소 및 계좌 조회를 수행한다.
 * - access_key + secret_key를 사용한 HMAC-SHA256 JWT 서명
 * - 쿼리 파라미터가 있는 경우 SHA-512 query_hash 포함
 * - API Key는 char[] 형태로 관리하며 사용 후 즉시 제거
 */
@Slf4j
public class UpbitOrderClient {

    /** 주문 생성 결과 — 파싱된 응답 + 원본 바디 */
    public record ExchangeResult(OrderResponse response, String rawBody) {}

    /**
     * 주문 API 주소. 🔴 기본값은 업비트다 — 바꾸는 방법은 JVM 시스템 속성 {@code upbit.api.base-url} 하나뿐이고,
     * 실제 프로세스 재시작 시험(별도 프로세스 거래소 대역)에서만 쓴다 (2026-10-13). 🔴 시험 빌드가 아닌데 이 속성이
     * 있으면 이 클래스의 초기화가 실패해 <b>기동이 거부된다</b>({@link com.cryptoautotrader.exchange.TestHooks}, 2026-10-14).
     */
    private static final String DEFAULT_BASE_URL = "https://api.upbit.com/v1";
    private static final String BASE_URL;

    static {
        String override = com.cryptoautotrader.exchange.TestHooks.require("upbit.api.base-url");
        BASE_URL = override != null ? override : DEFAULT_BASE_URL;
    }

    static {
        if (!DEFAULT_BASE_URL.equals(BASE_URL)) {
            org.slf4j.LoggerFactory.getLogger(UpbitOrderClient.class)
                    .warn("🔴 업비트 주문 API 주소가 바뀌어 있다: {} — 시험 전용 설정이다", BASE_URL);
        }
    }

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // API Key를 char[]로 보관 (보안)
    private final char[] accessKey;
    private final char[] secretKey;

    /**
     * @param accessKey Upbit API access key
     * @param secretKey Upbit API secret key
     */
    public UpbitOrderClient(char[] accessKey, char[] secretKey) {
        this.accessKey = Arrays.copyOf(accessKey, accessKey.length);
        this.secretKey = Arrays.copyOf(secretKey, secretKey.length);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 주문 생성
     *
     * @param market    마켓 코드 (예: "KRW-BTC")
     * @param side      "bid"(매수) 또는 "ask"(매도)
     * @param volume    주문량 (시장가 매수 시 null)
     * @param price     주문 가격 (시장가 매수 시 총액, 시장가 매도 시 null)
     * @param orderType "limit"(지정가), "price"(시장가 매수), "market"(시장가 매도)
     * @return 주문 응답
     */
    public ExchangeResult createOrder(String market, String side, BigDecimal volume,
                                      BigDecimal price, String orderType) {
        return createOrder(market, side, volume, price, orderType, null);
    }

    /**
     * 주문 생성 — <b>클라이언트 식별자를 함께 보낸다</b> (2026-10-12).
     *
     * <p>🔴 <b>{@code identifier} 는 멱등 키가 아니다.</b> 업비트 명세는 최대 64자, 계정 전체 주문에서
     * 고유해야 하며 <b>사용한 값은 재사용할 수 없다</b>고만 정한다. 같은 값으로 재요청하면 기존 성공
     * 응답을 돌려준다는 보장은 <b>없다</b> — 중복 제출을 막는 근거는 이 값이 아니라 claim 규칙이다.</p>
     *
     * <p>이 값이 하는 일은 하나다: <b>응답을 잃어도 그 주문을 조회할 단서가 남는다.</b> UUID 는 응답에만
     * 오므로 응답이 유실되면 추적 단서가 사라진다 — 식별자는 제출 <b>전에</b> 우리가 정하므로 남는다.</p>
     *
     * <p>🔴 보존 기간은 명세에 없다. 식별자로 조회되지 않는 것이 "주문이 없다" 는 뜻은 아니므로,
     * 조회 실패를 미접수로 해석하지 않는다.</p>
     */
    public ExchangeResult createOrder(String market, String side, BigDecimal volume,
                                      BigDecimal price, String orderType, String identifier) {
        log.info("주문 생성 요청: market={}, side={}, volume={}, price={}, type={}, identifier={}",
                market, side, volume, price, orderType, identifier);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market);
        params.put("side", side);
        params.put("ord_type", orderType);
        if (identifier != null && !identifier.isBlank()) {
            params.put("identifier", identifier);
        }
        if (volume != null) {
            params.put("volume", volume.toPlainString());
        }
        if (price != null) {
            params.put("price", price.toPlainString());
        }

        String rawBody = null;
        try {
            String queryString = buildQueryString(params);
            String token = generateJwtWithQuery(queryString);
            String requestBody = objectMapper.writeValueAsString(params);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/orders"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            rawBody = response.body();
            checkResponse(response, "주문 생성");

            OrderResponse result = objectMapper.readValue(rawBody, OrderResponse.class);
            log.info("주문 생성 성공: uuid={}, market={}, side={}", result.getUuid(), market, side);
            return new ExchangeResult(result, rawBody);
        } catch (Exception e) {
            log.error("주문 생성 실패: market={}, side={}, error={}", market, side, e.getMessage(), e);
            Throwable cause = e.getCause();
            String detail = (cause != null && cause.getMessage() != null) ? cause.getMessage() : e.getMessage();
            throw new RuntimeException("주문 생성 실패: " + detail + (rawBody != null ? " | body=" + rawBody : ""), e);
        }
    }

    /**
     * 주문 조회 (단건)
     *
     * @param uuid 주문 UUID
     * @return 주문 상태 정보
     */
    public OrderResponse getOrder(String uuid) {
        log.debug("주문 조회: uuid={}", uuid);

        Map<String, String> params = Map.of("uuid", uuid);
        String queryString = buildQueryString(params);

        try {
            String token = generateJwtWithQuery(queryString);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/order?" + queryString))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response, "주문 조회");

            return objectMapper.readValue(response.body(), OrderResponse.class);
        } catch (Exception e) {
            log.error("주문 조회 실패: uuid={}, error={}", uuid, e.getMessage(), e);
            throw new RuntimeException("주문 조회 실패", e);
        }
    }

    /**
     * 주문 조회 — <b>클라이언트 식별자로</b> (2026-10-12).
     *
     * <p>응답을 잃은 주문의 유일한 추적 경로다. UUID 가 없으므로 식별자로 묻는다.</p>
     *
     * <p>🔴 <b>조회되지 않음을 "접수되지 않았다" 로 읽지 않는다.</b> 업비트 명세에 식별자 보존 기간이
     * 없고, 조회 실패는 통신 장애일 수도 있다. 그래서 이 메서드는 <b>찾지 못한 것과 묻지 못한 것을
     * 구분한다</b> — 찾지 못하면 {@code null}, 묻지 못하면 예외다.</p>
     *
     * @return 주문 정보, 또는 거래소가 <b>명시적으로 없다고 답한</b> 경우 {@code null}
     */
    public OrderResponse getOrderByIdentifier(String identifier) {
        log.debug("주문 조회(식별자): identifier={}", identifier);

        Map<String, String> params = Map.of("identifier", identifier);
        String queryString = buildQueryString(params);

        try {
            String token = generateJwtWithQuery(queryString);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/order?" + queryString))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // 🔴 404 는 "그 식별자의 주문이 없다" 는 **거래소의 답**이다. 통신 실패와 구분한다.
            if (response.statusCode() == 404) {
                log.warn("식별자로 주문을 찾지 못했다 — 미접수로 단정하지 않는다 (identifier={})", identifier);
                return null;
            }
            checkResponse(response, "주문 조회(식별자)");

            return objectMapper.readValue(response.body(), OrderResponse.class);
        } catch (Exception e) {
            log.error("주문 조회(식별자) 실패: identifier={}, error={}", identifier, e.getMessage(), e);
            throw new RuntimeException("주문 조회(식별자) 실패: " + identifier, e);
        }
    }

    /**
     * 대기 중인 주문 목록 조회
     *
     * @param market 마켓 코드 (예: "KRW-BTC")
     * @return 대기 주문 목록
     */
    public List<OrderResponse> getOpenOrders(String market) {
        log.debug("대기 주문 목록 조회: market={}", market);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market);
        params.put("state", "wait");

        String queryString = buildQueryString(params);

        try {
            String token = generateJwtWithQuery(queryString);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/orders?" + queryString))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response, "대기 주문 조회");

            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (Exception e) {
            log.error("대기 주문 조회 실패: market={}, error={}", market, e.getMessage(), e);
            throw new RuntimeException("대기 주문 조회 실패", e);
        }
    }

    /**
     * 주문 취소
     *
     * @param uuid 취소할 주문 UUID
     * @return 취소된 주문 정보
     */
    public OrderResponse cancelOrder(String uuid) {
        log.info("주문 취소 요청: uuid={}", uuid);

        Map<String, String> params = Map.of("uuid", uuid);
        String queryString = buildQueryString(params);

        try {
            String token = generateJwtWithQuery(queryString);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/order?" + queryString))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(15))
                    .method("DELETE", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response, "주문 취소");

            OrderResponse result = objectMapper.readValue(response.body(), OrderResponse.class);
            log.info("주문 취소 성공: uuid={}", uuid);
            return result;
        } catch (Exception e) {
            log.error("주문 취소 실패: uuid={}, error={}", uuid, e.getMessage(), e);
            throw new RuntimeException("주문 취소 실패", e);
        }
    }

    /**
     * 주문 취소 — <b>클라이언트 식별자로</b> (2026-10-13).
     *
     * <p>응답을 잃은 주문({@code SUBMITTING}·{@code UNKNOWN})은 UUID 가 없어 UUID 취소를 쓸 수 없다.
     * 업비트 주문 취소는 {@code uuid} 대신 {@code identifier} 를 받는다.</p>
     *
     * <p>🔴 <b>취소 요청의 성공은 취소 확정이 아니다.</b> 응답은 요청 시점의 주문 상태이고, 그사이
     * 체결이 있었을 수 있다. 호출자는 이후 조회로 상태와 체결량을 확인해야 한다.</p>
     *
     * <p>🔴 {@code getOrderByIdentifier} 와 같이 <b>찾지 못한 것과 묻지 못한 것을 구분한다</b> —
     * 404 는 {@code null}, 그 밖의 실패는 예외다. 404 를 "접수되지 않았다" 로 읽지 않는다.</p>
     *
     * @return 취소 요청 응답, 또는 거래소가 <b>명시적으로 없다고 답한</b> 경우 {@code null}
     */
    public OrderResponse cancelOrderByIdentifier(String identifier) {
        log.info("주문 취소 요청(식별자): identifier={}", identifier);

        Map<String, String> params = Map.of("identifier", identifier);
        String queryString = buildQueryString(params);

        try {
            String token = generateJwtWithQuery(queryString);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/order?" + queryString))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(15))
                    .method("DELETE", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                log.warn("식별자로 취소할 주문을 찾지 못했다 — 미접수로 단정하지 않는다 (identifier={})", identifier);
                return null;
            }
            checkResponse(response, "주문 취소(식별자)");

            OrderResponse result = objectMapper.readValue(response.body(), OrderResponse.class);
            log.info("주문 취소 요청 접수(식별자): identifier={}, uuid={}", identifier, result.getUuid());
            return result;
        } catch (Exception e) {
            log.error("주문 취소(식별자) 실패: identifier={}, error={}", identifier, e.getMessage(), e);
            throw new RuntimeException("주문 취소(식별자) 실패: " + identifier, e);
        }
    }

    /**
     * 전체 계좌 잔고 조회
     *
     * @return 보유 자산 목록
     */
    public List<AccountResponse> getAccounts() {
        log.debug("계좌 잔고 조회");

        try {
            String token = generateJwtWithoutQuery();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/accounts"))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response, "계좌 조회");

            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (Exception e) {
            log.error("계좌 조회 실패: {}", e.getMessage(), e);
            throw new RuntimeException("계좌 조회 실패", e);
        }
    }

    /**
     * 주문 가능 정보 조회 (GET /v1/orders/chance)
     * - 수수료율, 최소 주문 금액, 마켓별 잔고 확인
     */
    public Map<String, Object> getOrderChance(String market) throws Exception {
        Map<String, String> params = Map.of("market", market);
        String queryString = buildQueryString(params);
        String token = generateJwtWithQuery(queryString);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/orders/chance?" + queryString))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "주문 가능 정보");
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /**
     * 주문 생성 테스트 (POST /v1/orders/test) — 실거래 없이 주문 형식/권한 검증
     */
    public OrderResponse createTestOrder(String market, String side, BigDecimal volume,
                                         BigDecimal price, String orderType) throws Exception {
        log.info("주문 생성 테스트: market={}, side={}, volume={}, price={}, type={}",
                market, side, volume, price, orderType);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market);
        params.put("side", side);
        params.put("ord_type", orderType);
        if (volume != null) params.put("volume", volume.toPlainString());
        if (price != null) params.put("price", price.toPlainString());

        String queryString = buildQueryString(params);
        String token = generateJwtWithQuery(queryString);
        String requestBody = objectMapper.writeValueAsString(params);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/orders/test"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "주문 생성 테스트");
        return objectMapper.readValue(response.body(), OrderResponse.class);
    }

    /**
     * 최근 주문 이력 조회 (GET /v1/orders) — 거래소에서 직접 조회
     * @param market 마켓 코드
     * @param state  주문 상태: "done" | "cancel" | "wait"
     * @param limit  조회 건수 (최대 100)
     */
    public List<Map<String, Object>> getRecentOrders(String market, String state, int limit) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market);
        params.put("state", state);
        params.put("limit", String.valueOf(limit));
        String queryString = buildQueryString(params);
        String token = generateJwtWithQuery(queryString);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/orders?" + queryString))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response, "주문 이력 조회");
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    /**
     * 리소스 정리 - API Key 메모리에서 제거
     */
    public void destroy() {
        Arrays.fill(accessKey, '\0');
        Arrays.fill(secretKey, '\0');
        log.info("API Key 메모리 정리 완료");
    }

    // ========== JWT 생성 ==========

    /**
     * 쿼리 파라미터가 있는 요청용 JWT 생성
     * query_hash (SHA-512)를 포함한다.
     */
    private String generateJwtWithQuery(String queryString) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update(queryString.getBytes(StandardCharsets.UTF_8));
            String queryHash = bytesToHex(md.digest());

            SecretKeySpec keySpec = buildSecretKeySpec();

            return Jwts.builder()
                    .claim("access_key", new String(accessKey))
                    .claim("nonce", UUID.randomUUID().toString())
                    .claim("query_hash", queryHash)
                    .claim("query_hash_alg", "SHA512")
                    .signWith(keySpec)
                    .compact();
        } catch (Exception e) {
            throw new RuntimeException("JWT 생성 실패", e);
        }
    }

    /**
     * 쿼리 파라미터가 없는 요청용 JWT 생성 (계좌 조회 등)
     */
    private String generateJwtWithoutQuery() {
        try {
            SecretKeySpec keySpec = buildSecretKeySpec();

            return Jwts.builder()
                    .claim("access_key", new String(accessKey))
                    .claim("nonce", UUID.randomUUID().toString())
                    .signWith(keySpec)
                    .compact();
        } catch (Exception e) {
            throw new RuntimeException("JWT 생성 실패", e);
        }
    }

    /**
     * char[] secretKey를 CharBuffer/ByteBuffer로 변환하여 SecretKeySpec 생성.
     * new String(secretKey) 방식 대비 평문 시크릿이 String pool에 잔류하는 위험을 줄인다.
     */
    private SecretKeySpec buildSecretKeySpec() {
        ByteBuffer keyBuf = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secretKey));
        byte[] keyBytes = new byte[keyBuf.remaining()];
        keyBuf.get(keyBytes);
        SecretKeySpec spec = new SecretKeySpec(keyBytes, "HmacSHA256");
        Arrays.fill(keyBytes, (byte) 0);
        return spec;
    }

    // ========== 유틸 ==========

    /**
     * 쿼리 파라미터 맵을 URL 인코딩된 쿼리 스트링으로 변환
     */
    private String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append("=")
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * 바이트 배열을 16진수 문자열로 변환
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * HTTP 응답 상태 코드 확인
     */
    private void checkResponse(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("Upbit API {} 실패: status={}, body={}",
                    operation, response.statusCode(), response.body());
            throw new RuntimeException(String.format(
                    "Upbit API %s 실패: HTTP %d - %s",
                    operation, response.statusCode(), response.body()));
        }
    }
}
