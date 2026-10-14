package com.cryptoautotrader.api.service;

import com.cryptoautotrader.exchange.upbit.UpbitApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 제출 실패 분류 표 (2026-10-14) — <b>명시적 거절로 볼 수 있는 응답을 목록으로 고정</b>한다.
 *
 * <p>명시적 거절의 핵심 근거는 응답의 오류 의미다: HTTP 4xx + JSON 본문 {@code {"error":{"name":<아래 목록>}}}.
 * 식별자 조회 404 는 보조 확인이다(엔진 {@code resolveExplicitRejection}). 목록을 바꾸면 이 시험이 깨진다 — 의도한
 * 변경인지 사람이 확인하라는 뜻이다.</p>
 */
class SubmitRejectionClassificationTest {

    static final Set<String> FIXED = Set.of(
            "insufficient_funds_bid", "insufficient_funds_ask",
            "under_min_total_bid", "under_min_total_ask",
            "invalid_volume_bid", "invalid_volume_ask", "invalid_price_bid", "invalid_price_ask",
            "validation_error", "invalid_parameter", "invalid_query_payload",
            "jwt_verification", "expired_access_key", "invalid_access_key", "nonce_used",
            "no_authorization_i_p", "no_authorization_ip", "out_of_scope");

    private static OrderExecutionEngine.SubmitRejection classify(int status, String body) {
        return OrderExecutionEngine.SubmitRejection.classify(
                new RuntimeException("주문 생성 실패", new UpbitApiException("주문 생성", status, body)));
    }

    private static String err(String name) {
        return "{\"error\":{\"name\":\"" + name + "\",\"message\":\"m\"}}";
    }

    @Test
    void 명시적_거절_목록은_고정돼_있다() {
        for (String name : FIXED) {
            assertThat(classify(400, err(name)).explicit()).as(name).isTrue();
        }
        assertThat(classify(401, err("jwt_verification")).explicit()).isTrue();
    }

    @ParameterizedTest(name = "HTTP {0} {1}")
    @CsvSource(delimiter = '|', value = {
            "400|{\"error\":{\"name\":\"create_bid_error\"}}",         // 생성 도중 서버 오류 — 생성됐을 수 있다
            "400|{\"error\":{\"name\":\"create_ask_error\"}}",
            "400|{\"error\":{\"name\":\"some_new_error\"}}",           // 모르는 이름
            "400|{\"error\":{}}",                                      // 이름 없음
            "400|Bad Request",                                         // JSON 아님
            "429|{\"error\":{\"name\":\"too_many_requests\"}}",        // 목록 밖
            "500|{\"error\":{\"name\":\"insufficient_funds_bid\"}}",   // 이름이 있어도 5xx 는 아니다
            "503|Service Unavailable"})
    void 목록_밖이거나_4xx_가_아니면_명시적_거절이_아니다(int status, String body) {
        assertThat(classify(status, body).explicit()).isFalse();
    }

    @Test
    void 업비트_예외가_아닌_실패는_명시적_거절이_아니다() {
        assertThat(OrderExecutionEngine.SubmitRejection.classify(new java.net.http.HttpTimeoutException("timed out"))
                .explicit()).isFalse();
        assertThat(OrderExecutionEngine.SubmitRejection.classify(new RuntimeException("connection reset")).explicit()).isFalse();
    }
}
