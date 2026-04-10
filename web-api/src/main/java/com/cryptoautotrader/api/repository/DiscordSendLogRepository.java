package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.DiscordSendLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DiscordSendLogRepository extends JpaRepository<DiscordSendLogEntity, Long> {

    @Query("SELECT d FROM DiscordSendLogEntity d ORDER BY d.createdAt DESC")
    List<DiscordSendLogEntity> findRecent(Pageable pageable);
}
