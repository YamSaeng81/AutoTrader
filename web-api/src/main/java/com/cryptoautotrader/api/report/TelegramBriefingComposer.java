package com.cryptoautotrader.api.report;

import com.cryptoautotrader.api.entity.NewsItemCacheEntity;
import com.cryptoautotrader.api.llm.LlmResponse;
import com.cryptoautotrader.api.llm.LlmTask;
import com.cryptoautotrader.api.llm.LlmTaskRouter;
import com.cryptoautotrader.api.news.NewsAggregatorService;
import com.cryptoautotrader.api.report.LogAnalyzerService.NoTradeFunnel;
import com.cryptoautotrader.api.report.MarketTrendScanner.CoinTrend;
import com.cryptoautotrader.api.service.TelegramNotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 텔레그램 아침 시황 브리핑 빌더 (매일 05:00 KST).
 *
 * <p>기존 Discord 07:00 브리핑({@code MorningBriefingComposer})과 별개 채널로,
 * 대형/중형 코인 48h 추세 + 시스템 자기진단(무거래 퍼널 포함) + 뉴스 이슈를 텔레그램으로 발송.
 * 분석 엔진({@link LogAnalyzerService})·추세 스캐너({@link MarketTrendScanner})·
 * 뉴스({@link NewsAggregatorService})·LLM({@link LlmTaskRouter})을 재사용한다.
 *
 * <p>텔레그램 메시지 3건: ① AI 시황+공포탐욕+추세 ② 시스템 자기진단(무거래 퍼널) ③ 뉴스 이슈.
 */
@Service
@RequiredArgsConstructor
public class TelegramBriefingComposer {

    private static final Logger log = LoggerFactory.getLogger(TelegramBriefingComposer.class);
    private static final DateTimeFormatter DATE_KST =
            DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 (E)").withZone(ZoneId.of("Asia/Seoul"));

    private static final int TREND_TOP_N = 8;
    private static final int WINDOW_HOURS = 48;
    /** 거래량 급증 강조 임계(%) */
    private static final BigDecimal VOL_SURGE_HIGHLIGHT = BigDecimal.valueOf(50);
    private static final String FNG_URL = "https://api.alternative.me/fng/";

    private final LogAnalyzerService logAnalyzer;
    private final MarketTrendScanner trendScanner;
    private final NewsAggregatorService newsAggregator;
    private final LlmTaskRouter llmTaskRouter;
    private final TelegramNotificationService telegram;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    /** 아침 브리핑 전체 전송 */
    public void sendMorningBriefing() {
        Instant now  = Instant.now();
        Instant from = now.minus(WINDOW_HOURS, ChronoUnit.HOURS);
        String today = DATE_KST.format(now);

        AnalysisReport report = logAnalyzer.analyze(from, now);
        List<CoinTrend> trends = trendScanner.scanTopCoins(TREND_TOP_N);
        NoTradeFunnel funnel = logAnalyzer.buildNoTradeFunnel(from, now);
        String fearGreed = fetchFearGreed();

        telegram.sendMarkdown(buildMarketSection(today, report, trends, fearGreed));
        telegram.sendMarkdown(buildSystemSection(report, funnel));
        telegram.sendMarkdown(buildNewsSection());
        log.info("[TelegramBriefing] 아침 브리핑 전송 완료 — 추세 {}개, F&G={}", trends.size(), fearGreed);
    }

    // ── 1) AI 시황 + 공포탐욕 + 추세 스캔 ─────────────────────────────────────

