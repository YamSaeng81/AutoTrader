package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.llm.LlmResponse;
import com.cryptoautotrader.api.llm.LlmTask;
import com.cryptoautotrader.api.llm.LlmTaskRouter;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 세션 LLM 분석이 <b>머리말만 만들고 끝나지 않는지</b> 고정한다.
 *
 * <p>2026-09-01 에 첫 분석 요청이 머리말만 도착했다. 원인은 이 클래스가 아니라
 * {@code ClaudeProvider} 가 {@code /content/0/text} 만 읽어 <b>본문을 빈 문자열로 만든 것</b>
 * 이었고({@code ClaudeResponseParsingTest} 참조), 여기서는 그 본문이 <b>실제로 전송 호출까지
 * 도달하는지</b>를 본다. 둘은 다른 명제다 — 응답을 잘 파싱해도 전송부에 안 실으면
 * 사용자가 보는 결과는 똑같이 비어 있다.</p>
 *
 * <p><b>서식은 MarkdownV2 를 그대로 쓴다.</b> 한때 평문 전환을 검토했으나,
 * 09-01 10:13 수신 메시지가 {@code **제목**} 서식을 정상 렌더링해 도착한 것을 확인했다.
 * 평문으로 바꾸면 잘 나오던 서식만 잃는다. 대신 서식이 <b>거부당했을 때만</b>
 * 평문으로 폴백하도록 {@code TelegramNotificationService} 에 넣었다
 * ({@code TelegramMarkdownFallbackTest} 참조).</p>
 */
class SessionLlmTelegramDeliveryTest {

    private LlmTaskRouter router;
    private TelegramNotificationService telegram;
    private SessionLlmAnalysisService service;

    @BeforeEach
    void setUp() {
        router = mock(LlmTaskRouter.class);
        telegram = mock(TelegramNotificationService.class);
        service = new SessionLlmAnalysisService(
                mock(StrategyLogRepository.class),
                mock(PositionRepository.class),
                router,
                telegram);
    }

    /** 실제 모델이 돌려준 것과 같은 모양 — 별표 서식이 섞여 있다(정상 렌더링된다). */
    private static final String MARKDOWN_HEAVY = """
            **1. 진단**: 표본 부족 — 24시간 692회 스캔 중 진입 1건.

            **2. 근거 (원 수치)**
            - 신호 692건 중 BUY 1건, HOLD 688건
            - 4h 사후 수익률 평균 -0.543%, 양수 비율 33.3%(1/3)
            """;

    private void llmReturns(String content) {
        when(router.route(any(LlmTask.class), anyString(), anyString()))
                .thenReturn(LlmResponse.builder()
                        .success(true).providerName("CLAUDE").modelUsed("claude-sonnet-5")
                        .content(content).promptTokens(100).completionTokens(500)
                        .build());
    }

    // ── 🔴 회귀 방지 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("분석 결과가 실제로 전송된다 — 머리말만 만들고 끝나지 않는다")
    void analysisIsActuallySent() {
        llmReturns(MARKDOWN_HEAVY);

        service.analyzeAsync("DYN_PAPER", 81L, 24, List.of(), List.of());

        verify(telegram).sendCustomNotification(anyString());
    }

    @Test
    @DisplayName("보낸 본문에 LLM 응답이 그대로 들어 있다 — 머리말만 가고 끝나지 않는다")
    void bodyIsIncludedNotJustHeader() {
        llmReturns(MARKDOWN_HEAVY);

        service.analyzeAsync("DYN_PAPER", 81L, 24, List.of(), List.of());

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendCustomNotification(sent.capture());

        assertThat(sent.getValue())
                .as("머리말")
                .contains("동적(모의) 세션 #81")
                .contains("claude-sonnet-5")
                .as("본문 — 파싱 버그 때는 이 자리가 통째로 비어 머리말만 나갔다")
                .contains("표본 부족")
                .contains("4h 사후 수익률");
    }

    @Test
    @DisplayName("LLM 실패도 알린다 — 조용히 끝나면 사람이 모른다")
    void failureIsAlsoReported() {
        when(router.route(any(LlmTask.class), anyString(), anyString()))
                .thenReturn(LlmResponse.error("CLAUDE",
                        "응답 본문을 추출하지 못했습니다 (outputTokens=900, stopReason=max_tokens, blocks=thinking)"));

        service.analyzeAsync("DYN_PAPER", 81L, 24, List.of(), List.of());

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendCustomNotification(sent.capture());
        assertThat(sent.getValue()).contains("LLM 분석 실패").contains("max_tokens");
    }

    @Test
    @DisplayName("4096자를 넘으면 나눠 보낸다 — 잘려서 결론이 사라지면 안 된다")
    void longAnalysisIsChunked() {
        llmReturns("가".repeat(8000));

        service.analyzeAsync("DYN_PAPER", 81L, 24, List.of(), List.of());

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(telegram, org.mockito.Mockito.atLeast(2)).sendCustomNotification(sent.capture());

        assertThat(sent.getAllValues())
                .as("각 조각은 텔레그램 한도(4096자) 안이어야 한다")
                .allSatisfy(chunk -> assertThat(chunk.length()).isLessThan(4096));
        assertThat(String.join("", sent.getAllValues()))
                .as("나눠 보내도 전체 내용은 남아 있어야 한다")
                .contains("가".repeat(100));
    }
}
