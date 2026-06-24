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

    // ── CSV export 용 (페이징 없이) ──────────────────────────────
    List<StrategyLogEntity> findAllBySessionIdOrderByCreatedAtDesc(Long sessionId);
    List<StrategyLogEntity> findAllBySessionTypeOrderByCreatedAtDesc(String sessionType);
    List<StrategyLogEntity> findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc(String sessionType, Long sessionId);

    /** 세션 인덱스용 — 로그에 존재하는 세션(번호·구분·전략·코인) distinct 조회 (삭제/모의 세션 포함) */
    @Query("SELECT DISTINCT l.sessionId, l.sessionType, l.strategyName, l.coinPair " +
           "FROM StrategyLogEntity l WHERE l.sessionId IS NOT NULL")
    List<Object[]> findDistinctSessionRefs();

    /** 분석 구간 로그 조회 (LogAnalyzerService용 — DB에서 기간 필터) */
    @Query("SELECT l FROM StrategyLogEntity l WHERE l.createdAt >= :from AND l.createdAt <= :to ORDER BY l.createdAt DESC")
    List<StrategyLogEntity> findByPeriod(@Param("from") Instant from, @Param("to") Instant to);

    /** 분석 구간 로그 조회 — 세션 타입(REAL/PAPER) 필터 */
    @Query("SELECT l FROM StrategyLogEntity l WHERE l.sessionType = :sessionType AND l.createdAt >= :from AND l.createdAt <= :to ORDER BY l.createdAt DESC")
    List<StrategyLogEntity> findByPeriodAndSessionType(@Param("sessionType") String sessionType, @Param("from") Instant from, @Param("to") Instant to);

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

    /** 신호 품질 집계용 — 4h 또는 24h 평가가 완료된 BUY/SELL 신호 조회 */
    @Query("SELECT l FROM StrategyLogEntity l WHERE l.signal IN ('BUY', 'SELL') " +
           "AND l.createdAt >= :from " +
           "AND (l.return4hPct IS NOT NULL OR l.return24hPct IS NOT NULL)")
    List<StrategyLogEntity> findEvaluatedSignals(@Param("from") Instant from);

    @Query("SELECT l FROM StrategyLogEntity l WHERE l.signal IN ('BUY', 'SELL') " +
           "AND l.sessionType = :sessionType AND l.createdAt >= :from " +
           "AND (l.return4hPct IS NOT NULL OR l.return24hPct IS NOT NULL)")
    List<StrategyLogEntity> findEvaluatedSignalsBySessionType(
            @Param("sessionType") String sessionType, @Param("from") Instant from);

    // ── 필터 차단(HOLD) 집계용 — forward return 없음(반사실), 건수만 사용 ──────────
    @Query("SELECT l FROM StrategyLogEntity l WHERE l.signal = 'HOLD' AND l.createdAt >= :from")
    List<StrategyLogEntity> findHoldLogsSince(@Param("from") Instant from);

    @Query("SELECT l FROM StrategyLogEntity l WHERE l.signal = 'HOLD' " +
           "AND l.sessionType = :sessionType AND l.createdAt >= :from")
    List<StrategyLogEntity> findHoldLogsSinceBySessionType(
            @Param("sessionType") String sessionType, @Param("from") Instant from);

    /** 모의→실전 승격 검사용 — 특정 세션의 평가 완료 BUY/SELL 신호 조회 */
    @Query("SELECT l FROM StrategyLogEntity l WHERE l.signal IN ('BUY', 'SELL') " +
           "AND l.sessionId = :sessionId " +
           "AND l.return4hPct IS NOT NULL")
    List<StrategyLogEntity> findEvaluatedSignalsBySessionId(@Param("sessionId") Long sessionId);
}
