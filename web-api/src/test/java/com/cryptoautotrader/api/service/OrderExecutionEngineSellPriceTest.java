package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 매도 체결가 확정 회귀 테스트 (2026-05-31 "가짜 본전" 버그).
 *
 * <p>시장가 매도가 Upbit에서 즉시 done 으로 떠도 trades(executed_funds)가 정산되기 전이면
 * 평균 체결가를 알 수 없다. 이때 FILLED 로 확정하면 finalizeSellPosition 이 체결가를
 * 진입 평균가로 대체해 realizedPnl 이 항상 -매도수수료로만 기록되는 버그가 있었다.
 *
 * <p>수정 후 기대 동작:
 * <ul>
 *   <li>executed_funds 미정산 → FILLED 보류(SUBMITTED 유지), 체결 처리(processFilledOrder) 미호출</li>
 *   <li>executed_funds 정산 → 평균가 = funds/volume 로 확정, FILLED 전이</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderExecutionEngineSellPriceTest {

    @Mock OrderRepository orderRepository;
    @Mock PositionRepository positionRepository;
    @Mock TradeLogRepository tradeLogRepository;
    @Mock RiskManagementService riskManagementService;
    @Mock UpbitOrderClient upbitOrderClient;
    @Mock UpbitRestClient upbitRestClient;

    OrderExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new OrderExecutionEngine(orderRepository, positionRepository,
                tradeLogRepository, riskManagementService, new ObjectMapper());
        // @Autowired(required=false) 필드 주입
        ReflectionTestUtils.setField(engine, "upbitRestClient", upbitRestClient);
        ReflectionTestUtils.setField(engine, "upbitOrderClient", upbitOrderClient);
    }

    private OrderEntity submittedSellOrder() {
        return OrderEntity.builder()
                .id(1L)
                .coinPair("KRW-BTC")
                .side("SELL")
                .orderType("MARKET")
                .quantity(new BigDecimal("0.5"))
                .state("SUBMITTED")
                .exchangeOrderId("uuid-1")
                .sessionId(100L)   // 세션 주문 → handleSellFill 은 reconcile 위임(조기 return)
                .build();
    }

    private OrderResponse doneResponse(BigDecimal executedFunds) {
        OrderResponse resp = new OrderResponse();
        resp.setUuid("uuid-1");
        resp.setState("done");
        resp.setSide("ask");
        resp.setOrdType("market");
        resp.setExecutedVolume(new BigDecimal("0.5"));
        resp.setExecutedFunds(executedFunds); // null 이면 미정산
        return resp;
    }

    /** 최상위 executed_funds 는 없고 trades[] 만 내려오는 Upbit GET /v1/order 응답 */
    private OrderResponse doneResponseWithTrades(BigDecimal... tradeFunds) {
        OrderResponse resp = new OrderResponse();
        resp.setUuid("uuid-1");
        resp.setState("done");
        resp.setSide("ask");
        resp.setOrdType("market");
        resp.setExecutedVolume(new BigDecimal("0.5"));
        resp.setExecutedFunds(null); // 최상위 미제공
        java.util.List<OrderResponse.Trade> trades = new java.util.ArrayList<>();
        for (BigDecimal funds : tradeFunds) {
            OrderResponse.Trade t = new OrderResponse.Trade();
            t.setFunds(funds);
            trades.add(t);
        }
        resp.setTrades(trades);
        return resp;
    }

    @Test
    void 매도_체결가_미정산이면_FILLED_보류하고_SUBMITTED_유지() {
        OrderEntity order = submittedSellOrder();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(upbitOrderClient.getOrder("uuid-1")).thenReturn(doneResponse(null));

        engine.checkOrderStatus(1L);

        assertThat(order.getState()).isEqualTo("SUBMITTED");
        assertThat(order.getPrice()).isNull();
        // FILLED 미전이 → 체결 처리(포지션 조회) 호출 안 됨
        verify(positionRepository, never()).findById(any());
    }

    @Test
    void 매도_체결가_정산되면_평균가_확정하고_FILLED_전이() {
        OrderEntity order = submittedSellOrder();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        // executed_funds=60,000,000 / executed_volume=0.5 → 평균가 120,000,000
        when(upbitOrderClient.getOrder("uuid-1"))
                .thenReturn(doneResponse(new BigDecimal("60000000")));

        engine.checkOrderStatus(1L);

        assertThat(order.getState()).isEqualTo("FILLED");
        assertThat(order.getPrice()).isEqualByComparingTo("120000000");
        assertThat(order.getFilledAt()).isNotNull();
    }

    @Test
    void 최상위_funds_없어도_trades_합산으로_평균가_확정하고_FILLED_전이() {
        OrderEntity order = submittedSellOrder();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        // executed_funds 미제공 + trades 합산 = 20,000,000 + 40,000,000 = 60,000,000
        // / executed_volume 0.5 → 평균가 120,000,000
        when(upbitOrderClient.getOrder("uuid-1"))
                .thenReturn(doneResponseWithTrades(
                        new BigDecimal("20000000"), new BigDecimal("40000000")));

        engine.checkOrderStatus(1L);

        assertThat(order.getState()).isEqualTo("FILLED");
        assertThat(order.getPrice()).isEqualByComparingTo("120000000");
        assertThat(order.getFilledAt()).isNotNull();
    }
}
