package com.cryptoautotrader.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 자동매매 상태 응답 DTO
 */
@Data
@Builder
public class TradingStatusResponse {

    /** 매매 상태: RUNNING, STOPPED, EMERGENCY_STOPPED */
    private String status;

    /** 열린 포지션 수 */
    private int openPositions;

    /** 활성 주문 수 (PENDING, SUBMITTED, PARTIAL_FILLED) */
    private int activeOrders;

    /** 전체 실현 + 미실현 손익 (KRW) */
    private BigDecimal totalPnl;

    /** 자동매매 시작 시각 */
    private Instant startedAt;

    /** 거래소 연결 상태: UP, DEGRADED, DOWN */
    private String exchangeHealth;

    /** 실행 중인 세션 수 */
    private int runningSessions;

    /** 전체 세션 수 */
    private int totalSessions;
}
