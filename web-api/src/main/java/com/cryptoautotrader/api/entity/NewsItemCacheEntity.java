package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "news_item_cache",
       uniqueConstraints = @UniqueConstraint(columnNames = {"source_id", "external_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsItemCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false, length = 50)
    private String sourceId;

    /** 소스별 고유 ID — 중복 수집 방지용 */
    @Column(name = "external_id", length = 200)
    private String externalId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(length = 1000)
    private String url;

    /** LLM이 요약한 텍스트 (수집 시점에는 null, 이후 채워짐) */
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 30)
    private String category;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @PrePersist
    void prePersist() {
        if (fetchedAt == null) fetchedAt = Instant.now();
    }
}
