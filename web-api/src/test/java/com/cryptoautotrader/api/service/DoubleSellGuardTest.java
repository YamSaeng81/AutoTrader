package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이중 매도 차단 — 전량 정지 리허설 S18b 에서 재현한 결함 (2026-10-15).
 *
 * <p>정지가 매도를 낸 뒤 롤백되면 CLOSING 표시만 사라지고 거래소 매도는 체결된 채 남는다. 그 상태에서 다시 매도를 내면 같은
 * 수량을 두 번 판다. 매도 제출 입구가 "이 포지션에 반영되지 않은 매도" 를 보고 새 매도를 내지 않아야 한다.</p>
 */
@DisplayName("이중 매도 차단 — 반영되지 않은 매도가 있는 포지션에는 새 매도를 내지 않는다")
class DoubleSellGuardTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine engine;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private UpbitOrderClient upbitOrderClient;

    private final List<Long> sessions = new ArrayList<>();

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField((Object) AopTestUtils.getUltimateTargetObject(engine), "sellTrackingSince", Instant.EPOCH);
        for (Long id : sessions) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
    }

    private PositionEntity openPosition() {
        Long sid = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair("KRW-DSG").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7058.53"))
                .totalAssetKrw(new BigDecimal("10000")).status("RUNNING").startedAt(Instant.now()).build()).getId();
        sessions.add(sid);
        return positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DSG").side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(new BigDecimal("9.8")).investedKrw(new BigDecimal("2941.47")).entryAllocationKrw(new BigDecimal("3000"))
                .status("OPEN").sessionKind("LIVE").sessionId(sid).openedAt(Instant.now()).build());
    }

    /** 정지가 낸 뒤 롤백으로 반영되지 못한 매도 — 거래소에서는 체결됐다. */
    private OrderEntity filledUnappliedSell(PositionEntity p, Instant createdAt) {
        return orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DSG").side("SELL").orderType("MARKET")
                .quantity(new BigDecimal("9.8")).filledQuantity(new BigDecimal("9.8")).state("FILLED")
                .sessionKind("LIVE").sessionId(p.getSessionId()).positionId(p.getId())
                .exchangeOrderId("dsg-" + UUID.randomUUID()).clientOrderId("dsg-" + UUID.randomUUID())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING).createdAt(createdAt).build());
    }

    private void sell(PositionEntity p) {
        OrderRequest r = new OrderRequest();
        r.setCoinPair("KRW-DSG");
        r.setSide("SELL");
        r.setOrderType("MARKET");
        r.setQuantity(new BigDecimal("9.8"));
        r.setReason("이중 매도 시험");
        r.setSessionId(p.getSessionId());
        r.setSessionKind("LIVE");
        r.setPositionId(p.getId());
        // @Async 프록시를 지나지 않고 같은 스레드에서 끝까지 돈다
        ((OrderExecutionEngine) AopTestUtils.getUltimateTargetObject(engine)).submitOrder(r);
    }

    private List<OrderEntity> sells(PositionEntity p) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", p.getSessionId()).stream()
                .filter(o -> "SELL".equals(o.getSide())).toList();
    }

    private static OrderResponse accepted() {
        OrderResponse r = new OrderResponse();
        r.setUuid("dsg-new-" + UUID.randomUUID());
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("wait");
        r.setMarket("KRW-DSG");
        return r;
    }

    @Test
    @DisplayName("🔴 체결됐지만 포지션에 반영되지 않은 매도가 있으면 새 매도는 거래소로 나가지 않고 주문 행도 생기지 않는다")
    void filledUnappliedSellBlocksNewSell() {
        PositionEntity p = openPosition();
        filledUnappliedSell(p, Instant.now());

        sell(p);

        verify(upbitOrderClient, never()).createOrder(any(), any(), any(), any(), any(), any());
        assertThat(sells(p)).as("식별자를 소비하기 전에 거절한다").hasSize(1);
    }

    @Test
    @DisplayName("반영된 뒤에는 막지 않는다 — 남은 수량을 다시 팔 수 있다(부분 체결 규칙). 대조군: 매도 이력 없는 포지션도 통과")
    void appliedSellDoesNotBlock() {
        when(upbitOrderClient.createOrder(anyString(), anyString(), any(), any(), anyString(), anyString())).thenReturn(new UpbitOrderClient.ExchangeResult(accepted(), "{}"));
        PositionEntity p = openPosition();
        OrderEntity first = filledUnappliedSell(p, Instant.now());
        jdbc.update("UPDATE \"order\" SET sell_applied_at = ? WHERE id = ?", Timestamp.from(Instant.now()), first.getId());

        sell(p);
        verify(upbitOrderClient, times(1)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
        assertThat(sells(p)).hasSize(2);

        PositionEntity q = openPosition();
        sell(q);
        verify(upbitOrderClient, times(2)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("V87 설치 전에 만든 매도는 반영 표시가 없어도 막지 않는다 — 종전 코드가 체결 때 반영했다")
    void legacySellBeforeTrackingDoesNotBlock() {
        when(upbitOrderClient.createOrder(anyString(), anyString(), any(), any(), anyString(), anyString())).thenReturn(new UpbitOrderClient.ExchangeResult(accepted(), "{}"));
        ReflectionTestUtils.setField((Object) AopTestUtils.getUltimateTargetObject(engine), "sellTrackingSince",
                Instant.now().minus(1, ChronoUnit.HOURS));
        PositionEntity p = openPosition();
        OrderEntity legacy = filledUnappliedSell(p, Instant.now().minus(2, ChronoUnit.HOURS));
        jdbc.update("UPDATE \"order\" SET created_at = ? WHERE id = ?", Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)), legacy.getId());

        sell(p);
        verify(upbitOrderClient, times(1)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
    }
}
