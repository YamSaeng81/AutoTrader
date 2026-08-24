package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.BacktestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestRunRepository extends JpaRepository<BacktestRunEntity, Long> {
    List<BacktestRunEntity> findAllByOrderByCreatedAtDesc();
    List<BacktestRunEntity> findByIdIn(List<Long> ids);

    /**
     * 전략의 Walk Forward 실행 이력 — 최신순, 코인 무관.
     *
     * <p>2026-08-24: {@code WalkForwardValidationGate}가 코인을 모르는 상황(DYNAMIC 세션 생성 —
     * 아직 어떤 코인을 살지 정해지지 않음)에서 "이 전략이 검증된 코인이 하나라도 있는가"를
     * 판단하는 용도로만 쓴다. 코인이 정해진 경우(LIVE)는 아래
     * {@link #findByStrategyNameAndCoinPairAndIsWalkForwardTrueOrderByCreatedAtDesc}를 쓸 것 —
     * 전략은 같아도 코인마다 성적이 크게 갈리므로(Tier1/Tier2 표 참조), 코인을 특정할 수 있는데
     * 이 메서드로 판정하면 엉뚱한 코인의 결과가 섞여 들어간다.</p>
     */
    List<BacktestRunEntity> findByStrategyNameAndIsWalkForwardTrueOrderByCreatedAtDesc(String strategyName);

    /** 전략×코인 조합의 Walk Forward 실행 이력 — 최신순. 코인이 정해진 세션(LIVE) 판정용. */
    List<BacktestRunEntity> findByStrategyNameAndCoinPairAndIsWalkForwardTrueOrderByCreatedAtDesc(
            String strategyName, String coinPair);
}
