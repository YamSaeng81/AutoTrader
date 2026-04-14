package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.NightlySchedulerConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NightlySchedulerConfigRepository extends JpaRepository<NightlySchedulerConfigEntity, Long> {
}
