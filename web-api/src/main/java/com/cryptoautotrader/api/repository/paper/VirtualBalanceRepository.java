package com.cryptoautotrader.api.repository.paper;

import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VirtualBalanceRepository extends JpaRepository<VirtualBalanceEntity, Long> {

    List<VirtualBalanceEntity> findAllByOrderByIdDesc();

    long countByStatus(String status);

    List<VirtualBalanceEntity> findByStatusOrderByStartedAtAsc(String status);
}
