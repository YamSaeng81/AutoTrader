package com.cryptoautotrader.api.service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Deterministic dispatcher-only probe: no Spring, DB, orders, threads, network or wall-time claim. */
class FifoFairnessWitnessTest {
  static RealtimePriceEvent price(String c) {return new RealtimePriceEvent(c,new BigDecimal("280"),Instant.now(),RealtimePriceEvent.WS_TRADE);}
  @Test void oneBusyCoinHasNoPerTurnBudgetDespiteBoundedPendingQueue() {
    ArrayDeque<Runnable> tasks=new ArrayDeque<>();
    Executor executor=tasks::addLast;
    var live=mock(LiveTradingService.class); var dyn=mock(DynamicTradingService.class);
    var ref=new AtomicReference<RealtimeProtectionDispatcher>();
    AtomicInteger replenished=new AtomicInteger(),a=new AtomicInteger(),b=new AtomicInteger();
    org.mockito.stubbing.Answer<Void> handler=inv->{
      RealtimePriceEvent e=inv.getArgument(0);
      if(e.getCoinCode().equals("KRW-A")){ a.incrementAndGet(); if(replenished.incrementAndGet()<250) ref.get().onPrice(price("KRW-A")); }
      else b.incrementAndGet();
      return null;
    };
    doAnswer(handler).when(live).onRealtimePriceEvent(any()); doAnswer(handler).when(dyn).onRealtimePriceEvent(any());
    var d=new RealtimeProtectionDispatcher(executor,live,dyn); ref.set(d);
    d.onPrice(price("KRW-A")); d.onPrice(price("KRW-B"));
    assertThat(tasks).hasSize(4);
    tasks.removeFirst().run();
    assertThat(a.get()).isEqualTo(250); assertThat(b.get()).isZero();
    assertThat(d.snapshot("LIVE").rejected()+d.snapshot("DYNAMIC").rejected()).isZero();
    System.out.println("[ROOT F2] one queued A task consumed 250 handler calls; already queued B calls=0; rejection=0; queue capacity 100 does not bound a worker turn");
  }
}
