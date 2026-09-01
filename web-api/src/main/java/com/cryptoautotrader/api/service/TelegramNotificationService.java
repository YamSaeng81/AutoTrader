package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.TelegramNotificationLogEntity;
import com.cryptoautotrader.api.repository.TelegramNotificationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 텔레그램 봇 알림 서비스.
 * - 매수/매도 이벤트는 내부 버퍼에 적재 후 12:00 / 00:00 KST 일별 요약으로 일괄 전송.
 * - 세션별로 분리하여 각 세션 요약을 개별 메시지로 전송.
 * - 세션 시작/종료, 손절, 거래소 장애 등 긴급 알림은 즉시 전송.
 * - 전송 이력은 telegram_notification_log 테이블에 저장.
 */
@Service
@RequiredArgsConstructor
public class TelegramNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    /**
     * 텔레그램 API 베이스 URL.
     *
     * <p>{@code static final} 상수가 아니라 필드다 — 상수면 컴파일 시간에 기계어로
     * 인라이닝돼 테스트에서 스텀 서버로 돌릴 방법이 없다. 전송 실패·폴백 경로는
     * 실제 HTTP 응답에 따라 갈리므로 모의 서버 없이는 검증할 수 없다.</p>
     */
    private String telegramApi = "https://api.telegram.org/bot";

    /**
     * sendMessage 응답 대기 시간. 필드인 이유는 {@link #telegramApi} 와 같다 —
     * "응답을 못 받은" 경우의 동작(폴백하지 않는다)을 검증하려면 테스트에서
     * 짧게 줄일 수 있어야 한다. 10초를 실제로 기다리는 테스트는 아무도 안 돌린다.
     */
    private Duration requestTimeout = Duration.ofSeconds(10);
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

    private final TelegramNotificationLogRepository logRepository;

    @Autowired
    @Qualifier("telegramExecutor")
    private Executor telegramExecutor;

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
                "• 세션 ID: `%d`\n• 전략: `%s`\n• 코인: `%s`\n• 타임프레임: `%s`\n• 투자금: `%,d KRW`\n• 시각: `%s`",
                sessionId, strategyType, coinPair, timeframe, initialCapital,
                KST_FMT.format(Instant.now()));
        sendMarkdownAndLog(msg, "SESSION_START", null);
    }

    /** 매매 세션 정지 알림 */
    public void notifySessionStopped(Long sessionId, String coinPair, double returnPct, long totalAsset, boolean isEmergency) {
        String icon = isEmergency ? "🚨" : "🛑";
        String title = isEmergency ? "비상 정지" : "세션 종료";
        String msg = String.format(
                "%s *실전매매 %s*\n\n" +
                "• 세션 ID: `%d`\n• 코인: `%s`\n• 총 자산: `%,d KRW`\n• 수익률: `%s%.2f%%`\n• 시각: `%s`",
                icon, title, sessionId, coinPair, totalAsset,
                returnPct >= 0 ? "+" : "", returnPct,
                KST_FMT.format(Instant.now()));
        sendMarkdownAndLog(msg, "SESSION_STOP", "세션#" + sessionId);
    }

    /** 손절 알림 */
    public void notifyStopLoss(String coinPair, double lossPct, long sessionId) {
        String msg = String.format(
                "⚠️ *손절 실행*\n\n" +
                "• 세션 ID: `%d`\n• 코인: `%s`\n• 손실률: `%.2f%%`\n• 시각: `%s`",
                sessionId, coinPair, Math.abs(lossPct),
                KST_FMT.format(Instant.now()));
        sendMarkdownAndLog(msg, "STOP_LOSS", "세션#" + sessionId);
    }

    /**
     * 시간 초과 청산(time stop) 알림 — 2026-08-18 신설.
     *
     * <p>time stop 은 손절도 익절도 아니라 {@code STOP_LOSS} 알림 유형에 잡히지 않았고,
     * 그래서 <b>자본 회수 이벤트가 사용자에게 통지되지 않았다</b>. 08-18 LIVE time stop 을
     * 처음 켰을 때 259시간 고착 XRP 4건이 청산됐는데 알림이 한 건도 가지 않은 것이 실측 사례다.
     * 손익 부호와 무관하게 발생하므로 손실률이 아니라 손익률을 그대로 표기한다.</p>
     */
    public void notifyTimeStop(String coinPair, long heldHours, int maxHoldHours,
                               double pnlPct, long sessionId) {
        String msg = String.format(
                "⏱️ *시간 초과 청산*\n\n" +
                "• 세션 ID: `%d`\n• 코인: `%s`\n• 보유: `%d시간` (한도 `%d시간`)\n" +
                "• 손익률: `%+.2f%%`\n• 시각: `%s`",
                sessionId, coinPair, heldHours, maxHoldHours, pnlPct,
                KST_FMT.format(Instant.now()));
        sendMarkdownAndLog(msg, "TIME_STOP", "세션#" + sessionId);
    }

    /** 거래소 DOWN 알림 */
    public void notifyExchangeDown(String reason) {
        String msg = String.format(
                "🔴 *거래소 연결 끊김*\n\n" +
                "• 사유: `%s`\n• 모든 실전매매 세션이 비상 정지됩니다.\n• 시각: `%s`",
                reason, KST_FMT.format(Instant.now()));
        sendMarkdownAndLog(msg, "EXCHANGE_DOWN", null);
    }

    /** 거래소 복구 후 세션 자동 재시작 알림 */
    public void notifyExchangeRecovered(List<String> restartedSessions, List<String> failedSessions) {
        if (!enabled) return;
        StringBuilder sb = new StringBuilder();
        sb.append("🟢 *거래소 연결 복구*\n\n");
        if (!restartedSessions.isEmpty()) {
            sb.append("✅ *재시작 완료 ").append(restartedSessions.size()).append("개*\n");
            restartedSessions.forEach(s -> sb.append("• `").append(s).append("`\n"));
        }
        if (!failedSessions.isEmpty()) {
            sb.append("❌ *재시작 실패 ").append(failedSessions.size()).append("개*\n");
            failedSessions.forEach(s -> sb.append("• `").append(s).append("`\n"));
        }
        sb.append("• 시각: `").append(KST_FMT.format(Instant.now())).append("`");
        sendMarkdownAndLog(sb.toString(), "EXCHANGE_RECOVERED", null);
    }

    /** 모의투자 세션 시작 알림 */
    public void notifyPaperSessionStarted(Long sessionId, String strategyType, String coinPair, String timeframe, java.math.BigDecimal initialCapital) {
        String msg = String.format(
                "🎮 *\\[모의투자\\] 세션 시작*\n\n" +
                "• 세션 ID: `%d`\n• 전략: `%s`\n• 코인: `%s`\n• 타임프레임: `%s`\n• 초기자본: `%,.0f KRW`",
                sessionId, strategyType, coinPair, timeframe, initialCapital.doubleValue());
        sendMarkdownAndLog(msg, "SESSION_START", "[모의투자] 세션#" + sessionId);
    }

    /** 모의투자 세션 종료 알림 */
    public void notifyPaperSessionStopped(Long sessionId, String strategyName, String coinPair, java.math.BigDecimal totalKrw, double returnPct) {
        String msg = String.format(
                "🛑 *\\[모의투자\\] 세션 종료*\n\n" +
                "• 세션 ID: `%d`\n• 전략: `%s`\n• 코인: `%s`\n" +
                "• 최종 자산: `%,.0f KRW`\n• 수익률: `%s%.2f%%`",
                sessionId, strategyName, coinPair,
                totalKrw.doubleValue(),
                returnPct >= 0 ? "+" : "", returnPct);
        sendMarkdownAndLog(msg, "SESSION_STOP", "[모의투자] 세션#" + sessionId);
    }

    /** 낙폭 경고 알림 — 손절 임박 시 즉시 전송 (스팸 방지: 세션당 30분 쿨다운은 호출부에서 관리) */
    public void notifyDrawdownWarning(long sessionId, String coinPair, double pnlPct, double stopLossPct) {
        String msg = String.format(
                "📉 *낙폭 경고*\n\n" +
                "• 세션 ID: `%d`\n• 코인: `%s`\n• 현재 손실: `%.2f%%`\n• 손절 한도: `%.2f%%`\n• 시각: `%s`",
                sessionId, coinPair, Math.abs(pnlPct), stopLossPct,
                KST_FMT.format(Instant.now()));
        sendMarkdownAndLog(msg, "DRAWDOWN_WARNING", "세션#" + sessionId);
    }

    /** 리스크 한도 초과 알림 */
    public void notifyRiskLimitBreached(String reason) {
        String msg = String.format(
                "⛔ *리스크 한도 초과*\n\n• 사유: `%s`\n• 시각: `%s`",
                reason, KST_FMT.format(Instant.now()));
        sendMarkdownAndLog(msg, "RISK_LIMIT", null);
    }

    // ── 백테스트 비동기 작업 알림 ───────────────────────────────────────────────

    /**
     * 백테스트 완료 알림 — 핵심 성과 지표 + 기간/타임프레임 포함.
     * @param jobId      백테스트 Job ID
     * @param coinPair   코인 쌍 (예: KRW-BTC)
     * @param strategy   전략명
     * @param startDate  백테스트 시작일 (yyyy.MM.dd 포맷)
     * @param endDate    백테스트 종료일 (yyyy.MM.dd 포맷)
     * @param timeframe  타임프레임 (예: 1M, H1)
     * @param result     runBacktest() 반환값 (id, metrics.totalReturn 등)
     */
    public void notifyBacktestCompleted(Long jobId, String coinPair, String strategy,
                                         String startDate, String endDate, String timeframe,
                                         java.util.Map<String, Object> result) {
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> metrics = result.containsKey("metrics")
                    ? (java.util.Map<String, Object>) result.get("metrics")
                    : java.util.Map.of();

            Object totalReturn = metrics.getOrDefault("totalReturn", "N/A");
            Object winRate     = metrics.getOrDefault("winRate",     "N/A");
            Object mdd         = metrics.getOrDefault("maxDrawdown", "N/A");
            Object sharpe      = metrics.getOrDefault("sharpeRatio", "N/A");
            Object trades      = metrics.getOrDefault("totalTrades", "N/A");
            Object runId       = result.getOrDefault("id", "N/A");

            String returnStr = (totalReturn instanceof java.math.BigDecimal bd)
                    ? String.format("%s%.2f%%", bd.compareTo(java.math.BigDecimal.ZERO) >= 0 ? "+" : "", bd.doubleValue())
                    : String.valueOf(totalReturn);

            String period = startDate + " ~ " + endDate + " / " + timeframe;

            String msg = String.format(
                    "✅ *백테스트 완료*\n\n" +
                    "• Job ID: `%d`  \\|  Run ID: `%s`\n" +
                    "• 전략: `%s`\n• 코인: `%s`\n" +
                    "• 기간: `%s`\n\n" +
                    "📊 *성과 지표*\n" +
                    "• 수익률: `%s`\n" +
                    "• 승률: `%s%%`\n" +
                    "• MDD: `%s%%`\n" +
                    "• Sharpe: `%s`\n" +
                    "• 거래 횟수: `%s`\n\n" +
                    "• 완료 시각: `%s`",
                    jobId, runId,
                    escapeMarkdownV2(strategy), escapeMarkdownV2(coinPair),
                    escapeMarkdownV2(period),
                    escapeMarkdownV2(returnStr),
                    escapeMarkdownV2(String.valueOf(winRate)),
                    escapeMarkdownV2(String.valueOf(mdd)),
                    escapeMarkdownV2(String.valueOf(sharpe)),
                    escapeMarkdownV2(String.valueOf(trades)),
                    KST_FMT.format(java.time.Instant.now()));

            sendMarkdownAndLog(msg, "BACKTEST_COMPLETE", "백테스트#" + jobId);
        } catch (Exception e) {
            log.warn("[Telegram] 백테스트 완료 알림 전송 실패: jobId={}, error={}", jobId, e.getMessage());
        }
    }

    /**
     * 백테스트 실패 알림 — 오류 원인 + 기간/타임프레임 포함.
     * @param jobId      백테스트 Job ID
     * @param coinPair   코인 쌍
     * @param strategy   전략명
     * @param startDate  백테스트 시작일 (yyyy.MM.dd 포맷)
     * @param endDate    백테스트 종료일 (yyyy.MM.dd 포맷)
     * @param timeframe  타임프레임
     * @param cause      발생한 예외
     */
    public void notifyBacktestFailed(Long jobId, String coinPair, String strategy,
                                      String startDate, String endDate, String timeframe,
                                      Throwable cause) {
        try {
            String errorMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            StackTraceElement[] stack = cause.getStackTrace();
            String location = (stack != null && stack.length > 0)
                    ? stack[0].getClassName() + "." + stack[0].getMethodName() + ":" + stack[0].getLineNumber()
                    : "알 수 없음";

            String period = startDate + " ~ " + endDate + " / " + timeframe;

            String msg = String.format(
                    "❌ *백테스트 실패*\n\n" +
                    "• Job ID: `%d`\n" +
                    "• 전략: `%s`\n• 코인: `%s`\n" +
                    "• 기간: `%s`\n\n" +
                    "🔴 *오류 정보*\n" +
                    "• 오류 유형: `%s`\n" +
                    "• 오류 메시지: `%s`\n" +
                    "• 발생 위치: `%s`\n\n" +
                    "• 시각: `%s`",
                    jobId,
                    escapeMarkdownV2(strategy), escapeMarkdownV2(coinPair),
                    escapeMarkdownV2(period),
                    escapeMarkdownV2(cause.getClass().getSimpleName()),
                    escapeMarkdownV2(errorMsg.length() > 200 ? errorMsg.substring(0, 200) + "..." : errorMsg),
                    escapeMarkdownV2(location),
                    KST_FMT.format(java.time.Instant.now()));

            sendMarkdownAndLog(msg, "BACKTEST_FAILED", "백테스트#" + jobId);
        } catch (Exception e) {
            log.warn("[Telegram] 백테스트 실패 알림 전송 실패: jobId={}, error={}", jobId, e.getMessage());
        }
    }

    /**
     * Walk-Forward 배치 완료 알림.
     * 백테스트 완료 알림과 별도 포맷: verdict 분포(ROBUST/CAUTION/OVERFITTING) +
     * 코인×전략별 OOS 수익률과 판정 목록을 표시한다.
     *
     * @param jobId    배치 Job ID
     * @param results  executeWalkForwardBatchAsync에서 수집한 결과 리스트
     *                 각 요소: { coin, strategy, verdict, oosTotalReturn, overfittingScore } 또는 { coin, strategy, error }
     * @param period   "yyyy.MM.dd ~ yyyy.MM.dd / TF" 형식 기간 문자열
     */
    /**
     * Walk-Forward 배치 완료 알림.
     * verdict 값: ACCEPTABLE(양호) / CAUTION(주의) / OVERFITTING(과적합)
     * OOS 수익률: windows[].outSample.totalReturn 합산
     */
    @SuppressWarnings("unchecked")
    public void notifyWalkForwardBatchCompleted(Long jobId, java.util.List<java.util.Map<String, Object>> results, String period) {
        try {
            long total      = results.size();
            long failCnt    = results.stream().filter(r -> r.containsKey("error")).count();
            long acceptable = results.stream().filter(r -> "ACCEPTABLE".equals(r.get("verdict"))).count();
            long caution    = results.stream().filter(r -> "CAUTION".equals(r.get("verdict"))).count();
            long overfit    = total - failCnt - acceptable - caution;

            StringBuilder detail = new StringBuilder();
            for (var r : results) {
                String coin     = String.valueOf(r.getOrDefault("coin", "?")).replace("KRW-", "");
                String strategy = String.valueOf(r.getOrDefault("strategy", "?"));
                // 전략명 축약
                strategy = strategy.replace("COMPOSITE_MOMENTUM_ICHIMOKU_V2", "CMI_V2")
                                   .replace("COMPOSITE_MOMENTUM_ICHIMOKU",    "CMI_V1")
                                   .replace("COMPOSITE_MOMENTUM",             "C_MOM")
                                   .replace("COMPOSITE_BREAKOUT",             "C_BRK");
                if (r.containsKey("error")) {
                    detail.append(String.format("  ❌ %s/%s — 오류\n", coin, strategy));
                } else {
                    String verdict = String.valueOf(r.getOrDefault("verdict", "?"));
                    String icon    = "ACCEPTABLE".equals(verdict) ? "✅" : "CAUTION".equals(verdict) ? "⚠️" : "🔴";

                    // OOS 수익률 합산: windows[].outSample.totalReturn (BigDecimal, 소수 형태 e.g. 0.1254)
                    double oosSum = 0.0;
                    var windows = r.get("windows");
                    if (windows instanceof java.util.List<?> wList) {
                        for (var w : wList) {
                            if (w instanceof java.util.Map<?, ?> wm) {
                                var outSample = wm.get("outSample");
                                if (outSample instanceof java.util.Map<?, ?> om) {
                                    Object tr = om.get("totalReturn");
                                    if (tr instanceof java.math.BigDecimal bd) oosSum += bd.doubleValue();
                                    else if (tr instanceof Number n) oosSum += n.doubleValue();
                                }
                            }
                        }
                    }
                    String oosStr = String.format("%+.2f%%", oosSum * 100);

                    Object score = r.get("overfittingScore");
                    String scoreStr = (score instanceof java.math.BigDecimal bd2)
                            ? String.format("%.4f", bd2.doubleValue())
                            : (score instanceof Number n3) ? String.format("%.4f", n3.doubleValue()) : "-";

                    detail.append(String.format("  %s %s/%s OOS:%s score:%s\n",
                            icon, coin, strategy, oosStr, scoreStr));
                }
            }

            String msg = String.format(
                    "📈 *Walk\\-Forward 배치 완료*\n\n" +
                    "• Job ID: `%d`  \\|  총 `%d`개 조합\n" +
                    "• 기간: `%s`\n\n" +
                    "📊 *판정 요약*\n" +
                    "• ✅ ACCEPTABLE: `%d`개\n" +
                    "• ⚠️ CAUTION: `%d`개\n" +
                    "• 🔴 OVERFITTING: `%d`개\n" +
                    "• ❌ 오류: `%d`개\n\n" +
                    "📋 *코인×전략 결과*\n%s\n" +
                    "• 완료 시각: `%s`",
                    jobId, total,
                    escapeMarkdownV2(period),
                    acceptable, caution, overfit, failCnt,
                    detail.toString(),
                    KST_FMT.format(java.time.Instant.now()));

            sendMarkdownAndLog(msg, "WF_BATCH_COMPLETE", "WF배치#" + jobId);
        } catch (Exception e) {
            log.warn("[Telegram] Walk-Forward 배치 완료 알림 전송 실패: jobId={}, error={}", jobId, e.getMessage());
        }
    }

    /** 테스트 메시지 전송 — 결과 확인이 필요하므로 동기 전송 */
    public boolean sendTestMessage() {
        String msg = String.format(
                "✅ *텔레그램 알림 연동 테스트*\n\n" +
                "크립토 자동매매 시스템이 정상적으로\n텔레그램 알림에 연결되었습니다! 🎉\n\n• 시각: `%s`",
                KST_FMT.format(Instant.now()));
        return doSendMarkdownAndLog(msg, "TEST", null);
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

        List<TradeEvent> events = new ArrayList<>(tradeBuffer);
        tradeBuffer.removeAll(events);

        if (events.isEmpty()) {
            String msg = String.format("📊 *%s*\n\n• 해당 시간대 매매 없음\n• 기준 시각: `%s`\n",
                    periodLabel, KST_FMT.format(Instant.now()));
            sendMarkdownAndLog(msg, "TRADE_SUMMARY", null);
            log.info("[Telegram] {} - 거래 없음 요약 전송", periodLabel);
            return;
        }

        // 세션별로 그룹화하여 개별 메시지 전송
        Map<String, List<TradeEvent>> bySession = events.stream()
                .collect(Collectors.groupingBy(TradeEvent::sessionLabel, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<TradeEvent>> entry : bySession.entrySet()) {
            String sessionLabel = entry.getKey();
            List<TradeEvent> sessionEvents = entry.getValue();
            String msg = buildSessionSummary(periodLabel, sessionLabel, sessionEvents);
            sendMarkdownAndLog(msg, "TRADE_SUMMARY", sessionLabel);
            log.info("[Telegram] {} {} 요약 전송 완료: {}건", periodLabel, sessionLabel, sessionEvents.size());
        }
    }

    private String buildSessionSummary(String periodLabel, String sessionLabel, List<TradeEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 *%s*\n", periodLabel));
        sb.append(String.format("📌 세션: `%s`\n\n", escapeMarkdownV2(sessionLabel)));

        long buyCount  = events.stream().filter(e -> "BUY".equals(e.side())).count();
        long sellCount = events.stream().filter(e -> "SELL".equals(e.side())).count();

        BigDecimal totalFee = events.stream()
                .map(TradeEvent::fee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPnl = events.stream()
                .filter(e -> "SELL".equals(e.side()) && e.realizedPnl() != null)
                .map(TradeEvent::realizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        sb.append(String.format("• 매수: `%d회` / 매도: `%d회`\n", buyCount, sellCount));
        sb.append(String.format("• 누적 수수료: `%,.0f KRW`\n", totalFee.doubleValue()));
        sb.append(String.format("• 실현 손익 합계: `%s%,.0f KRW`\n",
                totalPnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "", totalPnl.doubleValue()));
        sb.append(String.format("• 기준 시각: `%s`\n\n", KST_FMT.format(Instant.now())));

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
            sb.append(String.format("\\.\\.\\. 외 %d건\n", events.size() - 10));
        }

        return sb.toString();
    }

    // ── 전송 이력 조회 ────────────────────────────────────────────────────────

    public Page<TelegramNotificationLogEntity> getLogs(int page, int size) {
        return logRepository.findAllByOrderBySentAtDesc(PageRequest.of(page, size));
    }

    // ── 내부 전송 ────────────────────────────────────────────────────────────

    /**
     * 비동기 전송 — telegramExecutor에 제출 후 즉시 반환.
     * 텔레그램 서버 지연/장애가 매매 루프를 블로킹하지 않도록 분리.
     */
    /** 범용 알림 — 분류 가능한 type/session 이 없는 경우 */
    public void sendCustomNotification(String message) {
        sendMarkdownAndLog(message, "CUSTOM", "system");
    }

    // ── 데이터 수집 완료 알림 ─────────────────────────────────────────────────

    /**
     * 배치 캔들 수집 완료 시 텔레그램으로 결과를 전송한다.
     *
     * @param timeframe      타임프레임 (H1, M5 등)
     * @param startDate      수집 시작일
     * @param endDate        수집 종료일
     * @param results        코인별 수집 결과
     * @param totalDurationMs 전체 소요 시간 (ms)
     */
    public void notifyDataCollectionCompleted(String timeframe,
                                               java.time.LocalDate startDate,
                                               java.time.LocalDate endDate,
                                               java.util.List<com.cryptoautotrader.api.dto.CoinCollectResult> results,
                                               long totalDurationMs) {
        if (!enabled) return;
        try {
            long successCount = results.stream().filter(com.cryptoautotrader.api.dto.CoinCollectResult::isSuccess).count();
            long totalCandleCount = results.stream().mapToLong(com.cryptoautotrader.api.dto.CoinCollectResult::getCandleCount).sum();

            StringBuilder sb = new StringBuilder();
            sb.append("✅ *데이터 수집 완료*\n\n");
            sb.append("📅 기간: `").append(startDate).append(" ~ ").append(endDate).append("`\n");
            sb.append("⏱ 타임프레임: `").append(formatTimeframe(timeframe)).append("`\n");
            sb.append("━━━━━━━━━━━━━━━━\n");

            for (com.cryptoautotrader.api.dto.CoinCollectResult r : results) {
                if (r.isSuccess()) {
                    sb.append("✅ ").append(r.getCoinPair())
                      .append(": `").append(String.format("%,d", r.getCandleCount())).append("개`")
                      .append(" (").append(formatDuration(r.getDurationMs())).append(")\n");
                } else {
                    sb.append("❌ ").append(r.getCoinPair()).append(": 실패");
                    if (r.getErrorMessage() != null && !r.getErrorMessage().isBlank()) {
                        String errShort = r.getErrorMessage().length() > 50
                                ? r.getErrorMessage().substring(0, 50) + "…"
                                : r.getErrorMessage();
                        sb.append(" — `").append(errShort).append("`");
                    }
                    sb.append("\n");
                }
            }

            sb.append("━━━━━━━━━━━━━━━━\n");
            sb.append("• 총 캔들: `").append(String.format("%,d", totalCandleCount)).append("개`\n");
            sb.append("• 소요시간: `").append(formatDuration(totalDurationMs)).append("`\n");
            sb.append("• 성공: `").append(successCount).append("/").append(results.size()).append("` 코인\n");
            sb.append("• 시각: `").append(KST_FMT.format(Instant.now())).append("`");

            sendMarkdownAndLog(sb.toString(), "DATA_COLLECTION", "데이터수집");
        } catch (Exception e) {
            log.warn("[Telegram] 데이터 수집 완료 알림 전송 실패: {}", e.getMessage());
        }
    }

    private static String formatTimeframe(String tf) {
        return switch (tf.toUpperCase()) {
            case "M1"  -> "1분봉";
            case "M5"  -> "5분봉";
            case "M15" -> "15분봉";
            case "M30" -> "30분봉";
            case "H1"  -> "1시간봉";
            case "H4"  -> "4시간봉";
            case "D1"  -> "일봉";
            default    -> tf;
        };
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "초";
        long minutes = seconds / 60;
        long remaining = seconds % 60;
        return minutes + "분 " + (remaining > 0 ? remaining + "초" : "");
    }

    /**
     * 전송 실패 시 재시도하는 알림 유형 — 유실되면 사람이 개입할 기회 자체가 사라지는 것들.
     *
     * <p><b>왜 필요한가 (2026-08-06 운영 DB 실측)</b>: 08-04 18:32 세션 41의 {@code STOP_LOSS}
     * 알림이 {@code success=false}로 유실됐다. 같은 급락으로 세션 43도 동시에 손절됐고 그쪽
     * 알림은 <b>1초 뒤 성공</b>했다 — 두 건이 1초 안에 몰려 텔레그램 레이트리밋에 걸린 것으로
     * 보인다. 동적 세션 7개가 워치리스트를 공유해 같은 코인을 동시에 들고 있으므로(같은 날
     * 39·45가 DOGE 동시 진입) <b>손절이 여러 건 동시에 터지는 것은 예외가 아니라 기본 패턴</b>
     * 이고, 그때마다 뒤쪽 알림이 조용히 사라진다.</p>
     *
     * <p>요약(TRADE_SUMMARY) 같은 정기 알림은 다음 주기에 다시 오므로 재시도하지 않는다.</p>
     */
    private static final Set<String> RETRY_TYPES = Set.of("STOP_LOSS", "SESSION_STOP");

    /** 재시도 총 횟수(최초 시도 포함) */
    private static final int RETRY_MAX_ATTEMPTS = 3;

    /** 재시도 간 대기(ms) — 레이트리밋이 풀릴 시간을 준다. 최대 추가 지연 7초. */
    private static final long[] RETRY_BACKOFF_MS = {2000L, 5000L};

    /** 테스트에서만 0으로 낮춘다 — 재시도 횟수 검증에 실제 대기가 필요하지 않다. */
    long backoffMs(int attemptIndex) {
        return RETRY_BACKOFF_MS[Math.min(attemptIndex, RETRY_BACKOFF_MS.length - 1)];
    }

    private void sendMarkdownAndLog(String text, String type, String sessionLabel) {
        telegramExecutor.execute(() -> doSendMarkdownAndLog(text, type, sessionLabel));
    }

    /** 동기 전송 — sendTestMessage()에서만 사용 */
    private boolean doSendMarkdownAndLog(String text, String type, String sessionLabel) {
        boolean success = sendWithRetry(text, type);
        try {
            logRepository.save(new TelegramNotificationLogEntity(type, sessionLabel, text, success));
        } catch (Exception e) {
            log.warn("[Telegram] 이력 저장 실패: {}", e.getMessage());
        }
        return success;
    }

    /**
     * {@link #RETRY_TYPES}에 해당하면 실패 시 재시도하고, 그 외 유형은 1회만 시도한다.
     *
     * <p>이 메서드는 {@code telegramExecutor}(코어 1) 위에서 돈다 — 대기 중 다른 알림은
     * 큐(50)에 쌓일 뿐 유실되지 않으며, 중요 알림이 먼저 나가는 편이 낫다.</p>
     */
    // 테스트에서 백오프를 0으로 낮춰 재시도 로직만 검증할 수 있게 package-private
    boolean sendWithRetry(String text, String type) {
        boolean retryable = type != null && RETRY_TYPES.contains(type);
        int attempts = retryable ? RETRY_MAX_ATTEMPTS : 1;

        for (int i = 0; i < attempts; i++) {
            if (sendMarkdown(text)) {
                if (i > 0) {
                    log.info("[Telegram] {} 재전송 성공 ({}번째 시도)", type, i + 1);
                }
                return true;
            }
            if (i < attempts - 1) {
                long backoff = backoffMs(i);
                log.warn("[Telegram] {} 전송 실패 — {}ms 후 재시도 ({}/{})",
                        type, backoff, i + 1, attempts);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        if (retryable) {
            log.error("[Telegram] 🔴 {} 알림 {}회 전송 실패 — 유실됨", type, attempts);
        }
        return false;
    }

    /**
     * Markdown 형식 메시지 전송.
     *
     * <p><b>API 가 거부하면 서식 없이 한 번 다시 보낸다 (2026-09-01)</b>.
     * {@link #escapeMarkdownV2} 는 {@code *} {@code [} {@code ]} <code>`</code> 를 일부러
     * 이스케이프하지 않는다 — 알림 템플릿이 그 문자로 굵게·코드 서식을 쓰기 때문이다.
     * 템플릿은 짝을 맞춰 쓰니 안전하지만, <b>밖에서 온 텍스트</b>(LLM 응답, 외부 오류 문구)는
     * 짝이 안 맞을 수 있고 그러면 텔레그램이 400 을 돌려 <b>메시지가 통째로 사라진다</b>.
     * 서식을 잃는 편이 내용을 잃는 것보다 낫다.</p>
     *
     * <p><b>전송 계층 오류(타임아웃 등)에는 폴백하지 않는다</b> — 응답을 못 받았을 뿐
     * 메시지는 이미 도착했을 수 있고, 그 상태에서 재전송하면 중복 알림이 된다.
     * 실제로 2026-09-01 10:12 세션 LLM 분석은 {@code success=false} 로 기록됐지만
     * 메시지는 정상 도착했다 — 응답 수신에만 실패한 경우다.</p>
     *
     * @return 전송 성공 여부
     */
    public boolean sendMarkdown(String text) {
        if (!enabled) {
            log.debug("[Telegram] 알림 비활성화 상태. 메시지 스킵: {}", text.substring(0, Math.min(50, text.length())));
            return true;
        }
        int status = post(escapeMarkdownV2(text), "MarkdownV2");
        if (status == 200) {
            log.info("[Telegram] 메시지 전송 성공");
            return true;
        }
        if (status > 0) {
            log.warn("[Telegram] MarkdownV2 거부(HTTP {}) — 서식 없이 재전송한다", status);
            return sendPlain(text);
        }
        return false;   // 응답 미수신 — 이미 도착했을 수 있어 재전송하지 않는다
    }

    /**
     * 서식 없이 그대로 전송한다. {@code parse_mode} 를 붙이지 않으므로 어떤 문자가
     * 들어있든 파싱 오류가 나지 않는다.
     */
    boolean sendPlain(String text) {
        if (!enabled) return true;
        return post(text, null) == 200;
    }

    /**
     * sendMessage 호출 — HTTP 상태코드를 그대로 돌려준다.
     *
     * <p><b>응답을 받지 못한 경우에만 {@code -1}</b> 이다. "거부당함"과 "모름"을 구별해야
     * 폴백 여부를 정할 수 있다 — 둘을 같은 {@code false} 로 뭉개면 중복 발송을 막을 수 없다.</p>
     */
    private int post(String text, String parseMode) {
        try {
            String body = objectMapper.writeValueAsString(
                    new SendMessageRequest(chatId, text, parseMode));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(telegramApi + botToken + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(requestTimeout)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[Telegram] 메시지 전송 거부: HTTP {} / {}", response.statusCode(), response.body());
            }
            return response.statusCode();
        } catch (Exception e) {
            log.error("[Telegram] 메시지 전송 중 오류(응답 미수신): {}", e.getMessage());
            return -1;
        }
    }

    private String escapeMarkdownV2(String text) {
        return text
                .replace("_", "\\_")
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
                .replace(">", "\\>")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("~", "\\~");
    }

    // parse_mode 가 null 이면 필드 자체를 빼고 보낸다 — 평문 전송은 서식을
    // 지정하지 않는 것이지 "null 이라는 서식"을 지정하는 게 아니다.
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record SendMessageRequest(String chat_id, String text, String parse_mode) {}

    record TradeEvent(
            String sessionLabel, String coinPair, String side,
            BigDecimal price, BigDecimal quantity, BigDecimal fee,
            BigDecimal realizedPnl, String reason, Instant time
    ) {}
}
