package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.entity.LlmProviderConfigEntity;
import com.cryptoautotrader.api.entity.LlmTaskConfigEntity;
import com.cryptoautotrader.api.llm.LlmProvider;
import com.cryptoautotrader.api.llm.LlmProviderRegistry;
import com.cryptoautotrader.api.llm.LlmRequest;
import com.cryptoautotrader.api.llm.LlmResponse;
import com.cryptoautotrader.api.llm.LlmTask;
import com.cryptoautotrader.api.llm.LlmTaskRouter;
import com.cryptoautotrader.api.repository.LlmProviderConfigRepository;
import com.cryptoautotrader.api.repository.LlmTaskConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 프로바이더·작업 설정 관리 API.
 * GET/PUT /api/v1/admin/llm/providers
 * GET/PUT /api/v1/admin/llm/tasks
 * POST    /api/v1/admin/llm/test
 */
@RestController
@RequestMapping("/api/v1/admin/llm")
@RequiredArgsConstructor
public class LlmConfigController {

    private final LlmProviderConfigRepository providerConfigRepo;
    private final LlmTaskConfigRepository taskConfigRepo;
    private final LlmProviderRegistry providerRegistry;
    private final LlmTaskRouter llmTaskRouter;

    // ── 프로바이더 관리 ────────────────────────────────────────────────────────

    @GetMapping("/providers")
    public ApiResponse<List<Map<String, Object>>> getProviders() {
        List<Map<String, Object>> list = providerConfigRepo.findAll().stream()
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", p.getId());
                    m.put("providerName", p.getProviderName());
                    m.put("displayName", p.getDisplayName());
                    m.put("baseUrl", p.getBaseUrl());
                    // API 키는 마스킹 처리
                    m.put("apiKeyConfigured", p.getApiKey() != null && !p.getApiKey().isBlank());
                    m.put("defaultModel", p.getDefaultModel());
                    m.put("timeoutSeconds", p.getTimeoutSeconds());
                    m.put("enabled", p.isEnabled());
                    // 실시간 가용성 확인
                    LlmProvider provider = providerRegistry.get(p.getProviderName());
                    m.put("available", provider.isAvailable());
                    return m;
                }).toList();
        return ApiResponse.ok(list);
    }

    @PutMapping("/providers/{providerName}")
    public ApiResponse<String> updateProvider(
            @PathVariable String providerName,
            @RequestBody Map<String, Object> body) {

        LlmProviderConfigEntity config = providerConfigRepo.findByProviderName(providerName.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Provider 없음: " + providerName));

        if (body.containsKey("apiKey")) {
            String key = (String) body.get("apiKey");
            if (key != null && !key.isBlank()) {
                config.setApiKey(key);
            }
        }
        if (body.containsKey("baseUrl"))      config.setBaseUrl((String) body.get("baseUrl"));
        if (body.containsKey("defaultModel")) config.setDefaultModel((String) body.get("defaultModel"));
        if (body.containsKey("timeoutSeconds")) {
            config.setTimeoutSeconds(((Number) body.get("timeoutSeconds")).intValue());
        }
        if (body.containsKey("enabled")) {
            config.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        }

        providerConfigRepo.save(config);
        return ApiResponse.ok("업데이트 완료: " + providerName);
    }

    // ── 작업 라우팅 관리 ───────────────────────────────────────────────────────

    @GetMapping("/tasks")
    public ApiResponse<List<Map<String, Object>>> getTasks() {
        List<Map<String, Object>> list = taskConfigRepo.findAll().stream()
                .map(t -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", t.getId());
                    m.put("taskName", t.getTaskName());
                    m.put("providerName", t.getProviderName());
                    m.put("model", t.getModel());
                    m.put("temperature", t.getTemperature());
                    m.put("maxTokens", t.getMaxTokens());
                    m.put("enabled", t.isEnabled());
                    return m;
                }).toList();
        return ApiResponse.ok(list);
    }

    @PutMapping("/tasks/{taskName}")
    public ApiResponse<String> updateTask(
            @PathVariable String taskName,
            @RequestBody Map<String, Object> body) {

        LlmTaskConfigEntity config = taskConfigRepo.findByTaskName(taskName.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Task 설정 없음: " + taskName));

        if (body.containsKey("providerName")) config.setProviderName((String) body.get("providerName"));
        if (body.containsKey("model"))        config.setModel((String) body.get("model"));
        if (body.containsKey("temperature")) {
            config.setTemperature(new BigDecimal(body.get("temperature").toString()));
        }
        if (body.containsKey("maxTokens")) {
            config.setMaxTokens(((Number) body.get("maxTokens")).intValue());
        }
        if (body.containsKey("enabled")) {
            config.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        }

        taskConfigRepo.save(config);
        return ApiResponse.ok("업데이트 완료: " + taskName);
    }

    // ── 연결 테스트 ────────────────────────────────────────────────────────────

    /**
     * 특정 provider 직접 테스트 (task 라우팅 없이 raw 호출).
     * body: { providerName, prompt }
     */
    @PostMapping("/test/provider")
    public ApiResponse<Map<String, Object>> testProvider(@RequestBody Map<String, Object> body) {
        String providerName = (String) body.getOrDefault("providerName", "MOCK");
        String prompt = (String) body.getOrDefault("prompt", "안녕하세요. 간단히 응답해주세요.");

        LlmProvider provider = providerRegistry.get(providerName.toUpperCase());
        LlmResponse response = provider.complete(LlmRequest.builder()
                .systemPrompt("You are a helpful assistant.")
                .userPrompt(prompt)
                .maxTokens(200)
                .build());

        Map<String, Object> result = new HashMap<>();
        result.put("success", response.isSuccess());
        result.put("providerName", response.getProviderName());
        result.put("modelUsed", response.getModelUsed());
        result.put("content", response.getContent());
        result.put("promptTokens", response.getPromptTokens());
        result.put("completionTokens", response.getCompletionTokens());
        result.put("errorMessage", response.getErrorMessage());
        return ApiResponse.ok(result);
    }

    /**
     * task 라우팅 테스트.
     * body: { task, systemPrompt, userPrompt }
     */
    @PostMapping("/test/task")
    public ApiResponse<Map<String, Object>> testTask(@RequestBody Map<String, Object> body) {
        String taskName = (String) body.getOrDefault("task", "LOG_SUMMARY");
        String systemPrompt = (String) body.getOrDefault("systemPrompt", "You are a helpful assistant.");
        String userPrompt = (String) body.getOrDefault("userPrompt", "간단한 테스트입니다. 응답해주세요.");

        LlmTask task;
        try {
            task = LlmTask.valueOf(taskName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("INVALID_TASK", "유효하지 않은 task: " + taskName);
        }

        LlmResponse response = llmTaskRouter.route(task, systemPrompt, userPrompt);

        Map<String, Object> result = new HashMap<>();
        result.put("success", response.isSuccess());
        result.put("providerName", response.getProviderName());
        result.put("modelUsed", response.getModelUsed());
        result.put("content", response.getContent());
        result.put("promptTokens", response.getPromptTokens());
        result.put("completionTokens", response.getCompletionTokens());
        result.put("errorMessage", response.getErrorMessage());
        return ApiResponse.ok(result);
    }
}
