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

/** Reviewer witness only: assertions describe candidate defects, not acceptance criteria. */
@org.springframework.test.context.TestPropertySource(properties="test.fixture.entry-rounds-context=AlignedReview949Test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AlignedReview949Test extends AbstractSma20SameInputFixture {
    @MockBean UpbitWebSocketClient ws;
    @MockBean MarkPriceBook mockBook;
    protected Map<String,Object> paperParams() { return Map.of(PaperMaxInvestment.KEY,20000,PaperExecutionMode.KEY,PaperExecutionMode.LIVE_ALIGNED); }
    protected String reportName() { return "review-949"; }
    private PaperTradingService target() { return AopTestUtils.getUltimateTargetObject(paperTradingService); }
    @AfterEach void resetReview() { PaperTradingService.alignedRaceHook=n->{}; target().alignedNowMs=System::currentTimeMillis; }
    private VirtualBalanceEntity timed() {
        VirtualBalanceEntity p=paper(BTC); p.setMaxHoldHours(1); return balanceRepo.save(p);
    }
    private PaperPositionEntity pos(VirtualBalanceEntity p) {
        return paperPos(p,new BigDecimal("100"),new BigDecimal("95"),new BigDecimal("110"),Instant.now().minus(2,ChronoUnit.HOURS));
    }
    private long sells(Long sid) { return paperOrderRepo.findBySessionIdOrderByCreatedAtDesc(sid,org.springframework.data.domain.Pageable.unpaged()).stream().filter(x->"SELL".equals(x.getSide())).count(); }
    @SuppressWarnings("unchecked") private Map<Long,Instant> pending() {return (Map<Long,Instant>)ReflectionTestUtils.getField(target(),"alignedPendingTimeExit");}

    @Test void r1ExpiryAfterCheckStillBuys() {
        insertCache(BTC,uptrend(lastOpen));
        VirtualBalanceEntity p=paper(BTC);
        runPaper(p);
        Instant expiry=EntryPriorityRounds.expiry(new EntryPriorityRounds.RoundKey("H1",lastOpen));
        PaperTradingService.alignedRaceHook=n->{if("APPROVE_BEFORE_BUY".equals(n)) entryClock.setForTest(Clock.fixed(expiry.plusSeconds(1),ZoneOffset.UTC));};
        var outcomes=approveRound();
        var after=balanceRepo.findById(p.getId()).orElseThrow();
        System.out.println("[R1] expiredBeforeBuy="+!entryClock.now().isBefore(expiry)+" positions="+paperPositions(p).size()+" available="+after.getAvailableKrw()+" outcomes="+outcomes.stream().map(EntryPriorityRounds.Outcome::reason).toList());
        assertThat(entryClock.now()).isAfter(expiry);
        assertThat(paperPositions(p)).hasSize(1);
        assertThat(after.getAvailableKrw()).isEqualByComparingTo("200");
    }
    @Test void r2StopWithoutAnyPriceClosesAtZero() {
        VirtualBalanceEntity p=paper(BTC); PaperPositionEntity pp=pos(p);
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ?",BTC);
        var stopped=paperTradingService.stop(p.getId());
        var after=paperPositionRepo.findById(pp.getId()).orElseThrow();
        System.out.println("[R2] status="+stopped.getStatus()+" position="+after.getStatus()+" available="+stopped.getAvailableKrw()+" pnl="+after.getRealizedPnl()+" sells="+sells(p.getId()));
        assertThat(after.getStatus()).isEqualTo("CLOSED");
        assertThat(stopped.getAvailableKrw()).isEqualByComparingTo("200");
        assertThat(after.getRealizedPnl()).isEqualByComparingTo("-20000");
    }
    @Test void r3PendingTimeExitBecomesTakeProfitOnPeriodicPrice() {
        VirtualBalanceEntity p=timed(); PaperPositionEntity pp=pos(p);
        ReflectionTestUtils.invokeMethod(target(),"alignedExitsWithoutCandles",p,BTC);
        assertThat(pending()).containsKey(pp.getId());
        ReflectionTestUtils.invokeMethod(target(),"alignedPriceExits",p,pp,new BigDecimal("120"),"review periodic price");
        var after=paperPositionRepo.findById(pp.getId()).orElseThrow();
        System.out.println("[R3] pending TIME_STOP -> periodic price 120 -> exitReason="+after.getExitReason()+" sells="+sells(p.getId()));
        assertThat(after.getExitReason()).isEqualTo(ExitReason.TAKE_PROFIT);
    }
    @Test void r4FirstValidPriceSkippedByThrottleAfterPending() {
        VirtualBalanceEntity p=timed(); PaperPositionEntity pp=pos(p);
        ReflectionTestUtils.invokeMethod(target(),"refreshAlignedCoins",balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING"));
        for(String f:List.of("alignedPriceHistory","alignedSpikeLastCheckMs","alignedRtLastCheckMs")) ((Map<?,?>)ReflectionTestUtils.getField(target(),f)).clear();
        target().alignedNowMs=()->10000L;
        target().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC,new BigDecimal("100"),Instant.now(),RealtimePriceEvent.WS_TRADE));
        ReflectionTestUtils.invokeMethod(target(),"alignedExitsWithoutCandles",p,BTC);
        assertThat(pending()).containsKey(pp.getId());
        target().alignedNowMs=()->10100L;
        target().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC,new BigDecimal("100"),Instant.now(),RealtimePriceEvent.WS_TRADE));
        var first=paperPositionRepo.findById(pp.getId()).orElseThrow();
        System.out.println("[R4] first valid at +100ms status="+first.getStatus()+" sells="+sells(p.getId())+" pending="+pending().containsKey(pp.getId()));
        assertThat(first.getStatus()).isEqualTo("OPEN"); assertThat(sells(p.getId())).isZero();
        target().alignedNowMs=()->15000L;
        target().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC,new BigDecimal("100"),Instant.now(),RealtimePriceEvent.WS_TRADE));
        var after=paperPositionRepo.findById(pp.getId()).orElseThrow();
        System.out.println("[R4-control] +5000ms status="+after.getStatus()+" reason="+after.getExitReason());
        assertThat(after.getExitReason()).isEqualTo(ExitReason.TIME_STOP);
    }
}
