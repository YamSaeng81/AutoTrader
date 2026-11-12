package com.cryptoautotrader.api.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import static org.assertj.core.api.Assertions.assertThat;

/** Root reviewer probe; uses immutable submitted fixture and real Spring service/order paths. */
class ProtectionPendingWitnessTest extends RealtimeProtectionSaturationTest {
    private Object call(String method,Object...args) { return ReflectionTestUtils.invokeMethod(this,method,args); }
    private ApplicationContext ctx() { return (ApplicationContext)ReflectionTestUtils.getField(this,"ctx"); }
    private static final java.util.concurrent.atomic.AtomicInteger SEQ=new java.util.concurrent.atomic.AtomicInteger();
    private String coin() { return "KRW-ROOT"+SEQ.incrementAndGet(); }
    private void price(String coin,String price,Instant at) {
        ((ApplicationEventPublisher)ctx()).publishEvent(new RealtimePriceEvent(coin,new BigDecimal(price),at,RealtimePriceEvent.WS_TRADE));
    }
    private long sells(String coin) { return ((Number)call("exchangeSells",coin)).longValue(); }
    private RealtimeProtectionDispatcher d() { return ctx().getBean(RealtimeProtectionDispatcher.class); }
    private final class Busy implements AutoCloseable {
        final CountDownLatch release=new CountDownLatch(1);
        final List<Future<?>> held=new ArrayList<>();
        Busy() throws Exception {
            ThreadPoolExecutor pool=((ThreadPoolTaskExecutor)ReflectionTestUtils.getField(ProtectionPendingWitnessTest.this,"marketDataExecutor")).getThreadPoolExecutor();
            call("barrier");
            int n=Math.max(pool.getCorePoolSize(),pool.getPoolSize());
            CountDownLatch entered=new CountDownLatch(n);
            for(int i=0;i<n;i++) held.add(pool.submit(()->{entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(pool.getQueue().remainingCapacity()).isGreaterThan(10);
        }
        public void close() throws Exception {release.countDown();for(Future<?> f:held)f.get(15,TimeUnit.SECONDS);call("barrier");d().retryPending();call("barrier");}
    }
    @Test void queuedReboundRemovesStopCrossingWithoutAnyRejection() throws Exception {
        String c=coin(); Object lf=call("holding","LIVE",c); Object df=call("holding","DYNAMIC",c);
        call("clearThrottle",c);
        long lr=d().snapshot("LIVE").rejected(),dr=d().snapshot("DYNAMIC").rejected();
        Instant t=Instant.now();
        try(Busy b=new Busy()) { price(c,"280",t);price(c,"300",t.plusMillis(1)); }
        assertThat(d().snapshot("LIVE").rejected()).isEqualTo(lr);
        assertThat(d().snapshot("DYNAMIC").rejected()).isEqualTo(dr);
        assertThat(sells(c)).isZero();
        assertThat((String)call("status",lf)).isEqualTo("OPEN");
        assertThat((String)call("status",df)).isEqualTo("OPEN");
        System.out.println("[ROOT F] queue available; 280(SL285) then300 before drain -> LIVE/DYNAMIC OPEN, SELL0, rejection delta0");
    }
    @Test void isolatedStopCrossingControlSellsBothEngines() throws Exception {
        String c=coin();call("holding","LIVE",c);call("holding","DYNAMIC",c);call("clearThrottle",c);
        try(Busy b=new Busy()) {price(c,"280",Instant.now());}
        assertThat(sells(c)).isEqualTo(2);
        System.out.println("[ROOT F] control: 280 only -> LIVE/DYNAMIC SELL2");
    }
    @Test void futureObservedPendingQuoteSuppressesFollowingCurrentStopQuote() throws Exception {
        String c=coin();Object lf=call("holding","LIVE",c);Object df=call("holding","DYNAMIC",c);call("clearThrottle",c);
        Instant t=Instant.now();
        try(Busy b=new Busy()) {price(c,"300",t.plusSeconds(86400));price(c,"280",t);}
        assertThat(sells(c)).isZero();assertThat((String)call("status",lf)).isEqualTo("OPEN");assertThat((String)call("status",df)).isEqualTo("OPEN");
        assertThat(ctx().getBean(MarkPriceBook.class).freshPrice(c,Instant.now())).isEqualByComparingTo("280");
        System.out.println("[ROOT F] future300 then current280 -> book280, LIVE/DYNAMIC OPEN SELL0 (raw pending time suppresses current quote)");
    }
}
