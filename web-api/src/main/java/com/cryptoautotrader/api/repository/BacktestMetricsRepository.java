package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.BacktestMetricsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BacktestMetricsRepository extends JpaRepository<BacktestMetricsEntity, Long> {
    Optional<BacktestMetricsEntity> findByBacktestRunIdAndSegment(Long backtestRunId, String segment);

    List<BacktestMetricsEntity> findByBacktestRunIdIn(List<Long> runIds);

    @Modifying
    @Query("DELETE FROM BacktestMetricsEntity m WHERE m.backtestRunId = :backtestRunId")
    void deleteByBacktestRunId(@Param("backtestRunId") Long backtestRunId);

    @Modifying
    @Query("DELETE FROM BacktestMetricsEntity m WHERE m.backtestRunId IN :backtestRunIds")
    void deleteByBacktestRunIdIn(@Param("backtestRunIds") List<Long> backtestRunIds);
}
