package com.cryptoautotrader.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 동적 멀티코인 세션 생성 요청 DTO.
 *
 * <p>종목을 고정하지 않고 거래량 상위 후보군에서 실시간 필터링해 매매한다.
 * 필터 파이프라인: 거래량 상위 maxCandidateSize개 → 호가 스프레드 → ATR 변동성
 * → 최종 targetWatchSize개 감시 → BUY 신호 코인 진입 → 매도 후 재스캔.
 */
@Getter
@Setter
public class DynamicSessionRequest {

    @NotBlank(message = "전략을 선택하세요")
    private String strategyType;

    @NotBlank(message = "타임프레임을 입력하세요")
    private String timeframe;

    @NotNull(message = "투자 원금을 입력하세요")
    @DecimalMin(value = "10000", message = "최소 투자금은 10,000 KRW입니다")
    private BigDecimal initialCapital;

    /** 손절률 (기본 5%) */
    private BigDecimal stopLossPct;

    /** 투자 비율 1~100 (기본 80) */
    private BigDecimal investRatio;

    /** 거래량 기준 최초 후보 수 (기본 30) */
    private Integer maxCandidateSize;

    /** 필터 통과 후 최종 감시 종목 수 (기본 10) */
    private Integer targetWatchSize;

    /** ATR(14) / 현재가 최소 비율 % (기본 0.5) — 이 미만이면 감시 제외 */
    private BigDecimal minAtrPct;

    /** 호가 스프레드 최대 비율 % (기본 0.1) — 이 초과이면 감시 제외 */
    private BigDecimal maxSpreadPct;

    /** 워치리스트 재필터링 주기 (분, 기본 60) */
    private Integer watchlistRefreshMin;

    /**
     * 최대 보유시간 (시) — 초과 시 손익 무관 시장가 청산. 0 이하면 비활성.
     * 미지정 시 {@link com.cryptoautotrader.api.entity.DynamicSessionEntity#DEFAULT_MAX_HOLD_HOURS}
     * (현재 0 = 비활성, 매도 후처리 롤백 P0 해결 전까지의 임시값).
     */
    private Integer maxHoldHours;

    /**
     * REAL(실거래, 기본값) | PAPER(모의) — (2026-08-06).
     * PAPER는 전략/게이트/SL·TP/time stop을 REAL과 동일하게 적용하되 체결만 시뮬레이션한다.
     */
    private String tradingMode;
}
