package com.cryptoautotrader.api.llm.provider;

import com.cryptoautotrader.api.entity.LlmProviderConfigEntity;
import com.cryptoautotrader.api.llm.LlmProvider;
import com.cryptoautotrader.api.llm.LlmRequest;
import com.cryptoautotrader.api.llm.LlmResponse;
import com.cryptoautotrader.api.repository.LlmProviderConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI Chat Completions API 프로바이더.
 * 기본 모델: gpt-4o-mini
 *
 * <p>API 키는 llm_provider_config.api_key 에서 런타임에 로드된다.
 * 관리 페이지에서 변경하면 즉시 반영된다 (재시작 불필요).
 */
@Component
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private static final String PROVIDER_NAME = "OPENAI";

    private final LlmProviderConfigRepository configRepo;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiProvider(LlmProviderConfigRepository configRepo, ObjectMapper objectMapper) {
        this.configRepo   = configRepo;
        this.objectMapper = objectMapper;
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        LlmProviderConfigEntity config = loadConfig();
        if (config == null || !config.isEnabled()) {
            return LlmResponse.error(PROVIDER_NAME, "OpenAI 프로바이더가 비활성화 상태입니다.");
        }

        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return LlmResponse.error(PROVIDER_NAME, "OpenAI API 키가 설정되지 않았습니다.");
        }

        try {
            String model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
            double temperature = request.getTemperature() != null ? request.getTemperature() : 0.3;
            int maxTokens = request.getMaxTokens() != null ? request.getMaxTokens() : 2000;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);

            ArrayNode messages = body.putArray("messages");
            if (request.getSystemPrompt() != null) {
                messages.addObject().put("role", "system").put("content", request.getSystemPrompt());
            }
            messages.addObject().put("role", "user").put("content", request.getUserPrompt());

            String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.openai.com/v1";
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[OpenAiProvider] 응답 오류: status={} body={}", response.statusCode(), response.body());
                return LlmResponse.error(PROVIDER_NAME, "HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String content = json.at("/choices/0/message/content").asText("");
            int promptTokens = json.at("/usage/prompt_tokens").asInt(0);
            int completionTokens = json.at("/usage/completion_tokens").asInt(0);

            log.debug("[OpenAiProvider] 완료 — model={} promptTokens={} completionTokens={}",
                    model, promptTokens, completionTokens);

            return LlmResponse.builder()
                    .success(true)
                    .providerName(PROVIDER_NAME)
                    .modelUsed(model)
                    .content(content)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .build();

        } catch (Exception e) {
            log.error("[OpenAiProvider] 요청 실패", e);
            return LlmResponse.error(PROVIDER_NAME, e.getMessage());
        }
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        LlmProviderConfigEntity config = loadConfig();
        return config != null && config.isEnabled()
                && config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    private LlmProviderConfigEntity loadConfig() {
        return configRepo.findByProviderName(PROVIDER_NAME).orElse(null);
    }
}