    private String buildMarketSection(String today, AnalysisReport r, List<CoinTrend> trends, String fearGreed) {
        StringBuilder sb = new StringBuilder();
        sb.append("☀️ *").append(today).append(" 아침 시황 브리핑*\n");
        sb.append("최근 48시간 기준\n\n");

        sb.append("*AI 시황 분석*\n");
        sb.append(sanitize(narrate(r, trends, fearGreed))).append("\n\n");

        sb.append("*시장 흐름 (48h)*\n");
        sb.append("• 비트코인(BTC): ").append(fmtPct(r.getBtcPriceChange12h())).append("\n");
        sb.append("• 이더리움(ETH): ").append(fmtPct(r.getEthPriceChange12h())).append("\n");
        sb.append("• 현재 레짐: ").append(regimeEmoji(r.getCurrentRegime())).append(" ")
          .append(r.getCurrentRegime()).append("\n");
        if (fearGreed != null) sb.append("• 공포·탐욕 지수: ").append(fearGreed).append("\n");
        sb.append(breadthLine(trends)).append("\n");

        sb.append("*대형·중형 코인 추세 (거래대금 상위 ").append(trends.size()).append("개)*\n");
        if (trends.isEmpty()) {
            sb.append("추세 데이터를 불러오지 못했습니다.\n");
        } else {
            BigDecimal btc48 = btcChange48(trends);
            for (CoinTrend t : trends) {
                sb.append(signEmoji(t.change48hPct())).append(" ").append(t.koreanName())
                  .append("  48h ").append(fmtPct(t.change48hPct()))
                  .append("·24h ").append(fmtPct(t.change24hPct()))
                  .append("  변동성").append(fmtPlain(t.atrPct())).append("%")
                  .append(trendArrow(t));
                // 거래량 급증
                if (t.volSurgePct() != null && t.volSurgePct().compareTo(BigDecimal.ZERO) > 0) {
                    sb.append(" 거래량").append(fmtPct(t.volSurgePct()));
                    if (t.volSurgePct().compareTo(VOL_SURGE_HIGHLIGHT) >= 0) sb.append("🔥");
                }
                // BTC 대비 상대강도 (BTC 자신 제외)
                if (btc48 != null && !t.market().equals("KRW-BTC") && t.change48hPct() != null) {
                    BigDecimal rel = t.change48hPct().subtract(btc48);
                    sb.append("  BTC比").append(fmtPct(rel));
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String breadthLine(List<CoinTrend> trends) {
        if (trends.isEmpty()) return "";
        long up = trends.stream().filter(t -> t.change48hPct() != null
                && t.change48hPct().compareTo(BigDecimal.ZERO) > 0).count();
        double avg = trends.stream().filter(t -> t.change48hPct() != null)
                .mapToDouble(t -> t.change48hPct().doubleValue()).average().orElse(0);
        String tone = up * 2 >= trends.size() ? "리스크온↑" : "리스크오프↓";
        return String.format("• 시장 폭: 상위 %d개 중 상승 %d개 (평균 %s%.1f%%, %s)",
                trends.size(), up, avg >= 0 ? "+" : "", avg, tone);
    }

    private static BigDecimal btcChange48(List<CoinTrend> trends) {
        return trends.stream().filter(t -> "KRW-BTC".equals(t.market()))
                .map(CoinTrend::change48hPct).filter(v -> v != null).findFirst().orElse(null);
    }

    private String narrate(AnalysisReport r, List<CoinTrend> trends, String fearGreed) {
        String system = """
                당신은 암호화폐 시장 분석가입니다. 아래 최근 48시간 데이터를 바탕으로
                한국어 4~5문장으로 아침 시황을 작성하세요:
                1. 전체 시장 국면(레짐 + BTC/ETH 48h + 공포탐욕지수 근거)
                2. 대형/중형 코인들의 공통 흐름(상승/하락 편중, 거래량 급증 코인, BTC 대비 강한 코인)
                3. 자동매매 시스템 관점의 오늘 관전 포인트(진입 셋업이 보이는지)
                과장 없이 수치를 근거로 담백하게. 별표(*)나 마크다운 기호는 쓰지 마세요.
                """;
        StringBuilder u = new StringBuilder();
        u.append("[시장 지수 48h]\n레짐: ").append(r.getCurrentRegime())
         .append(", BTC ").append(fmtPct(r.getBtcPriceChange12h()))
         .append(", ETH ").append(fmtPct(r.getEthPriceChange12h()));
        if (fearGreed != null) u.append(", 공포탐욕 ").append(fearGreed);
        u.append("\n").append(breadthLine(trends)).append("\n\n[대형/중형 48h 추세]\n");
        BigDecimal btc48 = btcChange48(trends);
        for (CoinTrend t : trends) {
            u.append("- ").append(t.koreanName())
             .append(": 48h ").append(fmtPct(t.change48hPct()))
             .append(", 24h ").append(fmtPct(t.change24hPct()))
             .append(", 변동성 ").append(fmtPlain(t.atrPct())).append("%")
             .append(", ").append(t.uptrend() == null ? "추세미상" : (t.uptrend() ? "상승추세" : "하락추세"));
            if (t.volSurgePct() != null) u.append(", 거래량 ").append(fmtPct(t.volSurgePct()));
            if (btc48 != null && !t.market().equals("KRW-BTC") && t.change48hPct() != null)
                u.append(", BTC대비 ").append(fmtPct(t.change48hPct().subtract(btc48)));
            u.append("\n");
        }
        LlmResponse resp = llmTaskRouter.route(LlmTask.REPORT_NARRATION, system, u.toString());
        return resp.isSuccess() && resp.getContent() != null && !resp.getContent().isBlank()
                ? resp.getContent().trim()
                : "(AI 시황 분석을 불러오지 못했습니다 — LLM 설정/크레딧 확인 필요)";
    }

    // ── 2) 시스템 자기진단 + 무거래 퍼널 ──────────────────────────────────────

    private String buildSystemSection(AnalysisReport r, NoTradeFunnel f) {
        StringBuilder sb = new StringBuilder();
        sb.append("🤖 *자동매매 시스템 점검 (48h)*\n\n");

        // 무거래 퍼널 — 이 시스템의 핵심 질문 "왜 거래가 없나"
        sb.append("*동적세션 신호 퍼널 (왜 거래?)*\n");
        if (f.total() == 0) {
            sb.append("동적 세션 평가 로그 없음\n");
        } else {
            int holdOther = f.hold() - f.holdLowScore();
            sb.append("• 총 평가 ").append(f.total()).append("회\n");
            sb.append("• HOLD ").append(f.hold()).append(" (점수미달 ").append(f.holdLowScore())
              .append(" / 기타 ").append(holdOther).append(")\n");
            sb.append("• 매수신호 ").append(f.buySignals())
              .append(" → 게이트차단 ").append(f.buyBlocked())
              .append(" / 체결 ").append(f.buyExecuted()).append("\n");
            if (f.buyExecuted() == 0 && f.buySignals() > 0) {
                sb.append("  ⓘ 매수신호는 났으나 전량 게이트 차단(체결 0)\n");
            } else if (f.buySignals() == 0) {
                sb.append("  ⓘ 매수신호 자체가 없음 — 병목은 스코어(점수미달), 게이트 아님\n");
            }
            if (f.buyBlockReasons() != null && !f.buyBlockReasons().isEmpty()) {
                String top = f.buyBlockReasons().entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue()).limit(3)
                        .map(e -> e.getKey() + " " + e.getValue())
                        .reduce((a, b) -> a + " · " + b).orElse("");
                sb.append("  차단사유: ").append(top).append("\n");
            }
        }
        sb.append("\n");

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
        return sb.toString();
    }

    // ── 3) 뉴스 이슈 요약 ─────────────────────────────────────────────────────

    private String buildNewsSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("📰 *코인 뉴스·이슈 요약*\n\n");

        List<NewsItemCacheEntity> news = newsAggregator.getRecentByCategory("CRYPTO", 25);
        if (news.isEmpty()) {
            sb.append("최근 수집된 코인 뉴스가 없습니다.\n");
            return sb.toString();
        }

        String titles = news.stream().limit(20)
                .map(n -> "- " + n.getTitle())
                .reduce((a, b) -> a + "\n" + b).orElse("");
        String system = """
                당신은 코인 뉴스 요약 전문가입니다. 아래 최근 뉴스·공지 제목들을 분석해
                오늘 투자에 영향을 줄 핵심 이슈를 한국어 3~4문장으로 요약하세요.
                업비트 상장/유의/거래중단 공지나 국내 규제 이슈가 있으면 우선 언급하세요.
                별표(*)나 마크다운 기호는 쓰지 마세요.
                """;
        LlmResponse resp = llmTaskRouter.route(LlmTask.NEWS_SUMMARY, system, titles);
        String summary = resp.isSuccess() && resp.getContent() != null && !resp.getContent().isBlank()
                ? resp.getContent().trim()
                : "(뉴스 요약을 불러오지 못했습니다 — LLM 설정/크레딧 확인 필요)";
        sb.append(sanitize(summary)).append("\n\n");

        sb.append("*주요 헤드라인*\n");
        news.stream().limit(6).forEach(n -> sb.append("• ").append(n.getTitle()).append("\n"));
        return sb.toString();
    }

    // ── 전문가 지표: 공포·탐욕 지수 ───────────────────────────────────────────

    /** alternative.me 공포·탐욕 지수. 실패 시 null(브리핑엔 생략). */
    private String fetchFearGreed() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(FNG_URL)).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonNode data = objectMapper.readTree(resp.body()).path("data");
            if (!data.isArray() || data.isEmpty()) return null;
            JsonNode d = data.get(0);
            String value = d.path("value").asText(null);
            String cls   = d.path("value_classification").asText("");
            if (value == null) return null;
            return value + " (" + cls + ")";
        } catch (Exception e) {
            log.debug("[TelegramBriefing] 공포탐욕 지수 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    private static String trendArrow(CoinTrend t) {
        if (t.uptrend() == null) return "";
        String gap = t.emaGapPct() != null ? "(" + fmtPct(t.emaGapPct()) + ")" : "";
        return t.uptrend() ? " 추세↑" + gap : " 추세↓" + gap;
    }

    private static String fmtPct(BigDecimal v) {
        if (v == null) return "-";
        String sign = v.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        return sign + v.toPlainString() + "%";
    }

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

    /** LLM 출력에서 텔레그램 MarkdownV2 파싱을 깨뜨릴 수 있는 기호 제거. */
    private static String sanitize(String text) {
        if (text == null) return "";
        return text.replace("*", "").replace("`", "");
    }
}
