package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.config.AsyncConfig;
import com.cryptoautotrader.api.support.ExecutorBarrier;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조사 후보(미승인) — marketDataExecutor 포화 거절 격리 (2026-11-12, 총괄 v1.152 F). {@link SharedMarketDataExecutorSaturationTest} ① 과 같은
 * 실제 포화(래치)에서 발행 1건: 발행 쪽 예외 없음, 동기 시세 장부는 그 가격을 받음, PAPER 선택 모드 리스너도 호출됨(그 작업은 거절 수로 셈),
 * 거절된 @Async 작업은 버린 수로 셈. 해제 뒤 정상 처리.
 */
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=MarketDataRejectIsolationTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("조사 후보 — 포화 거절이 같은 시세 이벤트의 다른 리스너(시세 장부·PAPER)와 발행 쪽을 끊지 않음")
class MarketDataRejectIsolationTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient upbitWebSocketClient;
    @Autowired @Qualifier("marketDataExecutor") private java.util.concurrent.Executor marketDataExecutor;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "md-reject-isolation";
    }

    @Test
    void rejectionDoesNotCutOtherListeners() throws Exception {
        ThreadPoolExecutor pool = ((ThreadPoolTaskExecutor) marketDataExecutor).getThreadPoolExecutor();
        int core = pool.getCorePoolSize(), max = pool.getMaximumPoolSize();
        int qcap = pool.getQueue().size() + pool.getQueue().remainingCapacity();
        insertCache(BTC, uptrend(lastOpen));
        paper(BTC);
        PaperTradingService paperTarget = AopTestUtils.getUltimateTargetObject(paperTradingService);
        ReflectionTestUtils.invokeMethod(paperTarget, "refreshAlignedCoins", balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING"));
        ExecutorBarrier.awaitPriorTasks(pool, 15_000);

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(max);
        List<Future<?>> blocked = new ArrayList<>();
        Runnable blocker = () -> {
            running.countDown();
            try { release.await(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        for (int i = 0; i < core; i++) blocked.add(pool.submit(blocker));
        for (int i = 0; i < qcap; i++) blocked.add(pool.submit(() -> { }));
        for (int i = core; i < max; i++) blocked.add(pool.submit(blocker));
        assertThat(running.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.getQueue().remainingCapacity()).isZero();

        long droppedBefore = AsyncConfig.MARKET_DATA_REJECTED.get();
        long paperRejBefore = paperTarget.alignedRtRejected.get();
        Throwable thrown = null;
        try {
            eventPublisher.publishEvent(new RealtimePriceEvent(BTC, new BigDecimal("12000000"), Instant.now(), RealtimePriceEvent.WS_TRADE));
        } catch (Throwable t) {
            thrown = t;
        }
        BigDecimal mark = markPriceBook.freshPrice(BTC, Instant.now());
        long dropped = AsyncConfig.MARKET_DATA_REJECTED.get() - droppedBefore;
        long paperRej = paperTarget.alignedRtRejected.get() - paperRejBefore;
        System.out.println("[F-격리] 발행 예외=" + thrown + " 시세 장부=" + mark + " 버린 @Async 작업=" + dropped + " PAPER 작업(버림)=" + paperRej);

        release.countDown();
        for (Future<?> f : blocked) f.get(20, TimeUnit.SECONDS);
        ExecutorBarrier.awaitPriorTasks(pool, 15_000);

        assertThat(thrown).as("발행 쪽 예외 없음").isNull();
        assertThat(mark).as("동기 시세 장부는 같은 이벤트를 받음").isEqualByComparingTo("12000000");
        assertThat(dropped).as("거절된 @Async 리스너 작업(LIVE·DYNAMIC 등)은 버린 수로 셈").isGreaterThanOrEqualTo(2);
        // PAPER 는 자기 거절 경로(alignedRtRejected)로 셈하거나, 실행기 버림 수에 들어간다 — 어느 쪽이든 호출됐다는 뜻
        assertThat(paperRej + dropped).as("PAPER 선택 모드 리스너도 호출됨").isGreaterThanOrEqualTo(3);
    }
}
