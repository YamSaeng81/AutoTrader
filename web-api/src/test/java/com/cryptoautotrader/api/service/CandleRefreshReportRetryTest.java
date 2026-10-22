package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.TelegramNotificationLogEntity;
import com.cryptoautotrader.api.repository.CandleDataRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-22 리뷰 — <b>04:10 캔들 갱신 보고를 전용 유형으로 분리해 기존 재시도 경로에 넣는다</b>.
 *
 * <p>운영 30일 중 17일 이 보고가 {@code CUSTOM} 1회 시도로 {@code Connection reset}(응답 전 끊김) 실패였고, 같은 시각 1초 뒤의 다음 전송은
 * 성공했다(10-12·10-14). 실제 보고 호출 경로({@link CandleDataFreshnessScheduler#refreshNow} → 전용 유형 → 모의 텔레그램 서버)로 본다.</p>
 *
 * <ul>
 *   <li>실패(응답 없이 연결이 닫힘) 뒤 성공 — 두 번째 시도에서 전달, 이력 성공 한 줄(유형 {@code CANDLE_REFRESH_REPORT}).</li>
 *   <li>계속 실패 — 기존 횟수(3)·대기(2초·5초)에서 멈추고 이력 최종 실패 한 줄.</li>
 *   <li>다른 {@code CUSTOM} 은 영향 없음 — 같은 실패에 1회로 끝.</li>
 * </ul>
 *
 * <p>실제로 자지 않는다 — 재시도 대기는 {@code pause} 를 바꿔 끼워 기록만 한다.</p>
 */
class CandleRefreshReportRetryTest {

    /** 요청마다의 동작 — 200 은 정상, -1 은 응답 없이 연결을 닫는다(운영의 {@code Connection reset} 과 같은 "응답 미수신"). 목록이 끝나면 마지막 값. */
    private final List<Integer> script = new CopyOnWriteArrayList<>();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<Long> pauses = new CopyOnWriteArrayList<>();
    private final AtomicInteger index = new AtomicInteger();

    private HttpServer server;
    private TelegramNotificationLogRepository logRepo;
    private TelegramNotificationService telegram;
    private CandleDataFreshnessScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int i = index.getAndIncrement();
            int status = script.get(Math.min(i, script.size() - 1));
            if (status < 0) {
                exchange.close();   // 응답 헤더 없이 닫는다 — 보낸 쪽은 응답을 못 받는다
                return;
            }
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();

        logRepo = mock(TelegramNotificationLogRepository.class);
        telegram = new TelegramNotificationService(logRepo) {
            @Override
            boolean pause(long ms) {
                pauses.add(ms);
                return true;
            }
        };
        ReflectionTestUtils.setField(telegram, "botToken", "token");
        ReflectionTestUtils.setField(telegram, "chatId", "chat");
        ReflectionTestUtils.setField(telegram, "enabled", true);
        ReflectionTestUtils.setField(telegram, "telegramApi", "http://127.0.0.1:" + server.getAddress().getPort() + "/bot");
        ReflectionTestUtils.setField(telegram, "requestTimeout", java.time.Duration.ofMillis(500));
        ReflectionTestUtils.setField(telegram, "telegramExecutor", (Executor) Runnable::run);

        // 실제 보고 호출 경로 — 캔들 이력이 아예 없는 조합 하나 → "캔들 수집 불가" 보고 한 건
        CandleDataRepository candleRepo = mock(CandleDataRepository.class);
        StrategyLogRepository strategyLogRepo = mock(StrategyLogRepository.class);
        when(candleRepo.findDataSummary()).thenReturn(new ArrayList<>());
        when(strategyLogRepo.findRecentlyWatchedCoins(any(), anyLong())).thenReturn(List.of());
        scheduler = new CandleDataFreshnessScheduler(candleRepo, strategyLogRepo, mock(DataCollectionService.class), telegram);
        ReflectionTestUtils.setField(scheduler, "coinsCsv", "KRW-BTC");
        ReflectionTestUtils.setField(scheduler, "timeframesCsv", "H1");
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "spacingSeconds", 0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private TelegramNotificationLogEntity savedOnce() {
        ArgumentCaptor<TelegramNotificationLogEntity> c = ArgumentCaptor.forClass(TelegramNotificationLogEntity.class);
        verify(logRepo, times(1)).save(c.capture());
        return c.getValue();
    }

    @Test
    @DisplayName("🔴 보고 첫 전송이 응답 없이 끊기면 다시 보내 전달된다 — 운영 30일 중 17일 미확인이던 그 보고")
    void 보고_실패후_성공() {
        script.addAll(List.of(-1, 200));

        scheduler.refreshNow();

        assertThat(received).hasSize(2);
        assertThat(received).as("두 번 모두 같은 보고(MarkdownV2 이스케이프로 KRW\\-BTC)").allMatch(b -> b.contains("BTC H1") && b.contains("캔들 수집 불가"));
        assertThat(pauses).containsExactly(2000L);
        TelegramNotificationLogEntity row = savedOnce();
        assertThat(row.getType()).isEqualTo("CANDLE_REFRESH_REPORT");
        assertThat(row.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("보고가 계속 실패하면 기존 횟수(3)·대기(2초·5초)에서 멈추고 이력에 최종 실패 한 줄")
    void 보고_계속실패() {
        script.add(-1);

        scheduler.refreshNow();

        assertThat(received).hasSize(3);
        assertThat(pauses).containsExactly(2000L, 5000L);
        TelegramNotificationLogEntity row = savedOnce();
        assertThat(row.getType()).isEqualTo("CANDLE_REFRESH_REPORT");
        assertThat(row.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("대조 — 다른 CUSTOM 은 재시도 대상이 아니다: 같은 실패에 1회로 끝, 유형도 CUSTOM 그대로")
    void 다른_CUSTOM_영향없음() {
        script.add(-1);

        telegram.sendCustomNotification("세션 LLM 분석");

        assertThat(received).hasSize(1);
        assertThat(pauses).isEmpty();
        TelegramNotificationLogEntity row = savedOnce();
        assertThat(row.getType()).isEqualTo("CUSTOM");
        assertThat(row.isSuccess()).isFalse();
    }
}
