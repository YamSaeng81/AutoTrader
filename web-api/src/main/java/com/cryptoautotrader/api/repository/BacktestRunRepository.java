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

    /** 전략×코인 조합의 Walk Forward 실행 이력 — 최신순. 타임프레임을 모를 때만 쓴다. */
    List<BacktestRunEntity> findByStrategyNameAndCoinPairAndIsWalkForwardTrueOrderByCreatedAtDesc(
            String strategyName, String coinPair);

    /**
     * 전략×코인×타임프레임 조합의 Walk Forward 실행 이력 — 최신순.
     *
     * <p>2026-09-08 신설. 그전까지 게이트는 타임프레임을 <b>전혀 보지 않았다</b>. 08-24 에
     * 코인 축을 분리하면서 같은 문제가 타임프레임 축에 그대로 남아 있었던 것이다.</p>
     *
     * <p>같은 (전략, 코인) 이라도 H1 과 M15 는 다른 실행이므로, 타임프레임을 무시하면
     * <b>나중에 실행된 쪽이 다른 쪽 판정을 덮어쓴다</b> — 실행 순서가 판정을 좌우한다.
     * 운영 실태가 이 구분을 요구한다: 고정코인 PAPER 40세션은 전부 M15, 동적 12세션은
     * H1 6 / M15 6 이고, 2026-09-08 이전 WF 350건은 전부 H1 이었다.</p>
     */
    List<BacktestRunEntity> findByStrategyNameAndCoinPairAndTimeframeAndIsWalkForwardTrueOrderByCreatedAtDesc(
            String strategyName, String coinPair, String timeframe);
}
