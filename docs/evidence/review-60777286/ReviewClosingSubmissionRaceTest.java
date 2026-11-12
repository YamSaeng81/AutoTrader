package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionCleanupEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionCleanupRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.ExecutorBarrier;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * v1.157 §125-4 진단(커밋 안 함) — SchedulerProfileCleanupTest LIVE 실패 원문(diag11-Rhalf1-S, 12:26:19.391 정리 제출 → 4ms 뒤
 * "CLOSING 포지션에 SELL 주문 없음 — OPEN 롤백")의 순서를 결정적으로 재현한다. 주문 실행기 작업자를 붙잡아 커밋 뒤 비동기 매도 제출을
 * 대기열에 두고, 그 사이 청산 확인(reconcileClosingPositions)을 직접 부른 뒤 풀어 준다. 판정 없이 관측만 출력한다.
 */
class ReviewClosingSubmissionRaceTest extends IntegrationTestBase {

    @Autowired private LiveTradingService liveTradingService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private PositionCleanupRepository cleanupRepository;
    @Autowired private PositionCleanupRegistrar cleanupRegistrar;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired @Qualifier("orderExecutor") private ThreadPoolTaskExecutor orderExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient ws;
    @Autowired private OrderExecutionEngine orderEngine;

    private void out(String s) { System.out.println("[DIAG157-CR] " + s); }

