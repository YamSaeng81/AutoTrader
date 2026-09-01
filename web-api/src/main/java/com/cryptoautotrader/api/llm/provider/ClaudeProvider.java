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
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * .env(ANTHROPIC_API_KEY) → application.yml(anthropic.api-key)로 주입되는 API 키.
     * 값이 있으면 DB(llm_provider_config.api_key)보다 우선한다. 비어 있으면 DB로 폴백.
     */
    @Value("${anthropic.api-key:}")
    private String envApiKey;

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

        String apiKey = resolveApiKey(config);
        if (apiKey == null || apiKey.isBlank()) {
            return LlmResponse.error(PROVIDER_NAME,
                    "Anthropic API 키가 설정되지 않았습니다. (.env ANTHROPIC_API_KEY 또는 DB api_key)");
        }

        try {
            String model = (request.getModel() != null && !request.getModel().isBlank())
                    ? request.getModel() : config.getDefaultModel();
            int maxTokens = request.getMaxTokens() != null ? request.getMaxTokens() : 2000;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            // temperature는 전송하지 않는다 — Claude 5 계열(sonnet-5 등)에서 deprecated(400 유발)이며
            // 선택 파라미터라 생략 시 모델 기본값 사용. 요약·분석 용도엔 기본값으로 충분.

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
            String content = extractText(json);
            int inputTokens = json.at("/usage/input_tokens").asInt(0);
            int outputTokens = json.at("/usage/output_tokens").asInt(0);
            String stopReason = json.at("/stop_reason").asText("");

            // 모델이 토큰을 썼는데 본문이 비었다면 응답을 제대로 못 읽은 것이다.
            // 이걸 success=true 로 넣어보내면 호출부는 "분석 결과가 없다"를 정상으로 받아들여
            // 빈 리포트를 발송한다 — 실제로 2026-09-01 이전까지 SIGNAL_ANALYSIS 27건 중 24건이
            // 그렇게 조용히 비어서 나갔다. 오류로 드러내야 사람이 알아차린다.
            if (content.isBlank() && outputTokens > 0) {
                log.error("[ClaudeProvider] 토큰은 생성됐는데 본문을 추출하지 못했다 — "
                                + "model={} outputTokens={} stopReason={} blockTypes={}",
                        model, outputTokens, stopReason, blockTypes(json));
                return LlmResponse.error(PROVIDER_NAME, String.format(
                        "응답 본문을 추출하지 못했습니다 (outputTokens=%d, stopReason=%s, blocks=%s)",
                        outputTokens, stopReason, blockTypes(json)));
            }

            log.debug("[ClaudeProvider] 완료 — model={} inputTokens={} outputTokens={} stopReason={}",
                    model, inputTokens, outputTokens, stopReason);

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

    /**
     * Messages API 응답에서 본문을 꺼낸다.
     *
     * <p><b>2026-09-01 수정</b> — 이전에는 {@code /content/0/text} 를 썼다. 그런데
     * {@code content} 는 <b>블록 배열</b>이고 첫 블록이 항상 텍스트가 아니다 —
     * 모델이 생각하면 {@code thinking} 블록이 앞에 붙고, 그러면 {@code /content/0/text} 는
     * 존재하지 않아 <b>빈 문자열</b>이 된다.</p>
     *
     * <p>실측 피해(2026-09-01 운영 DB): 출력이 길수록 빈 응답이 많았다 —
     * LOG_SUMMARY(평균 315토큰) 28건 중 0건, REPORT_NARRATION(724) 20건 중 11건,
     * <b>SIGNAL_ANALYSIS(977) 27건 중 24건</b>이 본문 없이 저장됐다. 12시간 리포트의
     * AI 분석 섹션이 몇 주간 조용히 비어 있었다.</p>
     *
     * <p>이제 <b>모든 {@code type=="text"} 블록을 순서대로 이어 붙인다</b>.
     * 텍스트가 여러 블록으로 쪼개져 오는 경우까지 함께 처리된다.</p>
     */
    static String extractText(JsonNode json) {
        JsonNode content = json.path("content");
        if (!content.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if (!"text".equals(block.path("type").asText())) continue;   // thinking/tool_use 등은 건너뛴다
            String text = block.path("text").asText("");
            if (text.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(text);
        }
        return sb.toString().trim();
    }

    /** 본문 추출 실패 진단용 — 응답에 어떤 블록이 들어 있었는지 남긴다. */
    private static String blockTypes(JsonNode json) {
        JsonNode content = json.path("content");
        if (!content.isArray()) return "(content 배열 아님)";
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if (sb.length() > 0) sb.append(',');
            sb.append(block.path("type").asText("?"));
        }
        return sb.length() == 0 ? "(빈 배열)" : sb.toString();
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        LlmProviderConfigEntity config = loadConfig();
        if (config == null || !config.isEnabled()) return false;
        String key = resolveApiKey(config);
        return key != null && !key.isBlank();
    }

    /**
     * API 키 해석 — .env(ANTHROPIC_API_KEY) 우선, 없으면 DB(api_key) 폴백.
     * 시크릿은 .env로 관리하는 것이 표준이며, DB 값은 하위호환/비상용.
     */
    private String resolveApiKey(LlmProviderConfigEntity config) {
        if (envApiKey != null && !envApiKey.isBlank()) return envApiKey.trim();
        return config != null ? config.getApiKey() : null;
    }

    private LlmProviderConfigEntity loadConfig() {
        return configRepo.findByProviderName(PROVIDER_NAME).orElse(null);
    }
}
