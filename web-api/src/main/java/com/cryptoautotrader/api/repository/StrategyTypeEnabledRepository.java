package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.StrategyTypeEnabledEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategyTypeEnabledRepository extends JpaRepository<StrategyTypeEnabledEntity, String> {
}
