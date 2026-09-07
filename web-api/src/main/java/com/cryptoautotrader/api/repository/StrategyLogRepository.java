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

    /**
     * 재기동 후 BLACK_SWAN 쿨다운 복원용 — 쿨다운 구간 내에 <b>가드가 실제로 차단한</b> 코인과
     * 마지막 차단 시각을 조회한다.
     *
     * <p>쿨다운 맵({@code blackSwanBlockedAt})은 인메모리라 재기동 시 사라진다. 2026-08-04
     * 배포 재기동 때 KRW-META2의 잔여 쿨다운(약 40분)이 실제로 소실됐다. 차단 사실 자체는
     * 이 테이블에 남으므로 별도 저장소(마이그레이션) 없이 복원할 수 있다.</p>
     *
     * <p>대상을 {@code BLACK_SWAN_GUARD 발동}으로 한정하는 것이 중요하다 — 쿨다운이 만든
     * 차단 로그({@code BLACK_SWAN 쿨다운 …})까지 포함하면 쿨다운이 스스로를 연장해
     * 영구 차단으로 굳는다.</p>
     *
     * <p>차단 <b>시각</b>뿐 아니라 차단 시점의 {@code signalPrice}도 함께 돌려준다 — 진입가
     * 가드(차단가보다 비싸면 재진입 금지)가 재기동 후에도 유지되어야 하기 때문이다.
     * 그룹 집계 대신 오래된 순으로 정렬해 돌려주므로, 호출부에서 맵에 순서대로 넣으면
     * 코인별 <b>가장 최근</b> 차단이 자연스럽게 남는다.</p>
     *
     * @return {@code [coinPair, 차단 시각, 차단 시점가]} 배열 목록 (오래된 순)
     */
    @Query("SELECT l.coinPair, l.createdAt, l.signalPrice FROM StrategyLogEntity l " +
           "WHERE l.sessionType = :sessionType AND l.createdAt >= :from " +
           "AND l.blockedReason LIKE CONCAT(:reasonPrefix, '%') " +
           "ORDER BY l.createdAt ASC")
    List<Object[]> findRecentBlockedCoins(@Param("sessionType") String sessionType,
                                          @Param("from") Instant from,
                                          @Param("reasonPrefix") String reasonPrefix);

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

    // ── HOLD 기준선(대조군) 백필용 ────────────────────────────────────────────
    //
    // 2026-09-07: 사후수익 백필이 BUY/SELL 에만 돌아 HOLD 74,462건(DYN_PAPER)이 전부 미평가였다.
    // 대조군이 없으면 "BUY 신호 사후 -1.25%" 가 신호가 나쁜 건지 그 시기 그 코인이 빠진 건지
    // 구분할 수 없다 — 코인·시각을 통제하니 실제로 -1.25% → -0.16% 로 바뀌었고 전략 순위가
    // 통째로 뒤집혔다(MTF_BTC -0.48% → +1.21%). 그 통제군을 상시 확보하기 위한 경로다.
    //
    // <b>(코인, 정시) 당 1건만 평가한다</b>: 같은 코인·같은 1시간의 HOLD 는 signal_price 가
    // 사실상 같아 사후수익이 동일하다. 전량(74,462건)을 부르면 Upbit 호출만 10배 낭비된다
    // (distinct coin-hour = 7,658건). NOT EXISTS 로 <b>이미 평가된 시간대를 통째로 제외</b>하는
    // 것이 핵심이다 — 이게 없으면 대표 1건이 평가되는 순간 같은 시간대의 다음 행이 새 대표가
    // 되어 결국 74,462건을 전부 부른다.
    @Query(value = """
            SELECT DISTINCT ON (l.coin_pair, date_trunc('hour', l.created_at)) l.*
            FROM strategy_log l
            WHERE l.signal = 'HOLD'
              AND l.created_at < :cutoff
              AND l.signal_price IS NOT NULL
              AND l.price_after_4h IS NULL
              AND NOT EXISTS (
                    SELECT 1 FROM strategy_log e
                    WHERE e.signal = 'HOLD'
                      AND e.coin_pair = l.coin_pair
                      AND e.created_at >= date_trunc('hour', l.created_at)
                      AND e.created_at <  date_trunc('hour', l.created_at) + interval '1 hour'
                      AND e.price_after_4h IS NOT NULL)
            ORDER BY l.coin_pair, date_trunc('hour', l.created_at), l.created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<StrategyLogEntity> findPendingHoldFor4hEval(@Param("cutoff") Instant cutoff,
                                                     @Param("limit") int limit);

    /** 24h 판. 4h 판과 동일 규칙 — price_after_24h 기준. */
    @Query(value = """
            SELECT DISTINCT ON (l.coin_pair, date_trunc('hour', l.created_at)) l.*
            FROM strategy_log l
            WHERE l.signal = 'HOLD'
              AND l.created_at < :cutoff
              AND l.signal_price IS NOT NULL
              AND l.price_after_24h IS NULL
              AND NOT EXISTS (
                    SELECT 1 FROM strategy_log e
                    WHERE e.signal = 'HOLD'
                      AND e.coin_pair = l.coin_pair
                      AND e.created_at >= date_trunc('hour', l.created_at)
                      AND e.created_at <  date_trunc('hour', l.created_at) + interval '1 hour'
                      AND e.price_after_24h IS NOT NULL)
            ORDER BY l.coin_pair, date_trunc('hour', l.created_at), l.created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<StrategyLogEntity> findPendingHoldFor24hEval(@Param("cutoff") Instant cutoff,
                                                      @Param("limit") int limit);

    // ── 필터 차단(HOLD) 집계용 — 건수만 사용 ────────────────────────────────────
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
