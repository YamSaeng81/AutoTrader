package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DataController 통합 테스트.
 *
 * 테스트 범위:
 * - GET /api/v1/data/status  — 수집 현황 조회
 * - GET /api/v1/data/summary — 요약 조회
 * - POST /api/v1/data/collect — 수집 요청 파라미터 검증
 *
 * 주의: GET /api/v1/data/coins 는 UpbitRestClient를 직접 생성하여 외부 HTTP 호출을 수행하므로
 *       통합 테스트 범위에서 제외한다.
 */
@DisplayName("DataController 통합 테스트")
class DataControllerIntegrationTest extends IntegrationTestBase {

    // ── GET /api/v1/data/status ───────────────────────────────────────

    @Test
    @DisplayName("수집 현황 조회 — 200 OK, 필수 필드 포함")
    void 수집_현황_조회_200() throws Exception {
        mockMvc.perform(get("/api/v1/data/status")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalCandles").exists())
                .andExpect(jsonPath("$.data.pairCount").exists())
                .andExpect(jsonPath("$.data.status").exists());
    }

    @Test
    @DisplayName("수집 현황 조회 — DB 비어있을 때 EMPTY 상태 반환")
    void 수집_현황_DB_비어있을때_EMPTY() throws Exception {
        mockMvc.perform(get("/api/v1/data/status")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EMPTY"))
                .andExpect(jsonPath("$.data.totalCandles").value(0))
                .andExpect(jsonPath("$.data.pairCount").value(0));
    }

    // ── GET /api/v1/data/summary ──────────────────────────────────────

    @Test
    @DisplayName("데이터 요약 조회 — 200 OK, 빈 배열 반환")
    void 데이터_요약_조회_빈_배열() throws Exception {
        mockMvc.perform(get("/api/v1/data/summary")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // ── POST /api/v1/data/collect — 유효성 검증 ─────────────────────

    @Test
    @DisplayName("POST /api/v1/data/collect — 모든 필수 필드 정상 요청 시 202 Accepted")
    void 캔들_수집_요청_202() throws Exception {
        // @Async("taskExecutor") 로 비동기 실행되므로 HTTP 응답은 즉시 202 로 반환된다.
        // 백그라운드 수집 스레드는 Upbit API 를 호출하지만 테스트 검증 대상은 HTTP 응답뿐이다.
        // (실제 수집 성공 여부는 DataCollectionService 단위 테스트에서 검증)
        String body = """
                {
                  "coinPair": "KRW-BTC",
                  "timeframe": "M15",
                  "startDate": "2024-01-01",
                  "endDate": "2024-01-31"
                }
                """;

        mockMvc.perform(post("/api/v1/data/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("STARTED"))
                .andExpect(jsonPath("$.data.coinPair").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.timeframe").value("M15"));
    }

    @Test
    @DisplayName("POST /api/v1/data/collect — coinPair 누락 시 400")
    void 캔들_수집_코인페어_누락_400() throws Exception {
        String body = """
                {
                  "timeframe": "M15",
                  "startDate": "2024-01-01",
                  "endDate": "2024-01-31"
                }
                """;

        mockMvc.perform(post("/api/v1/data/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/data/collect — timeframe 누락 시 400")
    void 캔들_수집_타임프레임_누락_400() throws Exception {
        String body = """
                {
                  "coinPair": "KRW-BTC",
                  "startDate": "2024-01-01",
                  "endDate": "2024-01-31"
                }
                """;

        mockMvc.perform(post("/api/v1/data/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/data/collect — startDate 누락 시 400")
    void 캔들_수집_시작날짜_누락_400() throws Exception {
        String body = """
                {
                  "coinPair": "KRW-BTC",
                  "timeframe": "M15",
                  "endDate": "2024-01-31"
                }
                """;

        mockMvc.perform(post("/api/v1/data/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/data/collect — endDate 누락 시 400")
    void 캔들_수집_종료날짜_누락_400() throws Exception {
        String body = """
                {
                  "coinPair": "KRW-BTC",
                  "timeframe": "M15",
                  "startDate": "2024-01-01"
                }
                """;

        mockMvc.perform(post("/api/v1/data/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/v1/data/candles ──────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/data/candles — DB 비어있을 때 빈 배열 반환")
    void 캔들_조회_DB_비어있을때_빈_배열() throws Exception {
        mockMvc.perform(get("/api/v1/data/candles")
                        .param("coinPair", "KRW-BTC")
                        .param("timeframe", "M15")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/data/candles — coinPair 파라미터 없으면 400")
    void 캔들_조회_코인페어_없으면_400() throws Exception {
        mockMvc.perform(get("/api/v1/data/candles")
                        .param("timeframe", "M15")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
