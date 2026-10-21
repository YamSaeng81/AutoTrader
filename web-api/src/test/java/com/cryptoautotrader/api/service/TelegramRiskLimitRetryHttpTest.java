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
 * 2026-10-21 리뷰 — <b>중요 경보의 제한된 재시도와 최종 실패 표시</b>를 실제 HTTP 경로로 확인한다.
 *
 * <p>{@link TelegramCriticalRetryTest} 는 한 번 보내기를 바꿔 끼워 재시도 횟수만 본다. 여기서는 {@code notify*} 부터 모의 텔레그램 서버까지
 * 그대로 돈다.</p>
 *
 * <ul>
 *   <li>🔴 <b>429 에는 평문으로 곧바로 다시 보내지 않는다</b> — 응답의 {@code parameters.retry_after} 만큼 기다린 뒤 다음 시도. 평문 재전송은
 *       서식 오류(400)에만 한다(리뷰: 종전에는 429 에도 평문을 보내 요청이 최대 6번 나갔다).</li>
 *   <li>다음 대기가 합계 상한({@link TelegramNotificationService#RETRY_TOTAL_WAIT_CAP_MS})을 넘으면 기다리지 않고 최종 실패로 끝낸다.</li>
 *   <li>응답 미수신(타임아웃)도 재시도한다 — 이미 도착했을 수 있어 <b>중복 경보가 날 수 있다</b>. 경보는 유실보다 중복이 낫다는
 *       {@code STOP_LOSS}·{@code SESSION_STOP} 과 같은 선택이다(시도 안의 서식 폴백은 하지 않는다).</li>
 *   <li>이력은 재시도가 끝난 뒤 최종 결과 한 줄.</li>
 * </ul>
 *
 * <p>실제로 자지 않는다 — 재시도 대기는 {@code pause} 를 바꿔 끼워 기록만 한다.</p>
 */
class TelegramRiskLimitRetryHttpTest {

    /** 요청마다 돌려줄 상태 — 목록이 끝나면 마지막 값을 계속 쓴다. 0 은 "응답을 제때 주지 않음". */
    private final List<Integer> script = new CopyOnWriteArrayList<>();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<Long> pauses = new CopyOnWriteArrayList<>();
    private final AtomicInteger index = new AtomicInteger();
    /** 429 응답 본문의 retry_after(초). 음수면 parameters 를 넣지 않는다. */
    private volatile int retryAfterSec = 1;

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
            String json = status == 429
                    ? (retryAfterSec >= 0
                        ? "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after " + retryAfterSec
                          + "\",\"parameters\":{\"retry_after\":" + retryAfterSec + "}}"
                        : "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\"}")
                    : status == 400 ? "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: can't parse entities\"}"
                    : "{\"ok\":true}";
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
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
            boolean pause(long ms) {
                pauses.add(ms);
                return true;
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

    private TelegramNotificationLogEntity savedOnce(String type) {
        ArgumentCaptor<TelegramNotificationLogEntity> c = ArgumentCaptor.forClass(TelegramNotificationLogEntity.class);
        verify(repo, times(1)).save(c.capture());
        assertThat(c.getValue().getType()).isEqualTo(type);
        return c.getValue();
    }

    private long markdownCalls() {
        return received.stream().filter(b -> b.contains("MarkdownV2")).count();
    }

    private long plainCalls() {
        return received.size() - markdownCalls();
    }

    // ── 🔴 429 — 평문 즉시 재전송 없이 retry_after 대기 ────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 429(retry_after=1) 뒤 성공 — 평문으로 다시 보내지 않고 1초 기다린 뒤 두 번째 시도에서 전달, 이력 성공 한 줄")
    void 레이트리밋_대기_뒤_성공() {
        script.addAll(List.of(429, 200));

        service.notifyRiskLimitBreached("공유 풀 누적 손실 한도 도달");

        assertThat(received).hasSize(2);
        assertThat(plainCalls()).as("429 에 평문 재전송을 하지 않는다").isZero();
        assertThat(pauses).containsExactly(1000L);
        assertThat(savedOnce("RISK_LIMIT").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("🔴 계속 429 — 요청은 시도 수만큼(3)만, 대기는 retry_after 대로, 이력 최종 실패")
    void 레이트리밋_계속_최종실패() {
        script.add(429);
        retryAfterSec = 2;

        service.notifyRiskLimitBreached("공유 풀 누적 손실 한도 도달");

        assertThat(received).as("종전 6번(서식+평문 × 3) → 3번").hasSize(3);
        assertThat(plainCalls()).isZero();
        assertThat(pauses).containsExactly(2000L, 2000L);
        assertThat(savedOnce("RISK_LIMIT").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("🔴 retry_after 가 대기 상한(30초)을 넘으면 기다리지 않고 한 번으로 최종 실패")
    void 레이트리밋_상한초과_즉시종료() {
        script.add(429);
        retryAfterSec = 31;

        service.notifyExchangeDown("WebSocket 끊김");

        assertThat(received).hasSize(1);
        assertThat(pauses).as("상한을 넘는 대기는 하지 않는다").isEmpty();
        assertThat(savedOnce("EXCHANGE_DOWN").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("대기 합계로 상한을 본다 — retry_after 20초 두 번이면 두 번째 대기(합계 40초)는 하지 않는다")
    void 레이트리밋_합계상한() {
        script.add(429);
        retryAfterSec = 20;

        service.notifyCleanupNeedsHuman("LIVE", 1L, 2L, "정리 중단");

        assertThat(received).hasSize(2);
        assertThat(pauses).containsExactly(20_000L);
        assertThat(savedOnce("CLEANUP_NEEDS_HUMAN").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("429 에 retry_after 가 없으면 기본 대기(2초·5초)를 쓴다")
    void 레이트리밋_retryAfter_없음() {
        script.add(429);
        retryAfterSec = -1;

        service.notifyCleanupIncomplete(7L, 1L);

        assertThat(received).hasSize(3);
        assertThat(pauses).containsExactly(2000L, 5000L);
        assertThat(savedOnce("CLEANUP_INCOMPLETE").isSuccess()).isFalse();
    }

    // ── 서식 오류(400)는 그대로 평문 폴백 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("서식 오류(400)는 같은 시도 안에서 평문으로 한 번 다시 보내 전달 — 재시도 대기 없음")
    void 서식오류_평문폴백() {
        script.addAll(List.of(400, 200));

        service.notifyRiskLimitBreached("깨진 서식 [ 대괄호");

        assertThat(markdownCalls()).isEqualTo(1);
        assertThat(plainCalls()).isEqualTo(1);
        assertThat(pauses).isEmpty();
        assertThat(savedOnce("RISK_LIMIT").isSuccess()).isTrue();
    }

    // ── 응답 미수신 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("응답 미수신이 이어지면 서식 폴백 없이 3회 시도(기본 대기 2초·5초)하고 최종 실패 — 중복 가능성은 감수한다")
    void 응답미수신_최종실패() {
        script.add(0);

        service.notifyRiskLimitBreached("포지션별 한도 STOP_LOSS");

        assertThat(markdownCalls()).isEqualTo(3);
        assertThat(plainCalls()).as("미수신에는 평문 폴백을 하지 않는다").isZero();
        assertThat(pauses).containsExactly(2000L, 5000L);
        assertThat(savedOnce("RISK_LIMIT").isSuccess()).isFalse();
    }

    // ── 대조 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("대조 — 재시도 대상이 아닌 유형(CUSTOM)은 429 면 한 번으로 끝(평문 재전송도 없음)")
    void 비대상_유형은_1회() {
        script.add(429);

        service.sendCustomNotification("요약");

        assertThat(received).hasSize(1);
        assertThat(pauses).isEmpty();
        assertThat(savedOnce("CUSTOM").isSuccess()).isFalse();
    }
}
