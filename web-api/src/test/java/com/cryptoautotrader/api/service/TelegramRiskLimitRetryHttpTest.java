package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.TelegramNotificationLogEntity;
import com.cryptoautotrader.api.repository.TelegramNotificationLogRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 2026-10-21 리뷰 — <b>RISK_LIMIT 경보의 제한된 재시도와 최종 실패 표시</b>를 실제 HTTP 경로로 확인한다.
 *
 * <p>{@link TelegramCriticalRetryTest} 는 {@code sendMarkdown} 을 바꿔 끼워 재시도 횟수만 본다. 여기서는
 * {@code notifyRiskLimitBreached} 부터 모의 텔레그램 서버까지 그대로 돈다 — 거부(429)면 서식 없는 폴백까지 한 시도로 치고,
 * 시도가 다 실패하면 다음 시도로 넘어가며, 끝난 뒤 이력에 최종 결과 한 줄만 남는지 본다.</p>
 *
 * <p>🔴 응답 미수신(타임아웃)도 재시도한다 — 이미 도착했을 수 있어 <b>중복 알림이 날 수 있다</b>. 경보는 유실보다 중복이 낫다는
 * {@code STOP_LOSS}·{@code SESSION_STOP} 과 같은 선택이다(시도 안의 서식 폴백은 여전히 하지 않는다).</p>
 */
class TelegramRiskLimitRetryHttpTest {

    /** 요청마다 돌려줄 상태 — 목록이 끝나면 마지막 값을 계속 쓴다. 0 은 "응답을 제때 주지 않음". */
    private final List<Integer> script = new CopyOnWriteArrayList<>();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger index = new AtomicInteger();

    private HttpServer server;
    private TelegramNotificationService service;
    private TelegramNotificationLogRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int i = index.getAndIncrement();
            int status = script.get(Math.min(i, script.size() - 1));
            if (status == 0) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                status = 200;   // 너무 늦은 응답 — 보낸 쪽은 이미 미수신으로 처리했다
            }
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();

        repo = mock(TelegramNotificationLogRepository.class);
        service = new TelegramNotificationService(repo) {
            @Override
            long backoffMs(int attemptIndex) {
                return 0L;
            }
        };
        ReflectionTestUtils.setField(service, "botToken", "token");
        ReflectionTestUtils.setField(service, "chatId", "chat");
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "telegramApi", "http://127.0.0.1:" + server.getAddress().getPort() + "/bot");
        ReflectionTestUtils.setField(service, "requestTimeout", java.time.Duration.ofMillis(300));
        // 전송 실행기를 호출 스레드로 — notify* 가 돌아오면 재시도와 이력 기록까지 끝나 있다
        ReflectionTestUtils.setField(service, "telegramExecutor", (Executor) Runnable::run);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private TelegramNotificationLogEntity savedOnce() {
        ArgumentCaptor<TelegramNotificationLogEntity> c = ArgumentCaptor.forClass(TelegramNotificationLogEntity.class);
        verify(repo, times(1)).save(c.capture());
        assertThat(c.getValue().getType()).isEqualTo("RISK_LIMIT");
        return c.getValue();
    }

    private long markdownCalls() {
        return received.stream().filter(b -> b.contains("MarkdownV2")).count();
    }

    @Test
    @DisplayName("레이트리밋(429) 한 번 뒤 성공 — 두 번째 시도에서 전달되고 이력은 성공 한 줄")
    void 거부_뒤_성공() {
        script.addAll(List.of(429, 429, 200));   // 1차 시도: 서식 429 → 평문 429 / 2차 시도: 서식 200

        service.notifyRiskLimitBreached("공유 풀 누적 손실 한도 도달");

        assertThat(received).hasSize(3);
        assertThat(markdownCalls()).isEqualTo(2);
        assertThat(savedOnce().isSuccess()).isTrue();
    }

    @Test
    @DisplayName("계속 거부 — 3회 시도(각각 서식+평문)에서 멈추고 이력에 최종 실패 success=false 한 줄")
    void 계속_거부_최종실패() {
        script.add(429);

        service.notifyRiskLimitBreached("공유 풀 누적 손실 한도 도달");

        assertThat(received).as("3회 시도 × (서식 + 평문 폴백)").hasSize(6);
        assertThat(savedOnce().isSuccess()).isFalse();
    }

    @Test
    @DisplayName("응답 미수신이 이어지면 서식 폴백 없이 3회 시도하고 최종 실패 — 중복 가능성은 감수한다")
    void 응답미수신_최종실패() {
        script.add(0);

        service.notifyRiskLimitBreached("포지션별 한도 STOP_LOSS");

        assertThat(markdownCalls()).isEqualTo(3);
        assertThat(received).as("미수신에는 평문 폴백을 하지 않는다").hasSize(3);
        assertThat(savedOnce().isSuccess()).isFalse();
    }

    @Test
    @DisplayName("대조 — 재시도 대상이 아닌 유형(CUSTOM)은 거부되면 한 번(서식+평문)으로 끝")
    void 비대상_유형은_1회() {
        script.add(429);

        service.sendCustomNotification("요약");

        assertThat(received).hasSize(2);
        ArgumentCaptor<TelegramNotificationLogEntity> c = ArgumentCaptor.forClass(TelegramNotificationLogEntity.class);
        verify(repo, times(1)).save(c.capture());
        assertThat(c.getValue().getType()).isEqualTo("CUSTOM");
        assertThat(c.getValue().isSuccess()).isFalse();
    }
}
