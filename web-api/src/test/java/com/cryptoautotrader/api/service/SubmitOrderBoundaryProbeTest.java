package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진단 — <b>{@code submitOrder} 가 어느 스레드·어느 트랜잭션에서 도는가.</b>
 *
 * <h3>왜 측정하는가</h3>
 *
 * <p>2026-10-07 에 ③-1 수정을 철회하면서 "{@code submitOrder} 는 바깥 트랜잭션 안에서 돈다" 고
 * 적었다. 🔴 <b>그것은 근거를 넘어선 결론이었다.</b> {@code submitOrder} 는 {@code @Async} 이므로
 * 호출자의 트랜잭션이 <b>전달되지 않는다</b>. "바깥 트랜잭션" 이 평가 스레드의 것인지, 주문 작업
 * 스레드 안에서 <b>따로 시작된</b> 것인지 구분해야 한다 — {@code PROPAGATION_REQUIRED} 도 다른
 * 스레드의 트랜잭션에 합류시키지는 못하므로, 이 구분이 재설계의 전제다.</p>
 *
 * <h3>지금까지의 증거는 여기까지만 말한다</h3>
 * <ul>
 *   <li>승인 트랜잭션 변경을 되돌리자 중복 저장이 사라졌다 → <b>그 변경이 재현에 관여했다.</b></li>
 *   <li>별도 조회에서 주문을 찾지 못했다 → <b>그 시점의 조회에서는 주문이 보이지 않았다.</b></li>
 *   <li>🔴 이것만으로 중복 INSERT 의 정확한 원인이나 {@code REQUIRES_NEW} 방식 전체의 불가능성까지
 *       확정되지는 않는다.</li>
 * </ul>
 *
 * <p>이 시험은 <b>생산 코드를 바꾸지 않고</b> 제출 경로의 여러 지점에서 스레드 이름과 트랜잭션
 * 활성/이름을 수집해 순서를 드러낸다. 결과는 실패 메시지에 실어 눈으로 확인한다.</p>
 */
@DisplayName("진단 — submitOrder 의 스레드·트랜잭션 경계를 측정한다")
class SubmitOrderBoundaryProbeTest extends IntegrationTestBase {

    @Autowired
    private LiveTradingService liveTradingService;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    @Qualifier("orderExecutor")
    private ThreadPoolTaskExecutor orderExecutor;

    @MockBean
    private UpbitOrderClient upbitOrderClient;

    /** 제출 경로 안의 관측 지점 — PENDING 주문 생성 직후에 불린다. */
    @SpyBean
    private TradeLogRepository tradeLogRepository;

    /** 청산 경로 안의 관측 지점 — 정지 트랜잭션 안에서 불린다. */
    @SpyBean
    private PositionStateTransition positionStateTransition;

