package com.cryptoautotrader.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * 거래소 연결 상태 응답 DTO
 */
@Data
@Builder
public class ExchangeHealthResponse {

    /** 상태: UP, DEGRADED, DOWN */
    private String status;

    /** 최근 API 응답 지연시간 (ms) */
    private long latencyMs;

    /** WebSocket 연결 여부 */
    private boolean webSocketConnected;

    /** 마지막 상태 체크 시각 */
    private Instant lastCheckedAt;

    /** 최근 5분 지연시간 이력 (ms) */
    private List<Long> recentLatencies;
}
