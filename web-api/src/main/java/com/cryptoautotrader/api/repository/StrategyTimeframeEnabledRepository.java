package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.StrategyTimeframeEnabledEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * (전략 × 타임프레임) 차단 목록 — V80 (2026-09-08).
 *
 * <p>행이 없으면 활성이다({@code StrategyEnablementGate} 참조). 차단 목록이지 허용 목록이 아니다.</p>
 */
@Repository
public interface StrategyTimeframeEnabledRepository
        extends JpaRepository<StrategyTimeframeEnabledEntity, StrategyTimeframeEnabledEntity.Key> {
}
