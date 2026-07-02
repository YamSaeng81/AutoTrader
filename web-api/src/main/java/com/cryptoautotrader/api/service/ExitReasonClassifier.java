package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * 청산된 포지션이 실제로 어떤 경로로 닫혔는지 분류한다 (S-2, 2026-07-02 감사).
 *
 * <p>전략 SELL 신호는 CompositeStrategy EMA 필터 + IchimokuFilteredStrategy 3중 필터 +
 * LiveTradingService 최소보유시간(180분)·본전가드에 의해 대부분 억제되는 구조라, 실제 청산은
 * SL/TP가 지배할 것으로 추정된다. 이 분류기는 그 추정을 신호품질 로그 없이도 이미 저장된
 * SELL 주문의 {@code signalReason} 접두어만으로 검증할 수 있게 한다 — 별도 계측 코드 불필요.</p>
 *
 * <p>분류 우선순위: SL/TP/강제청산은 항상 고정 접두어이므로 먼저 검사하고, "전략" 접두어는
 * 뒤에 전략 고유 텍스트가 붙어 다른 키워드를 우연히 포함할 수 있으므로 마지막에 검사한다.</p>
 */
public final class ExitReasonClassifier {

    public enum Category {
        /** 손절(SL) — 틱 스케줄 또는 WS 실시간 감시 */
        STOP_LOSS,
        /** 익절(TP) */
        TAKE_PROFIT,
        /** 전략 SELL 신호로 청산 */
        STRATEGY_SELL,
        /** 세션 정지·비상 정지에 의한 강제 시장가 청산 */
        FORCED_STOP,
        /** §15 팬텀 포지션 안전망 — 거래소 잔고 대조로 자동 정리 (연결 SELL 주문 없음) */
        PHANTOM,
        /** 매수 자체가 실패/취소되어 실거래가 발생하지 않은 무효 포지션 (청산으로 집계하면 안 됨) */
        BUY_FAILED,
        /** 위 어디에도 해당하지 않는 경우 */
        OTHER
    }

    private ExitReasonClassifier() {}

    /**
     * @param positionOrders 해당 포지션에 연결된 전체 주문 (여러 포지션 분의 주문을 한 번에 로드했다면
     *                       호출 전에 positionId로 미리 필터링해서 넘길 것)
     */
    public static Category classify(PositionEntity pos, List<OrderEntity> positionOrders) {
        OrderEntity latestFilledSell = positionOrders.stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide()))
                .filter(o -> "FILLED".equals(o.getState()))
                .max(Comparator.comparing(OrderEntity::getCreatedAt))
                .orElse(null);

        if (latestFilledSell != null) {
            return classifyReason(latestFilledSell.getSignalReason());
        }

        // 연결된 체결 SELL 주문이 없다 — 팬텀 자동정리(§15, 주문 자체를 생성하지 않음) 또는
        // 애초에 매수가 체결된 적이 없는 무효 포지션(오더 실패 정리) 중 하나.
        boolean hasFilledBuy = positionOrders.stream()
                .anyMatch(o -> "BUY".equalsIgnoreCase(o.getSide())
                        && "FILLED".equals(o.getState())
                        && o.getFilledQuantity() != null
                        && o.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0);

        return hasFilledBuy ? Category.PHANTOM : Category.BUY_FAILED;
    }

    private static Category classifyReason(String reason) {
        if (reason == null) return Category.OTHER;
        String r = reason.trim();
        if (r.startsWith("손절") || r.startsWith("실시간 손절")) return Category.STOP_LOSS;
        if (r.startsWith("익절")) return Category.TAKE_PROFIT;
        if (r.startsWith("세션 정지") || r.startsWith("비상 정지") || r.startsWith("전체 비상 정지")) {
            return Category.FORCED_STOP;
        }
        if (r.startsWith("전략")) return Category.STRATEGY_SELL;
        return Category.OTHER;
    }
}
