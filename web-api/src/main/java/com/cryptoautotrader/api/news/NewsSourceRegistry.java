package com.cryptoautotrader.api.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 뉴스 소스 레지스트리.
 * Spring이 관리하는 모든 {@link NewsSource} 구현체를 source_type → 인스턴스로 매핑.
 * 새 소스 타입 추가 시 {@code @Component}만 붙이면 자동 등록.
 */
@Component
public class NewsSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(NewsSourceRegistry.class);

    private final Map<String, NewsSource> sources;

    public NewsSourceRegistry(List<NewsSource> sourceList) {
        this.sources = sourceList.stream()
                .collect(Collectors.toMap(
                        s -> s.getSupportedType().toUpperCase(),
                        Function.identity()));
        log.info("[NewsSourceRegistry] 등록된 소스 타입: {}", this.sources.keySet());
    }

    /**
     * source_type으로 구현체 조회.
     * 지원하지 않는 타입이면 null 반환.
     */
    public NewsSource get(String sourceType) {
        return sources.get(sourceType.toUpperCase());
    }

    public Map<String, NewsSource> getAll() {
        return sources;
    }
}
