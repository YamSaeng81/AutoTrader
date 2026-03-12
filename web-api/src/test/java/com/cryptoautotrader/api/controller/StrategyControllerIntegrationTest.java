package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * StrategyController 통합 테스트.
 *
 * 테스트 범위:
 * - 전략 목록 조회 (StrategyRegistry 기반 — DB 불필요)
 * - 전략 설정 CRUD (H2 인메모리 DB 사용)
 * - 활성/비활성 토글
 */
@DisplayName("StrategyController 통합 테스트")
class StrategyControllerIntegrationTest extends IntegrationTestBase {

    // ── GET /api/v1/strategies ────────────────────────────────────────

    @Test
    @DisplayName("전략 목록 조회 — 200 OK, 10개 이상 반환")
    void 전략_목록_조회_10개_이상() throws Exception {
        mockMvc.perform(get("/api/v1/strategies")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(10)));
    }

    @Test
    @DisplayName("전략 목록 — 각 항목에 name, minimumCandleCount, status, description 필드 포함")
    void 전략_목록_필드_구조_확인() throws Exception {
        mockMvc.perform(get("/api/v1/strategies")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").exists())
                .andExpect(jsonPath("$.data[0].minimumCandleCount").exists())
                .andExpect(jsonPath("$.data[0].status").exists())
                .andExpect(jsonPath("$.data[0].description").exists());
    }

    @Test
    @DisplayName("전략 목록 — VWAP 전략이 AVAILABLE 상태로 포함")
    void 전략_목록_VWAP_포함() throws Exception {
        mockMvc.perform(get("/api/v1/strategies")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name == 'VWAP')].status", hasItem("AVAILABLE")));
    }

    @Test
    @DisplayName("전략 목록 — EMA_CROSS 전략이 포함")
    void 전략_목록_EMA_CROSS_포함() throws Exception {
        mockMvc.perform(get("/api/v1/strategies")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name == 'EMA_CROSS')]").exists());
    }

    // ── GET /api/v1/strategies/{name} ────────────────────────────────

    @Test
    @DisplayName("전략 단건 조회 — VWAP 조회 시 200 OK")
    void 전략_단건_조회_VWAP_성공() throws Exception {
        mockMvc.perform(get("/api/v1/strategies/VWAP")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("VWAP"))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("전략 단건 조회 — 존재하지 않는 전략명은 success=false 반환")
    void 전략_단건_조회_없는_전략() throws Exception {
        mockMvc.perform(get("/api/v1/strategies/NONEXISTENT_STRATEGY")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())  // 컨트롤러가 200으로 반환하며 success=false
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ── POST /api/v1/strategies ───────────────────────────────────────

    @Test
    @DisplayName("전략 설정 생성 — 최소 필드만으로 200 OK 반환 및 DB 저장")
    void 전략_설정_생성_최소_필드() throws Exception {
        String body = """
                {
                  "name": "테스트_VWAP_설정",
                  "strategyType": "VWAP",
                  "coinPair": "KRW-BTC",
                  "timeframe": "M15"
                }
                """;

        mockMvc.perform(post("/api/v1/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").value("테스트_VWAP_설정"))
                .andExpect(jsonPath("$.data.strategyType").value("VWAP"))
                .andExpect(jsonPath("$.data.isActive").value(true));
    }

    @Test
    @DisplayName("전략 설정 생성 — 모든 선택 필드 포함 시 정상 저장")
    void 전략_설정_생성_모든_필드() throws Exception {
        String body = """
                {
                  "name": "VWAP_풀설정",
                  "strategyType": "VWAP",
                  "coinPair": "KRW-ETH",
                  "timeframe": "H1",
                  "maxInvestment": 1000000,
                  "stopLossPct": 2.5,
                  "reinvestPct": 50.0,
                  "configJson": {"period": 20, "thresholdPct": 1.5}
                }
                """;

        mockMvc.perform(post("/api/v1/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.coinPair").value("KRW-ETH"))
                .andExpect(jsonPath("$.data.timeframe").value("H1"))
                .andExpect(jsonPath("$.data.maxInvestment").value(1000000));
    }

    @Test
    @DisplayName("전략 설정 생성 후 목록 조회 — 생성한 설정이 시스템 전략 목록과 별개임을 확인")
    void 전략_설정_생성_후_시스템_전략_목록은_독립적() throws Exception {
        // POST 로 설정 저장
        String body = """
                {
                  "name": "독립성_테스트_설정",
                  "strategyType": "EMA_CROSS",
                  "coinPair": "KRW-BTC",
                  "timeframe": "M30"
                }
                """;
        mockMvc.perform(post("/api/v1/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // GET /api/v1/strategies 는 StrategyRegistry 기반이므로 저장한 설정 수와 무관하게 고정 개수
        mockMvc.perform(get("/api/v1/strategies")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(10)));
    }

    // ── PATCH /api/v1/strategies/{id}/toggle ─────────────────────────

    @Test
    @DisplayName("전략 설정 활성/비활성 토글 — isActive 반전 및 manualOverride=true 설정")
    void 전략_설정_토글_활성_비활성() throws Exception {
        // 1. 먼저 설정 생성
        String createBody = """
                {
                  "name": "토글_테스트_설정",
                  "strategyType": "BOLLINGER",
                  "coinPair": "KRW-BTC",
                  "timeframe": "M15"
                }
                """;
        String createResponse = mockMvc.perform(post("/api/v1/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(true))
                .andReturn().getResponse().getContentAsString();

        // ID 추출
        Long id = objectMapper.readTree(createResponse).at("/data/id").asLong();

        // 2. 토글 — false 로 전환
        mockMvc.perform(patch("/api/v1/strategies/" + id + "/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isActive").value(false))
                .andExpect(jsonPath("$.data.manualOverride").value(true));
    }

    @Test
    @DisplayName("전략 설정 토글 두 번 — isActive 원래대로 복원")
    void 전략_설정_토글_두번_복원() throws Exception {
        // 1. 설정 생성
        String createBody = """
                {
                  "name": "이중토글_테스트",
                  "strategyType": "GRID",
                  "coinPair": "KRW-BTC",
                  "timeframe": "M60"
                }
                """;
        String createResponse = mockMvc.perform(post("/api/v1/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Long id = objectMapper.readTree(createResponse).at("/data/id").asLong();

        // 2. 첫 번째 토글 (true → false)
        mockMvc.perform(patch("/api/v1/strategies/" + id + "/toggle"))
                .andExpect(jsonPath("$.data.isActive").value(false));

        // 3. 두 번째 토글 (false → true)
        mockMvc.perform(patch("/api/v1/strategies/" + id + "/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(true))
                .andExpect(jsonPath("$.data.manualOverride").value(true));
    }

    @Test
    @DisplayName("전략 설정 토글 — 존재하지 않는 ID는 success=false 반환")
    void 전략_설정_토글_없는_ID() throws Exception {
        mockMvc.perform(patch("/api/v1/strategies/99999/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ── PUT /api/v1/strategies/{id} ───────────────────────────────────

    @Test
    @DisplayName("전략 설정 수정 — coinPair 변경 확인")
    void 전략_설정_수정_코인페어_변경() throws Exception {
        // 1. 생성
        String createBody = """
                {
                  "name": "수정_테스트_설정",
                  "strategyType": "RSI",
                  "coinPair": "KRW-BTC",
                  "timeframe": "M15"
                }
                """;
        String createResponse = mockMvc.perform(post("/api/v1/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Long id = objectMapper.readTree(createResponse).at("/data/id").asLong();

        // 2. 수정
        String updateBody = """
                {
                  "coinPair": "KRW-ETH"
                }
                """;
        mockMvc.perform(put("/api/v1/strategies/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coinPair").value("KRW-ETH"));
    }

    @Test
    @DisplayName("전략 설정 수정 — 존재하지 않는 ID는 success=false 반환")
    void 전략_설정_수정_없는_ID() throws Exception {
        mockMvc.perform(put("/api/v1/strategies/99999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coinPair\": \"KRW-ETH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ── PATCH /api/v1/strategies/{id}/toggle-override ────────────────

    @Test
    @DisplayName("수동 오버라이드 해제 — manualOverride 반전 확인")
    void 수동_오버라이드_해제() throws Exception {
        // 1. 설정 생성
        String createBody = """
                {
                  "name": "오버라이드_해제_테스트",
                  "strategyType": "MACD",
                  "coinPair": "KRW-BTC",
                  "timeframe": "H1"
                }
                """;
        String createResponse = mockMvc.perform(post("/api/v1/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andReturn().getResponse().getContentAsString();

        Long id = objectMapper.readTree(createResponse).at("/data/id").asLong();

        // 2. toggle → manualOverride=true
        mockMvc.perform(patch("/api/v1/strategies/" + id + "/toggle"))
                .andExpect(jsonPath("$.data.manualOverride").value(true));

        // 3. toggle-override → manualOverride=false (해제)
        mockMvc.perform(patch("/api/v1/strategies/" + id + "/toggle-override"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.manualOverride").value(false));
    }
}
