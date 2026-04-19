package com.cryptoautotrader.core.risk;

import com.cryptoautotrader.strategy.StrategySignal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 통합 청산/리스크 판정기 — 순수 계산 로직만 포함.
 * 백테스트·모의매매·실전매매 모두 이 클래스를 통해 동일한 규칙을 적용한다.
 *
 * <p>상태를 갖지 않는다 (stateless). 포지션 상태는 호출자가 관리.</p>
 */
@RequiredArgsConstructor
public class ExitRuleChecker {

    private static final int SCALE = 8;

    private final ExitRuleConfig config;

    // ── 결과 타입 ─────────────────────────────────────────────

    @Getter
    @RequiredArgsConstructor
    public static class StopLevels {
        private final BigDecimal stopLossPrice;
        private final BigDecimal takeProfitPrice;
    }

    public enum ExitType { NONE, STOP_LOSS, TAKE_PROFIT }

    @Getter
    @RequiredArgsConstructor
    public static class ExitCheck {
        private final boolean shouldExit;
        private final BigDecimal exitPrice;
        private final String reason;
        private final ExitType type;
        /** 같은 캔들에서 SL·TP 양쪽이 모두 터치된 경우 true — 보수적으로 SL 로 처리되지만 소비자가 알 수 있도록 플래그. */
        private final boolean ambiguous;

        public static ExitCheck none() {
            return new ExitCheck(false, null, null, ExitType.NONE, false);
        }

        public static ExitCheck stopLoss(BigDecimal price, String reason, boolean ambiguous) {
            return new ExitCheck(true, price, reason, ExitType.STOP_LOSS, ambiguous);
        }

        public static ExitCheck takeProfit(BigDecimal price, String reason) {
            return new ExitCheck(true, price, reason, ExitType.TAKE_PROFIT, false);
        }
    }

    // ── SL/TP 초기 계산 ───────────────────────────────────────

    /**
     * 진입 시점 SL/TP 가격을 계산한다.
     * 전략이 제안한 값(suggestedStopLoss/TakeProfit)이 있으면 우선 사용,
     * 없으면 ExitRuleConfig 기본값으로 계산한다.
     *
     * @param entryPrice 진입가
     * @param signal     전략 신호 (null 가능)
     * @return SL/TP 가격 쌍
     */
    public StopLevels calculateStopLevels(BigDecimal entryPrice, StrategySignal signal) {
        BigDecimal slPct = config.getStopLossPct()
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal tpPct = config.getStopLossPct()
                .multiply(config.getTakeProfitMultiplier())
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

        BigDecimal sl = (signal != null && signal.getSuggestedStopLoss() != null)
                ? signal.getSuggestedStopLoss()
                : entryPrice.multiply(BigDecimal.ONE.subtract(slPct))
                        .setScale(SCALE, RoundingMode.HALF_DOWN);

        BigDecimal tp = (signal != null && signal.getSuggestedTakeProfit() != null)
                ? signal.getSuggestedTakeProfit()
                : entryPrice.multiply(BigDecimal.ONE.add(tpPct))
                        .setScale(SCALE, RoundingMode.HALF_UP);

        return new StopLevels(sl, tp);
    }

    // ── 캔들 기반 청산 체크 (백테스트용) ───────────────────────

    /**
     * 캔들의 고가/저가로 SL/TP 도달 여부를 판정한다.
     * 보수적으로 SL을 먼저 체크한다 (양쪽 모두 해당 시 손절 우선).
     *
     * @param candleLow  캔들 저가
     * @param candleHigh 캔들 고가
     * @param sl         현재 손절가
     * @param tp         현재 익절가
     * @return 청산 판정 결과
     */
    public ExitCheck checkCandleExit(BigDecimal candleLow, BigDecimal candleHigh,
                                      BigDecimal sl, BigDecimal tp) {
        boolean slHit = sl != null && candleLow.compareTo(sl) <= 0;
        boolean tpHit = tp != null && candleHigh.compareTo(tp) >= 0;

        // 손절 우선 (보수적) — 단, 양쪽 모두 터치된 경우는 ambiguous 플래그로 표시
        if (slHit) {
            return ExitCheck.stopLoss(sl,
                    "손절 발동 — 저가 " + candleLow + " ≤ 손절가 " + sl
                            + (tpHit ? " (경고: TP 도 동일 캔들에서 터치됨 — 경로 불확정)" : ""),
                    tpHit);
        }
        if (tpHit) {
            return ExitCheck.takeProfit(tp, "익절 발동 — 고가 " + candleHigh + " ≥ 익절가 " + tp);
        }
        return ExitCheck.none();
    }

