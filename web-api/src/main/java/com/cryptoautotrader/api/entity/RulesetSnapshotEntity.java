package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 규칙 지문의 실제 파라미터 (V71, 2026-08-19). 지문당 1행.
 *
 * <p>{@code position.ruleset_hash} 등에 찍힌 12자 지문만으로는 "무엇이 달랐는지" 를 알 수 없다.
 * 여기에 파라미터 원문을 남겨 역참조한다 — 지문 A와 B의 성과가 다를 때 두 행의
 * {@code paramsText} 를 diff 하면 무엇이 달라서 그런지 바로 나온다.</p>
 */
@Entity
@Table(name = "ruleset_snapshot")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RulesetSnapshotEntity {

    @Id
    @Column(name = "ruleset_hash", length = 16)
    private String rulesetHash;

    @Column(nullable = false, length = 20)
    private String engine;

    /** {@code key=value} 개행 구분 정규 직렬화. */
    @Column(name = "params_text", nullable = false, columnDefinition = "TEXT")
    private String paramsText;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @PrePersist
    void prePersist() {
        if (firstSeenAt == null) firstSeenAt = Instant.now();
    }

    public String getRulesetHash() { return rulesetHash; }
    public String getEngine() { return engine; }
    public String getParamsText() { return paramsText; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
}
