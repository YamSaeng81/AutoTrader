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
}
