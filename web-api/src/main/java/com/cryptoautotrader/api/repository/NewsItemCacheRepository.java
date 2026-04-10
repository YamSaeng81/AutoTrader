package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.NewsItemCacheEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NewsItemCacheRepository extends JpaRepository<NewsItemCacheEntity, Long> {

    Optional<NewsItemCacheEntity> findBySourceIdAndExternalId(String sourceId, String externalId);

    boolean existsBySourceIdAndExternalId(String sourceId, String externalId);

    /** 카테고리별 최근 뉴스 */
    @Query("SELECT n FROM NewsItemCacheEntity n WHERE n.category = :category ORDER BY n.fetchedAt DESC")
    List<NewsItemCacheEntity> findRecentByCategory(@Param("category") String category, Pageable pageable);

    /** 특정 시간 이후 수집된 전체 뉴스 */
    @Query("SELECT n FROM NewsItemCacheEntity n WHERE n.fetchedAt >= :since ORDER BY n.fetchedAt DESC")
    List<NewsItemCacheEntity> findSince(@Param("since") Instant since);

    /** 요약이 비어있는 뉴스 (LLM 요약 대상) */
    @Query("SELECT n FROM NewsItemCacheEntity n WHERE n.summary IS NULL ORDER BY n.fetchedAt DESC")
    List<NewsItemCacheEntity> findPendingSummary(Pageable pageable);

    /** 특정 시각 이전에 수집된 뉴스 일괄 삭제 (캐시 정리용) */
    void deleteByFetchedAtBefore(Instant cutoff);
}
