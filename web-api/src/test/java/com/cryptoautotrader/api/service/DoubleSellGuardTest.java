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
    @Autowired private SellQuarantineReport quarantineReport;

    @MockBean private UpbitOrderClient upbitOrderClient;

    private final List<Long> sessions = new ArrayList<>();

    @AfterEach
    void tearDown() {
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
    @DisplayName("🔴 다른 경로가 매도를 반영하고 포지션을 닫은 뒤 늦게 온 매도는 내지 않는다 (리뷰 8회차 재시작 시험 S18b — 정지 재호출과 유령 정산의 경쟁)")
    void lateSellAfterSettlementIsRefused() {
        PositionEntity p = openPosition();
        // 정지 재호출이 CLOSING 을 표시했다
        jdbc.update("UPDATE position SET status = 'CLOSING', closing_at = ? WHERE id = ?", Timestamp.from(Instant.now()), p.getId());
        // 그 사이 유령 정산이 앞선 체결 매도를 반영하고 포지션을 닫아 커밋했다(반영 표시와 CLOSED 는 한 트랜잭션)
        OrderEntity first = filledUnappliedSell(p, Instant.now());
        jdbc.update("UPDATE \"order\" SET sell_applied_at = ? WHERE id = ?", Timestamp.from(Instant.now()), first.getId());
        jdbc.update("UPDATE position SET status = 'CLOSED', closed_at = ? WHERE id = ?", Timestamp.from(Instant.now()), p.getId());

        sell(p);   // 정지가 넘긴 매도가 늦게 제출 단계에 온다

        verify(upbitOrderClient, never()).createOrder(any(), any(), any(), any(), any(), any());
        assertThat(sells(p)).as("식별자를 소비하기 전에 거절한다 — 매도 행은 앞선 1건뿐").hasSize(1);
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
    @DisplayName("🔴 V87 이전 매도도 판별 불가로 센다 — 반영 표시 없이 체결된 오래된 매도가 열린 포지션에 있으면 새 매도를 내지 않는다(격리)")
    void legacySellWithoutMarkIsQuarantined() {
        PositionEntity p = openPosition();
        OrderEntity legacy = filledUnappliedSell(p, Instant.now().minus(30, ChronoUnit.DAYS));
        jdbc.update("UPDATE \"order\" SET created_at = ? WHERE id = ?", Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), legacy.getId());

        sell(p);
        verify(upbitOrderClient, never()).createOrder(any(), any(), any(), any(), any(), any());
        assertThat(sells(p)).hasSize(1);
        // 기동 점검(배포 전 대조 SQL 과 같은 질의)이 이 포지션을 사람에게 알린다
        assertThat(quarantineReport.report()).anySatisfy(r -> assertThat(((Number) (r.get("position_id") != null
                ? r.get("position_id") : r.get("POSITION_ID"))).longValue()).isEqualTo(p.getId()));
    }

    @Test
    @DisplayName("🔴 같은 포지션의 동시 청산 요청 8개 — 제출 권한은 하나만 얻는다: 거래소 생성 1건, 매도 주문 1행")
    void concurrentSellsGetOneSubmission() throws Exception {
        when(upbitOrderClient.createOrder(anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> new UpbitOrderClient.ExchangeResult(accepted(), "{}"));
        PositionEntity p = openPosition();
        // 매수 주문(예약 3,000 · 반영 2,940/1.47) — 매도 경합이 예약·반영값을 건드리지 않는지 본다
        OrderEntity buy = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DSG").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000")).reservedKrw(new BigDecimal("3000")).filledQuantity(new BigDecimal("9.8"))
                .appliedExecutedFunds(new BigDecimal("2940")).appliedPaidFee(new BigDecimal("1.47")).state("FILLED")
                .sessionKind("LIVE").sessionId(p.getSessionId()).positionId(p.getId())
                .exchangeOrderId("dsg-buy-" + UUID.randomUUID()).clientOrderId("dsg-buy-" + UUID.randomUUID())
                .feeSource("REPORTED").createdAt(Instant.now()).build());
        OrderExecutionEngine target = (OrderExecutionEngine) AopTestUtils.getUltimateTargetObject(engine);
        int n = 8;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(n);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    barrier.await();
                    OrderRequest r = new OrderRequest();
                    r.setCoinPair("KRW-DSG");
                    r.setSide("SELL");
                    r.setOrderType("MARKET");
                    r.setQuantity(new BigDecimal("9.8"));
                    r.setReason("동시 청산 시험");
                    r.setSessionId(p.getSessionId());
                    r.setSessionKind("LIVE");
                    r.setPositionId(p.getId());
                    target.submitOrder(r);
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> f : fs) f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        verify(upbitOrderClient, times(1)).createOrder(anyString(), anyString(), any(), any(), anyString(), anyString());
        assertThat(sells(p)).as("의도 행도 하나 — 나머지는 식별자를 소비하기 전에 거절").hasSize(1);
        // 🔴 나머지 7건은 아무 흔적도 남기지 않는다 — 추가 차감·예약 해제·정리 완료 표시 없음
        assertThat(liveRepo.findById(p.getSessionId()).orElseThrow().getAvailableKrw()).as("세션 잔액 불변")
                .isEqualByComparingTo("7058.53");
        OrderEntity buyAfter = orderRepository.findById(buy.getId()).orElseThrow();
        assertThat(buyAfter.getReservedKrw()).as("매수 예약 불변").isEqualByComparingTo("3000");
        assertThat(buyAfter.getAppliedExecutedFunds()).isEqualByComparingTo("2940");
        assertThat(buyAfter.getState()).isEqualTo("FILLED");
        PositionEntity after = positionRepository.findById(p.getId()).orElseThrow();
        assertThat(after.getSize()).as("포지션 수량 불변").isEqualByComparingTo("9.8");
        assertThat(after.getStatus()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM position_cleanup WHERE position_id = ?", Integer.class, p.getId()))
                .as("정리 대기·완료 표시 없음").isZero();
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", p.getSessionId()))
                .as("주문은 매수 1 + 매도 1 뿐 — 거절된 요청은 FAILED 행도 남기지 않는다").hasSize(2);
    }
}
