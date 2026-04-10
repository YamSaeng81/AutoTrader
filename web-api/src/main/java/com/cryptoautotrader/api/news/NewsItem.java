package com.cryptoautotrader.api.news;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 소스 무관 공통 뉴스 아이템 모델.
 */
@Getter
@Builder
public class NewsItem {

    /** 소스 식별자 (news_source_config.source_id) */
    private final String sourceId;

    /** 소스 내 고유 ID — 중복 방지용 */
    private final String externalId;

    private final String title;
    private final String url;

    /** 원문 요약 (소스가 제공하는 경우) — LLM 미사용 시 그대로 저장 */
    private final String originalSummary;

    /** CRYPTO / ECONOMY / STOCK / GENERAL */
    private final String category;

    private final Instant publishedAt;
}
