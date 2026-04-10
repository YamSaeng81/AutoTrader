package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.NewsSourceConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NewsSourceConfigRepository extends JpaRepository<NewsSourceConfigEntity, Long> {
    Optional<NewsSourceConfigEntity> findBySourceId(String sourceId);
    List<NewsSourceConfigEntity> findAllByEnabledTrue();
}
