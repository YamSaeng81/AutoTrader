package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.OrderSettlementDiscrepancyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderSettlementDiscrepancyRepository
        extends JpaRepository<OrderSettlementDiscrepancyEntity, Long> {

    List<OrderSettlementDiscrepancyEntity> findByOrderIdOrderByDetectedAtDesc(Long orderId);

    /** 🔴 사람이 확인하지 않은 불일치 — 이것이 남아 있으면 정산을 확정으로 보지 않는다. */
    List<OrderSettlementDiscrepancyEntity> findByResolvedAtIsNull();
}
