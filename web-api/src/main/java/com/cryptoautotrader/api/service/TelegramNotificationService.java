package com.cryptoautotrader.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 텔레그램 봇 알림 서비스.
 * - 매수/매도 이벤트는 내부 버퍼에 적재 후 12:00 / 00:00 KST 일별 요약으로 일괄 전송.
 * - 세션 시작/종료, 손절, 거래소 장애 등 긴급 알림은 즉시 전송.
 */
@Service
@Slf4j
public class TelegramNotificationService {

    private static final String TELEGRAM_API = "https://api.telegram.org/bot";
    private static final DateTimeFormatter KST_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"));
    private static final DateTimeFormatter KST_TIME_FMT = DateTimeFormatter
            .ofPattern("HH:mm")
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

    /** 매수/매도 이벤트 버퍼 (스레드 세이프) */
    private final List<TradeEvent> tradeBuffer = new CopyOnWriteArrayList<>();

    // ── 버퍼링 대상: 매수/매도 이벤트 ─────────────────────────────────────────

    /**
     * 매수/매도 체결 이벤트를 버퍼에 적재한다.
     * 실제 전송은 12:00 / 00:00 일별 요약에서 처리된다.
     */
    public void bufferTradeEvent(String sessionLabel, String coinPair, String side,
                                  BigDecimal price, BigDecimal quantity, BigDecimal fee,
                                  BigDecimal realizedPnl, String reason) {
        tradeBuffer.add(new TradeEvent(sessionLabel, coinPair, side, price, quantity, fee, realizedPnl, reason, Instant.now()));
        log.debug("[Telegram] 거래 버퍼 적재: {} {} {} @ {}", sessionLabel, side, coinPair, price);
    }

    // ── 즉시 전송 대상: 세션/긴급 이벤트 ────────────────────────────────────────

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
                "• 시각: `%s`",
                KST_FMT.format(Instant.now()));
        return sendMarkdown(msg);
    }

    // ── 일별 요약 스케줄 ────────────────────────────────────────────────────────

    /** 매일 정오(12:00 KST) 오전 거래 요약 전송 */
    @Scheduled(cron = "0 0 12 * * *", zone = "Asia/Seoul")
    public void sendNoonSummary() {
        sendDailySummary("오전 거래 요약 (자정~정오)");
    }

    /** 매일 자정(00:00 KST) 오후 거래 요약 전송 */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void sendMidnightSummary() {
        sendDailySummary("오후 거래 요약 (정오~자정)");
    }

    private void sendDailySummary(String periodLabel) {
        if (!enabled) return;

        // 버퍼에서 현재 이벤트 전부 꺼내기 (원자적 스왑)
        List<TradeEvent> events = new ArrayList<>(tradeBuffer);
        tradeBuffer.removeAll(events);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 *%s*\n\n", periodLabel));

        if (events.isEmpty()) {
            sb.append("• 해당 시간대 매매 없음\n");
            sb.append(String.format("• 기준 시각: `%s`\n", KST_FMT.format(Instant.now())));
            sendMarkdown(sb.toString());
            log.info("[Telegram] {} - 거래 없음 요약 전송", periodLabel);
            return;
        }

        long buyCount  = events.stream().filter(e -> "BUY".equals(e.side())).count();
        long sellCount = events.stream().filter(e -> "SELL".equals(e.side())).count();

        BigDecimal totalFee = events.stream()
                .map(TradeEvent::fee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // realizedPnl 은 매도 시에만 의미 있음
        BigDecimal totalPnl = events.stream()
                .filter(e -> "SELL".equals(e.side()) && e.realizedPnl() != null)
                .map(TradeEvent::realizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        sb.append(String.format("• 매수: `%d회` / 매도: `%d회`\n", buyCount, sellCount));
        sb.append(String.format("• 누적 수수료: `%,.0f KRW`\n", totalFee.doubleValue()));
        sb.append(String.format("• 실현 손익 합계: `%s%,.0f KRW`\n",
                totalPnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "", totalPnl.doubleValue()));
        sb.append(String.format("• 기준 시각: `%s`\n\n", KST_FMT.format(Instant.now())));

        // 거래 상세 목록 (최대 10건)
        sb.append("*상세 내역*\n");
        events.stream().limit(10).forEach(e -> {
            String icon = "BUY".equals(e.side()) ? "📈" : "📉";
            String pnlStr = ("SELL".equals(e.side()) && e.realizedPnl() != null)
                    ? String.format(" | 손익 `%s%,.0f`",
                        e.realizedPnl().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "",
                        e.realizedPnl().doubleValue())
                    : "";
            sb.append(String.format("%s `%s` `%s` @ `%,.0f` \\[%s\\]%s\n",
                    icon, e.coinPair(), e.side(), e.price().doubleValue(),
                    KST_TIME_FMT.format(e.time()), pnlStr));
        });
        if (events.size() > 10) {
            sb.append(String.format("_\\.\\.\\. 외 %d건_\n", events.size() - 10));
        }

        sendMarkdown(sb.toString());
        log.info("[Telegram] {} 요약 전송 완료: 매수{}회 매도{}회", periodLabel, buyCount, sellCount);
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
     */
    private String escapeMarkdownV2(String text) {
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

    /** 버퍼에 적재되는 거래 이벤트 */
    record TradeEvent(
            String sessionLabel,
            String coinPair,
            String side,
            BigDecimal price,
            BigDecimal quantity,
            BigDecimal fee,
            BigDecimal realizedPnl,
            String reason,
            Instant time
    ) {}
}
