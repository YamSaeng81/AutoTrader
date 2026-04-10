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
 * Anthropic Claude API 프로바이더.
 * Messages API 사용 (claude-haiku-4-5-20251001 기본).
 *
 * <p>고품질 분석이 필요한 SIGNAL_ANALYSIS, REPORT_NARRATION에 적합.
 */
@Component
public class ClaudeProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProvider.class);
    private static final String PROVIDER_NAME = "CLAUDE";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final LlmProviderConfigRepository configRepo;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ClaudeProvider(LlmProviderConfigRepository configRepo, ObjectMapper objectMapper) {
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
            return LlmResponse.error(PROVIDER_NAME, "Claude 프로바이더가 비활성화 상태입니다.");
        }

        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return LlmResponse.error(PROVIDER_NAME, "Anthropic API 키가 설정되지 않았습니다.");
        }

        try {
            String model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
            double temperature = request.getTemperature() != null ? request.getTemperature() : 0.3;
            int maxTokens = request.getMaxTokens() != null ? request.getMaxTokens() : 2000;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);

            if (request.getSystemPrompt() != null) {
                body.put("system", request.getSystemPrompt());
            }

            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "user").put("content", request.getUserPrompt());

            String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.anthropic.com";
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[ClaudeProvider] 응답 오류: status={} body={}", response.statusCode(), response.body());
                return LlmResponse.error(PROVIDER_NAME, "HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String content = json.at("/content/0/text").asText("");
            int inputTokens = json.at("/usage/input_tokens").asInt(0);
            int outputTokens = json.at("/usage/output_tokens").asInt(0);

            log.debug("[ClaudeProvider] 완료 — model={} inputTokens={} outputTokens={}",
                    model, inputTokens, outputTokens);

            return LlmResponse.builder()
                    .success(true)
                    .providerName(PROVIDER_NAME)
                    .modelUsed(model)
                    .content(content)
                    .promptTokens(inputTokens)
                    .completionTokens(outputTokens)
                    .build();

        } catch (Exception e) {
            log.error("[ClaudeProvider] 요청 실패", e);
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
