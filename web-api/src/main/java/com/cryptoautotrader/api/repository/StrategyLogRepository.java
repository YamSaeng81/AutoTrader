package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface StrategyLogRepository extends JpaRepository<StrategyLogEntity, Long> {
    Page<StrategyLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<StrategyLogEntity> findAllBySessionTypeOrderByCreatedAtDesc(String sessionType, Pageable pageable);
    Page<StrategyLogEntity> findAllBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);
    Page<StrategyLogEntity> findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc(String sessionType, Long sessionId, Pageable pageable);

    /** 분석 구간 로그 조회 (LogAnalyzerService용 — DB에서 기간 필터) */
    @Query("SELECT l FROM StrategyLogEntity l WHERE l.createdAt >= :from AND l.createdAt <= :to ORDER BY l.createdAt DESC")
    List<StrategyLogEntity> findByPeriod(@Param("from") Instant from, @Param("to") Instant to);

    /** 4시간 경과 후 가격 평가가 아직 안 된 BUY/SELL 신호 조회 */
    @Query("SELECT l FROM StrategyLogEntity l WHERE l.signal IN ('BUY', 'SELL') " +
           "AND l.createdAt < :cutoff AND l.priceAfter4h IS NULL AND l.signalPrice IS NOT NULL " +
           "ORDER BY l.createdAt ASC")
    List<StrategyLogEntity> findPendingFor4hEval(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** 24시간 경과 후 가격 평가가 아직 안 된 BUY/SELL 신호 조회 */
    @Query("SELECT l FROM StrategyLogEntity l WHERE l.signal IN ('BUY', 'SELL') " +
           "AND l.createdAt < :cutoff AND l.priceAfter24h IS NULL AND l.signalPrice IS NOT NULL " +
           "ORDER BY l.createdAt ASC")
    List<StrategyLogEntity> findPendingFor24hEval(@Param("cutoff") Instant cutoff, Pageable pageable);
}
