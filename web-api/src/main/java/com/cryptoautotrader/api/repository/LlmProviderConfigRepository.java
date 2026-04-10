package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.LlmProviderConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LlmProviderConfigRepository extends JpaRepository<LlmProviderConfigEntity, Long> {
    Optional<LlmProviderConfigEntity> findByProviderName(String providerName);
}
