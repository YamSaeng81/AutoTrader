package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.NotionReportLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NotionReportLogRepository extends JpaRepository<NotionReportLogEntity, Long> {

    @Query("SELECT r FROM NotionReportLogEntity r ORDER BY r.createdAt DESC")
    List<NotionReportLogEntity> findRecent(Pageable pageable);
}
