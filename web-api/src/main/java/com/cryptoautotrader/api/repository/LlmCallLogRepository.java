package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.LlmCallLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLogEntity, Long> {

    Page<LlmCallLogEntity> findAllByOrderByCalledAtDesc(Pageable pageable);

    Page<LlmCallLogEntity> findByTaskNameOrderByCalledAtDesc(String taskName, Pageable pageable);

    Page<LlmCallLogEntity> findByProviderNameOrderByCalledAtDesc(String providerName, Pageable pageable);

    /** 기간 내 호출 건수 */
    long countByCalledAtBetween(Instant from, Instant to);

    /** 기간 내 총 토큰 합계 */
    @Query("SELECT COALESCE(SUM(l.promptTokens + l.completionTokens), 0) FROM LlmCallLogEntity l WHERE l.calledAt BETWEEN :from AND :to")
    long sumTotalTokensBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** task별 토큰 합계 */
    @Query("SELECT l.taskName, SUM(l.promptTokens), SUM(l.completionTokens) FROM LlmCallLogEntity l GROUP BY l.taskName")
    List<Object[]> sumTokensByTask();

    /** provider별 토큰 합계 */
    @Query("SELECT l.providerName, SUM(l.promptTokens), SUM(l.completionTokens) FROM LlmCallLogEntity l GROUP BY l.providerName")
    List<Object[]> sumTokensByProvider();

    /** 최근 N건 */
    List<LlmCallLogEntity> findTop50ByOrderByCalledAtDesc();
}
