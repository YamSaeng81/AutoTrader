package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.BacktestJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestJobRepository extends JpaRepository<BacktestJobEntity, Long> {
    List<BacktestJobEntity> findAllByOrderByCreatedAtDesc();
    List<BacktestJobEntity> findByStatusIn(List<String> statuses);
}
