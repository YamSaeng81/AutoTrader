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
}
