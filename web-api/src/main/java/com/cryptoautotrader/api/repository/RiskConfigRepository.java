package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.RiskConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RiskConfigRepository extends JpaRepository<RiskConfigEntity, Long> {

    /** 최신 리스크 설정 조회 (id 기준 내림차순 첫 번째) */
    Optional<RiskConfigEntity> findTopByOrderByIdDesc();
}
