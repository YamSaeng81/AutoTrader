package com.cryptoautotrader.api.discord;

import com.cryptoautotrader.api.entity.NewsItemCacheEntity;
import com.cryptoautotrader.api.llm.LlmResponse;
import com.cryptoautotrader.api.llm.LlmTask;
import com.cryptoautotrader.api.llm.LlmTaskRouter;
import com.cryptoautotrader.api.news.NewsAggregatorService;
import com.cryptoautotrader.api.report.AnalysisReport;
import com.cryptoautotrader.api.report.LogAnalyzerService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Discord 모닝 브리핑 메시지 빌더.
 *
 * <p>채널별 구성:
 * <ul>
 *   <li>TRADING_REPORT — 최근 12h 성과·레짐·추천</li>
 *   <li>CRYPTO_NEWS    — 코인 뉴스 LLM 요약</li>
 *   <li>ECONOMY_NEWS   — 경제 뉴스 LLM 요약</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MorningBriefingComposer {

    private static final Logger log = LoggerFactory.getLogger(MorningBriefingComposer.class);
    private static final DateTimeFormatter DATE_KST =
            DateTimeFormatter.ofPattern("yyyy년 MM월 dd일").withZone(ZoneId.of("Asia/Seoul"));

    private final LogAnalyzerService logAnalyzer;
    private final NewsAggregatorService newsAggregator;
    private final LlmTaskRouter llmTaskRouter;
    private final DiscordWebhookClient discordClient;

    /**
     * 모든 활성 채널에 모닝 브리핑을 전송한다.
     */
    public void sendAll() {
        sendSelected(Set.of("TRADING_REPORT", "CRYPTO_NEWS", "ECONOMY_NEWS", "ALERT"));
    }

    /**
     * 지정한 채널 타입에만 브리핑을 전송한다.
     *
     * @param channels 전송할 채널 타입 집합 (TRADING_REPORT / CRYPTO_NEWS / ECONOMY_NEWS / ALERT)
     */
    public void sendSelected(Set<String> channels) {
        log.info("[MorningBriefing] 선택 전송 시작 — channels={}", channels);
        Instant now  = Instant.now();
        Instant from = now.minus(12, ChronoUnit.HOURS);

        if (channels.contains("TRADING_REPORT")) sendTradingReport(from, now);
        if (channels.contains("CRYPTO_NEWS"))
            sendNewsChannel("CRYPTO_NEWS", "CRYPTO", 20, "🪙 코인 뉴스 요약", "코인", DiscordWebhookClient.COLOR_YELLOW);
        if (channels.contains("ECONOMY_NEWS"))
            sendNewsChannel("ECONOMY_NEWS", "ECONOMY", 15, "📰 경제 뉴스 요약", "경제", DiscordWebhookClient.COLOR_GREEN);
        if (channels.contains("ALERT")) sendMorningAlert(now);
    }

    // ── ALERT (모닝 상태 요약) ────────────────────────────────────────────────

    private void sendMorningAlert(Instant now) {
        try {
            String today = DATE_KST.format(now);
            ObjectNode embed = discordClient.embed(
                    "🔔 " + today + " 시스템 상태",
                    "CryptoAutoTrader 자동매매 시스템이 정상 운영 중입니다.",
                    DiscordWebhookClient.COLOR_GRAY);
            discordClient.addField(embed, "상태", "✅ 정상", true);
            discordClient.addField(embed, "시각", DATE_KST.format(now), true);
            discordClient.sendEmbed("ALERT", embed, "MORNING_BRIEFING");
        } catch (Exception e) {
            log.error("[MorningBriefing] ALERT 전송 실패", e);
        }
    }

    // ── TRADING_REPORT ────────────────────────────────────────────────────────

    private void sendTradingReport(Instant from, Instant now) {
        try {
            AnalysisReport report = logAnalyzer.analyze(from, now);
            String today = DATE_KST.format(now);

            ObjectNode embed = discordClient.embed(
                    "📊 " + today + " 모닝 브리핑",
                    buildTradingDescription(report),
                    DiscordWebhookClient.COLOR_BLUE);

            discordClient.addField(embed, "📈 신호 현황",
                    String.format("전체 **%d**건 | 매수 %d / 매도 %d\n실행 **%d** / 차단 %d",
                            report.getTotalSignals(), report.getBuySignals(), report.getSellSignals(),
                            report.getExecutedSignals(), report.getBlockedSignals()), true);

            discordClient.addField(embed, "💰 포지션 성과",
                    String.format("청산 **%d**건 | 승률 **%s%%**\n실현손익 **%s**원",
                            report.getClosedPositions(),
                            report.getWinRate() != null ? report.getWinRate().toPlainString() : "-",
                            fmt(report.getTotalRealizedPnl())), true);

            discordClient.addField(embed, "🌐 현재 레짐",
                    regimeEmoji(report.getCurrentRegime()) + " **" + report.getCurrentRegime() + "**"
                            + (report.getRegimeTransitions().isEmpty() ? "\n전환 없음"
                            : "\n전환 " + report.getRegimeTransitions().size() + "회"), true);

            if (report.getAccuracy4h() != null || report.getAccuracy24h() != null) {
                discordClient.addField(embed, "🎯 신호 적중률",
                        String.format("4h: **%s%%** | 24h: **%s%%**",
                                report.getAccuracy4h() != null ? report.getAccuracy4h() : "-",
                                report.getAccuracy24h() != null ? report.getAccuracy24h() : "-"), false);
            }

            if (!report.getBlockReasons().isEmpty()) {
                String reasons = report.getBlockReasons().entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .limit(3)
                        .map(e -> e.getKey() + " (" + e.getValue() + "건)")
                        .collect(Collectors.joining("\n"));
                discordClient.addField(embed, "🚫 주요 차단 사유", reasons, false);
            }

            discordClient.sendEmbed("TRADING_REPORT", embed, "MORNING_BRIEFING");

        } catch (Exception e) {
            log.error("[MorningBriefing] TRADING_REPORT 전송 실패", e);
        }
    }

    private String buildTradingDescription(AnalysisReport r) {
        String systemPrompt = """
                당신은 암호화폐 자동매매 시스템 분석가입니다.
                아래 12시간 성과 데이터를 바탕으로 다음 3가지를 한국어 3~4문장으로 작성하세요:
                1. 시장 상황 판단 (레짐 + 가격 흐름 근거)
                2. 전략 성과 평가 (적중률·손익 수치 언급)
                3. 오늘 주의사항 또는 개선 제언
                Discord 메시지이므로 핵심만, 수치를 근거로 구체적으로 작성하세요.
                """;

        String userPrompt = buildTradingUserPrompt(r);
        LlmResponse resp = llmTaskRouter.route(LlmTask.REPORT_NARRATION, systemPrompt, userPrompt);
        return resp.isSuccess() ? resp.getContent() : "AI 분석을 불러오는 중 오류가 발생했습니다.";
    }

    private String buildTradingUserPrompt(AnalysisReport r) {
        double execRate = r.getTotalSignals() > 0
                ? (double) r.getExecutedSignals() / r.getTotalSignals() * 100 : 0;

        StringBuilder sb = new StringBuilder();

        // 시장 가격 맥락
        sb.append("[시장 가격 맥락]\n");
        sb.append("현재 레짐: ").append(r.getCurrentRegime());
        if (!r.getRegimeTransitions().isEmpty())
            sb.append(" (12h 내 전환 ").append(r.getRegimeTransitions().size()).append("회)");
        sb.append("\n");
        sb.append("BTC 12h 변화: ").append(r.getBtcPriceChange12h() != null ? fmtChange(r.getBtcPriceChange12h()) : "조회 실패").append("\n");
        sb.append("ETH 12h 변화: ").append(r.getEthPriceChange12h() != null ? fmtChange(r.getEthPriceChange12h()) : "조회 실패").append("\n");

        // 신호 성과
        sb.append("\n[신호 성과]\n");
        sb.append(String.format("총 신호: %d건 (매수 %d / 매도 %d / 관망 %d)\n",
                r.getTotalSignals(), r.getBuySignals(), r.getSellSignals(), r.getHoldSignals()));
        sb.append(String.format("실행: %d건 (실행율 %.0f%%) / 차단: %d건\n",
                r.getExecutedSignals(), execRate, r.getBlockedSignals()));
        sb.append("4h 적중률: ").append(r.getAccuracy4h() != null ? r.getAccuracy4h() + "%" : "집계 중")
                .append(" (평균 ").append(r.getAvgReturn4h() != null ? r.getAvgReturn4h() + "%" : "-").append(")\n");
        sb.append("24h 적중률: ").append(r.getAccuracy24h() != null ? r.getAccuracy24h() + "%" : "집계 중")
                .append(" (평균 ").append(r.getAvgReturn24h() != null ? r.getAvgReturn24h() + "%" : "-").append(")\n");

        // 전략별 성과 (샘플 ≥ 3인 것만)
        if (r.getStrategyStats() != null && !r.getStrategyStats().isEmpty()) {
            sb.append("\n[전략별 성과]\n");
            r.getStrategyStats().entrySet().stream()
                    .filter(e -> (e.getValue().getBuy() + e.getValue().getSell()) >= 3)
                    .sorted((a, b) -> {
                        BigDecimal ra = a.getValue().getAccuracy4h();
                        BigDecimal rb = b.getValue().getAccuracy4h();
                        if (ra == null && rb == null) return 0;
                        if (ra == null) return 1;
                        if (rb == null) return -1;
                        return rb.compareTo(ra);
                    })
                    .forEach(e -> {
                        AnalysisReport.StrategySignalStat s = e.getValue();
                        sb.append("• ").append(e.getKey())
                                .append(": 신호 ").append(s.getBuy() + s.getSell()).append("건");
                        if (s.getAccuracy4h() != null)
                            sb.append(", 4h ").append(s.getAccuracy4h()).append("%");
                        if (s.getAvgReturn4h() != null)
                            sb.append("(avg ").append(s.getAvgReturn4h()).append("%)");
                        sb.append("\n");
                    });
        }

        // 포지션 성과
        sb.append("\n[포지션 성과]\n");
        sb.append(String.format("청산: %d건 | 승률: %s%% | 실현손익: %s원\n",
                r.getClosedPositions(),
                r.getWinRate() != null ? r.getWinRate().toPlainString() : "-",
                fmt(r.getTotalRealizedPnl())));
        sb.append("오픈 포지션: ").append(r.getOpenPositionCount()).append("건");
        if (r.getConsecutiveLosses() > 0)
            sb.append(" | ⚠ 연속 손실: ").append(r.getConsecutiveLosses()).append("회");
        sb.append("\n");

        // 실행 중 세션
        if (r.getActiveSessions() != null && !r.getActiveSessions().isEmpty()) {
            sb.append("\n[실행 중 세션 ").append(r.getActiveSessions().size()).append("개]\n");
            r.getActiveSessions().forEach(s -> {
                sb.append("• ").append(s.getStrategyType())
                        .append(" ").append(s.getCoinPair())
                        .append(" [").append(s.getTimeframe()).append("]");
                if (s.getReturnPct() != null) {
                    String sign = s.getReturnPct().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                    sb.append(" → ").append(sign).append(s.getReturnPct()).append("%");
                }
                if (r.getCoinPriceChanges() != null && r.getCoinPriceChanges().containsKey(s.getCoinPair())) {
                    BigDecimal chg = r.getCoinPriceChanges().get(s.getCoinPair());
                    String sign = chg.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                    sb.append(", 시세 12h ").append(sign).append(chg).append("%");
                }
                sb.append("\n");
            });
        } else {
            sb.append("\n[실행 중 세션] 없음\n");
        }

        // 코인별 포지션 성과
        if (r.getCoinPositionStats() != null && !r.getCoinPositionStats().isEmpty()) {
            sb.append("\n[코인별 포지션 성과]\n");
            r.getCoinPositionStats().forEach((coin, stat) ->
                    sb.append("• ").append(coin)
                            .append(": 청산 ").append(stat.getClosedCount()).append("건")
                            .append(" 승률 ").append(stat.getWinRate() != null ? stat.getWinRate() : "-").append("%")
                            .append(" 손익 ").append(fmt(stat.getTotalPnl())).append("원\n"));
        }

        // 주요 차단 사유
        if (r.getBlockReasons() != null && !r.getBlockReasons().isEmpty()) {
            sb.append("\n[주요 차단 사유]\n");
            r.getBlockReasons().entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(3)
                    .forEach(e -> sb.append("• ").append(e.getKey()).append(": ").append(e.getValue()).append("건\n"));
        }

        return sb.toString();
    }

    private String fmtChange(BigDecimal v) {
        String sign = v.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        return sign + v.toPlainString() + "%";
    }

    // ── 뉴스 채널 공통 전송 ───────────────────────────────────────────────────

    /**
     * 뉴스 카테고리 채널 전송 공통 로직.
     * CRYPTO_NEWS, ECONOMY_NEWS 채널이 동일한 구조이므로 파라미터로 분기한다.
     */
    private void sendNewsChannel(String channelType, String category, int fetchLimit,
                                  String embedTitle, String llmCategory, int embedColor) {
        try {
            List<NewsItemCacheEntity> news = newsAggregator.getRecentByCategory(category, fetchLimit);
            if (news.isEmpty()) {
                log.info("[MorningBriefing] {} 뉴스 캐시 없음 — 안내 메시지 전송", channelType);
                ObjectNode embed = discordClient.embed(embedTitle,
                        "현재 수집된 " + llmCategory + " 뉴스가 없습니다.\n뉴스 소스를 관리자 페이지에서 활성화하면 다음 수집(15분 주기) 후 요약이 전송됩니다.",
                        embedColor);
                discordClient.sendEmbed(channelType, embed, "MORNING_BRIEFING");
                return;
            }

            String newsSummary = summarizeNews(news, llmCategory);
            ObjectNode embed = discordClient.embed(embedTitle, newsSummary, embedColor);

            List<String> links = news.stream()
                    .limit(5)
                    .filter(n -> n.getUrl() != null)
                    .map(n -> "• [" + DiscordWebhookClient.truncate(n.getTitle(), 45) + "](" + n.getUrl() + ")")
                    .toList();
            if (!links.isEmpty()) {
                discordClient.addField(embed, "📰 주요 기사", String.join("\n", links), false);
            }

            discordClient.sendEmbed(channelType, embed, "MORNING_BRIEFING");

        } catch (Exception e) {
            log.error("[MorningBriefing] {} 전송 실패", channelType, e);
        }
    }

    // ── 뉴스 LLM 요약 ─────────────────────────────────────────────────────────

    private String summarizeNews(List<NewsItemCacheEntity> items, String category) {
        String titles = items.stream()
                .limit(15)
                .map(n -> "- " + n.getTitle())
                .collect(Collectors.joining("\n"));

        String systemPrompt = String.format("""
                당신은 %s 뉴스 요약 전문가입니다.
                아래 뉴스 제목들을 분석해 오늘 암호화폐 투자에 영향을 줄 수 있는
                핵심 동향을 한국어 3~4문장으로 요약하세요.
                Discord 메시지이므로 간결하게 작성하세요.
                """, category);

        LlmResponse resp = llmTaskRouter.route(LlmTask.NEWS_SUMMARY, systemPrompt, titles);
        return resp.isSuccess() ? resp.getContent() : "뉴스 요약을 불러오는 중 오류가 발생했습니다.";
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private String regimeEmoji(String regime) {
        return switch (regime) {
            case "TREND"        -> "📈";
            case "RANGE"        -> "↔️";
            case "VOLATILITY"   -> "⚡";
            case "TRANSITIONAL" -> "🔄";
            default             -> "❓";
        };
    }

    private String fmt(BigDecimal v) {
        if (v == null) return "-";
        return String.format("%,.0f", v);
    }
}