    private String state(Long posId) {
        PositionEntity p = positionRepository.findById(posId).orElseThrow();
        List<OrderEntity> os = orderRepository.findByPositionIdOrderByCreatedAtDesc(posId);
        StringBuilder b = new StringBuilder("포지션=" + p.getStatus() + " 주문=" + os.size());
        for (OrderEntity o : os) {
            b.append(" [#").append(o.getId()).append(' ').append(o.getSide()).append(' ').append(o.getState())
                    .append(" uuid=").append(o.getExchangeOrderId()).append(" 실패=").append(o.getFailureCode())
                    .append(" 사유=").append(o.getFailedReason()).append(']');
        }
        PositionCleanupEntity c = cleanupRepository.findById(posId).orElse(null);
        if (c != null) b.append(" 정리(시도=").append(c.getAttempts()).append(" 메모=").append(c.getLastNote()).append(')');
        long calls = Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName())).count();
        return b.append(" 거래소createOrder=").append(calls).toString();
    }

    @Test
    void cleanupSubmitThenReconcileBeforeIntent() throws Exception {
        Mockito.when(upbitOrderClient.getOrder(Mockito.anyString())).thenAnswer(inv -> {
            OrderResponse r = new OrderResponse(); r.setUuid(inv.getArgument(0)); r.setState("wait"); return r; });
        Mockito.when(upbitOrderClient.createOrder(Mockito.any(), Mockito.eq("ask"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> { OrderResponse r = new OrderResponse(); r.setUuid("uuid-diag-" + System.nanoTime());
                    r.setState("wait"); r.setSide("ask"); return new UpbitOrderClient.ExchangeResult(r, "{}"); });

        Long sid = liveRepo.save(LiveTradingSessionEntity.builder().sessionType("REAL").strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5").initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7000"))
                .totalAssetKrw(new BigDecimal("10000")).status("STOPPED").startedAt(Instant.now()).build()).getId();
        PositionEntity pos = positionRepository.save(PositionEntity.builder().coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300")).size(new BigDecimal("9.8"))
                .investedKrw(new BigDecimal("3000")).status("OPEN").sessionKind("LIVE").sessionId(sid).openedAt(Instant.now()).build());
        cleanupRegistrar.register(pos.getId(), "LIVE", sid, PositionCleanupEntity.REASON_LATE_FILL);

        ThreadPoolExecutor pool = orderExecutor.getThreadPoolExecutor();
        ExecutorBarrier.awaitPriorTasks(pool, 15_000);
        int workers = pool.getPoolSize();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(workers);
        for (int i = 0; i < workers; i++) pool.submit(() -> { running.countDown();
            try { release.await(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        out("작업자 붙잡음=" + running.await(10, TimeUnit.SECONDS) + " workers=" + workers + " queue=" + pool.getQueue().size());

        liveTradingService.cleanupStoppedSessionPositions();
        out("① 정리 직후(매도 제출 대기열): " + state(pos.getId()) + " | 대기열=" + pool.getQueue().size());

        liveTradingService.reconcileClosingPositions();
        out("② 청산 확인 직후(의도 생성 전): " + state(pos.getId()));

        release.countDown();
        Mockito.verify(upbitOrderClient, Mockito.timeout(20_000).times(1)).createOrder(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        ExecutorBarrier.awaitPriorTasks(pool, 20_000);
        out("③ 풀어 준 뒤 실행기 장벽: " + state(pos.getId()));

        liveTradingService.reconcileClosingPositions();
        out("④ 청산 확인 1회 더: " + state(pos.getId()));
        liveTradingService.cleanupStoppedSessionPositions();
        ExecutorBarrier.awaitPriorTasks(pool, 20_000);
        out("⑤ 정리 1회 더(+장벽): " + state(pos.getId()));
        OrderEntity submitted = orderRepository.findByPositionIdOrderByCreatedAtDesc(pos.getId()).get(0);
        org.assertj.core.api.Assertions.assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
        org.assertj.core.api.Assertions.assertThat(submitted.getState()).isEqualTo("SUBMITTED");
        BigDecimal before = liveRepo.findById(sid).orElseThrow().getAvailableKrw();
        OrderResponse fill = new OrderResponse(); fill.setUuid(submitted.getExchangeOrderId());
        fill.setState("done"); fill.setSide("ask"); fill.setOrdType("market"); fill.setMarket("KRW-DOGE");
        fill.setVolume(new BigDecimal("9.8")); fill.setExecutedVolume(new BigDecimal("9.8"));
        fill.setExecutedFunds(new BigDecimal("2744")); fill.setPaidFee(new BigDecimal("1.372"));
        Mockito.when(upbitOrderClient.getOrder(submitted.getExchangeOrderId())).thenReturn(fill);
        orderEngine.checkOrderStatus(submitted.getId());
        org.assertj.core.api.Assertions.assertThat(orderRepository.findById(submitted.getId()).orElseThrow().getState()).isEqualTo("FILLED");
        liveTradingService.reconcileClosingPositions();
        out("⑥ 실제 폴러 FILLED 뒤 일반 청산 확인: " + state(pos.getId()) + " · 가용=" + liveRepo.findById(sid).orElseThrow().getAvailableKrw());
        org.assertj.core.api.Assertions.assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
        org.assertj.core.api.Assertions.assertThat(orderRepository.findById(submitted.getId()).orElseThrow().getSellAppliedAt()).isNull();
        org.assertj.core.api.Assertions.assertThat(liveRepo.findById(sid).orElseThrow().getAvailableKrw()).isEqualByComparingTo(before);
        liveTradingService.cleanupStoppedSessionPositions();
        out("⑦ 정리 작업이 CLOSING을 복원한 뒤: " + state(pos.getId()) + " · 가용=" + liveRepo.findById(sid).orElseThrow().getAvailableKrw());
        org.assertj.core.api.Assertions.assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("CLOSING");
        org.assertj.core.api.Assertions.assertThat(liveRepo.findById(sid).orElseThrow().getAvailableKrw()).isEqualByComparingTo(before);
        liveTradingService.reconcileClosingPositions();
        out("⑧ 다음 일반 청산 확인 뒤: " + state(pos.getId()) + " · 가용=" + liveRepo.findById(sid).orElseThrow().getAvailableKrw());
        org.assertj.core.api.Assertions.assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("CLOSED");
        org.assertj.core.api.Assertions.assertThat(orderRepository.findById(submitted.getId()).orElseThrow().getSellAppliedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(liveRepo.findById(sid).orElseThrow().getAvailableKrw()).isGreaterThan(before);
        BigDecimal settled = liveRepo.findById(sid).orElseThrow().getAvailableKrw();
        liveTradingService.reconcileClosingPositions(); liveTradingService.cleanupStoppedSessionPositions();
        org.assertj.core.api.Assertions.assertThat(liveRepo.findById(sid).orElseThrow().getAvailableKrw()).isEqualByComparingTo(settled);
        org.assertj.core.api.Assertions.assertThat(orderRepository.findByPositionIdOrderByCreatedAtDesc(pos.getId())).hasSize(1);
        out("⑨ 재확인 후 입금/매도 중복 없음 · 가용="+settled);


    }
}
