package com.cryptoautotrader.api.util;

import com.cryptoautotrader.api.entity.OrderEntity;

import java.math.BigDecimal;

/**
 * {@code public."order"} 행에서 <b>코인 수량</b>과 <b>주문 금액(KRW)</b>을 안전하게 뽑아낸다.
 *
 * <p><b>왜 필요한가</b>: {@code order.quantity} 는 단위가 두 가지다.</p>
 * <ul>
 *   <li><b>시장가 매수</b>(MARKET + BUY): Upbit {@code price} 타입 주문의 파라미터가
 *       "총 KRW 금액"이라서, 이 컬럼에 <b>KRW 금액</b>이 들어간다
 *       ({@code OrderExecutionEngine#submitToExchange} 참조). 코인 수량이 아니다.</li>
 *   <li><b>그 외 전부</b>(시장가 매도 / 지정가): <b>코인 수량</b>이 들어간다.</li>
 * </ul>
 *
 * <p>이 이중 의미 때문에 {@code price × quantity} 로 체결금액을 계산하면 시장가 매수 행에서
 * 최대 10^8 배 과대한 값이 나온다. 운영 실측(2026-08-24): {@code public."order"} 의 BUY 행
 * 전량(DYN_PAPER 98/98, DYNAMIC 24/24, LIVE 195/271)이 {@code quantity = position.invested_krw}
 * 였고 {@code quantity = position.size} 인 행은 0건이었다.</p>
 *
 * <p>다행히 {@code filled_quantity} 는 전 구간에서 실제 코인 수량이 정확히 들어 있으므로
 * (같은 실측에서 {@code filled_quantity = position.size} 일치), 과거 데이터 보정 없이
 * 읽는 쪽만 이 유틸을 쓰면 된다.</p>
 *
 * <p>참고: {@code paper_trading."order"} 는 이 문제가 없다 — BUY/SELL 모두 코인 수량을 저장한다
 * (거래소를 거치지 않으므로 Upbit price 타입 제약이 없다). 그쪽에는 이 유틸이 필요 없다.</p>
 */
public final class OrderAmounts {

    private OrderAmounts() {}

    /**
     * 이 주문의 {@code quantity} 컬럼이 코인 수량이 아니라 KRW 금액인가.
     * (Upbit price 타입 = 시장가 매수)
     */
    public static boolean quantityIsKrw(String orderType, String side) {
        return "MARKET".equalsIgnoreCase(orderType) && "BUY".equalsIgnoreCase(side);
    }

    /**
     * 실제 <b>코인 수량</b>. 시장가 매수는 {@code quantity} 가 KRW라서 {@code filled_quantity} 를 쓴다.
     *
     * @return 코인 수량. 미체결 시장가 매수처럼 알 수 없으면 {@code null}
     *         (0을 돌려주면 "0개 체결"과 구분되지 않는다)
     */
    public static BigDecimal coinQuantity(OrderEntity o) {
        if (o == null) return null;
        if (quantityIsKrw(o.getOrderType(), o.getSide())) {
            return o.getFilledQuantity();
        }
        return o.getQuantity();
    }

    /**
     * 이 주문에 투입된 <b>KRW 금액</b>.
     *
     * <p>우선순위: {@code executed_funds}(거래소가 알려준 실제 사용 KRW) →
     * 시장가 매수면 {@code quantity}(주문 시 요청한 KRW 총액) → 그 외 {@code price × quantity}.</p>
     *
     * @return KRW 금액. 계산 근거가 없으면 {@code null}
     */
    public static BigDecimal krwAmount(OrderEntity o) {
        if (o == null) return null;
        if (o.getExecutedFunds() != null && o.getExecutedFunds().signum() > 0) {
            return o.getExecutedFunds();
        }
        if (quantityIsKrw(o.getOrderType(), o.getSide())) {
            return o.getQuantity();
        }
        if (o.getPrice() == null || o.getQuantity() == null) return null;
        return o.getPrice().multiply(o.getQuantity());
    }
}
