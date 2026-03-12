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
 * BacktestController 통합 테스트.
 *
 * 테스트 환경:
 * - H2 인메모리 DB (PostgreSQL 모드)
 * - Flyway 비활성화 / JPA ddl-auto: create-drop
 * - Redis MockBean
 */
@DisplayName("BacktestController 통합 테스트")
class BacktestControllerIntegrationTest extends IntegrationTestBase {

    // ── GET /api/v1/backtest/list ──────────────────────────────────────

    @Test
    @DisplayName("백테스트 목록 조회 — DB 비어있을 때 빈 배열 반환 (200)")
    void 백테스트_목록_조회_빈_결과() throws Exception {
        mockMvc.perform(get("/api/v1/backtest/list")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // ── POST /api/v1/backtest/run — 유효성 검증 ──────────────────────

    @Test
    @DisplayName("POST /api/v1/backtest/run — strategyType 누락 시 400")
    void 백테스트_실행_전략타입_누락_400() throws Exception {
        String body = """
                {
                  "coinPair": "KRW-BTC",
                  "timeframe": "M15",
                  "startDate": "2024-01-01",
                  "endDate": "2024-03-01"
                }
                """;

        mockMvc.perform(post("/api/v1/backtest/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/backtest/run — coinPair 누락 시 400")
    void 백테스트_실행_코인페어_누락_400() throws Exception {
        String body = """
                {
                  "strategyType": "VWAP",
                  "timeframe": "M15",
                  "startDate": "2024-01-01",
                  "endDate": "2024-03-01"
                }
                """;

        mockMvc.perform(post("/api/v1/backtest/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/backtest/run — timeframe 누락 시 400")
    void 백테스트_실행_타임프레임_누락_400() throws Exception {
        String body = """
                {
                  "strategyType": "VWAP",
                  "coinPair": "KRW-BTC",
                  "startDate": "2024-01-01",
                  "endDate": "2024-03-01"
                }
                """;

        mockMvc.perform(post("/api/v1/backtest/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/backtest/run — startDate 누락 시 400")
    void 백테스트_실행_시작날짜_누락_400() throws Exception {
        String body = """
                {
                  "strategyType": "VWAP",
                  "coinPair": "KRW-BTC",
                  "timeframe": "M15",
                  "endDate": "2024-03-01"
                }
                """;

        mockMvc.perform(post("/api/v1/backtest/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/backtest/run — 필수 필드 모두 있지만 데이터 부족 시 400 (서비스 예외)")
    void 백테스트_실행_데이터_부족_400() throws Exception {
        // DB에 캔들 데이터가 없으므로 서비스에서 IllegalArgumentException 발생 → 400
        String body = """
                {
                  "strategyType": "VWAP",
                  "coinPair": "KRW-BTC",
                  "timeframe": "M15",
                  "startDate": "2024-01-01",
                  "endDate": "2024-03-01"
                }
                """;

        mockMvc.perform(post("/api/v1/backtest/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("BACKTEST_001"));
    }

    @Test
    @DisplayName("POST /api/v1/backtest/run — 빈 요청 바디 시 400")
    void 백테스트_실행_빈_바디_400() throws Exception {
        mockMvc.perform(post("/api/v1/backtest/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/v1/backtest/{id} ─────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/backtest/{id} — 존재하지 않는 ID 조회 시 400 (서비스 IllegalArgumentException)")
    void 백테스트_단건_조회_없는_ID_400() throws Exception {
        // BacktestService.getBacktestResult() 는 없는 id에 IllegalArgumentException 던짐
        // GlobalExceptionHandler 가 이를 400 으로 변환
        mockMvc.perform(get("/api/v1/backtest/99999")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /api/v1/backtest/{id} — 음수 ID도 404/400 처리")
    void 백테스트_단건_조회_음수_ID() throws Exception {
        mockMvc.perform(get("/api/v1/backtest/-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── GET /api/v1/backtest/compare ─────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/backtest/compare — 존재하지 않는 ID 목록은 빈 배열 반환")
    void 백테스트_비교_없는_ID_빈_배열() throws Exception {
        mockMvc.perform(get("/api/v1/backtest/compare")
                        .param("ids", "99998", "99999")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // ── POST /api/v1/backtest/walk-forward — 유효성 검증 ─────────────

    @Test
    @DisplayName("POST /api/v1/backtest/walk-forward — strategyType 누락 시 400")
    void 워크포워드_전략타입_누락_400() throws Exception {
        String body = """
                {
                  "coinPair": "KRW-BTC",
                  "timeframe": "M15",
                  "startDate": "2024-01-01",
                  "endDate": "2024-06-01",
                  "inSampleRatio": 0.7,
                  "windowCount": 3
                }
                """;

        mockMvc.perform(post("/api/v1/backtest/walk-forward")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
