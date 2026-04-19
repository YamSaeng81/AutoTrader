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
            sb.append(String.format("_\\.\\.\\. 외 %d건_\n", events.size() - 10));
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

    private void sendMarkdownAndLog(String text, String type, String sessionLabel) {
        telegramExecutor.execute(() -> doSendMarkdownAndLog(text, type, sessionLabel));
    }

    /** 동기 전송 — sendTestMessage()에서만 사용 */
    private boolean doSendMarkdownAndLog(String text, String type, String sessionLabel) {
        boolean success = sendMarkdown(text);
        try {
            logRepository.save(new TelegramNotificationLogEntity(type, sessionLabel, text, success));
        } catch (Exception e) {
            log.warn("[Telegram] 이력 저장 실패: {}", e.getMessage());
        }
        return success;
    }

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
                .replace(">", "\\>")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("~", "\\~");
    }

    record SendMessageRequest(String chat_id, String text, String parse_mode) {}

    record TradeEvent(
            String sessionLabel, String coinPair, String side,
            BigDecimal price, BigDecimal quantity, BigDecimal fee,
            BigDecimal realizedPnl, String reason, Instant time
    ) {}
}
