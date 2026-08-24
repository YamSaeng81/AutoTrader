package com.cryptoautotrader.api.util;

import com.cryptoautotrader.api.entity.OrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrderAmounts} — order.quantity 이중 단위 방어.
 *
 * <p>회귀 기준값은 운영 실측 행이다(2026-08-24, order id 9609):
 * KRW-TRAC 시장가 매수, price=513.513, quantity=10334.152(KRW), position.size=20.11435917.
 * 이 행에서 {@code price × quantity} 를 계산하면 5,306,721원 — 실제 투입금의 513배다.</p>
 */
class OrderAmountsTest {

    private OrderEntity order(String type, String side, String price, String qty,
                              String filledQty, String executedFunds) {
        OrderEntity o = new OrderEntity();
        o.setOrderType(type);
        o.setSide(side);
        o.setPrice(price != null ? new BigDecimal(price) : null);
        o.setQuantity(qty != null ? new BigDecimal(qty) : null);
        o.setFilledQuantity(filledQty != null ? new BigDecimal(filledQty) : null);
        o.setExecutedFunds(executedFunds != null ? new BigDecimal(executedFunds) : null);
        return o;
    }

    @Test
    @DisplayName("시장가 매수: quantity 는 KRW — 코인 수량은 filledQuantity 에서 가져온다")
    void marketBuyUsesFilledQuantityAsCoinQuantity() {
        OrderEntity o = order("MARKET", "BUY", "513.513", "10334.152", "20.11435917", null);

        assertThat(OrderAmounts.quantityIsKrw("MARKET", "BUY")).isTrue();
        assertThat(OrderAmounts.coinQuantity(o)).isEqualByComparingTo("20.11435917");
        // price × quantity(=5,306,721) 가 아니라 quantity 자체가 투입 KRW다
        assertThat(OrderAmounts.krwAmount(o)).isEqualByComparingTo("10334.152");
    }

    @Test
    @DisplayName("시장가 매수 미체결: 코인 수량은 알 수 없으므로 null (0이 아니다)")
    void marketBuyWithoutFillHasUnknownCoinQuantity() {
        OrderEntity o = order("MARKET", "BUY", "513.513", "10334.152", null, null);

        assertThat(OrderAmounts.coinQuantity(o)).isNull();
        assertThat(OrderAmounts.krwAmount(o)).isEqualByComparingTo("10334.152");
    }

    @Test
    @DisplayName("시장가 매수: executedFunds 가 있으면 그쪽이 실제 사용 KRW다 (부분 체결)")
    void executedFundsWinsOverRequestedAmount() {
        OrderEntity o = order("MARKET", "BUY", "513.513", "10334.152", "15.0", "7700.0");

        assertThat(OrderAmounts.krwAmount(o)).isEqualByComparingTo("7700.0");
    }

    @Test
    @DisplayName("시장가 매도: quantity 가 그대로 코인 수량이고, 금액은 price × quantity")
    void marketSellQuantityIsCoinQuantity() {
        OrderEntity o = order("MARKET", "SELL", "520.0", "20.11435917", "20.11435917", null);

        assertThat(OrderAmounts.quantityIsKrw("MARKET", "SELL")).isFalse();
        assertThat(OrderAmounts.coinQuantity(o)).isEqualByComparingTo("20.11435917");
        assertThat(OrderAmounts.krwAmount(o)).isEqualByComparingTo("10459.46676840");
    }

    @Test
    @DisplayName("지정가 매수: price 타입이 아니므로 quantity 는 코인 수량이다")
    void limitBuyQuantityIsCoinQuantity() {
        OrderEntity o = order("LIMIT", "BUY", "500.0", "20.0", null, null);

        assertThat(OrderAmounts.quantityIsKrw("LIMIT", "BUY")).isFalse();
        assertThat(OrderAmounts.coinQuantity(o)).isEqualByComparingTo("20.0");
        assertThat(OrderAmounts.krwAmount(o)).isEqualByComparingTo("10000.0");
    }

    @Test
    @DisplayName("가격이 없으면 KRW 금액은 계산 불가 — 0이 아니라 null")
    void missingPriceYieldsNullAmount() {
        OrderEntity o = order("LIMIT", "SELL", null, "20.0", null, null);

        assertThat(OrderAmounts.krwAmount(o)).isNull();
        assertThat(OrderAmounts.krwAmount(null)).isNull();
        assertThat(OrderAmounts.coinQuantity(null)).isNull();
    }
}
