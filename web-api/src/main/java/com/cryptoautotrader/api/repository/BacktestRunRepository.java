package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.BacktestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestRunRepository extends JpaRepository<BacktestRunEntity, Long> {
    List<BacktestRunEntity> findAllByOrderByCreatedAtDesc();
    List<BacktestRunEntity> findByIdIn(List<Long> ids);

    /** 전략의 Walk Forward 실행 이력 — 최신순. 게이트가 "가장 최근 검증"만 본다. */
    List<BacktestRunEntity> findByStrategyNameAndIsWalkForwardTrueOrderByCreatedAtDesc(String strategyName);
}
