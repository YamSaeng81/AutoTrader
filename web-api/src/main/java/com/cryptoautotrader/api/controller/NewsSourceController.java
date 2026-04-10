package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.entity.NewsItemCacheEntity;
import com.cryptoautotrader.api.entity.NewsSourceConfigEntity;
import com.cryptoautotrader.api.news.NewsAggregatorService;
import com.cryptoautotrader.api.news.NewsItem;
import com.cryptoautotrader.api.news.NewsSourceRegistry;
import com.cryptoautotrader.api.repository.NewsItemCacheRepository;
import com.cryptoautotrader.api.repository.NewsSourceConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 뉴스 소스 관리 API.
 * GET/POST/PUT/DELETE /api/v1/admin/news-sources
 * POST /api/v1/admin/news-sources/{sourceId}/fetch  — 수동 수집 테스트
 * GET  /api/v1/admin/news-sources/cache             — 캐시 조회
 */
@RestController
@RequestMapping("/api/v1/admin/news-sources")
@RequiredArgsConstructor
public class NewsSourceController {

    private final NewsSourceConfigRepository sourceConfigRepo;
    private final NewsItemCacheRepository newsCacheRepo;
    private final NewsAggregatorService newsAggregatorService;
    private final NewsSourceRegistry newsSourceRegistry;

    // ── 소스 CRUD ──────────────────────────────────────────────────────────────

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> getSources() {
        List<Map<String, Object>> list = sourceConfigRepo.findAll().stream()
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", s.getId());
                    m.put("sourceId", s.getSourceId());
                    m.put("displayName", s.getDisplayName());
                    m.put("sourceType", s.getSourceType());
                    m.put("category", s.getCategory());
                    m.put("url", s.getUrl());
                    m.put("apiKeyConfigured", s.getApiKey() != null && !s.getApiKey().isBlank());
                    m.put("enabled", s.isEnabled());
                    m.put("fetchIntervalMin", s.getFetchIntervalMin());
                    m.put("lastFetchedAt", s.getLastFetchedAt() != null ? s.getLastFetchedAt().toString() : null);
                    m.put("configJson", s.getConfigJson());
                    // 지원 여부 (구현체 존재 확인)
                    m.put("supported", newsSourceRegistry.get(s.getSourceType()) != null);
                    return m;
                }).toList();
        return ApiResponse.ok(list);
    }

    @PostMapping
    public ApiResponse<String> createSource(@RequestBody Map<String, Object> body) {
        String sourceId = (String) body.get("sourceId");
        if (sourceId == null || sourceId.isBlank()) {
            return ApiResponse.error("INVALID_INPUT", "sourceId는 필수입니다.");
        }
        if (sourceConfigRepo.findBySourceId(sourceId).isPresent()) {
            return ApiResponse.error("DUPLICATE", "이미 존재하는 sourceId: " + sourceId);
        }

        NewsSourceConfigEntity entity = NewsSourceConfigEntity.builder()
                .sourceId(sourceId)
                .displayName((String) body.getOrDefault("displayName", sourceId))
                .sourceType(((String) body.getOrDefault("sourceType", "RSS")).toUpperCase())
                .category(((String) body.getOrDefault("category", "GENERAL")).toUpperCase())
                .url((String) body.getOrDefault("url", ""))
                .apiKey((String) body.get("apiKey"))
                .enabled(Boolean.TRUE.equals(body.get("enabled")))
                .fetchIntervalMin(body.containsKey("fetchIntervalMin")
                        ? ((Number) body.get("fetchIntervalMin")).intValue() : 60)
                .configJson((String) body.get("configJson"))
                .build();

        sourceConfigRepo.save(entity);
        return ApiResponse.ok("생성 완료: " + sourceId);
    }

    @PutMapping("/{sourceId}")
    public ApiResponse<String> updateSource(
            @PathVariable String sourceId,
            @RequestBody Map<String, Object> body) {

        NewsSourceConfigEntity entity = sourceConfigRepo.findBySourceId(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("소스 없음: " + sourceId));

        if (body.containsKey("displayName"))    entity.setDisplayName((String) body.get("displayName"));
        if (body.containsKey("url"))            entity.setUrl((String) body.get("url"));
        if (body.containsKey("category"))       entity.setCategory(((String) body.get("category")).toUpperCase());
        if (body.containsKey("sourceType"))     entity.setSourceType(((String) body.get("sourceType")).toUpperCase());
        if (body.containsKey("enabled"))        entity.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("fetchIntervalMin")) {
            entity.setFetchIntervalMin(((Number) body.get("fetchIntervalMin")).intValue());
        }
        if (body.containsKey("apiKey")) {
            String key = (String) body.get("apiKey");
            if (key != null && !key.isBlank()) entity.setApiKey(key);
        }
        if (body.containsKey("configJson")) entity.setConfigJson((String) body.get("configJson"));

        sourceConfigRepo.save(entity);
        return ApiResponse.ok("업데이트 완료: " + sourceId);
    }

    @DeleteMapping("/{sourceId}")
    public ApiResponse<String> deleteSource(@PathVariable String sourceId) {
        NewsSourceConfigEntity entity = sourceConfigRepo.findBySourceId(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("소스 없음: " + sourceId));
        sourceConfigRepo.delete(entity);
        return ApiResponse.ok("삭제 완료: " + sourceId);
    }

    // ── 수동 수집 테스트 ───────────────────────────────────────────────────────

    @PostMapping("/{sourceId}/fetch")
    public ApiResponse<Map<String, Object>> fetchNow(@PathVariable String sourceId) {
        List<NewsItem> items = newsAggregatorService.collectFromSourceManual(sourceId);
        Map<String, Object> result = new HashMap<>();
        result.put("sourceId", sourceId);
        result.put("collected", items.size());
        result.put("titles", items.stream().limit(5).map(NewsItem::getTitle).toList());
        return ApiResponse.ok(result);
    }

    // ── 캐시 조회 ─────────────────────────────────────────────────────────────

    @GetMapping("/cache")
    public ApiResponse<List<Map<String, Object>>> getCache(
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String since) {

        List<NewsItemCacheEntity> items;
        if (category != null && !category.isBlank()) {
            items = newsCacheRepo.findRecentByCategory(category.toUpperCase(), PageRequest.of(0, size));
        } else if (since != null && !since.isBlank()) {
            items = newsCacheRepo.findSince(Instant.parse(since));
        } else {
            items = newsCacheRepo.findRecentByCategory("CRYPTO", PageRequest.of(0, size));
        }

        List<Map<String, Object>> result = items.stream().map(n -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", n.getId());
            m.put("sourceId", n.getSourceId());
            m.put("title", n.getTitle());
            m.put("url", n.getUrl());
            m.put("summary", n.getSummary());
            m.put("category", n.getCategory());
            m.put("publishedAt", n.getPublishedAt() != null ? n.getPublishedAt().toString() : null);
            m.put("fetchedAt", n.getFetchedAt().toString());
            return m;
        }).toList();

        return ApiResponse.ok(result);
    }
}
