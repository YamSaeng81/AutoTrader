package com.cryptoautotrader.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 텔레그램 봇 알림 서비스.
 * Telegram Bot API를 직접 HTTP 호출 (추가 라이브러리 불필요).
 */
@Service
@Slf4j
public class TelegramNotificationService {

    private static final String TELEGRAM_API = "https://api.telegram.org/bot";
    private static final DateTimeFormatter KST_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"));

    @Value("${telegram.bot-token}")
    private String botToken;

    @Value("${telegram.chat-id}")
    private String chatId;

    @Value("${telegram.enabled:true}")
    private boolean enabled;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── 공개 알림 메서드 ─────────────────────────────────────────────────────

    /** 매매 세션 시작 알림 */
    public void notifySessionStarted(Long sessionId, String strategyType, String coinPair, String timeframe, long initialCapital) {
        String msg = String.format(
                "🚀 *실전매매 세션 시작*\n\n" +
                "• 세션 ID: `%d`\n" +
                "• 전략: `%s`\n" +
                "• 코인: `%s`\n" +
                "• 타임프레임: `%s`\n" +
                "• 투자금: `%,d KRW`\n" +
                "• 시각: `%s`",
                sessionId, strategyType, coinPair, timeframe, initialCapital,
                KST_FMT.format(Instant.now()));
        sendMarkdown(msg);
    }

    /** 매매 세션 정지 알림 */
    public void notifySessionStopped(Long sessionId, String coinPair, double returnPct, long totalAsset, boolean isEmergency) {
        String icon = isEmergency ? "🚨" : "🛑";
        String title = isEmergency ? "비상 정지" : "세션 종료";
        String msg = String.format(
                "%s *실전매매 %s*\n\n" +
                "• 세션 ID: `%d`\n" +
                "• 코인: `%s`\n" +
                "• 총 자산: `%,d KRW`\n" +
                "• 수익률: `%s%.2f%%`\n" +
                "• 시각: `%s`",
                icon, title, sessionId, coinPair, totalAsset,
                returnPct >= 0 ? "+" : "", returnPct,
                KST_FMT.format(Instant.now()));
        sendMarkdown(msg);
    }

    /** 매수 주문 체결 알림 */
    public void notifyOrderFilled(String coinPair, String side, double price, double quantity, String reason) {
        String icon = "BUY".equals(side) ? "📈" : "📉";
        String label = "BUY".equals(side) ? "매수 체결" : "매도 체결";
        String msg = String.format(
                "%s *%s*\n\n" +
                "• 코인: `%s`\n" +
                "• 가격: `%,.0f KRW`\n" +
                "• 수량: `%.8f`\n" +
                "• 금액: `%,.0f KRW`\n" +
                "• 사유: `%s`\n" +
                "• 시각: `%s`",
                icon, label, coinPair, price, quantity, price * quantity,
                reason != null ? reason : "-",
                KST_FMT.format(Instant.now()));
        sendMarkdown(msg);
    }

    /** 손절 알림 */
    public void notifyStopLoss(String coinPair, double lossPct, long sessionId) {
        String msg = String.format(
                "⚠️ *손절 실행*\n\n" +
                "• 세션 ID: `%d`\n" +
                "• 코인: `%s`\n" +
                "• 손실률: `%.2f%%`\n" +
                "• 시각: `%s`",
                sessionId, coinPair, Math.abs(lossPct),
                KST_FMT.format(Instant.now()));
        sendMarkdown(msg);
    }

    /** 거래소 DOWN 알림 */
    public void notifyExchangeDown() {
        String msg = String.format(
                "🔴 *거래소 연결 끊김*\n\n" +
                "Upbit WebSocket 연결이 중단되었습니다.\n" +
                "모든 실전매매 세션이 비상 정지됩니다.\n" +
                "• 시각: `%s`",
                KST_FMT.format(Instant.now()));
        sendMarkdown(msg);
    }

    /** 리스크 한도 초과 알림 */
    public void notifyRiskLimitBreached(String reason) {
        String msg = String.format(
                "⛔ *리스크 한도 초과*\n\n" +
                "• 사유: `%s`\n" +
                "• 시각: `%s`",
                reason, KST_FMT.format(Instant.now()));
        sendMarkdown(msg);
    }

    /** 테스트 메시지 전송 */
    public boolean sendTestMessage() {
        String msg = String.format(
                "✅ *텔레그램 알림 연동 테스트*\n\n" +
                "크립토 자동매매 시스템이 정상적으로\n" +
                "텔레그램 알림에 연결되었습니다! 🎉\n\n" +
                "• 봇: @AweSomeYambot\n" +
                "• 시각: `%s`",
                KST_FMT.format(Instant.now()));
        return sendMarkdown(msg);
    }

    // ── 내부 전송 ────────────────────────────────────────────────────────────

    /**
     * Markdown 형식 메시지 전송.
     * @return 전송 성공 여부
     */
    public boolean sendMarkdown(String text) {
        if (!enabled) {
            log.debug("[Telegram] 알림 비활성화 상태. 메시지 스킵: {}", text.substring(0, Math.min(50, text.length())));
            return true;
        }
        try {
            String body = objectMapper.writeValueAsString(new SendMessageRequest(chatId, text, "MarkdownV2"));
            // MarkdownV2 특수문자 이스케이프 처리
            String escapedBody = objectMapper.writeValueAsString(
                    new SendMessageRequest(chatId, escapeMarkdownV2(text), "MarkdownV2"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TELEGRAM_API + botToken + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(escapedBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("[Telegram] 메시지 전송 성공");
                return true;
            } else {
                log.warn("[Telegram] 메시지 전송 실패: HTTP {} / {}", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("[Telegram] 메시지 전송 중 오류: {}", e.getMessage());
            return false;
        }
    }

    /**
     * MarkdownV2에서 이스케이프해야 할 특수문자 처리.
     * 단, * ` _ [] () ~ 등 마크다운 문법 문자는 제외.
     */
    private String escapeMarkdownV2(String text) {
        // MarkdownV2 이스케이프: . ! # + - = | { } 등
        return text
                .replace(".", "\\.")
                .replace("!", "\\!")
                .replace("#", "\\#")
                .replace("+", "\\+")
                .replace("-", "\\-")
                .replace("=", "\\=")
                .replace("|", "\\|")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("<", "\\<")
                .replace(">", "\\>");
    }

    /** 텔레그램 sendMessage 요청 바디 */
    record SendMessageRequest(
            String chat_id,
            String text,
            String parse_mode
    ) {}
}
