package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategyLogRepository extends JpaRepository<StrategyLogEntity, Long> {
    Page<StrategyLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<StrategyLogEntity> findAllBySessionTypeOrderByCreatedAtDesc(String sessionType, Pageable pageable);
    Page<StrategyLogEntity> findAllBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);
    Page<StrategyLogEntity> findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc(String sessionType, Long sessionId, Pageable pageable);
}
