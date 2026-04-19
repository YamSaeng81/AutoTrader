package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LlmProviderConfigEntity;
import com.cryptoautotrader.api.entity.LlmTaskConfigEntity;
import com.cryptoautotrader.api.entity.NewsItemCacheEntity;
import com.cryptoautotrader.api.entity.NewsSourceConfigEntity;
import com.cryptoautotrader.api.entity.NotionReportLogEntity;
import com.cryptoautotrader.api.llm.LlmProvider;
import com.cryptoautotrader.api.llm.LlmProviderRegistry;
import com.cryptoautotrader.api.llm.LlmRequest;
import com.cryptoautotrader.api.llm.LlmResponse;
import com.cryptoautotrader.api.llm.LlmTask;
import com.cryptoautotrader.api.llm.LlmTaskRouter;
import com.cryptoautotrader.api.llm.provider.MockProvider;
import com.cryptoautotrader.api.news.NewsAggregatorService;
import com.cryptoautotrader.api.news.NewsItem;
import com.cryptoautotrader.api.news.NewsSource;
import com.cryptoautotrader.api.news.NewsSourceRegistry;
import com.cryptoautotrader.api.report.AnalysisReport;
import com.cryptoautotrader.api.report.LogAnalyzerService;
import com.cryptoautotrader.api.report.NotionApiClient;
import com.cryptoautotrader.api.report.ReportComposer;
import com.cryptoautotrader.api.repository.LlmTaskConfigRepository;
import com.cryptoautotrader.api.repository.NewsItemCacheRepository;
import com.cryptoautotrader.api.repository.NewsSourceConfigRepository;
import com.cryptoautotrader.api.repository.NotionReportLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AI 파이프라인 단위 테스트 — Mockito 기반.
 *
 * <p>외부 의존성(DB, HTTP)을 Mock 처리하여 순수 비즈니스 로직만 검증한다.
 */
class AiPipelineTest {

    // ── LlmTaskRouter ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("LlmTaskRouter")
    class LlmTaskRouterTest {

        LlmTaskConfigRepository taskConfigRepo;
        LlmProviderRegistry registry;
        LlmTaskRouter router;

        MockProvider mockProvider;

        @BeforeEach
        void setUp() {
            taskConfigRepo = mock(LlmTaskConfigRepository.class);
            registry       = mock(LlmProviderRegistry.class);
            mockProvider   = new MockProvider();
            router         = new LlmTaskRouter(taskConfigRepo, registry, mock(com.cryptoautotrader.api.repository.LlmCallLogRepository.class));
        }

        @Test
        @DisplayName("task 설정 없으면 MockProvider로 폴백")
        void route_noConfig_fallsBackToMock() {
            when(taskConfigRepo.findByTaskName(LlmTask.LOG_SUMMARY.name())).thenReturn(Optional.empty());
            when(registry.get("MOCK")).thenReturn(mockProvider);

            LlmResponse resp = router.route(LlmTask.LOG_SUMMARY, "sys", "user");

            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getProviderName()).isEqualTo("MOCK");
        }

        @Test
        @DisplayName("task 비활성화 → success=false 반환")
        void route_disabledTask_returnsError() {
            LlmTaskConfigEntity config = LlmTaskConfigEntity.builder()
                    .taskName(LlmTask.SIGNAL_ANALYSIS.name())
                    .providerName("OPENAI")
                    .enabled(false)
                    .build();
            when(taskConfigRepo.findByTaskName(LlmTask.SIGNAL_ANALYSIS.name())).thenReturn(Optional.of(config));

            LlmResponse resp = router.route(LlmTask.SIGNAL_ANALYSIS, "sys", "user");

            assertThat(resp.isSuccess()).isFalse();
            assertThat(resp.getErrorMessage()).contains("비활성화");
            verifyNoInteractions(registry);
        }

