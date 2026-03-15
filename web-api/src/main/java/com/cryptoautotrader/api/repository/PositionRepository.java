package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.PositionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /** 실전매매 세션에 연결된 포지션 수 카운트 (session_id가 있는 것만) */
    long countBySessionIdIsNotNullAndStatus(String status);

    /** 특정 세션의 특정 상태 포지션 조회 */
    List<PositionEntity> findBySessionIdAndStatus(Long sessionId, String status);

    /** 특정 세션의 전체 포지션 조회 */
    List<PositionEntity> findBySessionId(Long sessionId);

    /** 특정 세션 + 코인 + 상태 포지션 조회 */
    Optional<PositionEntity> findBySessionIdAndCoinPairAndStatus(Long sessionId, String coinPair, String status);
}
