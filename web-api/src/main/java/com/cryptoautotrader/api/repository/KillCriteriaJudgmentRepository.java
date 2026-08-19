package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.KillCriteriaJudgmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KillCriteriaJudgmentRepository extends JpaRepository<KillCriteriaJudgmentEntity, Long> {

    /** 특정 전략의 판정 이력 (최신순) — "이 전략이 언제 왜 걸렸는가" 조회용. */
    List<KillCriteriaJudgmentEntity> findByStrategyTypeOrderByEvaluatedAtDesc(String strategyType);

    /** 전체 판정 이력 (최신순). */
    List<KillCriteriaJudgmentEntity> findAllByOrderByEvaluatedAtDesc();
}
