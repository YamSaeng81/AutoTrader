package com.cryptoautotrader.api.news;

import com.cryptoautotrader.api.entity.NewsSourceConfigEntity;

import java.util.List;

/**
 * 뉴스 소스 추상화 인터페이스.
 *
 * <p>구현체: {@link source.CryptoPanicSource}, {@link source.RssNewsSource},
 * {@link source.CoinGeckoTrendingSource}
 *
 * <p>새 소스 추가 시 이 인터페이스만 구현하면 {@link NewsSourceRegistry}에 자동 등록된다.
 * (source_type 문자열로 매핑)
 */
public interface NewsSource {

    /**
     * 소스 타입 식별자 (news_source_config.source_type과 매핑).
     * 예: "API", "RSS", "CRAWLER"
     * 단일 구현체가 여러 URL을 처리할 수 있도록 type 기반 매핑 사용.
     */
    String getSupportedType();

    /**
     * 설정 기반 뉴스 수집.
     *
     * @param config DB에서 조회한 소스 설정 (url, api_key, config_json 등)
     * @return 수집된 뉴스 목록 (실패 시 빈 리스트 반환, 예외 throw 금지)
     */
    List<NewsItem> fetch(NewsSourceConfigEntity config);
}
