package com.cryptoautotrader.api.discord;

import com.cryptoautotrader.api.entity.NewsItemCacheEntity;
import com.cryptoautotrader.api.llm.LlmResponse;
import com.cryptoautotrader.api.llm.LlmTask;
import com.cryptoautotrader.api.llm.LlmTaskRouter;
import com.cryptoautotrader.api.news.NewsAggregatorService;
import com.cryptoautotrader.api.report.AnalysisReport;
import com.cryptoautotrader.api.report.LogAnalyzerService;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Discord 메시지 명령어 핸들러.
 *
 * <p>지원 명령어 (대소문자/공백 무관):
 * <ul>
 *   <li>뉴스 요약 / 코인 뉴스  → 최근 CRYPTO 뉴스 LLM 요약</li>
 *   <li>경제 뉴스              → 최근 ECONOMY 뉴스 LLM 요약</li>
 *   <li>매매 분석 / 성과       → 최근 12h 매매 성과 요약</li>
 *   <li>도움말 / !help        → 사용 가능한 명령어 목록</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DiscordCommandHandler extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordCommandHandler.class);

    private final NewsAggregatorService newsAggregator;
    private final LogAnalyzerService    logAnalyzer;
    private final LlmTaskRouter         llmTaskRouter;

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // 봇 자신의 메시지 무시
        if (event.getAuthor().isBot()) return;

        String raw = event.getMessage().getContentRaw().trim();
        if (raw.isBlank()) return;

        String msg = raw.toLowerCase().replaceAll("\\s+", " ");

        log.debug("[DiscordBot] 메시지 수신 — channel={} user={} content={}",
                event.getChannel().getName(), event.getAuthor().getName(), raw);

        if (matches(msg, "도움말", "help", "!help", "명령어")) {
            reply(event, buildHelp());
        } else if (matches(msg, "뉴스 요약", "코인 뉴스", "코인뉴스", "crypto news")) {
            reply(event, "🔍 코인 뉴스 요약 중...");
            reply(event, summarizeNews("CRYPTO", "코인"));
        } else if (matches(msg, "경제 뉴스", "경제뉴스", "economy news")) {
            reply(event, "🔍 경제 뉴스 요약 중...");
            reply(event, summarizeNews("ECONOMY", "경제"));
        } else if (matches(msg, "매매 분석", "매매분석", "성과", "분석")) {
            reply(event, "📊 매매 성과 분석 중...");
            reply(event, analyzeTradingPerformance());
        }
        // 그 외 메시지는 무시 (봇이 모든 대화에 반응하지 않도록)
    }

    // ── 명령어 처리 ───────────────────────────────────────────────────────────

    private String summarizeNews(String category, String label) {
        List<NewsItemCacheEntity> news = newsAggregator.getRecentByCategory(category, 20);
        if (news.isEmpty()) {
            return "📭 최근 수집된 " + label + " 뉴스가 없습니다.\n"
                    + "관리자 페이지에서 뉴스 소스를 활성화해 주세요.";
        }

        String titles = news.stream()
                .limit(15)
                .map(n -> "- " + n.getTitle())
                .collect(Collectors.joining("\n"));

        String systemPrompt = String.format("""
                당신은 %s 뉴스 요약 전문가입니다.
                아래 뉴스 제목들을 분석해 오늘 암호화폐 투자에 영향을 줄 수 있는
                핵심 동향을 한국어 3~4문장으로 요약하세요.
                Discord 메시지이므로 간결하게 작성하세요.
                """, label);

        LlmResponse resp = llmTaskRouter.route(LlmTask.NEWS_SUMMARY, systemPrompt, titles);
        String summary = resp.isSuccess() ? resp.getContent() : "⚠️ LLM 요약 실패 — 잠시 후 다시 시도해 주세요.";

        // 뉴스 링크 최대 5개
        String links = news.stream()
                .limit(5)
                .filter(n -> n.getUrl() != null)
                .map(n -> "• " + DiscordWebhookClient.truncate(n.getTitle(), 50) + "\n  " + n.getUrl())
                .collect(Collectors.joining("\n"));

        return "**🪙 " + label + " 뉴스 요약**\n\n"
                + summary
                + (links.isBlank() ? "" : "\n\n**📰 주요 기사**\n" + links);
    }

    private String analyzeTradingPerformance() {
        Instant now  = Instant.now();
        Instant from = now.minus(12, ChronoUnit.HOURS);

        AnalysisReport report;
        try {
            report = logAnalyzer.analyze(from, now);
        } catch (Exception e) {
            log.error("[DiscordBot] 매매 분석 실패", e);
            return "⚠️ 매매 데이터 조회 중 오류가 발생했습니다.";
        }

        String systemPrompt = """
                암호화폐 자동매매 시스템 분석가입니다.
                아래 지표를 바탕으로 최근 12시간 매매 성과를 한국어 3문장으로 요약하세요.
                주의할 점과 다음 전략 방향도 간략히 포함하세요.
                """;

        String userPrompt = String.format(
                "레짐: %s | 총 신호: %d건 | 실행: %d건 | 차단: %d건 | 청산: %d건 | 승률: %s%% | 실현손익: %s원",
                report.getCurrentRegime(),
                report.getTotalSignals(),
                report.getExecutedSignals(),
                report.getBlockedSignals(),
                report.getClosedPositions(),
                report.getWinRate() != null ? report.getWinRate().toPlainString() : "-",
                fmt(report.getTotalRealizedPnl()));

        LlmResponse resp = llmTaskRouter.route(LlmTask.REPORT_NARRATION, systemPrompt, userPrompt);
        String narrative = resp.isSuccess() ? resp.getContent() : "⚠️ LLM 분석 실패";

        return "**📊 최근 12h 매매 성과**\n\n"
                + narrative + "\n\n"
                + "```\n"
                + "레짐    : " + report.getCurrentRegime() + "\n"
                + "신호    : " + report.getTotalSignals() + "건 (실행 " + report.getExecutedSignals() + " / 차단 " + report.getBlockedSignals() + ")\n"
                + "청산    : " + report.getClosedPositions() + "건 | 승률 " + (report.getWinRate() != null ? report.getWinRate().toPlainString() : "-") + "%\n"
                + "실현손익: " + fmt(report.getTotalRealizedPnl()) + "원\n"
                + "```";
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private boolean matches(String msg, String... keywords) {
        for (String kw : keywords) {
            if (msg.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    private void reply(MessageReceivedEvent event, String text) {
        event.getChannel().sendMessage(text).queue(
                ok  -> {},
                err -> log.error("[DiscordBot] 응답 전송 실패: {}", err.getMessage())
        );
    }

    private String buildHelp() {
        return """
                **🤖 CryptoAutoTrader 봇 명령어**

                `뉴스 요약` / `코인 뉴스` — 최근 코인 뉴스 LLM 요약
                `경제 뉴스` — 최근 경제 뉴스 LLM 요약
                `매매 분석` / `성과` — 최근 12시간 매매 성과 분석
                `도움말` — 이 메시지 표시
                """;
    }

    private String fmt(BigDecimal v) {
        if (v == null) return "-";
        return String.format("%,.0f", v);
    }
}
