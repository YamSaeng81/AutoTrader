package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 일일 운영 건전성 점검 스냅샷 — V65 daily_health_snapshot 테이블 매핑.
 *
 * <p>{@link com.cryptoautotrader.api.service.OperationalHealthCheckService}가 매일 자동 기록한다.
 * 운영자가 인시던트마다 psycopg2로 직접 조회하던 4대 점검(잔고 정합성·주문 시퀀스 갭·
 * 유령 포지션·time stop 없이 장기 고착된 포지션)을 이력으로 남긴다.
 */
@Entity
@Table(name = "daily_health_snapshot")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyHealthSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "balance_mismatch_count", nullable = false)
    private int balanceMismatchCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "balance_mismatch_detail", columnDefinition = "jsonb")
    private List<Map<String, Object>> balanceMismatchDetail;

    @Column(name = "order_sequence_gap", nullable = false)
    private int orderSequenceGap;

    /** false면 시퀀스 갭 조회 자체가 실패한 것(H2 테스트 등 Postgres 시퀀스가 없는 환경) — 0(정상)과 구분 */
    @Column(name = "sequence_gap_checked", nullable = false)
    private boolean sequenceGapChecked;

    @Column(name = "ghost_position_count", nullable = false)
    private int ghostPositionCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ghost_position_detail", columnDefinition = "jsonb")
    private List<Map<String, Object>> ghostPositionDetail;

    @Column(name = "stuck_position_count", nullable = false)
    private int stuckPositionCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stuck_position_detail", columnDefinition = "jsonb")
    private List<Map<String, Object>> stuckPositionDetail;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (checkedAt == null) checkedAt = Instant.now();
        if (createdAt == null) createdAt = Instant.now();
    }

    // ── 명시적 getter ──────────────────────────────────

    public Long getId() { return id; }
    public Instant getCheckedAt() { return checkedAt; }
    public int getBalanceMismatchCount() { return balanceMismatchCount; }
    public List<Map<String, Object>> getBalanceMismatchDetail() { return balanceMismatchDetail; }
    public int getOrderSequenceGap() { return orderSequenceGap; }
    public boolean isSequenceGapChecked() { return sequenceGapChecked; }
    public int getGhostPositionCount() { return ghostPositionCount; }
    public List<Map<String, Object>> getGhostPositionDetail() { return ghostPositionDetail; }
    public int getStuckPositionCount() { return stuckPositionCount; }
    public List<Map<String, Object>> getStuckPositionDetail() { return stuckPositionDetail; }
    public Instant getCreatedAt() { return createdAt; }
}
