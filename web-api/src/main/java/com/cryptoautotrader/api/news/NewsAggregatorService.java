package com.cryptoautotrader.api.news;

import com.cryptoautotrader.api.entity.NewsItemCacheEntity;
import com.cryptoautotrader.api.entity.NewsSourceConfigEntity;
import com.cryptoautotrader.api.repository.NewsItemCacheRepository;
import com.cryptoautotrader.api.repository.NewsSourceConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 뉴스 수집 서비스.
 *
 * <p>활성화된 소스를 순회하며 fetch_interval_min 기준으로 수집 여부를 판단,
 * 신규 뉴스를 {@code news_item_cache} 테이블에 저장한다.
 *
 * <p>스케줄러: 15분마다 실행 — 각 소스의 fetch_interval_min과 last_fetched_at 비교.
 */
@Service
@RequiredArgsConstructor
public class NewsAggregatorService {

    private static final Logger log = LoggerFactory.getLogger(NewsAggregatorService.class);

    private final NewsSourceConfigRepository sourceConfigRepo;
    private final NewsItemCacheRepository newsCacheRepo;
    private final NewsSourceRegistry sourceRegistry;

    /**
     * 15분마다 실행 — 각 소스의 주기를 개별 체크.
     * initialDelay 3분: 앱 기동 직후 준비 시간.
     */
    @Scheduled(initialDelay = 3 * 60 * 1000, fixedDelay = 15 * 60 * 1000)
    @Transactional
    public void collectAll() {
        List<NewsSourceConfigEntity> sources = sourceConfigRepo.findAllByEnabledTrue();
        if (sources.isEmpty()) {
            log.debug("[NewsAggregator] 활성화된 뉴스 소스 없음");
            return;
        }

        for (NewsSourceConfigEntity sourceConfig : sources) {
            if (!isDue(sourceConfig)) continue;
            collectFromSource(sourceConfig);
        }
    }

    /**
     * 특정 소스 수동 수집 (관리 API 테스트용).
     */
    @Transactional
    public List<NewsItem> collectFromSourceManual(String sourceId) {
        NewsSourceConfigEntity config = sourceConfigRepo.findBySourceId(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("소스 없음: " + sourceId));
        return collectFromSource(config);
    }

    private List<NewsItem> collectFromSource(NewsSourceConfigEntity config) {
        NewsSource source = sourceRegistry.get(config.getSourceType());
        if (source == null) {
            log.warn("[NewsAggregator] 지원하지 않는 소스 타입: {} (sourceId={})",
                    config.getSourceType(), config.getSourceId());
            return List.of();
        }

        log.debug("[NewsAggregator] 수집 시작 — sourceId={}", config.getSourceId());
        List<NewsItem> items = source.fetch(config);
        int saved = 0;

        for (NewsItem item : items) {
            if (item.getExternalId() == null) continue;
            if (newsCacheRepo.existsBySourceIdAndExternalId(item.getSourceId(), item.getExternalId())) {
                continue; // 중복 스킵
            }
            NewsItemCacheEntity entity = NewsItemCacheEntity.builder()
                    .sourceId(item.getSourceId())
                    .externalId(item.getExternalId())
                    .title(item.getTitle())
                    .url(item.getUrl())
                    .summary(item.getOriginalSummary())
                    .category(item.getCategory())
                    .publishedAt(item.getPublishedAt())
                    .build();
            newsCacheRepo.save(entity);
            saved++;
        }

        config.setLastFetchedAt(Instant.now());
        sourceConfigRepo.save(config);

        log.info("[NewsAggregator] 수집 완료 — sourceId={} 전체={}건 신규={}건",
                config.getSourceId(), items.size(), saved);
        return items;
    }

    /** fetch_interval_min 기준으로 수집 시점 도래 여부 확인 */
    private boolean isDue(NewsSourceConfigEntity config) {
        if (config.getLastFetchedAt() == null) return true;
        return ChronoUnit.MINUTES.between(config.getLastFetchedAt(), Instant.now()) >= config.getFetchIntervalMin();
    }

    /** 특정 시간 이후 수집된 뉴스 조회 (보고서·브리핑 생성용) */
    public List<NewsItemCacheEntity> getNewsSince(Instant since) {
        return newsCacheRepo.findSince(since);
    }

    /** 카테고리별 최근 뉴스 조회 (대소문자 정규화) */
    public List<NewsItemCacheEntity> getRecentByCategory(String category, int limit) {
        return newsCacheRepo.findRecentByCategory(category.toUpperCase(),
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

    /**
     * 7일 이상 된 뉴스 캐시 자동 삭제.
     * 매일 새벽 3시(KST) 실행 — 테이블 무한 증가 방지.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public void cleanupOldCache() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        newsCacheRepo.deleteByFetchedAtBefore(cutoff);
        log.info("[NewsAggregator] 7일 이전 캐시 삭제 완료 (cutoff={})", cutoff);
    }
}
