package com.cryptoautotrader.exchange.upbit;

import com.cryptoautotrader.exchange.upbit.dto.TickerData;
import com.cryptoautotrader.exchange.upbit.dto.TradeData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Upbit WebSocket API v1 클라이언트
 *
 * 실시간 시세(ticker)와 체결(trade) 데이터를 수신한다.
 * - GZIP 압축 바이너리 디코딩
 * - 자동 재연결 (지수 백오프: 1s -> 2s -> 4s, 최대 30s)
 * - Ping/Pong 120초 간격 (응답 없으면 재연결)
 * - Thread-safe 설계
 */
@Component
@Slf4j
public class UpbitWebSocketClient {

    private static final String WS_URL = "wss://api.upbit.com/websocket/v1";
    private static final long PING_INTERVAL_SECONDS = 120;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 30_000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CopyOnWriteArrayList<Consumer<TickerData>> tickerListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<TradeData>> tradeListeners = new CopyOnWriteArrayList<>();
    private volatile Consumer<Boolean> connectionStateListener;

    private volatile WebSocket webSocket;
    private volatile boolean connected = false;
    private volatile boolean shutdownRequested = false;
    private volatile List<String> subscribedCoins;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "upbit-ws-scheduler");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> pingTask;
    private volatile long lastPongTime = System.currentTimeMillis();
    private volatile long currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS;

    /**
     * WebSocket 연결 및 구독 시작
     *
     * @param coinPairs 구독할 마켓 코드 목록 (예: ["KRW-BTC", "KRW-ETH"])
     */
    public synchronized void connect(List<String> coinPairs) {
        if (connected) {
            log.warn("이미 WebSocket에 연결되어 있음. 기존 연결 종료 후 재연결합니다.");
            disconnectInternal();
        }
        this.subscribedCoins = List.copyOf(coinPairs);
        this.shutdownRequested = false;
        doConnect();
    }

    /**
     * WebSocket 연결 종료 (재연결 가능 상태 유지)
     * scheduler는 종료하지 않으므로 connect() 재호출 시 Ping 스케줄러가 정상 동작한다.
     */
    public synchronized void disconnect() {
        shutdownRequested = true;
        disconnectInternal();
        log.info("Upbit WebSocket 연결 종료 (scheduler 유지 — 재연결 가능)");
    }

    /**
     * 완전 종료 — scheduler까지 종료한다. Bean 소멸 시 Spring이 자동 호출.
     * 이 메서드 호출 후에는 connect() 재호출이 불가능하다.
     */
    @PreDestroy
    public synchronized void destroy() {
        shutdownRequested = true;
        disconnectInternal();
        scheduler.shutdown();
        log.info("Upbit WebSocket 클라이언트 완전 종료");
    }

    /**
     * WebSocket 연결 상태 조회
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 실시간 시세(ticker) 리스너 등록
     */
    public void addTickerListener(Consumer<TickerData> listener) {
        tickerListeners.add(listener);
    }

    /**
     * 실시간 체결(trade) 리스너 등록
     */
    public void addTradeListener(Consumer<TradeData> listener) {
        tradeListeners.add(listener);
    }

    /**
     * WebSocket 연결 상태 변경 콜백 등록
     * connected=true: 연결 성공, connected=false: 연결 해제/오류
     */
    public void setConnectionStateListener(Consumer<Boolean> listener) {
        this.connectionStateListener = listener;
    }

    // ========== 내부 구현 ==========

    private void doConnect() {
        log.info("Upbit WebSocket 연결 시도: {}", WS_URL);

        HttpClient httpClient = HttpClient.newHttpClient();
        ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(WS_URL), new WebSocket.Listener() {

                    @Override
                    public void onOpen(WebSocket ws) {
                        log.info("Upbit WebSocket 연결 성공");
                        webSocket = ws;
                        connected = true;
                        currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS;
                        lastPongTime = System.currentTimeMillis();
                        notifyConnectionState(true);

                        sendSubscription(ws);
                        startPingScheduler();

                        ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        binaryBuffer.write(bytes, 0, bytes.length);

                        if (last) {
                            byte[] fullMessage = binaryBuffer.toByteArray();
                            binaryBuffer.reset();
                            processMessage(fullMessage);
                        }

                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        // Upbit은 바이너리(GZIP)로 전송하지만, 텍스트 수신 대비
                        log.debug("텍스트 메시지 수신: {}", data);
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
                        lastPongTime = System.currentTimeMillis();
                        log.debug("Pong 수신");
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        log.warn("Upbit WebSocket 연결 종료: code={}, reason={}", statusCode, reason);
                        connected = false;
                        notifyConnectionState(false);
                        scheduleReconnect();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        log.error("Upbit WebSocket 오류: {}", error.getMessage(), error);
                        connected = false;
                        notifyConnectionState(false);
                        scheduleReconnect();
                    }
                })
                .exceptionally(ex -> {
                    log.error("Upbit WebSocket 연결 실패: {}", ex.getMessage(), ex);
                    connected = false;
                    notifyConnectionState(false);
                    scheduleReconnect();
                    return null;
                });
    }

    /**
     * 구독 메시지 전송
     * 형식: [{"ticket":"unique"},{"type":"ticker","codes":[...]},{"type":"trade","codes":[...]},{"format":"SIMPLE"}]
     */
    private void sendSubscription(WebSocket ws) {
        try {
            String ticket = UUID.randomUUID().toString().substring(0, 8);
            String codesJson = objectMapper.writeValueAsString(subscribedCoins);

            String subscribeMessage = String.format(
                    "[{\"ticket\":\"%s\"},{\"type\":\"ticker\",\"codes\":%s},{\"type\":\"trade\",\"codes\":%s},{\"format\":\"SIMPLE\"}]",
                    ticket, codesJson, codesJson
            );

            ws.sendText(subscribeMessage, true);
            log.info("WebSocket 구독 메시지 전송: coins={}", subscribedCoins);
        } catch (Exception e) {
            log.error("구독 메시지 전송 실패", e);
        }
    }

    /**
     * 수신된 바이너리 메시지를 GZIP 디코딩 후 파싱
     */
    private void processMessage(byte[] rawBytes) {
        try {
            String json = decompressGzip(rawBytes);
            JsonNode node = objectMapper.readTree(json);

            String type = node.has("ty") ? node.get("ty").asText() : "";

            switch (type) {
                case "ticker" -> dispatchTicker(node);
                case "trade" -> dispatchTrade(node);
                default -> log.debug("알 수 없는 메시지 타입: {}", type);
            }
        } catch (Exception e) {
            // GZIP이 아닌 경우 plain text로 재시도
            try {
                String json = new String(rawBytes);
                JsonNode node = objectMapper.readTree(json);
                String type = node.has("ty") ? node.get("ty").asText() : "";
                switch (type) {
                    case "ticker" -> dispatchTicker(node);
                    case "trade" -> dispatchTrade(node);
                    default -> log.debug("메시지 파싱 스킵: {}", json);
                }
            } catch (Exception ex) {
                log.warn("메시지 파싱 실패: {}", ex.getMessage());
            }
        }
    }

    /**
     * GZIP 압축 해제
     */
    private String decompressGzip(byte[] compressed) throws Exception {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gis.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toString("UTF-8");
        }
    }

    /**
     * ticker 데이터를 TickerData로 변환 후 리스너에 전달
     * SIMPLE 포맷 필드명 사용: cd, tp, hp, lp, pcp, atv24h, scr, c, tms
     */
    private void dispatchTicker(JsonNode node) {
        TickerData ticker = TickerData.builder()
                .code(getTextSafe(node, "cd"))
                .tradePrice(getDecimalSafe(node, "tp"))
                .highPrice(getDecimalSafe(node, "hp"))
                .lowPrice(getDecimalSafe(node, "lp"))
                .prevClosingPrice(getDecimalSafe(node, "pcp"))
                .accTradeVolume24h(getDecimalSafe(node, "atv24h"))
                .signedChangeRate(getDecimalSafe(node, "scr"))
                .change(getTextSafe(node, "c"))
                .timestamp(getInstantSafe(node, "tms"))
                .build();

        for (Consumer<TickerData> listener : tickerListeners) {
            try {
                listener.accept(ticker);
            } catch (Exception e) {
                log.error("Ticker 리스너 실행 오류: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * trade 데이터를 TradeData로 변환 후 리스너에 전달
     * SIMPLE 포맷 필드명 사용: cd, tp, tv, ab, tms
     */
    private void dispatchTrade(JsonNode node) {
        TradeData trade = TradeData.builder()
                .code(getTextSafe(node, "cd"))
                .tradePrice(getDecimalSafe(node, "tp"))
                .tradeVolume(getDecimalSafe(node, "tv"))
                .askBid(getTextSafe(node, "ab"))
                .timestamp(getInstantSafe(node, "tms"))
                .build();

        for (Consumer<TradeData> listener : tradeListeners) {
            try {
                listener.accept(trade);
            } catch (Exception e) {
                log.error("Trade 리스너 실행 오류: {}", e.getMessage(), e);
            }
        }
    }

    // ========== Ping/Pong + 재연결 ==========

    /**
     * 120초 간격으로 Ping 전송, Pong 응답이 없으면 재연결
     */
    private void startPingScheduler() {
        stopPingScheduler();
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            if (!connected || webSocket == null) return;

            long elapsed = System.currentTimeMillis() - lastPongTime;
            if (elapsed > PING_INTERVAL_SECONDS * 1000 * 2) {
                // Pong이 2 * PING_INTERVAL 동안 없으면 연결 끊김으로 판단
                log.warn("Pong 응답 없음 ({}ms). 재연결 시도.", elapsed);
                connected = false;
                scheduleReconnect();
                return;
            }

            try {
                webSocket.sendPing(ByteBuffer.allocate(0));
                log.debug("Ping 전송");
            } catch (Exception e) {
                log.warn("Ping 전송 실패: {}", e.getMessage());
                connected = false;
                scheduleReconnect();
            }
        }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopPingScheduler() {
        if (pingTask != null && !pingTask.isCancelled()) {
            pingTask.cancel(false);
        }
    }

    /**
     * 지수 백오프 재연결 스케줄링 (1s -> 2s -> 4s -> ... -> 최대 30s)
     */
    private void scheduleReconnect() {
        if (shutdownRequested) {
            log.info("종료 요청됨. 재연결 스킵.");
            return;
        }

        stopPingScheduler();

        long delay = currentReconnectDelay;
        currentReconnectDelay = Math.min(currentReconnectDelay * 2, MAX_RECONNECT_DELAY_MS);

        log.info("{}ms 후 WebSocket 재연결 시도", delay);
        scheduler.schedule(() -> {
            if (!shutdownRequested && !connected) {
                doConnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void disconnectInternal() {
        stopPingScheduler();
        connected = false;
        notifyConnectionState(false);
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "정상 종료");
            } catch (Exception e) {
                log.debug("WebSocket 종료 중 오류 (무시): {}", e.getMessage());
            }
            webSocket = null;
        }
    }

    private void notifyConnectionState(boolean isConnected) {
        Consumer<Boolean> listener = connectionStateListener;
        if (listener != null) {
            try {
                listener.accept(isConnected);
            } catch (Exception e) {
                log.warn("연결 상태 콜백 실행 오류: {}", e.getMessage());
            }
        }
    }

    // ========== JSON 유틸 ==========

    private String getTextSafe(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child != null ? child.asText() : "";
    }

    private BigDecimal getDecimalSafe(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child != null && child.isNumber()) {
            return child.decimalValue();
        }
        return BigDecimal.ZERO;
    }

    private Instant getInstantSafe(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child != null && child.isNumber()) {
            return Instant.ofEpochMilli(child.asLong());
        }
        return Instant.now();
    }
}
