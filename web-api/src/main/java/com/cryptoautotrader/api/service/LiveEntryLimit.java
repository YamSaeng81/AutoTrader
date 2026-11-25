package com.cryptoautotrader.api.service;

import java.math.BigInteger;
import java.util.Map;

/**
 * LIVE 세션의 <b>새 매수 의도 최대 1회</b> 선택 옵션 — {@code strategy_params.maxEntries} (총괄 v1.241 §3, 스키마 없는 B안).
 *
 * <ul>
 *   <li>키가 <b>없으면</b> 제한 없음 — 기존 동작·지문 그대로(지문 입력에 아무것도 더하지 않는다).</li>
 *   <li>키가 있으면 값은 <b>정수 1</b>만 받는다. null·빈 문자열·문자열·불리언·소수·음수·0·2 이상은 생성 단계에서 거절한다.
 *       키가 있는 세션은 {@link RulesetRegistry#canonicalParams} 를 통해 지문이 달라진다.</li>
 *   <li>판정은 {@link CapitalReservationService#reserveForBuy} LIVE 분기에서 <b>계좌 잠금 뒤·차감 전</b>에 그 세션의 수명 전체 LIVE 매수 의도
 *       (상태 무관 — UNKNOWN·FAILED·CANCELLED·철회 포함)를 센다. 롤백된 예약은 의도 행이 없어 세지 않는다.</li>
 * </ul>
 *
 * <p>🔴 이 옵션은 다른 전략 파라미터의 의미를 바꾸지 않는다. 전략 평가·청산 오버라이드는 이 키를 읽지 않는다. 매도·청산·정지·DYNAMIC·PAPER
 * 경로는 이 옵션을 보지 않는다. 옵션을 모르는 구판에서는 키가 무시되어 <b>제한 없이</b> 돈다(복구 제한은 운영 절차가 맡는다).</p>
 */
public final class LiveEntryLimit {

    public static final String KEY = "maxEntries";

    /** 저장된 세션의 옵션 상태. */
    enum State { NONE, ONE, INVALID }

    private LiveEntryLimit() {
    }

    /**
     * 생성 요청 검증 — 자본 배정·계좌 동기화·저장보다 <b>먼저</b> 부른다.
     *
     * @throws IllegalArgumentException 키가 있는데 값이 정수 1 이 아닐 때
     */
    public static void validateForCreate(Map<String, Object> strategyParams) {
        if (of(strategyParams) == State.INVALID) {
            Object v = strategyParams.get(KEY);
            throw new IllegalArgumentException("strategyParams." + KEY + " 는 생략하거나 정수 1 이어야 한다 (받은 값 형식: "
                    + (v == null ? "null" : v.getClass().getSimpleName()) + ") — 세션을 만들지 않았다");
        }
    }

    static State of(Map<String, Object> strategyParams) {
        if (strategyParams == null || !strategyParams.containsKey(KEY)) {
            return State.NONE;
        }
        return isExactlyOne(strategyParams.get(KEY)) ? State.ONE : State.INVALID;
    }

    /** 정수형(Integer·Long·Short·Byte·BigInteger) 1 만 참. 문자열·불리언·소수형(Double·Float·BigDecimal)은 값이 1 이어도 거짓. */
    static boolean isExactlyOne(Object v) {
        if (v instanceof Integer i) return i == 1;
        if (v instanceof Long l) return l == 1L;
        if (v instanceof Short s) return s == 1;
        if (v instanceof Byte b) return b == 1;
        if (v instanceof BigInteger bi) return BigInteger.ONE.equals(bi);
        return false;
    }
}
