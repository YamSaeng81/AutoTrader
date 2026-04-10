package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.DiscordChannelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscordChannelConfigRepository extends JpaRepository<DiscordChannelConfigEntity, Long> {
    Optional<DiscordChannelConfigEntity> findByChannelType(String channelType);
    List<DiscordChannelConfigEntity> findAllByEnabledTrue();
}
