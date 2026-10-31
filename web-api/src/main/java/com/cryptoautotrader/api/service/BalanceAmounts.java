package com.cryptoautotrader.api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <b>세션 잔액에 넣는 금액</b> — 원시 기록(8자리)과 잔액 반영값(2자리)을 나눈다 (리뷰 2026-10-31 반올림 누적 결정).
 *
 * <p>세션 잔액({@code available_krw})은 NUMERIC(20,2) 이고 주문 반영 대금·수수료는 8자리다. 종전에는 8자리 차분을 그대로 잔액에
 * 더해 DB 가 매번 둘째 자리로 반올림했다 — 같은 최종 수수료라도 정정 횟수에 따라 잔액이 달라졌다(실측: 수수료 0.001원 정정 10회가
 * 잔액에 한 번도 들어가지 않아 차이 0.01, {@code BuySettlementEdgeTest} 22-c).</p>
 *
 * <p>규칙 — 잔액에는 <b>주문별 누적 금액을 두 자리로 반올림한 값</b>만 넣고, 정정은 그 반올림값끼리의 차분으로 넣는다. 그러면 정정을
 * 몇 번에 나눠 받든 최종 잔액은 최종 누적값 한 번 반영과 같다.</p>
 * <ul>
 *   <li>매수 요청액: 예약 전에 둘째 자리 <b>버림</b> — 그 금액으로 최소 주문·한도를 본다({@link #buyRequest}).</li>
 *   <li>매수 환급: {@code 요청액 − 반영 대금 − 반영 수수료} 를 둘째 자리 HALF_UP({@link #buyRefund}). 최초 반영의 이전 환급은 0.</li>
 *   <li>매도 입금: {@code 반영 대금 − 반영 수수료} 를 둘째 자리 HALF_UP({@link #sellCredit}). 최초 입금과 정정이 같은 식.</li>
 * </ul>
 *
 * <p>🔴 원시 기록(주문 대금·수수료, 포지션 손익·수수료·투입금)은 8자리 그대로 둔다 — 이 클래스는 잔액에 넣을 값만 만든다.
 * 이미 생긴 차이(이 규칙 전에 반영된 주문)는 자동 보정하지 않는다.</p>
 */
public final class BalanceAmounts {

    /** 세션 잔액 열의 소수 자릿수. */
    public static final int BALANCE_SCALE = 2;

    private BalanceAmounts() {
    }

    /** 매수 요청액 — 예약·최소 주문·한도 검사에 쓰는 금액. 둘째 자리 버림. */
    public static BigDecimal buyRequest(BigDecimal raw) {
        return raw.setScale(BALANCE_SCALE, RoundingMode.DOWN);
    }

    /** 잔액 반영값 — 누적 금액을 둘째 자리 HALF_UP. */
    public static BigDecimal toBalance(BigDecimal raw) {
        return raw.setScale(BALANCE_SCALE, RoundingMode.HALF_UP);
    }

    /** 시장가 매수의 누적 환급(미사용액) 반영값 = (요청액 − 대금 − 수수료) 를 두 자리로. */
    public static BigDecimal buyRefund(BigDecimal requested, BigDecimal funds, BigDecimal fee) {
        return toBalance(requested.subtract(funds).subtract(fee));
    }

    /** 매도의 누적 순입금 반영값 = (대금 − 수수료) 를 두 자리로. */
    public static BigDecimal sellCredit(BigDecimal funds, BigDecimal fee) {
        return toBalance(funds.subtract(fee));
    }
}
