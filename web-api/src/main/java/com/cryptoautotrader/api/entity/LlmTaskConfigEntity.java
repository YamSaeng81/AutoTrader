package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "llm_task_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmTaskConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LOG_SUMMARY / SIGNAL_ANALYSIS / NEWS_SUMMARY / REPORT_NARRATION */
    @Column(name = "task_name", nullable = false, unique = true, length = 50)
    private String taskName;

    /** llm_provider_config.provider_name 참조 */
    @Column(name = "provider_name", nullable = false, length = 30)
    private String providerName;

    /** NULL이면 provider default_model 사용 */
    @Column(length = 100)
    private String model;

    @Column(precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal temperature = new BigDecimal("0.3");

    @Column(name = "max_tokens")
    @Builder.Default
    private int maxTokens = 2000;

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
