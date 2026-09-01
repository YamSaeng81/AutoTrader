package com.cryptoautotrader.api.llm.provider;

import com.cryptoautotrader.api.entity.LlmProviderConfigEntity;
import com.cryptoautotrader.api.llm.LlmRequest;
import com.cryptoautotrader.api.llm.LlmResponse;
import com.cryptoautotrader.api.repository.LlmProviderConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ClaudeProvider} 를 <b>실제 HTTP 경로로</b> 검증한다 — 응답 파싱만이 아니라
 * {@code complete()} 가 그 파싱을 실제로 쓰는지까지 고정하기 위해서다.
 *
 * <p>{@link ClaudeResponseParsingTest} 는 {@code extractText} 단위만 본다. 그래서
 * {@code complete()} 안에서 다시 {@code /content/0/text} 로 되돌려도 그 테스트는 통과한다 —
 * 실제로 뮤테이션을 넣어보고 확인했다. 배선을 잡는 건 이 테스트다.</p>
 *
 * <p>스텁 서버를 띄우고 {@code base_url} 을 거기로 돌린다. 네트워크는 로컬 루프백만 쓴다.</p>
 */
class ClaudeProviderHttpTest {

    private HttpServer server;
    private ClaudeProvider provider;
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> statusCode = new AtomicReference<>(200);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] out = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode.get(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();

        LlmProviderConfigEntity config = LlmProviderConfigEntity.builder()
                .providerName("CLAUDE")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("test-key")
                .defaultModel("claude-sonnet-5")
                .timeoutSeconds(10)
                .enabled(true)
                .build();

        LlmProviderConfigRepository repo = mock(LlmProviderConfigRepository.class);
        when(repo.findByProviderName("CLAUDE")).thenReturn(Optional.of(config));

        provider = new ClaudeProvider(repo, new ObjectMapper());
        ReflectionTestUtils.setField(provider, "envApiKey", "");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private LlmResponse call() {
        return provider.complete(LlmRequest.builder()
                .systemPrompt("시스템 프롬프트")
                .userPrompt("분석해줘")
                .maxTokens(2000)
                .build());
    }

    // ── 🔴 배선 고정 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 thinking 블록이 앞에 온 응답에서 complete() 가 본문을 돌려준다")
    void completeReadsTextAfterThinkingBlock() {
        responseBody.set("""
            {"content":[
               {"type":"thinking","thinking":"표본을 세어보자"},
               {"type":"text","text":"1. 진단 — 표본 부족."}
             ],
             "usage":{"input_tokens":4441,"output_tokens":1474},
             "stop_reason":"end_turn"}
            """);

        LlmResponse resp = call();

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getContent())
                .as("complete() 가 /content/0/text 로 되돌아가면 여기서 빈 문자열이 된다")
                .isEqualTo("1. 진단 — 표본 부족.");
        assertThat(resp.getCompletionTokens()).isEqualTo(1474);
    }

    @Test
    @DisplayName("평범한 단일 텍스트 응답도 그대로 동작한다")
    void completeReadsPlainText() {
        responseBody.set("""
            {"content":[{"type":"text","text":"오늘 요약입니다."}],
             "usage":{"input_tokens":10,"output_tokens":20},"stop_reason":"end_turn"}
            """);

        LlmResponse resp = call();

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getContent()).isEqualTo("오늘 요약입니다.");
    }

    // ── 🔴 조용한 실패 금지 ───────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 토큰은 썼는데 본문이 없으면 success=false — 빈 리포트를 발송하지 않는다")
    void tokensSpentButNoText_isFailure() {
        responseBody.set("""
            {"content":[{"type":"thinking","thinking":"생각만 하고 끝났다"}],
             "usage":{"input_tokens":100,"output_tokens":900},
             "stop_reason":"max_tokens"}
            """);

        LlmResponse resp = call();

        assertThat(resp.isSuccess())
                .as("success=true 로 나가면 호출부가 빈 분석을 정상으로 받아 그대로 발송한다")
                .isFalse();
        assertThat(resp.getErrorMessage())
                .contains("추출하지 못했습니다")
                .contains("max_tokens")
                .as("원인 진단에 필요한 블록 종류가 있어야 한다")
                .contains("thinking");
    }

    @Test
    @DisplayName("HTTP 오류는 그대로 실패로 전달된다")
    void httpErrorIsFailure() {
        statusCode.set(400);
        responseBody.set("{\"error\":{\"message\":\"bad request\"}}");

        LlmResponse resp = call();

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getErrorMessage()).contains("HTTP 400");
    }
}
