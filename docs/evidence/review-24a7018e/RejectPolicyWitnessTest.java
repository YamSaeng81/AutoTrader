package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.config.AsyncConfig;
import com.cryptoautotrader.api.support.ExecutorBarrier;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
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
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Immutable 03cfd9eb source witness. Local synthetic saturation; no external orders. */
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=RootRejectPolicyWitness")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RejectPolicyWitnessTest extends AbstractSma20SameInputFixture {
    @MockBean private UpbitWebSocketClient upbitWebSocketClient;
    @Autowired @Qualifier("marketDataExecutor") private java.util.concurrent.Executor executor;
    @Autowired private ApplicationEventPublisher publisher;
    protected Map<String,Object> paperParams() {return Map.of(PaperMaxInvestment.KEY,20000,PaperExecutionMode.KEY,PaperExecutionMode.LIVE_ALIGNED);}
    protected String reportName() {return "root-reject-policy";}
    @SuppressWarnings("unchecked") private int liveHistory() {
        Object target=AopTestUtils.getUltimateTargetObject(liveTradingService);
        Deque<?> d=((Map<String,Deque<?>>)ReflectionTestUtils.getField(target,"priceHistory")).get(BTC);
        return d==null?0:d.size();
    }
    @Test @SuppressWarnings("unchecked")
    void globalDiscardKeepsMarkButLosesRealtimeAndRecoveryUntilNewEvent() throws Exception {
        ThreadPoolExecutor pool=((ThreadPoolTaskExecutor)executor).getThreadPoolExecutor();
        paper(BTC);
        Object pt=AopTestUtils.getUltimateTargetObject(paperTradingService);
        ReflectionTestUtils.invokeMethod(pt,"refreshAlignedCoins",balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING"));
        ExecutorBarrier.awaitPriorTasks(pool,15000);
        clearRealtimeThrottle();
        Object lt=AopTestUtils.getUltimateTargetObject(liveTradingService);
        Set<Long> recovery=(Set<Long>)ReflectionTestUtils.getField(lt,"exchangeStoppedSessionIds");
        long fakeId=Long.MAX_VALUE;
        assertThat(recovery).isEmpty();
        recovery.add(fakeId); // absent session: normal recovery removes the ID, creates no session/order
        CountDownLatch release=new CountDownLatch(1),running=new CountDownLatch(pool.getMaximumPoolSize());
        List<Future<?>> pending=new ArrayList<>();
        Runnable blocker=()->{running.countDown();try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}};
        long before=AsyncConfig.MARKET_DATA_REJECTED.get();
        try {
            int cap=pool.getQueue().size()+pool.getQueue().remainingCapacity();
            for(int i=0;i<pool.getCorePoolSize();i++)pending.add(pool.submit(blocker));
            for(int i=0;i<cap;i++)pending.add(pool.submit(()->{}));
            for(int i=pool.getCorePoolSize();i<pool.getMaximumPoolSize();i++)pending.add(pool.submit(blocker));
            assertThat(running.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(pool.getQueue().remainingCapacity()).isZero();
            publisher.publishEvent(new RealtimePriceEvent(BTC,new BigDecimal("12000000"),Instant.now(),RealtimePriceEvent.WS_TRADE));
            publisher.publishEvent(new ExchangeRecoveredEvent(this,"DOWN"));
            assertThat(markPriceBook.freshPrice(BTC,Instant.now())).isEqualByComparingTo("12000000");
            release.countDown();
            for(Future<?> f:pending)f.get(15,TimeUnit.SECONDS);
            ExecutorBarrier.awaitPriorTasks(pool,15000);
            assertThat(liveHistory()).as("discarded LIVE event is not replayed after draining").isZero();
            assertThat(recovery).as("recovery event was discarded too").contains(fakeId);
            long dropped=AsyncConfig.MARKET_DATA_REJECTED.get()-before;
            assertThat(dropped).isGreaterThanOrEqualTo(4);
            System.out.println("ROOT discard: mark=12000000, LIVE history=0 after drain, recovery ID retained, dropped="+dropped);
            publisher.publishEvent(new RealtimePriceEvent(BTC,new BigDecimal("12000100"),Instant.now(),RealtimePriceEvent.WS_TRADE));
            publisher.publishEvent(new ExchangeRecoveredEvent(this,"DOWN"));
            ExecutorBarrier.awaitPriorTasks(pool,15000);
            assertThat(liveHistory()).isPositive();
            assertThat(recovery).doesNotContain(fakeId);
            System.out.println("ROOT new-event control: LIVE history="+liveHistory()+", recovery ID removed");
        } finally {
            release.countDown();
            for(Future<?> f:pending)f.get(15,TimeUnit.SECONDS);
            recovery.remove(fakeId);
        }
    }
}
