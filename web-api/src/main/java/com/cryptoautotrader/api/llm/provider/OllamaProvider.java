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
 * Ollama 로컬 LLM 프로바이더.
 * Ollama /api/chat 엔드포인트 사용 (OpenAI 호환 모드).
 *
 * <p>기본 base_url: http://localhost:11434
 * <p>로그 요약 등 대용량·반복 작업에 적합.
 * <p>API 키 불필요, base_url만 설정하면 동작.
 */
@Component
public class OllamaProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);
    private static final String PROVIDER_NAME = "OLLAMA";

    private final LlmProviderConfigRepository configRepo;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OllamaProvider(LlmProviderConfigRepository configRepo, ObjectMapper objectMapper) {
        this.configRepo   = configRepo;
        this.objectMapper = objectMapper;
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        LlmProviderConfigEntity config = loadConfig();
        if (config == null || !config.isEnabled()) {
            return LlmResponse.error(PROVIDER_NAME, "Ollama 프로바이더가 비활성화 상태입니다.");
        }

        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : "http://localhost:11434";

        try {
            String model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
            double temperature = request.getTemperature() != null ? request.getTemperature() : 0.3;

            // Ollama /api/chat (OpenAI 호환)
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("stream", false);

            ObjectNode options = body.putObject("options");
            options.put("temperature", temperature);
            if (request.getMaxTokens() != null) {
                options.put("num_predict", request.getMaxTokens());
            }

            ArrayNode messages = body.putArray("messages");
            if (request.getSystemPrompt() != null) {
                messages.addObject().put("role", "system").put("content", request.getSystemPrompt());
            }
            messages.addObject().put("role", "user").put("content", request.getUserPrompt());

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[OllamaProvider] 응답 오류: status={}", response.statusCode());
                return LlmResponse.error(PROVIDER_NAME, "HTTP " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String content = json.at("/message/content").asText("");
            int promptTokens = json.at("/prompt_eval_count").asInt(0);
            int completionTokens = json.at("/eval_count").asInt(0);

            log.debug("[OllamaProvider] 완료 — model={} tokens={}", model, completionTokens);

            return LlmResponse.builder()
                    .success(true)
                    .providerName(PROVIDER_NAME)
                    .modelUsed(model)
                    .content(content)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .build();

        } catch (Exception e) {
            log.error("[OllamaProvider] 요청 실패", e);
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
        if (config == null || !config.isEnabled()) return false;

        // Ollama 서버 ping 확인
        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : "http://localhost:11434";
        try {
            HttpRequest ping = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(ping, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.debug("[OllamaProvider] 서버 미응답: {}", e.getMessage());
            return false;
        }
    }

    private LlmProviderConfigEntity loadConfig() {
        return configRepo.findByProviderName(PROVIDER_NAME).orElse(null);
    }
}
