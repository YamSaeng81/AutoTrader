package com.cryptoautotrader.api.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 모의투자 세션의 1회 매수 상한 — {@code strategy_params.paperMaxInvestment} (2026-11-10, 총괄 v1.142 §110 판단 2).
 *
 * <h3>왜 있나</h3>
 * <p>12-01 제한 실거래(SID 203: DAILY_SMA20·KRW-BTC·H1·초기 20,200원·투자 비율 0.995·<b>1회 상한 20,000원</b>)와
 * 같은 조건의 전향 모의 세션을 나란히 돌리려면 PAPER 에도 실전의 {@code maxInvestment} 가 있어야 한다.
 * 종전 PAPER 는 최소 원금 100,000원에 상한 필드가 없어, 10만원에 투자 비율만 낮춘 세션을 "같은 2만원 조건"이라 부를 수 없었다.</p>
 *
 * <h3>왜 새 열이 아닌 {@code strategy_params} 인가</h3>
 * <ul>
 *   <li>DDL 이 없다 — {@code virtual_balance.strategy_params}(jsonb, V74)가 이미 있고 세션 생성이 그대로 옮긴다.</li>
 *   <li>규칙 지문({@code strategy.params})에 그대로 실린다 — 이 키가 있는 세션은 다른 지문이 되어 기존 PAPER 표본과 섞이지 않는다.</li>
 *   <li>청산 오버라이드({@link ExitRuleOverrides})는 모르는 키를 무시하므로 손절·익절 해석이 바뀌지 않는다
 *       ({@code isPresent()} 가 거짓으로 남아 전략 제안 SL 과의 {@code min()} 도 LIVE 와 같다).</li>
 * </ul>
 *
 * <p>키가 없으면 종전과 완전히 같다 — 최소 원금 100,000원, 매수금 = 가용 × 투자 비율.
 * 키가 있는데 숫자가 아니거나 업비트 최소 주문(5,000원) 미만이면 <b>세션을 만들지 않는다</b>.
 * 조용히 무시하면 2026-08-24 파라미터 유실 사고처럼 실험군이 대조군과 같아진 채 아무도 모르게 된다.</p>
 */
public final class PaperMaxInvestment {

    public static final String KEY = "paperMaxInvestment";

    /** 업비트 최소 주문금액 — 상한이 이보다 작으면 매수가 영원히 불가하다. */
    public static final BigDecimal MIN = new BigDecimal("5000");

    /** 키가 없을 때의 최소 원금(종전 DTO 의 {@code @DecimalMin("100000")}). */
    public static final BigDecimal DEFAULT_MIN_CAPITAL = new BigDecimal("100000");

    private PaperMaxInvestment() {
    }

    /**
     * @return 상한. 키가 없으면 {@code null}
     * @throws IllegalStateException 키가 있는데 숫자가 아니거나 {@link #MIN} 미만
     */
    public static BigDecimal from(Map<String, Object> strategyParams) {
        if (strategyParams == null) return null;
        Object raw = strategyParams.get(KEY);
        if (raw == null) return null;
        BigDecimal v;
        try {
            v = new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(KEY + " 가 숫자가 아니다: " + raw);
        }
        if (v.compareTo(MIN) < 0) {
            throw new IllegalStateException(KEY + " 는 " + MIN.toPlainString() + "원 이상이어야 한다: " + v.toPlainString());
        }
        return v;
    }

    /**
     * 세션 생성 조건 — 상한이 없으면 원금 100,000원 이상, 있으면 원금 5,000원 이상.
     *
     * @return 위반 사유. 통과면 {@code null}
     */
    public static String capitalViolation(BigDecimal initialCapital, Map<String, Object> strategyParams) {
        BigDecimal cap;
        try {
            cap = from(strategyParams);
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        if (initialCapital == null) return null; // @NotNull 이 따로 막는다
        BigDecimal floor = cap == null ? DEFAULT_MIN_CAPITAL : MIN;
        if (initialCapital.compareTo(floor) < 0) {
            return "초기 자본은 " + floor.toPlainString() + "원 이상이어야 한다"
                    + (cap == null ? " (" + KEY + " 를 지정한 세션만 5,000원부터 허용)" : "");
        }
        return null;
    }
}
