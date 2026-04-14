package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.ExchangeHealthResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 거래소 헬스 모니터
 * - 30초마다 Upbit API latency 측정
 * - 상태: UP (< 3초), DEGRADED (3~10초), DOWN (응답 없음)
 * - DOWN 감지 시 ExchangeDownEvent 발행 → LiveTradingService가 리스닝하여 자동 정지
 * - 최근 5분 latency 이력 유지 (최대 10건)
 */
@Service
@Slf4j
public class ExchangeHealthMonitor {

    private static final String HEALTH_CHECK_URL = "https://api.upbit.com/v1/market/all";
    private static final long DEGRADED_THRESHOLD_MS = 3000;
    private static final int MAX_LATENCY_HISTORY = 10;

    private final HttpClient httpClient;
    private final ApplicationEventPublisher eventPublisher;
    private final List<Long> recentLatencies = new CopyOnWriteArrayList<>();

    private static final int DOWN_THRESHOLD = 3; // 연속 N회 실패 시 DOWN 선언

    private volatile String status = "UP";
    private volatile long latencyMs = 0;
    private volatile Instant lastCheckedAt;
    private volatile boolean webSocketConnected = false;
    private volatile int consecutiveFailCount = 0;

    public ExchangeHealthMonitor(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 30초마다 거래소 상태 체크
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void checkHealth() {
        long start = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(HEALTH_CHECK_URL))
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            latencyMs = elapsed;
            lastCheckedAt = Instant.now();
            addLatency(elapsed);

            if (response.statusCode() == 200) {
                consecutiveFailCount = 0;
                if (elapsed > DEGRADED_THRESHOLD_MS) {
                    updateStatus("DEGRADED");
                    log.warn("거래소 응답 지연: {}ms", elapsed);
                } else {
                    updateStatus("UP");
                }
            } else {
                consecutiveFailCount++;
                log.warn("거래소 비정상 응답: status={}, latency={}ms (연속 실패 {}/{}회)",
                        response.statusCode(), elapsed, consecutiveFailCount, DOWN_THRESHOLD);
                if (consecutiveFailCount >= DOWN_THRESHOLD) {
                    updateStatus("DOWN");
                } else {
                    updateStatus("DEGRADED");
                }
            }

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            latencyMs = elapsed;
            lastCheckedAt = Instant.now();
            addLatency(elapsed);
            consecutiveFailCount++;
            log.warn("거래소 연결 실패 ({}/{}회): {} ({}ms)",
                    consecutiveFailCount, DOWN_THRESHOLD, e.getMessage(), elapsed);
            if (consecutiveFailCount >= DOWN_THRESHOLD) {
                updateStatus("DOWN");
                log.error("거래소 연속 {}회 실패 — DOWN 선언", consecutiveFailCount);
            } else {
                updateStatus("DEGRADED");
            }
        }
    }

    /**
     * 현재 거래소 상태 조회
     */
    public ExchangeHealthResponse getHealthStatus() {
        return ExchangeHealthResponse.builder()
                .status(status)
                .latencyMs(latencyMs)
                .webSocketConnected(webSocketConnected)
                .lastCheckedAt(lastCheckedAt)
                .recentLatencies(List.copyOf(recentLatencies))
                .build();
    }

    /**
     * 현재 상태 문자열 반환 (TradingStatusResponse 용)
     */
    public String getStatus() {
        return status;
    }

    /**
     * WebSocket 연결 상태 갱신 (UpbitWebSocketClient에서 호출)
     */
    public void setWebSocketConnected(boolean connected) {
        this.webSocketConnected = connected;
        if (!connected) {
            log.warn("WebSocket 연결 해제 감지");
        }
    }

    // ── 내부 메서드 ───────────────────────────────────────────

    private void updateStatus(String newStatus) {
        String oldStatus = this.status;
        this.status = newStatus;

        if (!oldStatus.equals(newStatus)) {
            log.info("거래소 상태 변경: {} → {}", oldStatus, newStatus);

            // DOWN 감지 시 이벤트 발행 → LiveTradingService가 리스닝
            if ("DOWN".equals(newStatus)) {
                String reason = String.format("Upbit API 연속 %d회 연결 실패", consecutiveFailCount);
                log.error("거래소 DOWN 감지 — ExchangeDownEvent 발행 ({})", reason);
                eventPublisher.publishEvent(new ExchangeDownEvent(this, reason));
            }

            // DOWN 후 UP 복구 시 이벤트 발행 → LiveTradingService가 세션 자동 재시작
            if ("UP".equals(newStatus) && "DOWN".equals(oldStatus)) {
                log.info("거래소 DOWN → UP 복구 감지 — ExchangeRecoveredEvent 발행");
                eventPublisher.publishEvent(new ExchangeRecoveredEvent(this, oldStatus));
            }
        }
    }

    private void addLatency(long latency) {
        recentLatencies.add(latency);
        // 최근 10건만 유지 (약 5분치)
        while (recentLatencies.size() > MAX_LATENCY_HISTORY) {
            recentLatencies.remove(0);
        }
    }
}
