package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 잔액 반영값 규칙 (리뷰 2026-10-31) — 요청액 버림 · 누적 금액 HALF_UP. 정산 경로 전체는 {@code SettlementRoundingPgTest}. */
@DisplayName("잔액 반영값 규칙 — 요청액 버림·누적 금액 두 자리 HALF_UP")
class BalanceAmountsTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("매수 요청액은 둘째 자리 버림 — 최소 주문 5,000 판정이 버림 뒤 금액으로 갈린다")
    void buyRequestTruncates() {
        assertThat(BalanceAmounts.buyRequest(d("16160.0000")).toPlainString()).isEqualTo("16160.00");   // 20,200 × 0.8
        assertThat(BalanceAmounts.buyRequest(d("5000.009"))).isEqualByComparingTo("5000.00");
        assertThat(BalanceAmounts.buyRequest(d("5000.009")).compareTo(d("5000"))).as("최소 주문 통과").isZero();
        assertThat(BalanceAmounts.buyRequest(d("4999.999"))).as("반올림이면 5,000 이지만 버림").isEqualByComparingTo("4999.99");
        assertThat(BalanceAmounts.buyRequest(d("4999.999")).compareTo(d("5000"))).as("최소 주문 미달").isNegative();
        assertThat(BalanceAmounts.buyRequest(d("1234.5678")).scale()).isEqualTo(2);
    }

    @Test
    @DisplayName(".005 경계 — 0 에서 먼 쪽(HALF_UP), 그 바로 아래는 내림")
    void halfUpBoundary() {
        assertThat(BalanceAmounts.toBalance(d("58.525"))).isEqualByComparingTo("58.53");
        assertThat(BalanceAmounts.toBalance(d("58.52499999"))).isEqualByComparingTo("58.52");
        assertThat(BalanceAmounts.toBalance(d("-0.005"))).isEqualByComparingTo("-0.01");
        assertThat(BalanceAmounts.buyRefund(d("3000.00"), d("2940"), d("1.475"))).isEqualByComparingTo("58.53");
        assertThat(BalanceAmounts.sellCredit(d("21590"), d("10.795"))).isEqualByComparingTo("21579.21");
        assertThat(BalanceAmounts.sellCredit(d("21590"), d("10.796"))).isEqualByComparingTo("21579.20");
    }

    @Test
    @DisplayName("정정 차분의 합 = 최종 누적값 한 번 — 몇 번에 나눠 받아도 같다")
    void correctionsTelescope() {
        BigDecimal applied = BalanceAmounts.buyRefund(d("3000.00"), d("2940"), d("1.47"));
        BigDecimal sum = applied;
        BigDecimal fee = d("1.47");
        for (int i = 0; i < 10; i++) {
            BigDecimal next = fee.add(d("0.001"));
            sum = sum.add(BalanceAmounts.buyRefund(d("3000.00"), d("2940"), next)
                    .subtract(BalanceAmounts.buyRefund(d("3000.00"), d("2940"), fee)));
            fee = next;
        }
        assertThat(sum).isEqualByComparingTo(BalanceAmounts.buyRefund(d("3000.00"), d("2940"), d("1.48"))).isEqualByComparingTo("58.52");
    }
}
