package com.cryptoautotrader.api.report;

import com.cryptoautotrader.api.entity.NewsItemCacheEntity;
import com.cryptoautotrader.api.llm.LlmResponse;
import com.cryptoautotrader.api.llm.LlmTask;
import com.cryptoautotrader.api.llm.LlmTaskRouter;
import com.cryptoautotrader.api.news.NewsAggregatorService;
import com.cryptoautotrader.api.report.MarketTrendScanner.CoinTrend;
import com.cryptoautotrader.api.service.TelegramNotificationService;
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
import java.util.stream.Collectors;

/**
 * 텔레그램 아침 시황 브리핑 빌더 (매일 05:00 KST).
 *
 * <p>기존 Discord 07:00 브리핑({@code MorningBriefingComposer})과 별개 채널로,
 * 사용자 요청("아침 5시 대형/중형 코인 이슈·추세 48h 리포트")에 맞춰 구성한다.
 * 분석 엔진({@link LogAnalyzerService})·추세 스캐너({@link MarketTrendScanner})·
 * 뉴스({@link NewsAggregatorService})·LLM({@link LlmTaskRouter})을 재사용한다.
 *
 * <p>구성(텔레그램 메시지 3건):
 * <ol>
 *   <li>AI 시황 분석(48h) + 대형/중형 추세 스캔</li>
 *   <li>시스템 자기진단(신호·실행·차단 48h)</li>
 *   <li>코인 뉴스 이슈 요약</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class TelegramBriefingComposer {

    private static final Logger log = LoggerFactory.getLogger(TelegramBriefingComposer.class);
    private static final DateTimeFormatter DATE_KST =
            DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 (E)").withZone(ZoneId.of("Asia/Seoul"));

    /** 추세 스캔 대형/중형 코인 수 (24h 거래대금 상위) */
    private static final int TREND_TOP_N = 8;
    /** 분석 윈도우 — 48시간 */
    private static final int WINDOW_HOURS = 48;

    private final LogAnalyzerService logAnalyzer;
    private final MarketTrendScanner trendScanner;
    private final NewsAggregatorService newsAggregator;
    private final LlmTaskRouter llmTaskRouter;
    private final TelegramNotificationService telegram;

    /** 아침 브리핑 전체 전송 */
    public void sendMorningBriefing() {
        Instant now  = Instant.now();
        Instant from = now.minus(WINDOW_HOURS, ChronoUnit.HOURS);
        String today = DATE_KST.format(now);

        AnalysisReport report = logAnalyzer.analyze(from, now);
        List<CoinTrend> trends = trendScanner.scanTopCoins(TREND_TOP_N);

        telegram.sendMarkdown(buildMarketSection(today, report, trends));
        telegram.sendMarkdown(buildSystemSection(report));
        telegram.sendMarkdown(buildNewsSection());
        log.info("[TelegramBriefing] 아침 브리핑 전송 완료 — 추세 {}개", trends.size());
    }

    // ── 1) AI 시황 + 추세 스캔 ────────────────────────────────────────────────

    private String buildMarketSection(String today, AnalysisReport r, List<CoinTrend> trends) {
        StringBuilder sb = new StringBuilder();
        sb.append("☀️ *").append(today).append(" 아침 시황 브리핑*\n");
        sb.append("최근 48시간 기준\n\n");

        // AI 서술 (LLM)
        sb.append("*AI 시황 분석*\n");
        sb.append(sanitize(narrate(r, trends))).append("\n\n");

        // 시장 지수 맥락
        sb.append("*시장 흐름 (48h)*\n");
        sb.append("• 비트코인(BTC): ").append(fmtPct(r.getBtcPriceChange12h())).append("\n");
        sb.append("• 이더리움(ETH): ").append(fmtPct(r.getEthPriceChange12h())).append("\n");
        sb.append("• 현재 레짐: ").append(regimeEmoji(r.getCurrentRegime())).append(" ")
          .append(r.getCurrentRegime()).append("\n\n");

        // 대형/중형 추세 스캔
        sb.append("*대형·중형 코인 추세 (거래대금 상위 ").append(trends.size()).append("개)*\n");
        if (trends.isEmpty()) {
            sb.append("추세 데이터를 불러오지 못했습니다.\n");
        } else {
            for (CoinTrend t : trends) {
                String lead = signEmoji(t.change48hPct());
                String arrow = t.uptrend() == null ? "" : (t.uptrend() ? " 추세↑" : " 추세↓");
                sb.append(lead).append(" ").append(t.koreanName())
                  .append("  48h ").append(fmtPct(t.change48hPct()))
                  .append(" · 24h ").append(fmtPct(t.change24hPct()))
                  .append(" · 변동성 ").append(fmtPlain(t.atrPct())).append("%")
                  .append(arrow).append("\n");
            }
        }
        return sb.toString();
    }

    private String narrate(AnalysisReport r, List<CoinTrend> trends) {
        String system = """
                당신은 암호화폐 시장 분석가입니다. 아래 최근 48시간 데이터를 바탕으로
                한국어 4~5문장으로 아침 시황을 작성하세요:
                1. 전체 시장 국면(레짐 + BTC/ETH 48h 흐름 근거)
                2. 대형/중형 코인들의 공통 흐름 특징(상승/하락 편중, 변동성)
                3. 자동매매 시스템 관점의 오늘 관전 포인트(진입 셋업이 보이는지)
                과장 없이 수치를 근거로 담백하게. 별표(*)나 마크다운 기호는 쓰지 마세요.
                """;
        StringBuilder u = new StringBuilder();
        u.append("[시장 지수 48h]\n");
        u.append("레짐: ").append(r.getCurrentRegime()).append("\n");
        u.append("BTC: ").append(fmtPct(r.getBtcPriceChange12h())).append(", ETH: ")
         .append(fmtPct(r.getEthPriceChange12h())).append("\n\n");
        u.append("[대형/중형 코인 48h 추세]\n");
        for (CoinTrend t : trends) {
            u.append("- ").append(t.koreanName())
             .append(": 48h ").append(fmtPct(t.change48hPct()))
             .append(", 24h ").append(fmtPct(t.change24hPct()))
             .append(", 변동성 ").append(fmtPlain(t.atrPct())).append("%")
             .append(", ").append(t.uptrend() == null ? "추세미상" : (t.uptrend() ? "상승추세" : "하락추세"))
             .append("\n");
        }
        u.append("\n[시스템 48h]\n");
        u.append("신호 ").append(r.getTotalSignals()).append("건(매수 ").append(r.getBuySignals())
         .append("/매도 ").append(r.getSellSignals()).append("), 실행 ").append(r.getExecutedSignals())
         .append("/차단 ").append(r.getBlockedSignals()).append(", 오픈포지션 ")
         .append(r.getOpenPositionCount()).append("건\n");

        LlmResponse resp = llmTaskRouter.route(LlmTask.REPORT_NARRATION, system, u.toString());
        return resp.isSuccess() && resp.getContent() != null && !resp.getContent().isBlank()
                ? resp.getContent().trim()
                : "(AI 시황 분석을 불러오지 못했습니다 — LLM 설정/크레딧 확인 필요)";
    }

    // ── 2) 시스템 자기진단 ────────────────────────────────────────────────────

    private String buildSystemSection(AnalysisReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("🤖 *자동매매 시스템 점검 (48h)*\n\n");
        sb.append("*신호 현황*\n");
        sb.append("• 전체 ").append(r.getTotalSignals()).append("건 (매수 ")
          .append(r.getBuySignals()).append(" / 매도 ").append(r.getSellSignals()).append(")\n");
        sb.append("• 실행 ").append(r.getExecutedSignals()).append(" / 차단 ")
          .append(r.getBlockedSignals()).append("\n\n");

        sb.append("*포지션 성과*\n");
        sb.append("• 청산 ").append(r.getClosedPositions()).append("건 · 승률 ")
          .append(r.getWinRate() != null ? r.getWinRate().toPlainString() : "-").append("%\n");
        sb.append("• 실현손익 ").append(fmtKrw(r.getTotalRealizedPnl())).append("원 · 오픈 ")
          .append(r.getOpenPositionCount()).append("건");
        if (r.getConsecutiveLosses() > 0) sb.append(" · ⚠ 연속손실 ").append(r.getConsecutiveLosses()).append("회");
        sb.append("\n");

        if (r.getAccuracy4h() != null || r.getAccuracy24h() != null) {
            sb.append("• 신호 적중률 4h ").append(r.getAccuracy4h() != null ? r.getAccuracy4h() + "%" : "-")
              .append(" / 24h ").append(r.getAccuracy24h() != null ? r.getAccuracy24h() + "%" : "-").append("\n");
        }

        if (r.getBlockReasons() != null && !r.getBlockReasons().isEmpty()) {
            sb.append("\n*주요 차단 사유*\n");
            r.getBlockReasons().entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(4)
                    .forEach(e -> sb.append("• ").append(e.getKey()).append(": ").append(e.getValue()).append("건\n"));
        }
        return sb.toString();
    }

    // ── 3) 뉴스 이슈 요약 ─────────────────────────────────────────────────────

    private String buildNewsSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("📰 *코인 뉴스 이슈 요약*\n\n");

        List<NewsItemCacheEntity> news = newsAggregator.getRecentByCategory("CRYPTO", 20);
        if (news.isEmpty()) {
            sb.append("최근 수집된 코인 뉴스가 없습니다.\n");
            return sb.toString();
        }

        String titles = news.stream().limit(15)
                .map(n -> "- " + n.getTitle())
                .collect(Collectors.joining("\n"));
        String system = """
                당신은 코인 뉴스 요약 전문가입니다. 아래 최근 뉴스 제목들을 분석해
                오늘 투자에 영향을 줄 핵심 이슈를 한국어 3~4문장으로 요약하세요.
                별표(*)나 마크다운 기호는 쓰지 마세요.
                """;
        LlmResponse resp = llmTaskRouter.route(LlmTask.NEWS_SUMMARY, system, titles);
        String summary = resp.isSuccess() && resp.getContent() != null && !resp.getContent().isBlank()
                ? resp.getContent().trim()
                : "(뉴스 요약을 불러오지 못했습니다 — LLM 설정/크레딧 확인 필요)";
        sb.append(sanitize(summary)).append("\n\n");

        sb.append("*주요 헤드라인*\n");
        news.stream().limit(5).forEach(n -> sb.append("• ").append(n.getTitle()).append("\n"));
        return sb.toString();
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    /** 부호 포함 퍼센트. null → "-". (텔레그램이 +/-/. 를 자동 이스케이프하므로 그대로 렌더) */
    private static String fmtPct(BigDecimal v) {
        if (v == null) return "-";
        String sign = v.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        return sign + v.toPlainString() + "%";
    }

    /** 부호 없는 수치. null → "-" */
    private static String fmtPlain(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }

    private static String fmtKrw(BigDecimal v) {
        if (v == null) return "-";
        return String.format("%,.0f", v.doubleValue());
    }

    private static String signEmoji(BigDecimal change) {
        if (change == null) return "•";
        return change.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
    }

    private static String regimeEmoji(String regime) {
        if (regime == null) return "❓";
        return switch (regime) {
            case "TREND"        -> "📈";
            case "RANGE"        -> "↔️";
            case "VOLATILITY"   -> "⚡";
            case "TRANSITIONAL" -> "🔄";
            default             -> "❓";
        };
    }

    /**
     * LLM 출력에서 텔레그램 MarkdownV2 파싱을 깨뜨릴 수 있는 기호 제거.
     * ({@code *} 굵게, {@code `} 코드 — 짝이 안 맞으면 400 발생 → 제거)
     */
    private static String sanitize(String text) {
        if (text == null) return "";
        return text.replace("*", "").replace("`", "");
    }
}
