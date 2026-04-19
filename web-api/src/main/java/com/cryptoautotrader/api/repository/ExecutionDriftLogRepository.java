package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.ExecutionDriftLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface ExecutionDriftLogRepository extends JpaRepository<ExecutionDriftLogEntity, Long> {

    /** 전략별 최근 N건 drift 조회 */
    List<ExecutionDriftLogEntity> findTop50ByStrategyTypeOrderByExecutedAtDesc(String strategyType);

    /** 전략별 기간 내 평균 slippage */
    @Query("""
        SELECT COALESCE(AVG(d.slippagePct), 0)
          FROM ExecutionDriftLogEntity d
         WHERE d.strategyType = :strategyType
           AND d.executedAt >= :since
        """)
    BigDecimal avgSlippagePctSince(@Param("strategyType") String strategyType,
                                   @Param("since") Instant since);

    /** 세션별 최근 N건 drift 조회 */
    List<ExecutionDriftLogEntity> findTop20BySessionIdOrderByExecutedAtDesc(Long sessionId);
}