    private final List<Long> createdSessions = new ArrayList<>();
    private final Map<String, String> probes = new ConcurrentHashMap<>();

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            orderRepository.findBySessionIdAndStateIn(id,
                    List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED", "FILLED", "CANCELLED", "FAILED"))
                    .forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id)
                    .forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
    }

    /** 현재 지점의 스레드·트랜잭션 식별을 한 줄로 만든다. */
    private static String here() {
        return String.format("thread=%s txActive=%s txName=%s roTx=%s",
                Thread.currentThread().getName(),
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.getCurrentTransactionName(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }

    private UpbitOrderClient.ExchangeResult acceptedSell() {
        OrderResponse r = new OrderResponse();
        r.setUuid("probe-uuid");
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("wait");
        r.setMarket("KRW-DOGE");
        r.setExecutedVolume(BigDecimal.ZERO);
        return new UpbitOrderClient.ExchangeResult(r, "{}");
    }

    private void awaitOrderExecutorDrained() {
        var pool = orderExecutor.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @DisplayName("정지 경로와 제출 경로의 스레드·트랜잭션을 수집한다")
    void probeBoundaries() {
        probes.put("0-test", here());

        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE")
                .timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("2000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .status("RUNNING")
                .startedAt(Instant.now())
                .build());
        Long sessionId = session.getId();
        createdSessions.add(sessionId);

        positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(new BigDecimal("26.00000000"))
                .investedKrw(new BigDecimal("8000.00000000"))
                .status("OPEN").sessionKind("LIVE").sessionId(sessionId)
                .openedAt(Instant.now()).build());

        Mockito.doReturn(acceptedSell()).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());

        // 청산 경로 안 (정지 트랜잭션)
        Mockito.doAnswer(inv -> {
            probes.putIfAbsent("1-markClosing", here());
            return inv.callRealMethod();
        }).when(positionStateTransition).markClosingIfOpen(
                Mockito.anyLong(), Mockito.any(), Mockito.any());

        // 제출 경로 안 (PENDING 주문 생성 직후 · 거래소 호출 직전)
        Mockito.doAnswer(inv -> {
            probes.putIfAbsent("2-recordTradeLog", here());
            // 그 시점에 주문 행이 **다른 트랜잭션에서 보이는가** — 중복 INSERT 가설의 핵심
            // 🔴 V84: 신규 주문은 PENDING 이 아니라 INTENT_READY 로 시작한다.
            long visible = orderRepository.findBySessionIdAndStateIn(sessionId,
                    List.of("PENDING", "INTENT_READY", "SUBMITTING", "SUBMITTED")).size();
            probes.putIfAbsent("3-visibleOrders", "같은 스레드 조회로 보이는 활성 주문 수=" + visible);
            return inv.getArgument(0);
        }).when(tradeLogRepository).save(Mockito.any());

        liveTradingService.stopSession(sessionId);
        awaitOrderExecutorDrained();

        List<OrderEntity> rows = orderRepository.findBySessionIdAndStateIn(sessionId,
                List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED", "FILLED", "CANCELLED", "FAILED"));
        probes.put("4-finalRows", "주문 행 수=" + rows.size());

        String report = probes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + ": " + e.getValue())
                .reduce("", (a, b) -> a + " || " + b);

        // 🔴 측정값을 눈으로 보기 위해 일부러 실패시키지 않는다. 핵심 사실만 단정한다:
        //    제출 경로의 관측 지점이 실제로 지나갔는지와, 그 지점의 스레드가 평가 스레드와 같은지.
        assertThat(probes).as("제출 경로 관측 지점을 지나야 한다: %s", report)
                .containsKey("2-recordTradeLog");
        // 🔴 이 시험은 ③-1 수정이 **의존하는 전제**를 고정한다. 전제가 바뀌면 그 수정이 조용히
        //    무너지므로(예: `submitOrder` 가 호출자 트랜잭션에 합류하도록 바뀌면 거기서 거는 잠금의
        //    수명이 달라진다) 여기서 깨뜨려 알린다.
        assertThat(probes.get("1-markClosing"))
                .as("정지는 호출자 스레드에서 자기 트랜잭션으로 돈다: %s", report)
                .contains("txActive=true")
                .contains("LiveTradingService.stopSession");

        // 🔴 **측정값이 바뀌었다** (2026-10-12, V84). 종전에는 트랜잭션 이름이
        //    `OrderExecutionEngine.submitOrder` 였다 — `@Transactional @Async` 였기 때문이다.
        //    V84 에서 `submitOrder` 의 `@Transactional` 을 떼고 A·B·D 를 `TransactionTemplate`
        //    으로 나눴으므로, 이 지점의 트랜잭션은 **템플릿이 만든 이름 없는 트랜잭션**이다
        //    (`txName=null`).
        //
        //    🔴 바뀐 쪽이 측정값이고, **고정하려던 사실은 그대로다**: 제출은 호출자 트랜잭션이
        //    아니라 **자기 트랜잭션**에서 돈다. 그래서 여기서 REQUIRES_NEW 를 쓸 수 없고(주문 행을
        //    만든 바로 그 트랜잭션이 정지된다) 이 트랜잭션에서 그대로 잠근다. 이름이 아니라 그
        //    사실을 단정한다 — 이름을 단정하면 경계가 아니라 구현 세부를 고정하게 된다.
        assertThat(probes.get("2-recordTradeLog"))
                .as("🔴 제출은 **다른 스레드**에서 **자기 트랜잭션**으로 돈다 — 호출자 트랜잭션이 "
                        + "아니다: %s", report)
                .contains("txActive=true");
        assertThat(probes.get("2-recordTradeLog"))
                .as("🔴 호출자(정지)의 트랜잭션에 **합류하지 않았다** — 합류했다면 거기서 거는 잠금의 "
                        + "수명이 달라져 ③-1 수정이 조용히 무너진다: %s", report)
                .doesNotContain("LiveTradingService.stopSession");

        assertThat(probes.get("2-recordTradeLog"))
                .as("제출 스레드는 정지 스레드와 달라야 한다 — 같다면 @Async 가 동작하지 않는 것이다")
                .doesNotContain(Thread.currentThread().getName());

        assertThat(probes.get("3-visibleOrders"))
                .as("제출 트랜잭션 안에서는 그 주문 행이 보인다(같은 트랜잭션이므로): %s", report)
                .isEqualTo("같은 스레드 조회로 보이는 활성 주문 수=1");

        assertThat(probes.get("4-finalRows"))
                .as("주문 행은 하나여야 한다 — 둘이면 같은 주문이 중복 저장된 것이다: %s", report)
                .isEqualTo("주문 행 수=1");
    }
}
