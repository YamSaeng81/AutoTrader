package com.cryptoautotrader.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 야간 스케줄러 설정 요청/응답 DTO */
@Data
public class NightlySchedulerConfigDto {

    private Boolean enabled;

    /** KST 시 (0~23) */
    private Integer runHour;

    /** 분 (0~59) */
    private Integer runMinute;

    private String timeframe;

    private LocalDate startDate;

    private LocalDate endDate;

    /** 코인 목록 */
    private List<String> coinPairs;

    /** 전략 목록 */
    private List<String> strategyTypes;

    private Boolean includeBacktest;

    private Boolean includeWalkForward;

    private Double inSampleRatio;

    private Integer windowCount;

    private BigDecimal initialCapital;

    private BigDecimal slippagePct;

    private BigDecimal feePct;

    // ── 읽기 전용 (응답 전용) ────────────────────────────────────────────────────

    /** 마지막 자동 실행 시각 */
    private String lastTriggeredAt;

    /** 마지막 배치 백테스트 Job ID */
    private Long lastBatchJobId;

    /** 마지막 Walk-Forward Job ID */
    private Long lastWfJobId;

    /** 다음 실행 예정 시각 (KST, ISO 8601) */
    private String nextRunAt;
}
