package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.CandleDataId;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MarketDataCacheRepository extends JpaRepository<MarketDataCacheEntity, CandleDataId> {

    @Query("SELECT c FROM MarketDataCacheEntity c WHERE c.coinPair = :coinPair AND c.timeframe = :timeframe " +
            "AND c.time >= :start AND c.time <= :end ORDER BY c.time ASC")
    List<MarketDataCacheEntity> findCandles(@Param("coinPair") String coinPair,
                                            @Param("timeframe") String timeframe,
                                            @Param("start") Instant start,
                                            @Param("end") Instant end);

    @Query("SELECT c.coinPair, c.timeframe, MIN(c.time), MAX(c.time), COUNT(c) " +
            "FROM MarketDataCacheEntity c GROUP BY c.coinPair, c.timeframe ORDER BY c.coinPair, c.timeframe")
    List<Object[]> findDataSummary();

    /** 최근 캔들이 쌓이는 (코인, 타임프레임) — 결측 재수집 대상 (2026-10-19). */
    @Query("SELECT DISTINCT c.coinPair, c.timeframe FROM MarketDataCacheEntity c WHERE c.time > :since")
    List<Object[]> findActivePairs(@Param("since") Instant since);

    @Query("SELECT MAX(c.time) FROM MarketDataCacheEntity c WHERE c.coinPair = :coinPair AND c.timeframe = :timeframe")
    Instant findMaxTime(@Param("coinPair") String coinPair, @Param("timeframe") String timeframe);

    @Query("SELECT MIN(c.time) FROM MarketDataCacheEntity c WHERE c.coinPair = :coinPair AND c.timeframe = :timeframe")
    Instant findMinTime(@Param("coinPair") String coinPair, @Param("timeframe") String timeframe);
}
