package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LiveTradingSessionRepository extends JpaRepository<LiveTradingSessionEntity, Long> {

    /** 특정 상태의 세션 조회 */
    List<LiveTradingSessionEntity> findByStatus(String status);

    /** 전체 세션 최신순 조회 */
    List<LiveTradingSessionEntity> findAllByOrderByCreatedAtDesc();

    /** 특정 상태의 세션 수 */
    long countByStatus(String status);
}
