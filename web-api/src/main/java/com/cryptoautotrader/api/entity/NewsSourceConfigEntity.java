package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "news_source_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsSourceConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소스 고유 식별자 (cryptopanic / coindesk_rss 등) */
    @Column(name = "source_id", nullable = false, unique = true, length = 50)
    private String sourceId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** API / RSS / CRAWLER */
    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    /** CRYPTO / ECONOMY / STOCK / GENERAL */
    @Column(nullable = false, length = 30)
    private String category;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(name = "api_key", length = 500)
    private String apiKey;

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private boolean enabled = false;

    /** 수집 주기 (분) */
    @Column(name = "fetch_interval_min", nullable = false)
    @Builder.Default
    private int fetchIntervalMin = 60;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    /** 소스별 추가 설정 JSON */
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
