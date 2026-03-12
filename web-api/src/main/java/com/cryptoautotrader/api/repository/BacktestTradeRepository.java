package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.BacktestTradeEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BacktestTradeRepository extends JpaRepository<BacktestTradeEntity, Long> {
    Page<BacktestTradeEntity> findByBacktestRunIdOrderByExecutedAtAsc(Long backtestRunId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM BacktestTradeEntity t WHERE t.backtestRunId = :backtestRunId")
    void deleteByBacktestRunId(@Param("backtestRunId") Long backtestRunId);

    @Modifying
    @Query("DELETE FROM BacktestTradeEntity t WHERE t.backtestRunId IN :backtestRunIds")
    void deleteByBacktestRunIdIn(@Param("backtestRunIds") List<Long> backtestRunIds);
}
