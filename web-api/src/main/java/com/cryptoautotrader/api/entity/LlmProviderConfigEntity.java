package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "llm_provider_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmProviderConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** OPENAI / OLLAMA / CLAUDE / MOCK */
    @Column(name = "provider_name", nullable = false, unique = true, length = 30)
    private String providerName;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** API 엔드포인트 base URL (Ollama: http://localhost:11434) */
    @Column(name = "base_url", length = 500)
    private String baseUrl;

    /** API 인증 키 */
    @Column(name = "api_key", length = 500)
    private String apiKey;

    /** 기본 모델명 */
    @Column(name = "default_model", nullable = false, length = 100)
    private String defaultModel;

    @Column(name = "timeout_seconds", nullable = false)
    @Builder.Default
    private int timeoutSeconds = 60;

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private boolean enabled = false;

    /** JSON 형태 추가 설정 */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
