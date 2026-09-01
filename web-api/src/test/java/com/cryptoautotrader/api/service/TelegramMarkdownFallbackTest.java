package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.TelegramNotificationLogRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 2026-09-01 — <b>MarkdownV2 가 거부되면 서식을 버리고 내용을 살린다.</b>
 *
 * <h3>왜 이런 규칙이 필요한가</h3>
 * <p>{@code escapeMarkdownV2} 는 {@code *} {@code [} {@code ]} <code>`</code> 를 일부러
 * 이스케이프하지 않는다 — 알림 템플릿이 그 문자로 굵게·코드 서식을 쓰기 때문이다.
 * 템플릿은 짝을 맞춰 쓰니 안전하지만 <b>밖에서 온 텍스트</b>(LLM 응답 등)는 그렇지 않고,
 * 짝이 안 맞으면 텔레그램이 400 을 돌려 <b>메시지가 통째로 사라진다</b>.
 * 이스케이프 목록을 늘리는 건 답이 아니다 — 기존 템플릿의 의도된 서식이 전부 글자 그대로 나온다.</p>
 *
 * <h3>왜 타임아웃에는 폴백하지 않는가</h3>
 * <p>응답을 못 받은 것과 거부당한 것은 다르다. 응답 미수신은 <b>이미 도착했을 수도</b> 있어
 * 재전송하면 중복 알림이 된다. 실제로 2026-09-01 10:12 세션 LLM 분석은
 * {@code success=false} 로 기록됐지만 메시지는 정상 도착했다 — 처음엔 이걸 "유실"로 읽고
 * 전체를 평문 전환하려 했는데, 그러면 잘 나오던 서식만 잃을 뻔했다.</p>
 */
class TelegramMarkdownFallbackTest {

    /** 서버가 받은 요청 본문 — 몇 번, 어떤 parse_mode 로 왔는지 확인한다. */
    private final List<String> received = new ArrayList<>();
    private final AtomicInteger markdownStatus = new AtomicInteger(200);
    private final AtomicInteger plainStatus = new AtomicInteger(200);
    /** MarkdownV2 요청을 응답 대기시간보다 늘권히 처리해 타임아웃을 재현한다. */
    private volatile boolean stallMarkdown = false;

    private HttpServer server;
    private TelegramNotificationService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(body);
            boolean isMarkdown = body.contains("MarkdownV2");
            if (isMarkdown && stallMarkdown) {
                // 요청은 받았지만 응답을 제때 돌려주지 않는다
                // — 실제 운영에서 "메시지는 도착했는데 응답만 못 받은" 상황이다.
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            }
            int status = isMarkdown ? markdownStatus.get() : plainStatus.get();
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        // 기본 실행기는 요청을 한 줄로 순차 처리한다 — 첫 요청이 지연되는 동안
        // 뒤의 폴백 요청이 큐에 갇혀 "폴백이 안 오았다"로 보이게 된다.
        // 그러면 폴백 규칙을 깨는 변경을 이 테스트가 못 잡는다.
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();

        service = new TelegramNotificationService(mock(TelegramNotificationLogRepository.class));
        ReflectionTestUtils.setField(service, "botToken", "token");
        ReflectionTestUtils.setField(service, "chatId", "chat");
        ReflectionTestUtils.setField(service, "enabled", true);
        // 테스트용 엔드포인트로 돌린다
        ReflectionTestUtils.setField(service, "telegramApi",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/bot");
        // 타임아웃 경로를 빠르게 검증하기 위해 응답 대기를 짧게 준다
        ReflectionTestUtils.setField(service, "requestTimeout", java.time.Duration.ofMillis(300));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private long markdownCalls() {
        return received.stream().filter(b -> b.contains("MarkdownV2")).count();
    }

    private long plainCalls() {
        return received.stream().filter(b -> !b.contains("MarkdownV2")).count();
    }

    // ── 정상 경로 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MarkdownV2 가 통하면 그대로 끝 — 서식을 유지한다")
    void markdownSucceeds_noFallback() {
        markdownStatus.set(200);

        assertThat(service.sendMarkdown("**1. 진단**: 표본 부족")).isTrue();

        assertThat(markdownCalls()).isEqualTo(1);
        assertThat(plainCalls()).as("성공했는데 또 보내면 중복이다").isZero();
    }

    // ── 🔴 거부 → 평문 폴백 ───────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 400 으로 거부되면 서식 없이 다시 보낸다 — 내용을 잃지 않는다")
    void markdownRejected_fallsBackToPlain() {
        markdownStatus.set(400);
        plainStatus.set(200);

        assertThat(service.sendMarkdown("**깨진 서식 [ 대괄호"))
                .as("폴백이 성공했으므로 전체적으로 성공이다")
                .isTrue();

        assertThat(markdownCalls()).isEqualTo(1);
        assertThat(plainCalls()).as("서식을 버리고 한 번 더 보내야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("폴백까지 실패하면 실패로 보고한다 — 성공했다고 말하지 않는다")
    void bothFail_reportsFailure() {
        markdownStatus.set(400);
        plainStatus.set(500);

        assertThat(service.sendMarkdown("**깨진 서식")).isFalse();

        assertThat(markdownCalls()).isEqualTo(1);
        assertThat(plainCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("폴백 본문에는 parse_mode 가 아예 없다 — null 서식을 지정하는 게 아니다")
    void fallbackOmitsParseModeEntirely() {
        markdownStatus.set(400);
        plainStatus.set(200);

        service.sendMarkdown("**본문**");

        String plainBody = received.stream().filter(b -> !b.contains("MarkdownV2")).findFirst().orElseThrow();
        assertThat(plainBody)
                .as("parse_mode 필드 자체가 없어야 한다")
                .doesNotContain("parse_mode");
    }

    // ── 🔴 응답 미수신에는 폴백하지 않는다 ────────────────────────────────────

    @Test
    @DisplayName("🔴 응답을 못 받으면 재전송하지 않는다 — 이미 도착했을 수 있어 중복이 된다")
    void transportError_doesNotRetry() {
        stallMarkdown = true;    // 서버는 요청을 받지만 응답은 제때 안 돌려준다

        assertThat(service.sendMarkdown("**본문**")).isFalse();

        assertThat(markdownCalls())
                .as("요청 자체는 서버에 도달했다 — 메시지가 발송됐을 수 있는 상태다")
                .isEqualTo(1);
        assertThat(plainCalls())
                .as("이 상태에서 폴백하면 같은 알림이 두 번 간다")
                .isZero();
    }

    @Test
    @DisplayName("연결 자체가 안 되는 경우도 실패로 보고한다")
    void connectionRefused_isFailure() {
        server.stop(0);

        assertThat(service.sendMarkdown("**본문**")).isFalse();
        assertThat(received).isEmpty();
    }
}
