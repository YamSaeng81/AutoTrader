package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategyLogRepository extends JpaRepository<StrategyLogEntity, Long> {
    Page<StrategyLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
