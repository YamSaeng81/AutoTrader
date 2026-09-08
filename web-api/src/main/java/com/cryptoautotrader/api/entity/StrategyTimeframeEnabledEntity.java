package com.cryptoautotrader.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * (전략 × 타임프레임) 차단 목록 — {@code strategy_timeframe_enabled} 매핑 (V80, 2026-09-08).
 *
 * <h3>왜 이 테이블이 따로 있나</h3>
 * <p>kill criteria 의 판정 단위는 <b>세션(= 전략 × 타임프레임)</b> 인데
 * {@code strategy_type_enabled} 는 전략명만 키로 쓴다 — 차단이 판정보다 한 단계 거칠다.
 * 그래서 "모든 변형이 죽었을 때만" 전략을 끄도록 보수적으로 우회했고, 그 결과
 * <b>타임프레임 단위 폐기는 재생성을 전혀 막지 못했다</b>:</p>
 *
 * <pre>
 *   MEANREV_BB@M15 KILL  →  세션 정지                          O
 *                        →  MEANREV_BB@M15 새 세션 생성 차단?   X  (아무도 안 막았다)
 * </pre>
 *
 * <p>{@code KILL_CRITERIA.md} §5 가 전략 비활성화를 두는 이유가 바로 "세션만 정지하면 같은
 * 전략으로 새 세션을 만들어 재개할 수 있다" 인데, 그 목적이 타임프레임 단위에서는 달성되지
 * 않았다. 이 테이블이 그 층을 채운다.</p>
 *
 * <p><b>부재 = 활성.</b> {@code strategy_type_enabled} 과 같은 규칙 — 행이 없으면 허용한다.
 * 이 테이블은 차단 목록이지 허용 목록이 아니다.</p>
 */
@Entity
@Table(name = "strategy_timeframe_enabled")
@IdClass(StrategyTimeframeEnabledEntity.Key.class)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class StrategyTimeframeEnabledEntity {

    @Id
    @Column(name = "strategy_name", length = 100)
    private String strategyName;

    @Id
    @Column(name = "timeframe", length = 10)
    private String timeframe;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    /** 왜 껐는지 — 판정 코드와 사유. 부활 판단이 근거 없이 이뤄지지 않도록 남긴다. */
    @Column(name = "disabled_reason")
    private String disabledReason;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
        if (isActive == null) isActive = Boolean.TRUE;
    }

    /** 복합 키 (전략명, 타임프레임). */
    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class Key implements Serializable {
        private String strategyName;
        private String timeframe;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(strategyName, k.strategyName)
                    && Objects.equals(timeframe, k.timeframe);
        }

        @Override
        public int hashCode() {
            return Objects.hash(strategyName, timeframe);
        }
    }
}
