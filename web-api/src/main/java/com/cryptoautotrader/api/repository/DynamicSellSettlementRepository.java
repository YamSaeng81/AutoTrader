package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.DynamicSellSettlementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 동적 세션 매도 정산 표식 (V72). {@link DynamicSellSettlementEntity} 참조. */
public interface DynamicSellSettlementRepository
        extends JpaRepository<DynamicSellSettlementEntity, String> {

    List<DynamicSellSettlementEntity> findByPositionId(Long positionId);
}
