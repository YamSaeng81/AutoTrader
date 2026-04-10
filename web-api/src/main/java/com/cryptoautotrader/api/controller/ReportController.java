package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.entity.NotionReportConfigEntity;
import com.cryptoautotrader.api.entity.NotionReportLogEntity;
import com.cryptoautotrader.api.report.ReportComposer;
import com.cryptoautotrader.api.repository.NotionReportConfigRepository;
import com.cryptoautotrader.api.repository.NotionReportLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 노션 보고서 관리 API.
 * GET  /api/v1/admin/reports/config       — Notion 설정 조회
 * PUT  /api/v1/admin/reports/config       — Notion 설정 변경
 * POST /api/v1/admin/reports/trigger      — 수동 보고서 생성
 * GET  /api/v1/admin/reports/history      — 발송 이력 조회
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
public class ReportController {

    private final NotionReportConfigRepository configRepo;
    private final NotionReportLogRepository reportLogRepo;
    private final ReportComposer reportComposer;

    // ── 설정 ──────────────────────────────────────────────────────────────────

    @GetMapping("/config")
    public ApiResponse<List<Map<String, Object>>> getConfig() {
        List<Map<String, Object>> list = configRepo.findAll().stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("configKey", c.getConfigKey());
                    // 토큰은 마스킹
                    boolean isSensitive = "notion_token".equals(c.getConfigKey());
                    m.put("configValue", isSensitive && c.getConfigValue() != null
                            ? "***" + c.getConfigValue().substring(Math.max(0, c.getConfigValue().length() - 4))
                            : c.getConfigValue());
                    m.put("description", c.getDescription());
                    m.put("configured", c.getConfigValue() != null && !c.getConfigValue().isBlank());
                    return m;
                }).toList();
        return ApiResponse.ok(list);
    }

    @PutMapping("/config")
    public ApiResponse<String> updateConfig(@RequestBody Map<String, String> updates) {
        updates.forEach((key, value) -> {
            NotionReportConfigEntity entity = configRepo.findByConfigKey(key)
                    .orElseGet(() -> {
                        NotionReportConfigEntity e = new NotionReportConfigEntity();
                        e.setConfigKey(key);
                        return e;
                    });
            entity.setConfigValue(value);
            configRepo.save(entity);
        });
        return ApiResponse.ok("설정 업데이트 완료 (" + updates.size() + "건)");
    }

    // ── 수동 트리거 ────────────────────────────────────────────────────────────

    /**
     * 수동 보고서 생성.
     * body: { "hours": 12 } — 최근 N시간 분석 (기본 12)
     */
    @PostMapping("/trigger")
    public ApiResponse<Map<String, Object>> triggerReport(
            @RequestBody(required = false) Map<String, Object> body) {
        int hours = body != null && body.containsKey("hours")
                ? ((Number) body.get("hours")).intValue() : 12;

        Instant to   = Instant.now();
        Instant from = to.minus(hours, ChronoUnit.HOURS);

        NotionReportLogEntity result = reportComposer.compose(from, to);

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", result.getId());
        resp.put("status", result.getStatus());
        resp.put("notionPageId", result.getNotionPageId());
        resp.put("notionPageUrl", result.getNotionPageUrl());
        resp.put("llmSummary", result.getLlmSummary());
        resp.put("llmAnalysis", result.getLlmAnalysis());
        resp.put("errorMessage", result.getErrorMessage());
        return ApiResponse.ok(resp);
    }

    // ── 이력 조회 ─────────────────────────────────────────────────────────────

    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> getHistory(
            @RequestParam(defaultValue = "20") int size) {
        List<Map<String, Object>> list = reportLogRepo.findRecent(PageRequest.of(0, size)).stream()
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", r.getId());
                    m.put("reportType", r.getReportType());
                    m.put("periodStart", r.getPeriodStart().toString());
                    m.put("periodEnd", r.getPeriodEnd().toString());
                    m.put("status", r.getStatus());
                    m.put("notionPageId", r.getNotionPageId());
                    m.put("notionPageUrl", r.getNotionPageUrl());
                    m.put("llmSummary", r.getLlmSummary());
                    m.put("llmAnalysis", r.getLlmAnalysis());
                    m.put("errorMessage", r.getErrorMessage());
                    m.put("createdAt", r.getCreatedAt().toString());
                    m.put("completedAt", r.getCompletedAt() != null ? r.getCompletedAt().toString() : null);
                    return m;
                }).toList();
        return ApiResponse.ok(list);
    }
}
