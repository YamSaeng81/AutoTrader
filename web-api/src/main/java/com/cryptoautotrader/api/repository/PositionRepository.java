package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.PositionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<PositionEntity, Long> {

    /** 특정 상태의 포지션 조회 (예: "OPEN") */
    List<PositionEntity> findByStatus(String status);

    /** 특정 코인+상태의 포지션 조회 */
    Optional<PositionEntity> findByCoinPairAndStatus(String coinPair, String status);

    /** 전체 포지션 최신순 조회 */
    List<PositionEntity> findAllByOrderByOpenedAtDesc();

    /** 전체 포지션 페이징 조회 */
    Page<PositionEntity> findAllByOrderByOpenedAtDesc(Pageable pageable);

    /** 열린 포지션 수 카운트 (전체 — RiskManagementService용) */
    long countByStatus(String status);

    /** 실제 체결된 포지션 수 카운트 (size > 0 — 고아 포지션 제외, 리스크 한도 계산용) */
    @Query("SELECT COUNT(p) FROM PositionEntity p WHERE p.status = :status AND p.size > 0")
    long countRealPositionsByStatus(@Param("status") String status);

    /** 실전매매 세션에 연결된 포지션 수 카운트 (session_id가 있는 것만) */
    long countBySessionIdIsNotNullAndStatus(String status);

    /** 특정 세션의 특정 상태 포지션 조회 */
    List<PositionEntity> findBySessionIdAndStatus(Long sessionId, String status);

    /** 특정 세션의 전체 포지션 조회 */
    List<PositionEntity> findBySessionId(Long sessionId);

    /** 특정 세션 + 코인 + 상태 포지션 조회 */
    Optional<PositionEntity> findBySessionIdAndCoinPairAndStatus(Long sessionId, String coinPair, String status);

    /** 서킷 브레이커용: 세션의 체결 완료 포지션을 closedAt 역순으로 조회 (연속 손실 계산) */
    List<PositionEntity> findBySessionIdAndStatusOrderByClosedAtDesc(Long sessionId, String status);

    /** N+1 방지: 여러 세션의 포지션을 한 번에 일괄 조회 */
    @Query("SELECT p FROM PositionEntity p WHERE p.sessionId IN :sessionIds")
    List<PositionEntity> findBySessionIdIn(@Param("sessionIds") List<Long> sessionIds);

    /** 분석 구간 청산 포지션 조회 (LogAnalyzerService용 — DB에서 기간 필터) */
    @Query("SELECT p FROM PositionEntity p WHERE p.status = 'CLOSED' AND p.closedAt >= :from AND p.closedAt <= :to ORDER BY p.closedAt DESC")
    List<PositionEntity> findClosedByPeriod(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to);

    /** 분석 구간 청산 포지션 조회 — 세션 타입(REAL/PAPER) 필터 (live_trading_session 조인) */
    @Query(value = "SELECT p.* FROM position p " +
            "JOIN live_trading_session s ON p.session_id = s.id " +
            "WHERE p.status = 'CLOSED' " +
            "  AND p.closed_at >= :from AND p.closed_at <= :to " +
            "  AND s.session_type = :sessionType " +
            "ORDER BY p.closed_at DESC",
            nativeQuery = true)
    List<PositionEntity> findClosedByPeriodAndSessionType(
            @Param("sessionType") String sessionType,
            @Param("from") java.time.Instant from,
            @Param("to") java.time.Instant to);

    /** 최근 청산 포지션 조회 (closedAt 역순) — 연속 손실 계산용 */
    @Query("SELECT p FROM PositionEntity p WHERE p.status = 'CLOSED' ORDER BY p.closedAt DESC")
    List<PositionEntity> findRecentClosed(org.springframework.data.domain.Pageable pageable);

    /**
     * 원자적 포지션 CLOSE — status='OPEN'인 경우에만 CLOSED로 변경.
     * 반환값 1: 이 트랜잭션이 성공적으로 닫음 → KRW 복원 진행.
     * 반환값 0: 다른 스레드(reconcile or executeSessionSell)가 이미 처리함 → KRW 복원 스킵.
     * 동시에 두 경로(reconcileOrphanBuyPositions + executeSessionSell)가 같은 포지션을 처리할 때
     * 이중 KRW 복원을 방지하는 용도로 사용한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PositionEntity p SET p.status = 'CLOSED', p.closedAt = :now WHERE p.id = :id AND p.status = 'OPEN'")
    int closeIfOpen(@Param("id") Long id, @Param("now") java.time.Instant now);

    /**
     * StrategyWeightOptimizer §6 — 종료 포지션의 실현 수익률을 (전략, 레짐) 기준으로 집계.
     *
     * <p>각 행: [strategyType(String), marketRegime(String), sumRealizedPnl(BigDecimal),
     * sumInvestedKrw(BigDecimal), tradeCount(Long)]</p>
     *
     * <p>수수료·슬리피지는 이미 {@code realizedPnl} 에 반영되어 있음
     * ({@code finalizeSellPosition} 이 (실체결가 × 수량 − 수수료 − entryFee) 로 기록).
     * {@code investedKrw > 0} 필터로 고아(빈) 포지션 제외.</p>
     */
    @Query(value = "SELECT s.strategy_type AS strategy, " +
            "       p.market_regime AS regime, " +
            "       COALESCE(SUM(p.realized_pnl), 0) AS sum_pnl, " +
            "       COALESCE(SUM(p.invested_krw), 0) AS sum_invested, " +
            "       COUNT(p.id) AS trade_count " +
            "FROM position p " +
            "JOIN live_trading_session s ON p.session_id = s.id " +
            "WHERE p.status = 'CLOSED' " +
            "  AND p.closed_at >= :since " +
            "  AND p.invested_krw > 0 " +
            "  AND p.market_regime IS NOT NULL " +
            "  AND s.session_type = 'REAL' " +
            "GROUP BY s.strategy_type, p.market_regime",
            nativeQuery = true)
    List<Object[]> aggregateRealizedReturnsByStrategyAndRegime(@Param("since") java.time.Instant since);

    /**
     * 지수 가중 최적화용 — 개별 CLOSED 포지션을 전략·레짐·closed_at 과 함께 반환.
     * StrategyWeightOptimizer 가 exp(-days/halfLife) 가중치를 Java 에서 적용한다.
     *
     * <p>각 행: [strategyType, marketRegime, realizedPnl, investedKrw, closedAt]</p>
     */
    @Query(value = "SELECT s.strategy_type, p.market_regime, p.realized_pnl, p.invested_krw, p.closed_at " +
            "FROM position p " +
            "JOIN live_trading_session s ON p.session_id = s.id " +
            "WHERE p.status = 'CLOSED' " +
            "  AND p.closed_at >= :since " +
            "  AND p.invested_krw > 0 " +
            "  AND p.market_regime IS NOT NULL " +
            "  AND s.session_type = 'REAL'",
            nativeQuery = true)
    List<Object[]> findClosedPositionsForWeighting(@Param("since") java.time.Instant since);

    /**
     * 지수 가중 최적화용 (코인별) — coin_pair 컬럼 포함.
     *
     * <p>각 행: [strategyType, marketRegime, coinPair, realizedPnl, investedKrw, closedAt]</p>
     */
    @Query(value = "SELECT s.strategy_type, p.market_regime, p.coin_pair, p.realized_pnl, p.invested_krw, p.closed_at " +
            "FROM position p " +
            "JOIN live_trading_session s ON p.session_id = s.id " +
            "WHERE p.status = 'CLOSED' " +
            "  AND p.closed_at >= :since " +
            "  AND p.invested_krw > 0 " +
            "  AND p.market_regime IS NOT NULL " +
            "  AND s.session_type = 'REAL'",
            nativeQuery = true)
    List<Object[]> findClosedPositionsForWeightingByCoin(@Param("since") java.time.Instant since);

    /**
     * 코인×전략×레짐 3차원 실현 수익률 집계 — StrategyWeightOptimizer 코인별 가중치 최적화용.
     *
     * <p>각 행: [strategyType, marketRegime, coinPair, sumRealizedPnl, sumInvestedKrw, tradeCount]</p>
     */
    @Query(value = "SELECT s.strategy_type AS strategy, " +
            "       p.market_regime AS regime, " +
            "       p.coin_pair AS coin, " +
            "       COALESCE(SUM(p.realized_pnl), 0) AS sum_pnl, " +
            "       COALESCE(SUM(p.invested_krw), 0) AS sum_invested, " +
            "       COUNT(p.id) AS trade_count " +
            "FROM position p " +
            "JOIN live_trading_session s ON p.session_id = s.id " +
            "WHERE p.status = 'CLOSED' " +
            "  AND p.closed_at >= :since " +
            "  AND p.invested_krw > 0 " +
            "  AND p.market_regime IS NOT NULL " +
            "  AND s.session_type = 'REAL' " +
            "GROUP BY s.strategy_type, p.market_regime, p.coin_pair",
            nativeQuery = true)
    List<Object[]> aggregateRealizedReturnsByCoinStrategyAndRegime(@Param("since") java.time.Instant since);
}
