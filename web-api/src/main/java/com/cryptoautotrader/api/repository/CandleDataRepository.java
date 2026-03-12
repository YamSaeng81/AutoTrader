package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.CandleDataEntity;
import com.cryptoautotrader.api.entity.CandleDataId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CandleDataRepository extends JpaRepository<CandleDataEntity, CandleDataId> {

    @Query("SELECT c FROM CandleDataEntity c WHERE c.coinPair = :coinPair AND c.timeframe = :timeframe " +
            "AND c.time >= :start AND c.time <= :end ORDER BY c.time ASC")
    List<CandleDataEntity> findCandles(@Param("coinPair") String coinPair,
                                        @Param("timeframe") String timeframe,
                                        @Param("start") Instant start,
                                        @Param("end") Instant end);

    @Query("SELECT c.coinPair, c.timeframe, MIN(c.time), MAX(c.time), COUNT(c) " +
            "FROM CandleDataEntity c GROUP BY c.coinPair, c.timeframe ORDER BY c.coinPair, c.timeframe")
    List<Object[]> findDataSummary();

    /** 특정 코인+타임프레임 전체 삭제 */
    @Modifying
    @Query("DELETE FROM CandleDataEntity c WHERE c.coinPair = :coinPair AND c.timeframe = :timeframe")
    int deleteByPairAndTimeframe(@Param("coinPair") String coinPair,
                                 @Param("timeframe") String timeframe);

    /** 특정 코인 전체 타임프레임 삭제 */
    @Modifying
    @Query("DELETE FROM CandleDataEntity c WHERE c.coinPair = :coinPair")
    int deleteByPair(@Param("coinPair") String coinPair);
}
