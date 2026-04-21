package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.WeightOptimizerSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WeightOptimizerSnapshotRepository
        extends JpaRepository<WeightOptimizerSnapshotEntity, Long> {

    /**
     * (regime, coin_pair, strategy_name) 조합별로 가장 최신 가중치를 조회한다.
     *
     * <p>서버 재시작 시 WeightOverrideStore 복원에 사용.
     * H2 호환을 위해 서브쿼리 방식 사용 (DISTINCT ON 대신).
     */
    @Query("SELECT w FROM WeightOptimizerSnapshotEntity w " +
           "WHERE w.createdAt = (" +
           "  SELECT MAX(w2.createdAt) FROM WeightOptimizerSnapshotEntity w2 " +
           "  WHERE w2.regime = w.regime " +
           "  AND (w2.coinPair = w.coinPair OR (w2.coinPair IS NULL AND w.coinPair IS NULL)) " +
           "  AND w2.strategyName = w.strategyName" +
           ")")
    List<WeightOptimizerSnapshotEntity> findLatestPerKey();
}
