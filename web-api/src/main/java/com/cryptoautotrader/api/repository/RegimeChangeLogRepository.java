package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.RegimeChangeLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RegimeChangeLogRepository extends JpaRepository<RegimeChangeLogEntity, Long> {

    /** 최신 레짐 전환 기록 1건 조회 (현재 레짐 추적용) */
    @Query("SELECT r FROM RegimeChangeLogEntity r WHERE r.coinPair = :coinPair AND r.timeframe = :timeframe ORDER BY r.detectedAt DESC")
    List<RegimeChangeLogEntity> findLatestByCoinPairAndTimeframe(
            @Param("coinPair") String coinPair,
            @Param("timeframe") String timeframe,
            Pageable pageable);

    /** 최근 N건 이력 조회 (최신순) */
    @Query("SELECT r FROM RegimeChangeLogEntity r ORDER BY r.detectedAt DESC")
    List<RegimeChangeLogEntity> findRecent(Pageable pageable);

    default Optional<RegimeChangeLogEntity> findLatestOne(String coinPair, String timeframe) {
        List<RegimeChangeLogEntity> result = findLatestByCoinPairAndTimeframe(
                coinPair, timeframe, Pageable.ofSize(1));
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }
}
