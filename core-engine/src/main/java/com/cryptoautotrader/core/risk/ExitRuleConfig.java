package com.cryptoautotrader.core.risk;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 통합 리스크/청산 설정 — 백테스트·모의매매·실전매매 공통 기본값.
 * 실전매매(LiveTradingService)의 현행 설정을 기준으로 한다.
 *
 * <pre>
 * 기본값 요약:
 *   손절(SL)        = 진입가 −5%
 *   익절(TP)        = 진입가 +10% (SL × 2배)
 *   트레일링 TP     = 고점 −0.5% (단방향 래칫)
 *   트레일링 SL 조임 = 저점 −0.3% (손실 중일 때)
 *   투자 비율       = 가용 자금의 80%
 * </pre>
 */
@Getter
@Builder
public class ExitRuleConfig {

    // ── 손절/익절 ─────────────────────────────────────────────
    /** 기본 손절 비율 (%) — 진입가 대비 */
    @Builder.Default
    private final BigDecimal stopLossPct = new BigDecimal("5.0");

    /** 익절 배수 — TP% = SL% × 이 값 (기본 2.0 → SL 5%이면 TP 10%) */
    @Builder.Default
    private final BigDecimal takeProfitMultiplier = new BigDecimal("2.0");

    // ── 트레일링 ──────────────────────────────────────────────
    /** 트레일링 활성화 여부 */
    @Builder.Default
    private final boolean trailingEnabled = true;

    /** 트레일링 TP 마진 — 고점 대비 이 비율 아래로 TP 갱신 (0.005 = 0.5%) */
    @Builder.Default
    private final BigDecimal trailingTpMargin = new BigDecimal("0.005");

    /** 트레일링 SL 조임 마진 — 손실 중 저점 대비 이 비율 아래로 SL 상향 (0.003 = 0.3%) */
    @Builder.Default
    private final BigDecimal trailingSlMargin = new BigDecimal("0.003");

    // ── 포지션 사이징 ─────────────────────────────────────────
    /** 가용 자금 대비 투자 비율 (0.80 = 80%) */
    @Builder.Default
    private final BigDecimal investRatio = new BigDecimal("0.80");

    /** 최소 투자 금액 (KRW) — 이 이하면 매수 스킵 */
    @Builder.Default
    private final BigDecimal minInvestAmount = new BigDecimal("5000");

    // ── 팩토리 ────────────────────────────────────────────────
    /** 실전매매 기본 설정 (모든 경로의 기본값) */
    public static ExitRuleConfig defaults() {
        return ExitRuleConfig.builder().build();
    }
}
