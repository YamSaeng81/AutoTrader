package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.ExecutorBarrier;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.158 결정 3 — {@link OrderExecutionEngine#markClosingWithSellIntent} 의 롤백 계약 (로컬 실제 PostgreSQL).
 * 호출자 트랜잭션이 롤백되면 CLOSING·의도가 함께 사라지고 제출은 0회다. 이미 CLOSING 이면 의도를 만들지 않는다.
 */
@TestPropertySource(properties = {
        "spring.datasource.url=${CR_PG_URL}",
        "spring.datasource.username=${CR_PG_USER:trader}",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never"
})
@EnabledIfEnvironmentVariable(named = "CR_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("v1.158 결정 3 — CLOSING+의도 롤백 계약 (로컬 PostgreSQL)")
class ClosingIntentRollbackPgTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine orderEngine;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private TransactionTemplate tx;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private TelegramNotificationService telegramService;

    private PositionEntity pos(String status) {
        String coin = "KRW-CRR" + (System.nanoTime() % 100000);
        Long sid = liveRepo.save(LiveTradingSessionEntity.builder().sessionType("REAL").strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair(coin).timeframe("M5").initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                .totalAssetKrw(new BigDecimal("10000")).status("RUNNING").startedAt(Instant.now()).build()).getId();
        return positionRepository.save(PositionEntity.builder().coinPair(coin).side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300")).size(new BigDecimal("9.8"))
                .investedKrw(new BigDecimal("2940")).positionFee(BigDecimal.ZERO).realizedPnl(BigDecimal.ZERO)
                .status(status).closingAt("CLOSING".equals(status) ? Instant.now() : null)
                .sessionKind("LIVE").sessionId(sid).openedAt(Instant.now().minus(1, ChronoUnit.HOURS)).build());
    }

    private OrderRequest sell(PositionEntity p) {
        OrderRequest o = new OrderRequest();
        o.setCoinPair(p.getCoinPair()); o.setSide("SELL"); o.setOrderType("MARKET"); o.setQuantity(p.getSize());
        o.setReason("롤백 시험"); o.setSessionId(p.getSessionId()); o.setSessionKind("LIVE"); o.setPositionId(p.getId());
        return o;
    }

    @Test
    @DisplayName("⑥ 호출자 트랜잭션 롤백: CLOSING·의도 함께 사라짐, 제출 0")
    void callerRollback() {
        PositionEntity p = pos("OPEN");
        OrderExecutionEngine.ClosingSellIntent r = tx.execute(st -> {
            OrderExecutionEngine.ClosingSellIntent in = orderEngine.markClosingWithSellIntent(p.getId(), Instant.now(), ExitReason.STOP_LOSS, sell(p));
            st.setRollbackOnly();
            return in;
        });
        ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
        System.out.println("[CR158] ⑥ 트랜잭션 안 결과=" + r + " 롤백 뒤 상태=" + positionRepository.findById(p.getId()).orElseThrow().getStatus()
                + " 주문=" + orderRepository.findByPositionIdOrderByCreatedAtDesc(p.getId()).size());
        assertThat(r.marked()).isTrue();
        assertThat(r.orderId()).isNotNull();
        assertThat(positionRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
        assertThat(orderRepository.findByPositionIdOrderByCreatedAtDesc(p.getId())).isEmpty();
        Mockito.verify(upbitOrderClient, Mockito.never()).createOrder(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("⑦ 이미 CLOSING: 전환 0·의도 0·제출 0")
    void alreadyClosing() {
        PositionEntity p = pos("CLOSING");
        OrderExecutionEngine.ClosingSellIntent r = orderEngine.markClosingWithSellIntent(p.getId(), Instant.now(), ExitReason.STOP_LOSS, sell(p));
        ExecutorBarrier.awaitPriorTasks(orderExecutor.getThreadPoolExecutor(), 20_000);
        assertThat(r.marked()).isFalse();
        assertThat(r.orderId()).isNull();
        assertThat(orderRepository.findByPositionIdOrderByCreatedAtDesc(p.getId())).isEmpty();
        Mockito.verify(upbitOrderClient, Mockito.never()).createOrder(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }
}
