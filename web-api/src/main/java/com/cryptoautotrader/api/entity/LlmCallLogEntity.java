package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * LLM 호출 기록.
 * - 요청/응답 전문 보존
 * - 토큰 사용량 추적 (비용 관리)
 * - 소요 시간 측정
 */
@Entity
@Table(name = "llm_call_log", indexes = {
        @Index(name = "idx_llm_call_log_task", columnList = "task_name"),
        @Index(name = "idx_llm_call_log_called_at", columnList = "called_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmCallLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 작업 유형 (LOG_SUMMARY / SIGNAL_ANALYSIS / NEWS_SUMMARY / REPORT_NARRATION) */
    @Column(name = "task_name", nullable = false, length = 50)
    private String taskName;

    /** 사용된 프로바이더 (CLAUDE / OPENAI / OLLAMA / MOCK) */
    @Column(name = "provider_name", nullable = false, length = 30)
    private String providerName;

    /** 실제 사용된 모델명 */
    @Column(name = "model_used", length = 100)
    private String modelUsed;

    /** 시스템 프롬프트 전문 */
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    /** 유저 프롬프트 전문 */
    @Column(name = "user_prompt", columnDefinition = "TEXT")
    private String userPrompt;

    /** LLM 응답 내용 전문 */
    @Column(name = "response_content", columnDefinition = "TEXT")
    private String responseContent;

    /** 입력 토큰 수 */
    @Column(name = "prompt_tokens")
    @Builder.Default
    private int promptTokens = 0;

    /** 출력 토큰 수 */
    @Column(name = "completion_tokens")
    @Builder.Default
    private int completionTokens = 0;

    /** 성공 여부 */
    @Column(name = "success", nullable = false)
    @Builder.Default
    private boolean success = false;

    /** 실패 시 오류 메시지 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** API 호출 소요 시간 (ms) */
    @Column(name = "duration_ms")
    @Builder.Default
    private long durationMs = 0;

    @Column(name = "called_at", nullable = false)
    private Instant calledAt;

    @PrePersist
    void prePersist() {
        if (calledAt == null) calledAt = Instant.now();
    }

    /** 입력 + 출력 토큰 합계 */
    @Transient
    public int getTotalTokens() {
        return promptTokens + completionTokens;
    }
}