        @Test
        @DisplayName("task 활성화 → 지정 provider로 라우팅")
        void route_enabledTask_usesConfiguredProvider() {
            LlmTaskConfigEntity config = LlmTaskConfigEntity.builder()
                    .taskName(LlmTask.LOG_SUMMARY.name())
                    .providerName("MOCK")
                    .enabled(true)
                    .build();
            when(taskConfigRepo.findByTaskName(LlmTask.LOG_SUMMARY.name())).thenReturn(Optional.of(config));
            when(registry.get("MOCK")).thenReturn(mockProvider);

            LlmResponse resp = router.route(LlmTask.LOG_SUMMARY, "sys", "user");

            assertThat(resp.isSuccess()).isTrue();
            verify(registry).get("MOCK");
        }
    }

    // ── NewsAggregatorService ──────────────────────────────────────────────────

    @Nested
    @DisplayName("NewsAggregatorService")
    class NewsAggregatorServiceTest {

        NewsSourceConfigRepository sourceConfigRepo;
        NewsItemCacheRepository    newsCacheRepo;
        NewsSourceRegistry         sourceRegistry;
        NewsAggregatorService      service;

        NewsSource mockSource;

        @BeforeEach
        void setUp() {
            sourceConfigRepo = mock(NewsSourceConfigRepository.class);
            newsCacheRepo    = mock(NewsItemCacheRepository.class);
            sourceRegistry   = mock(NewsSourceRegistry.class);
            mockSource       = mock(NewsSource.class);
            service = new NewsAggregatorService(sourceConfigRepo, newsCacheRepo, sourceRegistry);
        }

        @Test
        @DisplayName("신규 뉴스 아이템 정상 저장")
        void collectFromSourceManual_savesNewItems() {
            NewsSourceConfigEntity config = NewsSourceConfigEntity.builder()
                    .sourceId("test_rss")
                    .sourceType("RSS")
                    .enabled(true)
                    .build();
            when(sourceConfigRepo.findBySourceId("test_rss")).thenReturn(Optional.of(config));
            when(sourceRegistry.get("RSS")).thenReturn(mockSource);

            NewsItem item = NewsItem.builder()
                    .sourceId("test_rss")
                    .externalId("item-001")
                    .title("BTC 신고가 달성")
                    .category("crypto")
                    .publishedAt(Instant.now())
                    .build();
            when(mockSource.fetch(config)).thenReturn(List.of(item));
            when(newsCacheRepo.existsBySourceIdAndExternalId("test_rss", "item-001")).thenReturn(false);
            when(newsCacheRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sourceConfigRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<NewsItem> result = service.collectFromSourceManual("test_rss");

            assertThat(result).hasSize(1);
            verify(newsCacheRepo).save(any(NewsItemCacheEntity.class));
        }

        @Test
        @DisplayName("중복 externalId는 저장하지 않음")
        void collectFromSourceManual_skipsDuplicates() {
            NewsSourceConfigEntity config = NewsSourceConfigEntity.builder()
                    .sourceId("test_rss")
                    .sourceType("RSS")
                    .enabled(true)
                    .build();
            when(sourceConfigRepo.findBySourceId("test_rss")).thenReturn(Optional.of(config));
            when(sourceRegistry.get("RSS")).thenReturn(mockSource);

            NewsItem item = NewsItem.builder()
                    .sourceId("test_rss")
                    .externalId("item-001")
                    .title("중복 뉴스")
                    .category("crypto")
                    .publishedAt(Instant.now())
                    .build();
            when(mockSource.fetch(config)).thenReturn(List.of(item));
            // 이미 존재하는 항목
            when(newsCacheRepo.existsBySourceIdAndExternalId("test_rss", "item-001")).thenReturn(true);
            when(sourceConfigRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.collectFromSourceManual("test_rss");

            verify(newsCacheRepo, never()).save(any(NewsItemCacheEntity.class));
        }

        @Test
        @DisplayName("externalId가 null인 아이템은 저장 건너뜀")
        void collectFromSourceManual_skipsNullExternalId() {
            NewsSourceConfigEntity config = NewsSourceConfigEntity.builder()
                    .sourceId("test_rss")
                    .sourceType("RSS")
                    .enabled(true)
                    .build();
            when(sourceConfigRepo.findBySourceId("test_rss")).thenReturn(Optional.of(config));
            when(sourceRegistry.get("RSS")).thenReturn(mockSource);

            NewsItem item = NewsItem.builder()
                    .sourceId("test_rss")
                    .externalId(null)
                    .title("ID 없는 뉴스")
                    .category("crypto")
                    .publishedAt(Instant.now())
                    .build();
            when(mockSource.fetch(config)).thenReturn(List.of(item));
            when(sourceConfigRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.collectFromSourceManual("test_rss");

            verify(newsCacheRepo, never()).save(any());
            verify(newsCacheRepo, never()).existsBySourceIdAndExternalId(any(), any());
        }
    }

    // ── ReportComposer ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ReportComposer")
    class ReportComposerTest {

        LogAnalyzerService       logAnalyzer;
        LlmTaskRouter            llmTaskRouter;
        NotionApiClient          notionClient;
        NotionReportLogRepository reportLogRepo;
        ReportComposer           composer;

        @BeforeEach
        void setUp() {
            logAnalyzer   = mock(LogAnalyzerService.class);
            llmTaskRouter = mock(LlmTaskRouter.class);
            notionClient  = mock(NotionApiClient.class);
            reportLogRepo = mock(NotionReportLogRepository.class);
            composer = new ReportComposer(logAnalyzer, llmTaskRouter, notionClient, reportLogRepo);

            // save() → 인자 그대로 반환
            when(reportLogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // NotionApiClient 블록 빌더 메서드 — null 반환 방지 (buildBlocks 내부 호출)
            ObjectNode stub = new ObjectMapper().createObjectNode();
            when(notionClient.callout(anyString(), anyString(), anyString())).thenReturn(stub);
            when(notionClient.divider()).thenReturn(stub);
            when(notionClient.heading2(anyString())).thenReturn(stub);
            when(notionClient.paragraph(anyString())).thenReturn(stub);
            when(notionClient.table(any(), any())).thenReturn(stub);
        }

        @Test
        @DisplayName("Notion 미활성화 시 SUCCESS로 저장")
        void compose_notionDisabled_successWithoutPage() {
            when(logAnalyzer.analyze(any(), any())).thenReturn(minimalReport());
            when(llmTaskRouter.route(any(), anyString(), anyString()))
                    .thenReturn(LlmResponse.builder().success(true).content("요약").providerName("MOCK").build());
            when(notionClient.isEnabled()).thenReturn(false);

            NotionReportLogEntity result = composer.compose(
                    Instant.now().minusSeconds(3600), Instant.now());

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getNotionPageId()).isNull();
            verify(notionClient, never()).createPage(any(), any());
        }

        @Test
        @DisplayName("Notion 활성화 + pageId 반환 시 SUCCESS + URL 저장")
        void compose_notionEnabled_successWithPageUrl() {
            when(logAnalyzer.analyze(any(), any())).thenReturn(minimalReport());
            when(llmTaskRouter.route(any(), anyString(), anyString()))
                    .thenReturn(LlmResponse.builder().success(true).content("내용").providerName("MOCK").build());
            when(notionClient.isEnabled()).thenReturn(true);
            when(notionClient.getConfig("report_title_prefix")).thenReturn("[리포트]");
            when(notionClient.createPage(anyString(), any())).thenReturn("page-abc123");
            when(notionClient.pageUrl("page-abc123")).thenReturn("https://notion.so/page-abc123");

            NotionReportLogEntity result = composer.compose(
                    Instant.now().minusSeconds(3600), Instant.now());

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getNotionPageId()).isEqualTo("page-abc123");
            assertThat(result.getNotionPageUrl()).isEqualTo("https://notion.so/page-abc123");
        }

        @Test
        @DisplayName("Notion createPage null 반환 시 FAILED")
        void compose_notionPageNull_failed() {
            when(logAnalyzer.analyze(any(), any())).thenReturn(minimalReport());
            when(llmTaskRouter.route(any(), anyString(), anyString()))
                    .thenReturn(LlmResponse.builder().success(true).content("내용").providerName("MOCK").build());
            when(notionClient.isEnabled()).thenReturn(true);
            when(notionClient.getConfig(anyString())).thenReturn("[리포트]");
            when(notionClient.createPage(anyString(), any())).thenReturn(null);

            NotionReportLogEntity result = composer.compose(
                    Instant.now().minusSeconds(3600), Instant.now());

            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getErrorMessage()).contains("Notion");
        }

        @Test
        @DisplayName("LLM 실패해도 파이프라인 계속 진행")
        void compose_llmFails_stillContinues() {
            when(logAnalyzer.analyze(any(), any())).thenReturn(minimalReport());
            when(llmTaskRouter.route(any(), anyString(), anyString()))
                    .thenReturn(LlmResponse.builder().success(false).errorMessage("timeout").build());
            when(notionClient.isEnabled()).thenReturn(false);

            NotionReportLogEntity result = composer.compose(
                    Instant.now().minusSeconds(3600), Instant.now());

            // LLM 실패여도 파이프라인 자체는 SUCCESS (Notion 비활성화)
            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getLlmSummary()).contains("LLM 요약 실패");
        }

        @Test
        @DisplayName("예외 발생 시 FAILED 상태로 저장")
        void compose_exception_savedAsFailed() {
            when(logAnalyzer.analyze(any(), any())).thenThrow(new RuntimeException("DB 오류"));

            NotionReportLogEntity result = composer.compose(
                    Instant.now().minusSeconds(3600), Instant.now());

            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getErrorMessage()).contains("DB 오류");
        }

        private AnalysisReport minimalReport() {
            return AnalysisReport.builder()
                    .periodStart(Instant.now().minusSeconds(3600))
                    .periodEnd(Instant.now())
                    .totalSignals(10)
                    .buySignals(5)
                    .sellSignals(3)
                    .holdSignals(2)
                    .executedSignals(4)
                    .blockedSignals(1)
                    .closedPositions(2)
                    .winCount(1)
                    .lossCount(1)
                    .winRate(new BigDecimal("50.00"))
                    .totalRealizedPnl(new BigDecimal("15000"))
                    .currentRegime("TREND")
                    .regimeTransitions(List.of())
                    .strategyStats(Map.of())
                    .blockReasons(Map.of())
                    .build();
        }
    }
}
