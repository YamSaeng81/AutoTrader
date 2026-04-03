package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 백테스트 비동기 작업 상태 관리 엔티티.
 * 대용량 캔들 데이터(수십만 건)를 백그라운드에서 처리할 때 작업 이력을 보존하고
 * 완료/실패 시 텔레그램으로 알림을 받기 위해 사용한다.
 */
@Entity
@Table(name = "backtest_job",
        indexes = {
                @Index(name = "idx_backtest_job_status", columnList = "status"),
                @Index(name = "idx_backtest_job_created_at", columnList = "createdAt DESC")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SINGLE / BULK / MULTI_STRATEGY */
    @Column(name = "job_type", nullable = false, length = 20)
    private String jobType;

    /** PENDING → RUNNING → COMPLETED / FAILED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "coin_pair", length = 20)
    private String coinPair;

    /** 전략명 또는 전략 목록 요약 */
    @Column(name = "strategy_name", length = 200)
    private String strategyName;

    @Column(name = "timeframe", length = 10)
    private String timeframe;

    /** 요청 파라미터 JSON (오류 재현 및 디버깅용) */
    @Column(name = "request_json", columnDefinition = "TEXT")
    private String requestJson;

    /** 처리 대상 총 캔들 수 */
    @Column(name = "total_candles")
    private Integer totalCandles;

    /** 총 청크 수 (100,000건 단위 분할) */
    @Column(name = "total_chunks")
    private Integer totalChunks;

    /** 완료된 청크 수 (진행률 추적) */
    @Builder.Default
    @Column(name = "completed_chunks")
    private Integer completedChunks = 0;

    /** 완료된 backtest_run 레코드 ID (단일 백테스트) */
    @Column(name = "backtest_run_id")
    private Long backtestRunId;

    /** FAILED 시 오류 메시지 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
