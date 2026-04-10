package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "notion_report_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotionReportLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ANALYSIS / MORNING */
    @Column(name = "report_type", nullable = false, length = 30)
    @Builder.Default
    private String reportType = "ANALYSIS";

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    /** PENDING / SUCCESS / FAILED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "notion_page_id", length = 100)
    private String notionPageId;

    @Column(name = "notion_page_url", length = 500)
    private String notionPageUrl;

    /** LLM 로그 요약 텍스트 */
    @Column(name = "llm_summary", columnDefinition = "TEXT")
    private String llmSummary;

    /** LLM 전략 분석 텍스트 */
    @Column(name = "llm_analysis", columnDefinition = "TEXT")
    private String llmAnalysis;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