    // ── 현재가 기반 청산 체크 (모의매매·실전매매용) ─────────────

    /**
     * 현재 가격으로 SL/TP 도달 여부를 판정한다.
     *
     * @param currentPrice 현재가
     * @param sl           현재 손절가
     * @param tp           현재 익절가
     * @return 청산 판정 결과
     */
    public ExitCheck checkPriceExit(BigDecimal currentPrice, BigDecimal sl, BigDecimal tp) {
        if (sl != null && currentPrice.compareTo(sl) <= 0) {
            return ExitCheck.stopLoss(currentPrice,
                    "손절 발동 — 현재가 " + currentPrice + " ≤ 손절가 " + sl, false);
        }
        if (tp != null && currentPrice.compareTo(tp) >= 0) {
            return ExitCheck.takeProfit(currentPrice,
                    "익절 발동 — 현재가 " + currentPrice + " ≥ 익절가 " + tp);
        }
        return ExitCheck.none();
    }

    // ── 트레일링 스탑 갱신 ────────────────────────────────────

    /**
     * 트레일링 스탑 레벨을 갱신한다.
     * - 수익 중 + 고가 갱신 → TP를 고점 × (1 − trailingTpMargin)으로 래칫
     * - 손실 중 + 저가 갱신 → SL을 저점 × (1 − trailingSlMargin)으로 래칫
     *
     * 모든 래칫은 단방향 — TP는 올라가기만, SL은 올라가기만(조여지기만) 한다.
     *
     * @param candleHigh  현재 캔들 고가 (또는 현재가)
     * @param candleLow   현재 캔들 저가 (또는 현재가)
     * @param entryPrice  진입가
     * @param currentSl   현재 손절가
     * @param currentTp   현재 익절가
     * @return 갱신된 SL/TP (변화 없으면 입력값 그대로)
     */
    public StopLevels updateTrailingStops(BigDecimal candleHigh, BigDecimal candleLow,
                                           BigDecimal entryPrice,
                                           BigDecimal currentSl, BigDecimal currentTp) {
        if (!config.isTrailingEnabled()) {
            return new StopLevels(currentSl, currentTp);
        }

        BigDecimal newTp = currentTp;
        BigDecimal newSl = currentSl;

        // 수익 구간: 고가가 현재 TP 이상이면 TP 래칫 상향
        if (candleHigh.compareTo(entryPrice) > 0) {
            BigDecimal trailedTp = candleHigh.multiply(
                    BigDecimal.ONE.subtract(config.getTrailingTpMargin()))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            if (trailedTp.compareTo(newTp) > 0) {
                newTp = trailedTp;
            }
        }

        // 손실 구간: 현재가가 진입가 아래이고 저점 갱신 → SL 조임
        if (candleLow.compareTo(entryPrice) < 0) {
            BigDecimal trailedSl = candleLow.multiply(
                    BigDecimal.ONE.subtract(config.getTrailingSlMargin()))
                    .setScale(SCALE, RoundingMode.HALF_DOWN);
            // SL은 올라가기만 해야 함 (조여지기만)
            if (currentSl != null && trailedSl.compareTo(currentSl) > 0) {
                newSl = trailedSl;
            }
        }

        return new StopLevels(newSl, newTp);
    }

    // ── 포지션 사이징 ─────────────────────────────────────────

    /**
     * 투자 금액을 계산한다.
     *
     * @param availableCapital 가용 자금 (KRW)
     * @return 투자 금액 (KRW), 최소 금액 미만이면 BigDecimal.ZERO
     */
    public BigDecimal calculateInvestAmount(BigDecimal availableCapital) {
        BigDecimal amount = availableCapital.multiply(config.getInvestRatio())
                .setScale(0, RoundingMode.HALF_UP);
        if (amount.compareTo(config.getMinInvestAmount()) < 0) {
            return BigDecimal.ZERO;
        }
        return amount;
    }

    // ── Config 접근자 (외부에서 개별 값 참조 시) ──────────────

    public ExitRuleConfig getConfig() {
        return config;
    }
}
