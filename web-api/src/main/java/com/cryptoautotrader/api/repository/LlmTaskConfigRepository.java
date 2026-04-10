package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.LlmTaskConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LlmTaskConfigRepository extends JpaRepository<LlmTaskConfigEntity, Long> {
    Optional<LlmTaskConfigEntity> findByTaskName(String taskName);
}
