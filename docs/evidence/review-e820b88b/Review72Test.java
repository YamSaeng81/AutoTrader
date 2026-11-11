package com.cryptoautotrader.api.service;
import com.cryptoautotrader.api.entity.paper.*;
import com.cryptoautotrader.api.controller.PaperTradingController;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** Assertions describe remaining behavior, not desired acceptance. No external orders or messages. */
@org.springframework.test.context.TestPropertySource(properties="test.fixture.entry-rounds-context=Review72Test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class Review72Test extends AbstractSma20SameInputFixture {
 @MockBean UpbitWebSocketClient ws;
 @org.springframework.beans.factory.annotation.Autowired PaperTradingController controller;
 protected Map<String,Object> paperParams(){return Map.of(PaperMaxInvestment.KEY,20000,PaperExecutionMode.KEY,PaperExecutionMode.LIVE_ALIGNED);}
 protected String reportName(){return "review72";}
 private PaperPositionEntity pos(VirtualBalanceEntity p){return paperPos(p,new BigDecimal("100"),new BigDecimal("95"),new BigDecimal("110"),Instant.now().minus(2,ChronoUnit.HOURS));}
 @Test void pendingStopNotificationFailureEscapesAfterCommittedStop(){
   var s=paper(BTC);s.setTelegramEnabled(true);balanceRepo.save(s);var p=pos(s);markPriceBook.remove(BTC);
   doThrow(new IllegalStateException("LOCAL stop notification failure")).when(telegramService).notifyPaperSessionStoppedPendingClose(eq(s.getId()),any(),any(),eq(1L));
   assertThatThrownBy(()->paperTradingService.stop(s.getId())).isInstanceOf(IllegalStateException.class).hasMessage("LOCAL stop notification failure");
   var stored=balanceRepo.findById(s.getId()).orElseThrow();var pp=paperPositionRepo.findById(p.getId()).orElseThrow();
   System.out.println("[S1] stop() throws; DB_session="+stored.getStatus()+" position="+pp.getStatus());
   assertThat(stored.getStatus()).isEqualTo("STOPPED");assertThat(pp.getStatus()).isEqualTo("CLOSING");
 }
 @Test void bulkStopNotificationFailureOmitsCommittedPendingSession(){
   var s=paper(BTC);s.setTelegramEnabled(true);balanceRepo.save(s);var p=pos(s);markPriceBook.remove(BTC);
   doThrow(new IllegalStateException("LOCAL stop notification failure")).when(telegramService).notifyPaperSessionStoppedPendingClose(eq(s.getId()),any(),any(),eq(1L));
   var result=controller.stopAll().getData();
   System.out.println("[S2] stopAll_response="+result+" DB_session="+balanceRepo.findById(s.getId()).orElseThrow().getStatus()+" pending="+paperTradingService.pendingCloseCount(s.getId()));
   assertThat(result.get("stoppedCount")).isEqualTo(0);assertThat(result.get("pendingCloseCount")).isEqualTo(0L);assertThat(result.get("settlement")).isEqualTo("완료");assertThat(balanceRepo.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("STOPPED");assertThat(paperTradingService.pendingCloseCount(s.getId())).isEqualTo(1);
 }
 @Test void twoStatusQueriesCanReturnSameHoldingTwice(){
   var target=AopTestUtils.<PaperTradingService>getUltimateTargetObject(paperTradingService);
   var repo=mock(PaperPositionRepository.class);var old=ReflectionTestUtils.getField(target,"positionRepo");
   var first=PaperPositionEntity.builder().id(101L).sessionId(10L).status("OPEN").build();
   var second=PaperPositionEntity.builder().id(101L).sessionId(10L).status("CLOSING").build();
   when(repo.findBySessionIdAndStatus(10L,"OPEN")).thenReturn(List.of(first));
   when(repo.findBySessionIdAndStatus(10L,"CLOSING")).thenReturn(List.of(second));
   // Simulated repository snapshots around a committed OPEN -> CLOSING transition; not a PG concurrency test.
   ReflectionTestUtils.setField(target,"positionRepo",repo);
   try {var out=target.getOpenPositions(10L);System.out.println("[S3] mock interleaving returned="+out.size()+" distinct_ids="+out.stream().map(PaperPositionEntity::getId).distinct().count());assertThat(out).hasSize(2);assertThat(out.stream().map(PaperPositionEntity::getId).distinct().count()).isEqualTo(1);}
   finally {ReflectionTestUtils.setField(target,"positionRepo",old);}
 }
 @Test void balanceEndpointStillReportsRecordedValuationWithoutPendingStatus(){
   var s=paper(BTC);pos(s);markPriceBook.remove(BTC);paperTradingService.stop(s.getId());
   var body=controller.getSessionBalance(s.getId()).getData();
   System.out.println("[S4] balance_asset="+body.get("totalAssetKrw")+" return="+body.get("totalReturnPct")+" valuation_status="+body.get("valuationStatus")+" pending_field="+body.get("pendingCloseCount")+" DB_pending="+paperTradingService.pendingCloseCount(s.getId()));
   assertThat(paperTradingService.pendingCloseCount(s.getId())).isEqualTo(1);assertThat(body.get("totalAssetKrw")).isNotNull();assertThat(body.get("totalReturnPct")).isNotNull();assertThat(body).doesNotContainKeys("valuationStatus","pendingCloseCount");
 }
}
