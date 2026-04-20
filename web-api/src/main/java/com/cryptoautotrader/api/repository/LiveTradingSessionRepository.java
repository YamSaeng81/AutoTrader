package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface LiveTradingSessionRepository extends JpaRepository<LiveTradingSessionEntity, Long> {

    /** 특정 상태의 세션 조회 */
    List<LiveTradingSessionEntity> findByStatus(String status);

    /** 전체 세션 최신순 조회 */
    List<LiveTradingSessionEntity> findAllByOrderByCreatedAtDesc();

    /** 특정 상태의 세션 수 */
    long countByStatus(String status);

    /** 활성 세션들의 initialCapital 합산 (Tier 2 §8 — 계좌 초과 배정 방지) */
    @Query("SELECT COALESCE(SUM(s.initialCapital), 0) FROM LiveTradingSessionEntity s WHERE s.status IN :statuses")
    BigDecimal sumInitialCapitalByStatusIn(@Param("statuses") List<String> statuses);

    /** 활성 세션들의 availableKrw 합산 (Tier 2 §8 — 거래소 잔고 drift 감지) */
    @Query("SELECT COALESCE(SUM(s.availableKrw), 0) FROM LiveTradingSessionEntity s WHERE s.status IN :statuses")
    BigDecimal sumAvailableKrwByStatusIn(@Param("statuses") List<String> statuses);

    /** 모의→실전 승격 검사용 — 특정 타입·상태의 세션 조회 */
    List<LiveTradingSessionEntity> findBySessionTypeAndStatus(String sessionType, String status);
}
