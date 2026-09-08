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
     * (regime, coin_pair, <b>timeframe</b>, strategy_name) 조합별로 가장 최신 가중치를 조회한다.
     *
     * <p>서버 재시작 시 WeightOverrideStore 복원에 사용.
     * H2 호환을 위해 서브쿼리 방식 사용 (DISTINCT ON 대신).</p>
     *
     * <p><b>2026-09-08 (V79): 키에 timeframe 추가.</b> 없으면 같은 (레짐, 코인) 의 H1 스냅샷과
     * M15 스냅샷이 서로를 밀어내 <b>마지막에 저장된 쪽만 복원된다</b> — 재시작할 때마다
     * 어느 타임프레임 가중치가 살아남을지가 저장 순서에 좌우된다.
     * NULL(타임프레임 무관) 행은 자기들끼리 별도 키를 이루므로 폴백용으로 그대로 남는다.</p>
     */
    @Query("SELECT w FROM WeightOptimizerSnapshotEntity w " +
           "WHERE w.createdAt = (" +
           "  SELECT MAX(w2.createdAt) FROM WeightOptimizerSnapshotEntity w2 " +
           "  WHERE w2.regime = w.regime " +
           "  AND (w2.coinPair = w.coinPair OR (w2.coinPair IS NULL AND w.coinPair IS NULL)) " +
           "  AND (w2.timeframe = w.timeframe OR (w2.timeframe IS NULL AND w.timeframe IS NULL)) " +
           "  AND w2.strategyName = w.strategyName" +
           ")")
    List<WeightOptimizerSnapshotEntity> findLatestPerKey();
}
