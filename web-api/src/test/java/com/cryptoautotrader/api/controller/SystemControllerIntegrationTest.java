package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SystemController 통합 테스트.
 *
 * 테스트 범위:
 * - GET /api/v1/health    — 헬스체크
 * - GET /api/v1/strategies/types — 전략 타입 목록
 */
@DisplayName("SystemController 통합 테스트")
class SystemControllerIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("헬스체크 — 200 OK, status=UP")
    void 헬스체크_정상() throws Exception {
        mockMvc.perform(get("/api/v1/health")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.version").exists());
    }

    @Test
    @DisplayName("전략 타입 목록 — 200 OK, type/name 필드 포함")
    void 전략_타입_목록_조회() throws Exception {
        mockMvc.perform(get("/api/v1/strategies/types")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(10)))
                .andExpect(jsonPath("$.data[0].type").exists())
                .andExpect(jsonPath("$.data[0].name").exists());
    }
}
