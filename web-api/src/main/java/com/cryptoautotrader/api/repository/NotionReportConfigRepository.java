package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.NotionReportConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotionReportConfigRepository extends JpaRepository<NotionReportConfigEntity, Long> {
    Optional<NotionReportConfigEntity> findByConfigKey(String configKey);
}
