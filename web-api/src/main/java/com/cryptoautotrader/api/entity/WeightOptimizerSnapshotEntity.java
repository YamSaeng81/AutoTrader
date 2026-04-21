package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 전략 가중치 최적화 결과 스냅샷 — weight_optimizer_snapshot 테이블 매핑.
 *
 * <p>StrategyWeightOptimizer가 optimize() 실행 후 WeightOverrideStore에 저장한 가중치를
 * DB에 영속화한다. 서버 재시작 시 이 테이블에서 최신 가중치를 복원해 WeightOverrideStore를 초기화한다.
 *
 * <p>레짐 레벨: {@code coinPair} = NULL
 * 코인 레벨: {@code coinPair} = "KRW-BTC" 등
 */
@Entity
@Table(name = "weight_optimizer_snapshot")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
public class WeightOptimizerSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 레짐: TREND / RANGE / VOLATILITY */
    @Column(name = "regime", nullable = false, length = 50)
    private String regime;

    /** 코인 쌍 (레짐 레벨이면 NULL) */
    @Column(name = "coin_pair", length = 30)
    private String coinPair;

    /** 전략명: COMPOSITE_BREAKOUT / COMPOSITE_MOMENTUM 등 */
    @Column(name = "strategy_name", nullable = false, length = 100)
    private String strategyName;

    /** 정규화된 가중치 (0.0 ~ 1.0, 레짐 내 합계 = 1.0) */
    @Column(name = "weight", nullable = false, precision = 8, scale = 6)
    private BigDecimal weight;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
