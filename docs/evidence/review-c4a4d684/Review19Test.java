package com.cryptoautotrader.api.service;
import com.cryptoautotrader.api.entity.paper.*;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** Local deterministic interleaving, with actual Spring/H2 commits. Not production concurrency evidence. */
@org.springframework.test.context.TestPropertySource(properties="test.fixture.entry-rounds-context=Review19Test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class Review19Test extends AbstractSma20SameInputFixture {
 @MockBean UpbitWebSocketClient ws;
 protected Map<String,Object> paperParams(){return Map.of(PaperMaxInvestment.KEY,20000,PaperExecutionMode.KEY,PaperExecutionMode.LIVE_ALIGNED);}
 protected String reportName(){return "review19";}
 @Test void performanceMixesPreSettlementBalanceAndPostSettlementPosition(){
   var session=paper(BTC);
   var pos=paperPos(session,new BigDecimal("100"),new BigDecimal("95"),new BigDecimal("110"),Instant.now().minus(2,ChronoUnit.HOURS));
   markPriceBook.remove(BTC);
   paperTradingService.stop(session.getId());
   assertThat(paperTradingService.pendingCloseCount(session.getId())).isEqualTo(1);
   var target=AopTestUtils.<PaperTradingService>getUltimateTargetObject(paperTradingService);
   var original=(PaperPositionRepository)ReflectionTestUtils.getField(target,"positionRepo");
   var injected=new AtomicBoolean(false);
   var decorated=mock(PaperPositionRepository.class,org.mockito.AdditionalAnswers.delegatesTo(original));
   doAnswer(inv->{
       Long sid=inv.getArgument(0);
       if(sid.equals(session.getId())&&injected.compareAndSet(false,true)) {
         // Another thread has its own persistence context, as a real quote handler does.
         java.util.concurrent.CompletableFuture.runAsync(()->target.doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC,new BigDecimal("101"),Instant.now(),RealtimePriceEvent.WS_TRADE))).get(20,java.util.concurrent.TimeUnit.SECONDS);
       }
       return original.findBySessionId(sid);
   }).when(decorated).findBySessionId(anyLong());
   ReflectionTestUtils.setField(target,"positionRepo",decorated);
   try {
     var response=paperTradingService.getOverallPerformance().getSessions().stream().filter(s->s.getSessionId().equals(session.getId())).findFirst().orElseThrow();
     var actual=balanceRepo.findById(session.getId()).orElseThrow();
     var storedPosition=original.findById(pos.getId()).orElseThrow();
     System.out.println("[S5] status="+response.getValuationStatus()+" pending="+response.getPendingCloseCount()+" trades="+response.getTotalTrades()+" responseAsset="+response.getCurrentAsset()+" databaseAsset="+actual.getTotalKrw()+" responseRealized="+response.getRealizedPnl()+" databaseRealized="+actual.getRealizedPnl()+" position="+storedPosition.getStatus());
     assertThat(injected.get()).isTrue();
     assertThat(storedPosition.getStatus()).isEqualTo("CLOSED");
     assertThat(response.getPendingCloseCount()).isZero();
     assertThat(response.getValuationStatus()).isEqualTo("평가 완료");
     assertThat(response.getTotalTrades()).isEqualTo(1);
     assertThat(response.getCurrentAsset()).isNotEqualByComparingTo(actual.getTotalKrw());
     assertThat(response.getRealizedPnl()).isNotEqualByComparingTo(actual.getRealizedPnl());
   } finally { ReflectionTestUtils.setField(target,"positionRepo",original); }
 }
}
