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
     *
     * <p>실전(REAL)·모의(PAPER)를 별도 집계해 하나의 Notion 페이지에 섹션으로 분리한다.
     * LLM 요약·분석은 실전 데이터를 주(主)로 하되, 모의 비교값을 추가 컨텍스트로 제공한다.
     */
    public NotionReportLogEntity compose(Instant from, Instant to) {
        NotionReportLogEntity logEntity = saveInitial(from, to);

        try {
            AnalysisReport realReport  = logAnalyzer.analyze(from, to, "REAL");
            AnalysisReport paperReport = logAnalyzer.analyze(from, to, "PAPER");

            String summaryText  = buildLlmSummary(realReport);
            logEntity.setLlmSummary(summaryText);

            String analysisText = buildLlmAnalysis(realReport, paperReport, summaryText);
            logEntity.setLlmAnalysis(analysisText);

            if (notionClient.isEnabled()) {
                String pageId = notionClient.createPage(
                        buildTitle(realReport),
                        buildBlocks(realReport, paperReport, summaryText, analysisText));
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
                [실행 중 세션]
                %s
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
                topBlockReasons(r.getBlockReasons(), 3),
                activeSessionsSummary(r));

        LlmResponse resp = llmTaskRouter.route(LlmTask.LOG_SUMMARY, systemPrompt, userPrompt);
        return resp.isSuccess() ? resp.getContent() : "(LLM 요약 실패: " + resp.getErrorMessage() + ")";
    }

    private String buildLlmAnalysis(AnalysisReport real, AnalysisReport paper, String summary) {
        String systemPrompt = """
                당신은 암호화폐 자동매매 전략 분석가입니다.
                실전(REAL)과 모의(PAPER) 매매 12시간 성과를 비교 분석하고 다음 항목을 한국어로 작성하세요:
                1. 현재 시장 레짐에서의 전략 적합성 평가 (실전 EV·적중률 수치 기반)
                2. 실전 vs 모의 적중률 차이 해석 (차이가 크면 과적합·데이터 편향 가능성 진단)
                3. 레짐별 EV 분석 — 어떤 레짐에서 기대값이 양수/음수인지, 개선 방향
                4. 신호 품질이 좋은 시간대 vs 나쁜 시간대 패턴 (있으면)
                5. 전략 간 컨센서스 vs 분산 신호 품질 차이 해석 (컨센서스가 유효한지 여부)
                6. 다음 12시간 주의사항
                총 800자 이내로 작성하세요.
                """;
        String userPrompt = String.format("""
                [요약] %s
                [실전 전략별 신호 품질 (EV 포함)]
                %s
                [모의 전략별 신호 품질]
                %s
                [실전 레짐별 신호 품질]
                %s
                [실전 시간대별 신호 품질]
                %s
                [실전 전략 간 상관관계]
                %s
                [실전 코인별 포지션 성과]
                %s
                [레짐 전환] %s
                [실행 중 세션]
                %s
                """,
                summary,
                strategyQualitySummary(real),
                strategyQualitySummary(paper),
                regimeStatsSummary(real),
                hourlyStatsSummary(real),
                correlationStatsSummary(real),
                coinPositionStatsSummary(real),
                regimeTransitionSummary(real.getRegimeTransitions()),
                activeSessionsSummary(real));

        LlmResponse resp = llmTaskRouter.route(LlmTask.SIGNAL_ANALYSIS, systemPrompt, userPrompt);
        return resp.isSuccess() ? resp.getContent() : "(분석 실패: " + resp.getErrorMessage() + ")";
    }

    // ── Notion 페이지 빌더 ────────────────────────────────────────────────────

    private String buildTitle(AnalysisReport r) {
        String prefix = notionClient.getConfig("report_title_prefix");
        if (prefix == null) prefix = "[매매분석]";
        return prefix + " " + KST_FMT.format(r.getPeriodStart()) + " ~ " + KST_FMT.format(r.getPeriodEnd());
    }

    private List<ObjectNode> buildBlocks(AnalysisReport real, AnalysisReport paper,
                                          String summary, String analysis) {
        List<ObjectNode> blocks = new ArrayList<>();

        // 헤더 — 실전 데이터 기준 기간·레짐 표시
        blocks.add(buildHeaderCallout(real));
        blocks.add(notionClient.divider());

        // AI 요약
        blocks.addAll(buildSummarySection(summary));

        // ── 실전 매매 ──
        blocks.add(notionClient.callout("📊", "실전 매매 (REAL)", "green_background"));
        blocks.addAll(buildSignalStatsSection(real));
        blocks.addAll(buildPositionStatsSection(real));
        if (!real.getStrategyStats().isEmpty()) blocks.addAll(buildStrategyStatsSection(real));
        if (!real.getBlockReasons().isEmpty())  blocks.addAll(buildBlockReasonsSection(real));
        if (real.getRegimeSignalStats() != null && !real.getRegimeSignalStats().isEmpty())
            blocks.addAll(buildRegimeSignalStatsSection(real));
        if (real.getHourlySignalStats() != null && !real.getHourlySignalStats().isEmpty())
            blocks.addAll(buildHourlySignalStatsSection(real));
        if (real.getCorrelationStats() != null && real.getCorrelationStats().getTotalBuckets() > 0)
            blocks.addAll(buildCorrelationStatsSection(real));

        // ── 모의 매매 ──
        blocks.add(notionClient.callout("🧪", "모의 매매 (PAPER)", "gray_background"));
        blocks.addAll(buildSignalStatsSection(paper));
        blocks.addAll(buildPositionStatsSection(paper));
        if (!paper.getStrategyStats().isEmpty()) blocks.addAll(buildStrategyStatsSection(paper));

        // ── 공통 섹션 ──
        if (real.getActiveSessions() != null && !real.getActiveSessions().isEmpty())
            blocks.addAll(buildActiveSessionsSection(real));
        if (real.getRegimeTransitions() != null && !real.getRegimeTransitions().isEmpty())
            blocks.addAll(buildRegimeTransitionSection(real));

        // AI 분석
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

    private List<ObjectNode> buildRegimeSignalStatsSection(AnalysisReport r) {
        Map<String, AnalysisReport.RegimeSignalQuality> stats = r.getRegimeSignalStats();
        if (stats == null || stats.isEmpty()) return List.of();
        List<List<String>> rows = new ArrayList<>();
        stats.forEach((regime, q) -> rows.add(List.of(
                regime,
                q.getSignalCount() + "건",
                q.getBuyWinRate4h()    != null ? q.getBuyWinRate4h()    + "%" : "-",
                q.getSellWinRate4h()   != null ? q.getSellWinRate4h()   + "%" : "-",
                q.getAvgReturn4h()     != null ? q.getAvgReturn4h()     + "%" : "-",
                q.getExpectedValue4h() != null ? q.getExpectedValue4h() + "%" : "-")));
        return List.of(
                notionClient.heading2("📐 레짐별 신호 품질"),
                notionClient.table(List.of("레짐", "신호수", "BUY 4h 적중", "SELL 4h 적중", "4h 평균수익", "EV"), rows),
                notionClient.divider());
    }

    private List<ObjectNode> buildHourlySignalStatsSection(AnalysisReport r) {
        List<AnalysisReport.HourlySignalQuality> stats = r.getHourlySignalStats();
        if (stats == null || stats.isEmpty()) return List.of();
        List<List<String>> rows = new ArrayList<>();
        stats.forEach(q -> rows.add(List.of(
                q.getHourBucket() + " KST",
                q.getSignalCount() + "건",
                q.getAccuracy4h()  != null ? q.getAccuracy4h()  + "%" : "-",
                q.getAvgReturn4h() != null ? q.getAvgReturn4h() + "%" : "-")));
        return List.of(
                notionClient.heading2("🕐 시간대별 신호 품질 (KST)"),
                notionClient.table(List.of("시간대", "신호수", "4h 적중률", "4h 평균수익"), rows),
                notionClient.divider());
    }

    private List<ObjectNode> buildCorrelationStatsSection(AnalysisReport r) {
        AnalysisReport.StrategyCorrelationStats c = r.getCorrelationStats();
        if (c == null || c.getTotalBuckets() == 0) return List.of();
        return List.of(
                notionClient.heading2("🔗 전략 간 상관관계 (컨센서스 vs 분산)"),
                notionClient.table(List.of("항목", "수치"), List.of(
                        List.of("분석 버킷 수 (≥2전략)", c.getTotalBuckets() + "개"),
                        List.of("컨센서스 버킷", c.getConsensusBuckets() + "개"),
                        List.of("분산(불일치) 버킷",  c.getDivergentBuckets() + "개"),
                        List.of("컨센서스 4h 적중률",
                                c.getConsensusAccuracy4h() != null ? c.getConsensusAccuracy4h() + "%" : "-"),
                        List.of("분산 4h 적중률",
                                c.getDivergentAccuracy4h() != null ? c.getDivergentAccuracy4h() + "%" : "-"),
                        List.of("컨센서스 4h 평균수익",
                                c.getConsensusAvgReturn4h() != null ? c.getConsensusAvgReturn4h() + "%" : "-"),
                        List.of("분산 4h 평균수익",
                                c.getDivergentAvgReturn4h() != null ? c.getDivergentAvgReturn4h() + "%" : "-")
                )),
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
     * 4h/24h 적중률, 평균 수익률, EV, 고신뢰 적중률을 포함한다.
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
            if (stat.getExpectedValue4h() != null)
                sb.append(", EV ").append(stat.getExpectedValue4h()).append("%");
            if (stat.getHighConfCount() > 0 && stat.getHighConfAccuracy4h() != null)
                sb.append(", 고신뢰 ").append(stat.getHighConfAccuracy4h()).append("%")
                  .append("(").append(stat.getHighConfCount()).append("건)");
            sb.append("\n");
        });
        return sb.toString().trim();
    }

    /** LLM 프롬프트용 레짐별 신호 품질 요약 */
    private String regimeStatsSummary(AnalysisReport r) {
        Map<String, AnalysisReport.RegimeSignalQuality> stats = r.getRegimeSignalStats();
        if (stats == null || stats.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        stats.forEach((regime, q) -> {
            sb.append("• ").append(regime).append(": 신호 ").append(q.getSignalCount()).append("건");
            if (q.getBuyWinRate4h()  != null) sb.append(", BUY 4h ").append(q.getBuyWinRate4h()).append("%");
            if (q.getSellWinRate4h() != null) sb.append(", SELL 4h ").append(q.getSellWinRate4h()).append("%");
            if (q.getExpectedValue4h() != null) sb.append(", EV ").append(q.getExpectedValue4h()).append("%");
            sb.append("\n");
        });
        return sb.toString().trim();
    }

    /** LLM 프롬프트용 전략 간 상관관계 요약 */
    private String correlationStatsSummary(AnalysisReport r) {
        AnalysisReport.StrategyCorrelationStats c = r.getCorrelationStats();
        if (c == null || c.getTotalBuckets() == 0) return "없음 (샘플 부족)";
        StringBuilder sb = new StringBuilder();
        sb.append("총 ").append(c.getTotalBuckets()).append("개 버킷")
          .append(" (컨센서스 ").append(c.getConsensusBuckets())
          .append(" / 분산 ").append(c.getDivergentBuckets()).append(")\n");
        sb.append("• 컨센서스 4h 적중률: ")
          .append(c.getConsensusAccuracy4h() != null ? c.getConsensusAccuracy4h() + "%" : "-");
        if (c.getConsensusAvgReturn4h() != null)
            sb.append(", 평균수익 ").append(c.getConsensusAvgReturn4h()).append("%");
        sb.append("\n• 분산 4h 적중률: ")
          .append(c.getDivergentAccuracy4h() != null ? c.getDivergentAccuracy4h() + "%" : "-");
        if (c.getDivergentAvgReturn4h() != null)
            sb.append(", 평균수익 ").append(c.getDivergentAvgReturn4h()).append("%");
        return sb.toString();
    }

    /** LLM 프롬프트용 시간대별 신호 품질 요약 */
    private String hourlyStatsSummary(AnalysisReport r) {
        List<AnalysisReport.HourlySignalQuality> stats = r.getHourlySignalStats();
        if (stats == null || stats.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        stats.stream()
                .filter(q -> q.getSignalCount() > 0)
                .forEach(q -> {
                    sb.append("• ").append(q.getHourBucket()).append(" KST: ")
                            .append(q.getSignalCount()).append("건");
                    if (q.getAccuracy4h()  != null) sb.append(", 적중률 ").append(q.getAccuracy4h()).append("%");
                    if (q.getAvgReturn4h() != null) sb.append(", 평균수익 ").append(q.getAvgReturn4h()).append("%");
                    sb.append("\n");
                });
        return sb.length() > 0 ? sb.toString().trim() : "없음";
    }

    private String regimeTransitionSummary(List<AnalysisReport.RegimeTransition> transitions) {
        if (transitions == null || transitions.isEmpty()) return "전환 없음";
        return transitions.stream()
                .map(t -> (t.getFromRegime() != null ? t.getFromRegime() : "초기") + "→" + t.getToRegime())
                .reduce((a, b) -> a + ", " + b)
                .orElse("없음");
    }

    /** LLM 프롬프트용 활성 세션 요약 */
    private String activeSessionsSummary(AnalysisReport r) {
        List<AnalysisReport.ActiveSessionInfo> sessions = r.getActiveSessions();
        if (sessions == null || sessions.isEmpty()) return "실행 중인 세션 없음";
        StringBuilder sb = new StringBuilder();
        sessions.forEach(s -> {
            sb.append("• ").append(s.getStrategyType())
                    .append(" ").append(s.getCoinPair())
                    .append(" [").append(s.getTimeframe()).append("]");
            if (s.getReturnPct() != null) {
                String sign = s.getReturnPct().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                sb.append(" 수익률 ").append(sign).append(s.getReturnPct()).append("%");
            }
            Map<String, BigDecimal> prices = r.getCoinPriceChanges();
            if (prices != null && prices.containsKey(s.getCoinPair())) {
                BigDecimal chg = prices.get(s.getCoinPair());
                String sign = chg.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                sb.append(", 12h 가격 ").append(sign).append(chg).append("%");
            }
            sb.append("\n");
        });
        return sb.toString().trim();
    }

    /** LLM 프롬프트용 코인별 포지션 통계 요약 */
    private String coinPositionStatsSummary(AnalysisReport r) {
        Map<String, AnalysisReport.CoinPositionStat> stats = r.getCoinPositionStats();
        if (stats == null || stats.isEmpty()) return "없음";
        StringBuilder sb = new StringBuilder();
        stats.forEach((coin, stat) ->
                sb.append("• ").append(coin)
                        .append(": 청산 ").append(stat.getClosedCount()).append("건")
                        .append(", 승률 ").append(stat.getWinRate() != null ? stat.getWinRate() : "-").append("%")
                        .append(", 손익 ").append(fmt(stat.getTotalPnl())).append("원\n"));
        return sb.toString().trim();
    }

    /** Notion 활성 세션 섹션 빌더 */
    private List<ObjectNode> buildActiveSessionsSection(AnalysisReport r) {
        List<List<String>> rows = new ArrayList<>();
        r.getActiveSessions().forEach(s -> {
            String returnStr = s.getReturnPct() != null
                    ? (s.getReturnPct().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + s.getReturnPct() + "%" : "-";
            String priceStr = "-";
            if (r.getCoinPriceChanges() != null && r.getCoinPriceChanges().containsKey(s.getCoinPair())) {
                BigDecimal chg = r.getCoinPriceChanges().get(s.getCoinPair());
                priceStr = (chg.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + chg + "%";
            }
            rows.add(List.of(
                    String.valueOf(s.getSessionId()),
                    s.getStrategyType(),
                    s.getCoinPair(),
                    s.getTimeframe(),
                    returnStr,
                    priceStr));
        });
        return List.of(
                notionClient.heading2("🤖 실행 중 세션"),
                notionClient.table(List.of("세션ID", "전략", "코인", "타임프레임", "수익률", "12h 가격변화"), rows),
                notionClient.divider());
    }
}
