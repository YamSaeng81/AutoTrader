package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.TelegramNotificationLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramNotificationLogRepository extends JpaRepository<TelegramNotificationLogEntity, Long> {

    Page<TelegramNotificationLogEntity> findAllByOrderBySentAtDesc(Pageable pageable);
}
