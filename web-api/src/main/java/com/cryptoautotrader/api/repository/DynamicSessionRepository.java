package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DynamicSessionRepository extends JpaRepository<DynamicSessionEntity, Long> {

    List<DynamicSessionEntity> findByStatus(String status);

    List<DynamicSessionEntity> findAllByOrderByCreatedAtDesc();

    @Query("SELECT COUNT(s) FROM DynamicSessionEntity s WHERE s.status = 'RUNNING'")
    long countRunning();
}
