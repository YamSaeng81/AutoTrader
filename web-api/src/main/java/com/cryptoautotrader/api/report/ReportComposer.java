package com.cryptoautotrader.api.report;

import com.cryptoautotrader.api.entity.NotionReportLogEntity;
import com.cryptoautotrader.api.llm.LlmTask;
import com.cryptoautotrader.api.llm.LlmTaskRouter;
import com.cryptoautotrader.api.llm.LlmResponse;
import com.cryptoautotrader.api.repository.NotionReportLogRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 보고서 생성 파이프라인.
 *
 * <p>흐름:
 * <ol>
 *   <li>LogAnalyzerService.analyze() → AnalysisReport</li>
 *   <li>LlmTaskRouter(LOG_SUMMARY) → 로그 요약 텍스트 (로컬 LLM 권장)</li>
 *   <li>LlmTaskRouter(SIGNAL_ANALYSIS) → 전략 분석 코멘트 (Cloud LLM 권장)</li>
 *   <li>NotionApiClient.createPage() → Notion 페이지 생성</li>
 *   <li>notion_report_log 저장</li>
 * </ol>
 *
 * <p>외부 HTTP 호출(LLM, Notion API)이 포함되므로 트랜잭션을 메서드 전체에 걸지 않는다.
 * DB 저장은 {@link #saveInitial}·{@link #saveFinal}로 분리하여 커넥션 점유를 최소화한다.
 */
@Service
@RequiredArgsConstructor
public class ReportComposer {

    private static final Logger log = LoggerFactory.getLogger(ReportComposer.class);
    private static final DateTimeFormatter KST_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    // 보고서 상태 상수
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED  = "FAILED";

    private final LogAnalyzerService logAnalyzer;
    private final LlmTaskRouter llmTaskRouter;
    private final NotionApiClient notionClient;
    private final NotionReportLogRepository reportLogRepo;

    /**
     * 12시간 분석 보고서를 생성하고 Notion에 저장한다.
     */
    public NotionReportLogEntity compose(Instant from, Instant to) {
        NotionReportLogEntity logEntity = saveInitial(from, to);

        try {
            AnalysisReport report = logAnalyzer.analyze(from, to);

            String summaryText  = buildLlmSummary(report);
            logEntity.setLlmSummary(summaryText);

            String analysisText = buildLlmAnalysis(report, summaryText);
            logEntity.setLlmAnalysis(analysisText);

            if (notionClient.isEnabled()) {
                String pageId = notionClient.createPage(buildTitle(report), buildBlocks(report, summaryText, analysisText));
                if (pageId != null) {
                    logEntity.setNotionPageId(pageId);
                    logEntity.setNotionPageUrl(notionClient.pageUrl(pageId));
                    logEntity.setStatus(STATUS_SUCCESS);
                } else {
                    logEntity.setStatus(STATUS_FAILED);
                    logEntity.setErrorMessage("Notion 페이지 생성 실패");
                }
            } else {
                log.info("[ReportComposer] Notion 미활성화 — 로그만 저장");
                logEntity.setStatus(STATUS_SUCCESS);
            }

        } catch (Exception e) {
            log.error("[ReportComposer] 보고서 생성 오류", e);
            logEntity.setStatus(STATUS_FAILED);
            logEntity.setErrorMessage(e.getMessage());
        }

        logEntity.setCompletedAt(Instant.now());
        return saveFinal(logEntity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotionReportLogEntity saveInitial(Instant from, Instant to) {
        return reportLogRepo.save(NotionReportLogEntity.builder()
                .reportType("ANALYSIS")
                .periodStart(from)
                .periodEnd(to)
                .status(STATUS_PENDING)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotionReportLogEntity saveFinal(NotionReportLogEntity entity) {
        return reportLogRepo.save(entity);
    }

    // ── LLM 프롬프트 ─────────────────────────────────────────────────────────

    private String buildLlmSummary(AnalysisReport r) {
        String systemPrompt = """
                당신은 암호화폐 자동매매 시스템의 로그 분석가입니다.
                주어진 12시간 신호 통계를 바탕으로 간결하고 명확한 한국어 요약을 작성하세요.
                핵심 수치와 눈에 띄는 이상 패턴(과도한 차단, 낮은 적중률 등)을 포함하되 400자 이내로 요약하세요.
                """;
        String userPrompt = String.format("""
                [분석 구간] %s ~ %s (KST)
                [신호 통계]
                - 전체 신호: %d건 (매수 %d / 매도 %d / 관망 %d)
                - 실행된 신호: %d건 / 차단된 신호: %d건
                - 4h 방향 적중률: %s (수수료 공제 후 실질 기준)
                - 24h 방향 적중률: %s (수수료 공제 후 실질 기준)
                - 4h 평균 수익률: %s%% / 24h 평균 수익률: %s%%
                [포지션 성과]
                - 청산 포지션: %d건 (승 %d / 패 %d, 승률 %s%%)
                - 실현손익: %s원
                [현재 레짐] %s
                [차단 사유 TOP3] %s
                """,
                KST_FMT.format(r.getPeriodStart()), KST_FMT.format(r.getPeriodEnd()),
                r.getTotalSignals(), r.getBuySignals(), r.getSellSignals(), r.getHoldSignals(),
                r.getExecutedSignals(), r.getBlockedSignals(),
                r.getAccuracy4h()  != null ? r.getAccuracy4h()  + "%" : "데이터 없음",
                r.getAccuracy24h() != null ? r.getAccuracy24h() + "%" : "데이터 없음",
                r.getAvgReturn4h()  != null ? r.getAvgReturn4h().toPlainString()  : "-",
                r.getAvgReturn24h() != null ? r.getAvgReturn24h().toPlainString() : "-",
                r.getClosedPositions(), r.getWinCount(), r.getLossCount(),
                r.getWinRate() != null ? r.getWinRate().toPlainString() : "-",
                fmt(r.getTotalRealizedPnl()),
                r.getCurrentRegime(),
                topBlockReasons(r.getBlockReasons(), 3));

        LlmResponse resp = llmTaskRouter.route(LlmTask.LOG_SUMMARY, systemPrompt, userPrompt);
        return resp.isSuccess() ? resp.getContent() : "(LLM 요약 실패: " + resp.getErrorMessage() + ")";
    }

    private String buildLlmAnalysis(AnalysisReport r, String summary) {
        String systemPrompt = """
                당신은 암호화폐 자동매매 전략 분석가입니다.
                제공된 12시간 성과 데이터를 분석하고 다음 항목을 한국어로 작성하세요:
                1. 현재 시장 레짐에서의 전략 적합성 평가 (적중률 수치 기반)
                2. 적중률이 낮은 전략의 문제점 및 개선 방향
                3. 다음 12시간 주의사항
                총 600자 이내로 작성하세요.
                """;
        String userPrompt = String.format("""
                [요약] %s
                [전략별 신호 품질]
                %s
                [레짐 전환] %s
                """,
                summary,
                strategyQualitySummary(r),
                regimeTransitionSummary(r.getRegimeTransitions()));

        LlmResponse resp = llmTaskRouter.route(LlmTask.SIGNAL_ANALYSIS, systemPrompt, userPrompt);
        return resp.isSuccess() ? resp.getContent() : "(분석 실패: " + resp.getErrorMessage() + ")";
    }

    // ── Notion 페이지 빌더 ────────────────────────────────────────────────────

    private String buildTitle(AnalysisReport r) {
        String prefix = notionClient.getConfig("report_title_prefix");
        if (prefix == null) prefix = "[매매분석]";
        return prefix + " " + KST_FMT.format(r.getPeriodStart()) + " ~ " + KST_FMT.format(r.getPeriodEnd());
    }

    private List<ObjectNode> buildBlocks(AnalysisReport r, String summary, String analysis) {
        List<ObjectNode> blocks = new ArrayList<>();
        blocks.add(buildHeaderCallout(r));
        blocks.add(notionClient.divider());
        blocks.addAll(buildSummarySection(summary));
        blocks.addAll(buildSignalStatsSection(r));
        blocks.addAll(buildPositionStatsSection(r));
        if (!r.getStrategyStats().isEmpty()) blocks.addAll(buildStrategyStatsSection(r));
        if (!r.getRegimeTransitions().isEmpty()) blocks.addAll(buildRegimeTransitionSection(r));
        if (!r.getBlockReasons().isEmpty()) blocks.addAll(buildBlockReasonsSection(r));
        blocks.addAll(buildAnalysisSection(analysis));
        return blocks;
    }

    private ObjectNode buildHeaderCallout(AnalysisReport r) {
        return notionClient.callout("📊",
                String.format("분석 기간: %s ~ %s | 현재 레짐: %s",
                        KST_FMT.format(r.getPeriodStart()), KST_FMT.format(r.getPeriodEnd()), r.getCurrentRegime()),
                "blue_background");
    }

    private List<ObjectNode> buildSummarySection(String summary) {
        return List.of(notionClient.heading2("📝 AI 로그 요약"), notionClient.paragraph(summary), notionClient.divider());
    }

    private List<ObjectNode> buildSignalStatsSection(AnalysisReport r) {
        return List.of(
                notionClient.heading2("📈 신호 통계"),
                notionClient.table(List.of("항목", "수치"), List.of(
                        List.of("전체 신호", r.getTotalSignals() + "건"),
                        List.of("매수 / 매도 / 관망", r.getBuySignals() + " / " + r.getSellSignals() + " / " + r.getHoldSignals()),
                        List.of("실행 / 차단", r.getExecutedSignals() + " / " + r.getBlockedSignals()),
                        List.of("4h 방향 적중률",  r.getAccuracy4h()  != null ? r.getAccuracy4h()  + "%" : "-"),
                        List.of("24h 방향 적중률", r.getAccuracy24h() != null ? r.getAccuracy24h() + "%" : "-"),
                        List.of("4h 평균 수익률",  r.getAvgReturn4h()  != null ? r.getAvgReturn4h()  + "%" : "-"),
                        List.of("24h 평균 수익률", r.getAvgReturn24h() != null ? r.getAvgReturn24h() + "%" : "-")
                )),
                notionClient.divider());
    }

    private List<ObjectNode> buildPositionStatsSection(AnalysisReport r) {
        return List.of(
                notionClient.heading2("💰 포지션 성과"),
                notionClient.table(List.of("항목", "수치"), List.of(
                        List.of("청산 포지션", r.getClosedPositions() + "건"),
                        List.of("승 / 패", r.getWinCount() + " / " + r.getLossCount()),
                        List.of("승률", r.getWinRate() != null ? r.getWinRate() + "%" : "-"),
                        List.of("실현손익", fmt(r.getTotalRealizedPnl()) + "원")
                )),
                notionClient.divider());
    }

    private List<ObjectNode> buildStrategyStatsSection(AnalysisReport r) {
        List<List<String>> rows = new ArrayList<>();
        r.getStrategyStats().forEach((strategy, stat) ->
                rows.add(List.of(strategy,
                        String.valueOf(stat.getBuy()), String.valueOf(stat.getSell()),
                        String.valueOf(stat.getHold()), String.valueOf(stat.getExecuted()))));
        return List.of(
                notionClient.heading2("🤖 전략별 신호"),
                notionClient.table(List.of("전략", "매수", "매도", "관망", "실행"), rows),
                notionClient.divider());
    }

    private List<ObjectNode> buildRegimeTransitionSection(AnalysisReport r) {
        List<List<String>> rows = new ArrayList<>();
        r.getRegimeTransitions().forEach(t ->
                rows.add(List.of(KST_FMT.format(t.getDetectedAt()),
                        t.getFromRegime() != null ? t.getFromRegime() : "초기", t.getToRegime())));
        return List.of(
                notionClient.heading2("🔄 레짐 전환"),
                notionClient.table(List.of("시각", "이전 레짐", "전환 레짐"), rows),
                notionClient.divider());
    }

    private List<ObjectNode> buildBlockReasonsSection(AnalysisReport r) {
        List<List<String>> rows = new ArrayList<>();
        r.getBlockReasons().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> rows.add(List.of(e.getKey(), e.getValue() + "건")));
        return List.of(
                notionClient.heading2("🚫 차단 사유"),
                notionClient.table(List.of("사유", "건수"), rows),
                notionClient.divider());
    }

    private List<ObjectNode> buildAnalysisSection(String analysis) {
        return List.of(
                notionClient.heading2("🧠 AI 전략 분석"),
                notionClient.callout("💡", analysis, "yellow_background"));
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private String fmt(BigDecimal v) {
        if (v == null) return "-";
        return String.format("%,.0f", v);
    }

    private String topBlockReasons(Map<String, Integer> reasons, int n) {
        if (reasons == null || reasons.isEmpty()) return "없음";
        return reasons.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .map(e -> e.getKey() + "(" + e.getValue() + "건)")
                .reduce((a, b) -> a + ", " + b)
                .orElse("없음");
    }

    private String strategyStatsSummary(Map<String, AnalysisReport.StrategySignalStat> stats) {
        if (stats == null || stats.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        stats.forEach((name, stat) ->
                sb.append(name).append(": 매수").append(stat.getBuy())
                        .append("/매도").append(stat.getSell())
                        .append("/실행").append(stat.getExecuted()).append(" | "));
        return sb.toString();
    }

    /**
     * LLM 분석용 전략별 신호 품질 요약.
     * 건수뿐 아니라 4h/24h 적중률과 평균 수익률을 포함한다.
     */
    private String strategyQualitySummary(AnalysisReport r) {
        Map<String, AnalysisReport.StrategySignalStat> stats = r.getStrategyStats();
        if (stats == null || stats.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        stats.forEach((name, stat) -> {
            sb.append("• ").append(name).append(": ");
            sb.append("신호 ").append(stat.getBuy() + stat.getSell()).append("건");
            sb.append("(실행 ").append(stat.getExecuted()).append(")");
            if (stat.getAccuracy4h() != null)
                sb.append(", 4h 적중률 ").append(stat.getAccuracy4h()).append("%");
            if (stat.getAvgReturn4h() != null)
                sb.append("(평균 ").append(stat.getAvgReturn4h()).append("%)");
            if (stat.getAccuracy24h() != null)
                sb.append(", 24h 적중률 ").append(stat.getAccuracy24h()).append("%");
            if (stat.getAvgReturn24h() != null)
                sb.append("(평균 ").append(stat.getAvgReturn24h()).append("%)");
            sb.append("\n");
        });
        return sb.toString().trim();
    }

    private String regimeTransitionSummary(List<AnalysisReport.RegimeTransition> transitions) {
        if (transitions == null || transitions.isEmpty()) return "전환 없음";
        return transitions.stream()
                .map(t -> (t.getFromRegime() != null ? t.getFromRegime() : "초기") + "→" + t.getToRegime())
                .reduce((a, b) -> a + ", " + b)
                .orElse("없음");
    }
}
