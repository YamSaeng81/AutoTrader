package com.cryptoautotrader.api.repository.paper;

import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaperPositionRepository extends JpaRepository<PaperPositionEntity, Long> {

    List<PaperPositionEntity> findBySessionId(Long sessionId);

    List<PaperPositionEntity> findBySessionIdAndStatus(Long sessionId, String status);

    Optional<PaperPositionEntity> findBySessionIdAndCoinPairAndStatus(Long sessionId, String coinPair, String status);

    @Modifying
    @Query("DELETE FROM PaperPositionEntity p WHERE p.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") Long sessionId);

    @Modifying
    @Query("DELETE FROM PaperPositionEntity p WHERE p.sessionId IN :sessionIds")
    void deleteBySessionIdIn(@Param("sessionIds") List<Long> sessionIds);
}
