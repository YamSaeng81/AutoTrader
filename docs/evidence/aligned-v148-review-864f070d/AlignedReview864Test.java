package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.paper.*;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Reviewer witness: assertions describe observed gaps, not acceptance. Fixed 864f070d product sources. */
@org.springframework.test.context.TestPropertySource(properties="test.fixture.entry-rounds-context=AlignedReview864Test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AlignedReview864Test extends AbstractSma20SameInputFixture {
    @MockBean UpbitWebSocketClient ws;
    @MockBean MarkPriceBook mockBook;
    protected Map<String,Object> paperParams() { return Map.of(PaperMaxInvestment.KEY,20000,PaperExecutionMode.KEY,PaperExecutionMode.LIVE_ALIGNED); }
    protected String reportName() { return "review-864"; }
    private PaperTradingService target() { return AopTestUtils.getUltimateTargetObject(paperTradingService); }
    @AfterEach void resetReview() { PaperTradingService.alignedRaceHook=n->{}; }
    private VirtualBalanceEntity timed() { var p=paper(BTC); p.setMaxHoldHours(1); return balanceRepo.save(p); }
    private PaperPositionEntity pos(VirtualBalanceEntity p) { return paperPos(p,new BigDecimal("100"),new BigDecimal("95"),new BigDecimal("110"),Instant.now().minus(2,ChronoUnit.HOURS)); }
    private List<PaperOrderEntity> sells(Long sid) { return paperOrderRepo.findBySessionIdOrderByCreatedAtDesc(sid,org.springframework.data.domain.Pageable.unpaged()).stream().filter(x->"SELL".equals(x.getSide())).toList(); }
    private PaperPositionEntity reload(PaperPositionEntity p) { return paperPositionRepo.findById(p.getId()).orElseThrow(); }

    @Test void w1PendingCanSettleAtPreDecisionCandleDespiteNewFreshQuote() {
        var p=timed(); var pp=pos(p);
        ReflectionTestUtils.invokeMethod(target(),"alignedExitsWithoutCandles",p,BTC);
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSING");
        var cs=uptrend(lastOpen); insertCache(BTC,cs);
        var oldClose=lastClose(cs); var freshPrice=oldClose.multiply(new BigDecimal("1.1"));
        when(mockBook.freshPrice(eq(BTC),any())).thenReturn(freshPrice);
        paperTradingService.runStrategy();
        var order=sells(p.getId()).get(0);
        System.out.println("[W1] candle_end="+lastOpen.plus(1,ChronoUnit.HOURS)+" fresh_quote="+freshPrice+" old_close="+oldClose+" fill="+order.getPrice()+" reason="+order.getSignalReason());
        assertThat(order.getPrice()).isEqualByComparingTo(oldClose.multiply(new BigDecimal("0.999")));
        assertThat(order.getPrice()).isNotEqualByComparingTo(freshPrice.multiply(new BigDecimal("0.999")));
    }
    @Test void w2SingleDeleteErasesUnsettledPosition() {
        var p=paper(BTC); var pp=pos(p); paperTradingService.stop(p.getId());
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSING");
        paperTradingService.deleteSession(p.getId());
        System.out.println("[W2] single_delete session_exists="+balanceRepo.existsById(p.getId())+" position_exists="+paperPositionRepo.existsById(pp.getId())+" sells="+sells(p.getId()).size());
        assertThat(balanceRepo.existsById(p.getId())).isFalse(); assertThat(paperPositionRepo.existsById(pp.getId())).isFalse(); assertThat(sells(p.getId())).isEmpty();
    }
    @Test void w3BulkDeleteErasesUnsettledPosition() {
        var p=paper(BTC); var pp=pos(p); paperTradingService.stop(p.getId());
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSING");
        paperTradingService.bulkDeleteSessions(List.of(p.getId()));
        System.out.println("[W3] bulk_delete session_exists="+balanceRepo.existsById(p.getId())+" position_exists="+paperPositionRepo.existsById(pp.getId())+" sells="+sells(p.getId()).size());
        assertThat(balanceRepo.existsById(p.getId())).isFalse(); assertThat(paperPositionRepo.existsById(pp.getId())).isFalse(); assertThat(sells(p.getId())).isEmpty();
    }
    @Test void w4PendingDisappearsFromOpenApiAndUnrealizedSummary() {
        var p=paper(BTC); var pp=pos(p); pp.setUnrealizedPnl(new BigDecimal("500")); paperPositionRepo.save(pp);
        var before=paperTradingService.getOverallPerformance().getSessions().stream().filter(x->x.getSessionId().equals(p.getId())).findFirst().orElseThrow();
        paperTradingService.stop(p.getId());
        var after=paperTradingService.getOverallPerformance().getSessions().stream().filter(x->x.getSessionId().equals(p.getId())).findFirst().orElseThrow();
        System.out.println("[W4] DB_status="+reload(pp).getStatus()+" open_api="+paperTradingService.getOpenPositions(p.getId()).size()+" summary_unrealized_before="+before.getUnrealizedPnl()+" after="+after.getUnrealizedPnl()+" summary_trades="+after.getTotalTrades());
        assertThat(paperTradingService.getOpenPositions(p.getId())).isEmpty(); assertThat(before.getUnrealizedPnl()).isEqualByComparingTo("500"); assertThat(after.getUnrealizedPnl()).isZero(); assertThat(after.getTotalTrades()).isZero();
    }
    @Test void w5AfterCommitNotificationFailureReportsFailedApprovalWithCommittedBuy() {
        insertCache(BTC,uptrend(lastOpen)); var p=paper(BTC); p.setTelegramEnabled(true); balanceRepo.save(p);
        doThrow(new IllegalStateException("review notification failure after commit")).when(telegramService).bufferTradeEvent(any(),any(),eq("BUY"),any(),any(),any(),any(),any());
        runPaper(p); var outcomes=approveRound();
        var reason=outcomes.stream().filter(o->"PAPER".equals(o.candidate().kind())).findFirst().orElseThrow().reason();
        var b=balanceRepo.findById(p.getId()).orElseThrow();
        System.out.println("[W5] positions="+paperPositions(p).size()+" available="+b.getAvailableKrw()+" outcome="+reason);
        assertThat(paperPositions(p)).hasSize(1); assertThat(b.getAvailableKrw()).isEqualByComparingTo("200"); assertThat(reason).isNotNull();
    }
    @Test void w6UnrealizedMddPeakStillLostAfterMemoryReset() {
        var p=paper(BTC); p.setTotalKrw(new BigDecimal("30000")); p.setAvailableKrw(new BigDecimal("30000")); p=balanceRepo.save(p);
        var warm=(CircuitBreakerResult)ReflectionTestUtils.invokeMethod(target(),"alignedCircuitBreaker",p);
        p.setTotalKrw(new BigDecimal("25000")); p.setAvailableKrw(new BigDecimal("25000")); p=balanceRepo.save(p);
        var before=(CircuitBreakerResult)ReflectionTestUtils.invokeMethod(target(),"alignedCircuitBreaker",p);
        ((Map<?,?>)ReflectionTestUtils.getField(target(),"alignedPeakTotalKrw")).clear();
        var after=(CircuitBreakerResult)ReflectionTestUtils.invokeMethod(target(),"alignedCircuitBreaker",p);
        System.out.println("[W6] peak=30000 current=25000 before_reset="+before.isTriggered()+" after_reset="+after.isTriggered());
        assertThat(warm.isTriggered()).isFalse(); assertThat(before.isTriggered()).isTrue(); assertThat(after.isTriggered()).isFalse();
    }
}
