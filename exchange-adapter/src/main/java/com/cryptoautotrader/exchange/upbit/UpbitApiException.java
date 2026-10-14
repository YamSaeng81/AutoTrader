package com.cryptoautotrader.exchange.upbit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 업비트가 2xx 가 아닌 응답을 돌려줬다 (2026-10-14) — 상태 코드와 응답의 오류 의미({@code error.name})를 함께 담는다.
 *
 * <p>🔴 이것은 "응답을 받았다" 는 뜻이지 "주문이 만들어지지 않았다" 는 뜻이 아니다. 거절로 확정할지는 호출자가 오류
 * 의미와 기존 주문 존재 여부로 판단한다(엔진의 {@code SubmitRejection}).</p>
 */
public class UpbitApiException extends RuntimeException {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int statusCode;
    private final String errorName;
    private final String errorMessage;
    private final String body;

    public UpbitApiException(String operation, int statusCode, String body) {
        super(String.format("Upbit API %s 실패: HTTP %d - %s", operation, statusCode, body));
        this.statusCode = statusCode;
        this.body = body;
        String name = null;
        String msg = null;
        try {
            JsonNode err = MAPPER.readTree(body == null ? "" : body).path("error");
            if (err.hasNonNull("name")) name = err.get("name").asText();
            if (err.hasNonNull("message")) msg = err.get("message").asText();
        } catch (Exception ignored) {
            // 본문이 JSON 이 아니다 — 오류 이름 없음
        }
        this.errorName = name;
        this.errorMessage = msg;
    }

    public int getStatusCode() { return statusCode; }
    public String getErrorName() { return errorName; }
    public String getErrorMessage() { return errorMessage; }
    public String getBody() { return body; }
}
