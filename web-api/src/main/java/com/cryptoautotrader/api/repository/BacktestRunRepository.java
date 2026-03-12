package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.BacktestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestRunRepository extends JpaRepository<BacktestRunEntity, Long> {
    List<BacktestRunEntity> findAllByOrderByCreatedAtDesc();
    List<BacktestRunEntity> findByIdIn(List<Long> ids);
}
