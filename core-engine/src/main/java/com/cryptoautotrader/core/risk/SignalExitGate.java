package com.cryptoautotrader.core.risk;

import java.math.BigDecimal;

/**
 * 전략 SELL 신호를 실제로 체결할지 판정하는 <b>단일 출처</b> — LIVE·DYNAMIC·PAPER·BACKTEST 공용.
 *
 * <h3>왜 하나로 모았나 (2026-09-08)</h3>
 * <p>같은 판정이 <b>네 벌</b>로 구현돼 있었다. 상수는 09-08 에 {@code ExitRuleConfig} 위임으로
 * 통일했지만({@code EngineConstantParityTest}) <b>조건식 자체는 여전히 따로</b>였다 —
 * 한쪽 부등호만 바뀌어도 아무도 모르는 상태다. 이 저장소의 반복 결함이 정확히 그 모양이라
 * (규칙을 한 엔진에만 적용하고 나머지를 잊는다) 판정을 여기로 모은다.</p>
 *
 * <h3>규칙</h3>
 * <ol>
 *   <li><b>최소 보유시간</b> 미달 → 차단. 진입 직후 SELL 신호로 동가 청산되는 패턴을 막는다.</li>
 *   <li><b>본전 근처</b>(= {@code lossEscapePct} ≤ pnl &lt; {@code minPnlPct}) → 차단.
 *       수수료·노이즈로 본전에서 왔다 갔다 하는 churn 을 막는다.</li>
 *   <li>손실이 {@code lossEscapePct} 보다 크면 <b>차단하지 않는다</b> — 손실 방치 방지.</li>
 * </ol>
 *
 * <p><b>SL/TP/트레일링/time stop 은 이 게이트와 무관하게 항상 별도 경로로 동작한다.</b>
 * 이 판정은 오직 "전략 신호에 의한" SELL 에만 적용된다.</p>
 *
 * <h3>손실 탈출 하한이 왜 −0.30 인가 (2026-08-18)</h3>
 * <p>이전 값(−1.00)은 데드밴드를 −1.00% ~ +0.30% 로 <b>비대칭</b>하게 만들었다. 전략이 SELL 을
 * 내도 손실이 1% 를 넘기 전에는 나갈 수 없어, <b>본전가드가 작은 손실을 1% 이상 손실로
 * 확정시켰다.</b> 운영 실측(08-07~08-18, 동적 52/53) — 게이트가 막은 첫 SELL 시점 pnl 과
 * 실제 청산 pnl:</p>
 * <pre>
 *   pos 2404  −0.428% → −1.225%     pos 2412  −0.430% → −1.230%
 *   pos 2405  −0.371% → −1.070%     pos 2413  −0.280% → −1.170%
 *   평균      −0.377% → −1.174%  (게이트 비용 0.797%p, 4건 전부 손해)
 * </pre>
 *
 * <p>상태가 없다. 임계값은 호출자가 넘긴다 — 세션별 A/B 오버라이드
 * ({@code ExitRuleOverrides}) 를 이 클래스가 알 필요가 없도록.</p>
 */
public final class SignalExitGate {

    private SignalExitGate() {}

    /** 차단 사유 분류 — 로그 문자열이 아니라 이 값으로 분기할 것. */
    public enum Block {
        /** 차단 없음 (체결 허용) */
        NONE,
        /** 최소 보유시간 미달 */
        MIN_HOLD,
        /** 본전 근처 — 수수료·노이즈 churn 방지 */
        BREAKEVEN
    }

    /**
     * 판정 결과.
     *
     * @param allowed true 면 SELL 체결 허용
     * @param block   차단 사유 분류 (허용이면 {@link Block#NONE})
     * @param reason  사람이 읽는 사유 — {@code signal_quality.blocked_reason} 에 그대로 저장한다.
     *                허용이면 null
     */
    public record Decision(boolean allowed, Block block, String reason) {
        public static Decision allow() {
            return new Decision(true, Block.NONE, null);
        }
        public static Decision blocked(Block block, String reason) {
            return new Decision(false, block, reason);
        }
    }

    /**
     * 전략 SELL 신호를 체결해도 되는지 판정한다.
     *
     * @param heldMinutes    진입 후 경과 시간(분)
     * @param pnlPct         현재 손익률(%) — {@code (현재가 − 진입가) / 진입가 × 100}
     * @param minHoldMinutes 최소 보유시간(분)
     * @param minPnlPct      본전 청산 차단 상한(%)
     * @param lossEscapePct  손실 탈출 허용 하한(%, 음수) — 이보다 더 잃고 있으면 본전가드를 푼다
     */
    public static Decision decide(long heldMinutes, BigDecimal pnlPct,
                                   long minHoldMinutes, BigDecimal minPnlPct,
                                   BigDecimal lossEscapePct) {
        if (heldMinutes < minHoldMinutes) {
            return Decision.blocked(Block.MIN_HOLD, String.format(
                    "최소 보유시간 미달: %d분 < %d분 (pnl=%s%%, 전략 SELL 차단, SL/TP는 유효)",
                    heldMinutes, minHoldMinutes, plain(pnlPct)));
        }
        if (pnlPct.compareTo(minPnlPct) < 0 && pnlPct.compareTo(lossEscapePct) >= 0) {
            return Decision.blocked(Block.BREAKEVEN, String.format(
                    "본전 청산 차단: pnl=%s%% < +%s%% (전략 SELL 무시, SL/TP/트레일링은 유효)",
                    plain(pnlPct), plain(minPnlPct)));
        }
        return Decision.allow();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.toPlainString();
    }
}
