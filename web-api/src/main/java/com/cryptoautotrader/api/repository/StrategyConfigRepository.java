package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.StrategyConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategyConfigRepository extends JpaRepository<StrategyConfigEntity, Long> {
    List<StrategyConfigEntity> findAllByOrderByCreatedAtDesc();

    /** manualOverride=false 인 전략 설정 목록 조회 (자동 스위칭 대상) */
    List<StrategyConfigEntity> findAllByManualOverrideFalse();

    /** 활성화된 전략 설정 목록 조회 */
    List<StrategyConfigEntity> findByIsActive(Boolean isActive);

    /** 특정 코인의 활성 전략 조회 */
    List<StrategyConfigEntity> findByCoinPairAndIsActive(String coinPair, Boolean isActive);
}
