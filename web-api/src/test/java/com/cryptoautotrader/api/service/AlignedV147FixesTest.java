package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 총괄 v1.147 재검토 경계의 보완 확인 (2026-11-11). R1~R4 는 총괄 관측 시험({@code AlignedReview949Test})과 같은 입력·같은 호출부에
 * <b>바라는 동작</b>을 단언한다. 시세 장부는 진짜 빈이고 시험이 값을 넣거나 지운다. sleep·반복 없음.
 */
@DisplayName("LIVE_ALIGNED v1.147 보완 — 쓰기 직전 만료·청산 대기(0원 금지·사유 보존·첫 유효 시세)·위험 입력·원자성·커밋 뒤 보고")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=AlignedV147FixesTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AlignedV147FixesTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient ws;

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "v147-fixes";
    }

    private PaperTradingService target() {
        return AopTestUtils.getUltimateTargetObject(paperTradingService);
    }

    @AfterEach
    void resetHooks() {
        PaperTradingService.alignedRaceHook = n -> { };
        target().alignedNowMs = System::currentTimeMillis;
        markPriceBook.remove(BTC);
    }

    private VirtualBalanceEntity timed() {
        VirtualBalanceEntity p = paper(BTC);
        p.setMaxHoldHours(1);
        return balanceRepo.save(p);
    }

    private PaperPositionEntity pos(VirtualBalanceEntity p) {
        return paperPos(p, new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"), Instant.now().minus(2, ChronoUnit.HOURS));
    }

    private List<PaperOrderEntity> sells(Long sid) {
        return paperOrderRepo.findBySessionIdOrderByCreatedAtDesc(sid, org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream().filter(x -> "SELL".equals(x.getSide())).toList();
    }

    private PaperPositionEntity reload(PaperPositionEntity p) {
        return paperPositionRepo.findById(p.getId()).orElseThrow();
    }

    private void rt(BigDecimal price) {
        target().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, price, Instant.now(), RealtimePriceEvent.WS_TRADE));
    }

    @Test
    @DisplayName("R1 — E 검사 뒤 시계가 E+1초로 가도 쓰기 직전 재검사에서 만료: 포지션·차감·주문 0, 사유는 만료")
    void r1ExpiryAfterCheckDoesNotBuy() {
        insertCache(BTC, uptrend(lastOpen));
        VirtualBalanceEntity p = paper(BTC);
        runPaper(p);
        Instant expiry = EntryPriorityRounds.expiry(new EntryPriorityRounds.RoundKey("H1", lastOpen));
        PaperTradingService.alignedRaceHook = n -> {
            if ("APPROVE_BEFORE_BUY".equals(n)) entryClock.setForTest(Clock.fixed(expiry.plusSeconds(1), ZoneOffset.UTC));
        };
        List<EntryPriorityRounds.Outcome> out = approveRound();
        VirtualBalanceEntity after = balanceRepo.findById(p.getId()).orElseThrow();
        String reason = out.stream().filter(o -> "PAPER".equals(o.candidate().kind())).findFirst().map(EntryPriorityRounds.Outcome::reason).orElse("후보 없음");
        System.out.println("[R1 보완] 사유=" + reason + " 포지션=" + paperPositions(p).size() + " 가용=" + after.getAvailableKrw());
        assertThat(reason).startsWith("승인 실행 만료(쓰기 직전 판정");
        assertThat(paperPositions(p)).isEmpty();
        assertThat(paperOrderRepo.findBySessionIdOrderByCreatedAtDesc(p.getId(), org.springframework.data.domain.Pageable.unpaged()).getContent()).isEmpty();
        assertThat(after.getAvailableKrw()).isEqualByComparingTo("20200");
    }

    @Test
    @DisplayName("R1b — 마지막 지연 가능 조회(지문) 뒤 BUY_BEFORE_WRITE 에서 만료돼도 같다")
    void r1bExpiryAtWriteBoundary() {
        insertCache(BTC, uptrend(lastOpen));
        VirtualBalanceEntity p = paper(BTC);
        runPaper(p);
        Instant expiry = EntryPriorityRounds.expiry(new EntryPriorityRounds.RoundKey("H1", lastOpen));
        PaperTradingService.alignedRaceHook = n -> {
            if ("BUY_BEFORE_WRITE".equals(n)) entryClock.setForTest(Clock.fixed(expiry, ZoneOffset.UTC));   // 경계 E 자체도 만료
        };
        List<EntryPriorityRounds.Outcome> out = approveRound();
        assertThat(out.stream().filter(o -> "PAPER".equals(o.candidate().kind())).findFirst().orElseThrow().reason()).startsWith("승인 실행 만료(쓰기 직전 판정");
        assertThat(paperPositions(p)).isEmpty();
        assertThat(balanceRepo.findById(p.getId()).orElseThrow().getAvailableKrw()).isEqualByComparingTo("20200");
    }

    @Test
    @DisplayName("R2 — 가격이 하나도 없을 때 정지: 0원 정산 없음, 청산 대기(FORCED_STOP)·정지 응답 대기 1 → 첫 유효 시세에 한 번 정산")
    void r2StopWithoutPriceWaitsAndSettlesOnce() {
        VirtualBalanceEntity p = paper(BTC);
        PaperPositionEntity pp = pos(p);
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ?", BTC);
        markPriceBook.remove(BTC);
        VirtualBalanceEntity stopped = paperTradingService.stop(p.getId());
        PaperPositionEntity waiting = reload(pp);
        System.out.println("[R2 보완] 정지 뒤 세션=" + stopped.getStatus() + " 포지션=" + waiting.getStatus() + "/" + waiting.getExitReason()
                + " 매도=" + sells(p.getId()).size() + " 가용=" + stopped.getAvailableKrw() + " 대기=" + paperTradingService.pendingCloseCount(p.getId()));
        assertThat(stopped.getStatus()).isEqualTo("STOPPED");
        assertThat(waiting.getStatus()).isEqualTo("CLOSING");
        assertThat(waiting.getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
        // 1회차는 isNull 로 적어 실패했다 — fixture 포지션의 실현 손익 초기값은 0 이다(시험 기대 결함). 총괄 관측의 결함은 −20,000 이었다.
        assertThat(waiting.getRealizedPnl()).isEqualByComparingTo("0");
        assertThat(sells(p.getId())).isEmpty();
        assertThat(paperTradingService.pendingCloseCount(p.getId())).isEqualTo(1);

        rt(new BigDecimal("0"));          // 가격 아님 — 무시
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSING");
        rt(new BigDecimal("101"));
        rt(new BigDecimal("150"));        // 두 번째 시세 — 다시 정산하지 않는다
        PaperPositionEntity settled = reload(pp);
        VirtualBalanceEntity b = balanceRepo.findById(p.getId()).orElseThrow();
        BigDecimal fill = new BigDecimal("101").multiply(new BigDecimal("0.999")).setScale(8, RoundingMode.HALF_DOWN);
        BigDecimal proceeds = settled.getSize().multiply(fill);
        System.out.println("[R2 보완] 정산 " + settled.getStatus() + "/" + settled.getExitReason() + " 매도=" + sells(p.getId()).size() + " 가용=" + b.getAvailableKrw()
                + " 사유=" + sells(p.getId()).get(0).getSignalReason());
        assertThat(settled.getStatus()).isEqualTo("CLOSED");
        assertThat(settled.getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
        assertThat(sells(p.getId())).hasSize(1);
        assertThat(sells(p.getId()).get(0).getPrice()).isEqualByComparingTo(fill);
        assertThat(sells(p.getId()).get(0).getSignalReason()).contains("청산 판단 ").contains("체결가 시각 ");
        assertThat(b.getAvailableKrw().setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("200").add(proceeds.subtract(proceeds.multiply(new BigDecimal("0.0005")))).setScale(2, RoundingMode.HALF_UP));
        assertThat(paperTradingService.pendingCloseCount(p.getId())).isZero();
    }

    @Test
    @DisplayName("R3 — 가격 없는 회차의 TIME_STOP 대기 뒤 익절선 위 가격으로 주기 청산을 불러도 사유는 TIME_STOP")
    void r3PendingTimeExitKeepsReason() {
        VirtualBalanceEntity p = timed();
        PaperPositionEntity pp = pos(p);
        markPriceBook.remove(BTC);
        ReflectionTestUtils.invokeMethod(target(), "alignedExitsWithoutCandles", p, BTC);
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSING");
        ReflectionTestUtils.invokeMethod(target(), "alignedPriceExits", p, pp, new BigDecimal("120"), "review periodic price");
        PaperPositionEntity after = reload(pp);
        System.out.println("[R3 보완] " + after.getStatus() + "/" + after.getExitReason() + " 매도=" + sells(p.getId()).size());
        assertThat(after.getExitReason()).isEqualTo(ExitReason.TIME_STOP);
        assertThat(after.getStatus()).isEqualTo("CLOSED");
        assertThat(sells(p.getId())).hasSize(1);
    }

    @Test
    @DisplayName("R4 — 정상 시세 확인 직후(100ms) 대기가 생겨도 첫 유효 현재가에 TIME_STOP 정산(간격 검사 앞)")
    void r4FirstValidPriceSettlesDespiteThrottle() {
        VirtualBalanceEntity p = timed();
        PaperPositionEntity pp = pos(p);
        markPriceBook.remove(BTC);
        ReflectionTestUtils.invokeMethod(target(), "refreshAlignedCoins", balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING"));
        for (String f : List.of("alignedPriceHistory", "alignedSpikeLastCheckMs", "alignedRtLastCheckMs")) {
            ((Map<?, ?>) ReflectionTestUtils.getField(target(), f)).clear();
        }
        target().alignedNowMs = () -> 10_000L;
        rt(new BigDecimal("100"));
        ReflectionTestUtils.invokeMethod(target(), "alignedExitsWithoutCandles", p, BTC);
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSING");
        target().alignedNowMs = () -> 10_100L;
        rt(new BigDecimal("100"));
        PaperPositionEntity after = reload(pp);
        System.out.println("[R4 보완] +100ms " + after.getStatus() + "/" + after.getExitReason() + " 매도=" + sells(p.getId()).size());
        assertThat(after.getStatus()).isEqualTo("CLOSED");
        assertThat(after.getExitReason()).isEqualTo(ExitReason.TIME_STOP);
        assertThat(sells(p.getId())).hasSize(1);
    }

    @Test
    @DisplayName("재기동 복원 — 메모리 표시를 지운 뒤 복원하면 DB 의 청산 대기를 첫 유효 시세에 정산(판단 시각 미상)")
    void restoreAfterRestart() {
        VirtualBalanceEntity p = paper(BTC);
        PaperPositionEntity pp = pos(p);
        markPriceBook.remove(BTC);
        paperTradingService.stop(p.getId());
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSING");
        ((Map<?, ?>) ReflectionTestUtils.getField(target(), "alignedClosingDecidedAt")).clear();
        ((Map<?, ?>) ReflectionTestUtils.getField(target(), "alignedClosingByCoin")).clear();
        rt(new BigDecimal("101"));
        assertThat(reload(pp).getStatus()).as("복원 전에는 대기 코인을 모른다").isEqualTo("CLOSING");
        target().restoreAlignedClosing();
        rt(new BigDecimal("101"));
        PaperPositionEntity after = reload(pp);
        assertThat(after.getStatus()).isEqualTo("CLOSED");
        assertThat(after.getExitReason()).isEqualTo(ExitReason.FORCED_STOP);
        assertThat(sells(p.getId()).get(0).getSignalReason()).contains("청산 판단 미상(재기동 전)");
    }

    @Test
    @DisplayName("주기 정산 — 실시간 시세가 없어도 시세 장부에 유효 가격이 들어오면 runStrategy 끝에서 정산(정지된 세션)")
    void periodicSettlementFromMarkBook() {
        VirtualBalanceEntity p = paper(BTC);
        PaperPositionEntity pp = pos(p);
        markPriceBook.remove(BTC);
        paperTradingService.stop(p.getId());
        paperTradingService.runStrategy();
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSING");
        markPriceBook.record(BTC, new BigDecimal("102"), Instant.now());
        paperTradingService.runStrategy();
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSED");
        assertThat(sells(p.getId())).hasSize(1);
    }

    @Test
    @DisplayName("청산 대기 중 승인 — 새 진입 없음")
    void noEntryWhileClosing() {
        insertCache(BTC, uptrend(lastOpen));
        VirtualBalanceEntity p = timed();
        runPaper(p);   // 회차 접수
        PaperPositionEntity pp = pos(p);
        pp.setStatus("CLOSING");
        pp.setExitReason(ExitReason.TIME_STOP);
        paperPositionRepo.save(pp);
        List<EntryPriorityRounds.Outcome> out = approveRound();
        assertThat(out.stream().filter(o -> "PAPER".equals(o.candidate().kind())).findFirst().orElseThrow().reason())
                .isEqualTo("승인 차례에 포지션 청산 대기 중");
        assertThat(paperPositions(p)).hasSize(1);
    }

    @Test
    @DisplayName("R5 — 같은 현금흐름(투입 20,000·매수 수수료 10·매도 순입금 19,995) 5회: LIVE 관례 위험 입력 +5 → 서킷 브레이커 아님")
    void r5RiskInputUsesLiveConvention() {
        VirtualBalanceEntity p = paper(BTC);
        BigDecimal fill = new BigDecimal("10000000");
        BigDecimal size = new BigDecimal("0.00199900");   // 19,990 / 10,000,000
        for (int i = 0; i < 5; i++) {
            Instant closed = Instant.now().minus(10 - i, ChronoUnit.MINUTES);
            paperPositionRepo.save(PaperPositionEntity.builder().sessionKind("PAPER").sessionId(p.getId()).coinPair(BTC).side("BUY")
                    .entryPrice(fill).avgPrice(fill).size(size).investedKrw(new BigDecimal("20000")).positionFee(new BigDecimal("20"))
                    .realizedPnl(new BigDecimal("-5")).status("CLOSED").openedAt(closed.minus(1, ChronoUnit.HOURS)).closedAt(closed).build());
        }
        PaperPositionEntity sample = paperPositions(p).get(0);
        assertThat(PaperTradingService.riskPnl(sample)).as("LIVE 정의 손익 = −5 + (20,000 − 19,990)").isEqualByComparingTo("5");
        CircuitBreakerResult cb = ReflectionTestUtils.invokeMethod(target(), "alignedCircuitBreaker", balanceRepo.findById(p.getId()).orElseThrow());
        System.out.println("[R5 보완] 위험 입력 +5 ×5 → 발동=" + cb.isTriggered() + " (성적표 realizedPnl 은 −5 그대로)");
        assertThat(cb.isTriggered()).isFalse();
        assertThat(sample.getRealizedPnl()).isEqualByComparingTo("-5");

        // 실제 손실(LIVE 정의로도 −) 5회면 둘 다 발동
        VirtualBalanceEntity q = paper(BTC);
        for (int i = 0; i < 5; i++) {
            Instant closed = Instant.now().minus(10 - i, ChronoUnit.MINUTES);
            paperPositionRepo.save(PaperPositionEntity.builder().sessionKind("PAPER").sessionId(q.getId()).coinPair(BTC).side("BUY")
                    .entryPrice(fill).avgPrice(fill).size(size).investedKrw(new BigDecimal("20000")).positionFee(new BigDecimal("20"))
                    .realizedPnl(new BigDecimal("-30")).status("CLOSED").openedAt(closed.minus(1, ChronoUnit.HOURS)).closedAt(closed).build());
        }
        CircuitBreakerResult cb2 = ReflectionTestUtils.invokeMethod(target(), "alignedCircuitBreaker", balanceRepo.findById(q.getId()).orElseThrow());
        assertThat(cb2.isTriggered()).isTrue();
    }

    @Test
    @DisplayName("MDD 피크 복원 — 메모리 피크가 없을 때 청산 이력의 실현 자산 고점에서 다시 본다")
    void mddPeakRestoredFromRealizedEquity() {
        VirtualBalanceEntity p = paper(BTC);
        Instant t = Instant.now().minus(3, ChronoUnit.HOURS);
        paperPositionRepo.save(PaperPositionEntity.builder().sessionKind("PAPER").sessionId(p.getId()).coinPair(BTC).side("BUY")
                .entryPrice(new BigDecimal("100")).avgPrice(new BigDecimal("100")).size(BigDecimal.ONE).investedKrw(new BigDecimal("100"))
                .positionFee(BigDecimal.ZERO).realizedPnl(new BigDecimal("4000")).status("CLOSED").openedAt(t).closedAt(t.plusSeconds(60)).build());
        VirtualBalanceEntity b = balanceRepo.findById(p.getId()).orElseThrow();
        b.setTotalKrw(new BigDecimal("21600"));   // 피크 24,200 대비 10.7% 하락 — MDD 10% 한도
        b.setAvailableKrw(new BigDecimal("21600"));
        balanceRepo.save(b);
        ((Map<?, ?>) ReflectionTestUtils.getField(target(), "alignedPeakTotalKrw")).clear();
        CircuitBreakerResult cb = ReflectionTestUtils.invokeMethod(target(), "alignedCircuitBreaker", balanceRepo.findById(p.getId()).orElseThrow());
        System.out.println("[MDD 피크 복원] " + cb.isTriggered() + " " + cb.getReason());
        assertThat(cb.isTriggered()).isTrue();
        assertThat(cb.getReason()).contains("MDD");
    }

    @Test
    @DisplayName("원자성 — 회차 밖 선택 모드 BUY 의 포지션 저장 뒤 실패: 포지션·주문·차감 0")
    void atomicNonRoundBuy() {
        insertCache(BTC, uptrend(lastOpen));
        rounds.setForcedForTest(false);
        try {
            VirtualBalanceEntity p = paper(BTC);
            PaperTradingService.alignedRaceHook = n -> {
                if ("WRITE_AFTER_POSITION".equals(n)) throw new IllegalStateException("시험 — 포지션 저장 뒤 실패");
            };
            runPaper(p);   // 운영 스케줄러와 같이 예외는 그 세션 실패로 끝난다
        } catch (Exception expected) {
            // ReflectionTestUtils 가 감싼 예외 — 아래에서 상태만 본다
        } finally {
            rounds.setForcedForTest(true);
        }
        VirtualBalanceEntity p = balanceRepo.findById(paperIds.get(paperIds.size() - 1)).orElseThrow();
        System.out.println("[원자성 BUY] 포지션=" + paperPositions(p).size() + " 가용=" + p.getAvailableKrw());
        assertThat(paperPositions(p)).isEmpty();
        assertThat(paperOrderRepo.findBySessionIdOrderByCreatedAtDesc(p.getId(), org.springframework.data.domain.Pageable.unpaged()).getContent()).isEmpty();
        assertThat(p.getAvailableKrw()).isEqualByComparingTo("20200");
    }

    @Test
    @DisplayName("원자성 — 선택 모드 전략 SELL 의 포지션 저장 뒤 실패: 포지션 OPEN 그대로·매도 0·잔액 그대로, 알림 0")
    void atomicStrategySellAndNoReportOnRollback() {
        List<com.cryptoautotrader.strategy.Candle> cs = downtrend(lastOpen);
        insertCache(BTC, cs);
        BigDecimal close = lastClose(cs);
        BigDecimal entry = close.multiply(new BigDecimal("0.99")).setScale(0, RoundingMode.HALF_UP);
        VirtualBalanceEntity p = paper(BTC);
        VirtualBalanceEntity tg = balanceRepo.findById(p.getId()).orElseThrow();
        tg.setTelegramEnabled(true);
        balanceRepo.save(tg);
        PaperPositionEntity pp = paperPos(p, entry, entry.multiply(new BigDecimal("0.95")), entry.multiply(new BigDecimal("1.10")),
                Instant.now().minus(20, ChronoUnit.HOURS));
        Mockito.clearInvocations(telegramService);
        PaperTradingService.alignedRaceHook = n -> {
            if ("WRITE_AFTER_POSITION".equals(n)) throw new IllegalStateException("시험 — 매도 포지션 저장 뒤 실패");
        };
        assertThatThrownBy(() -> runPaper(p)).isNotNull();
        PaperPositionEntity after = reload(pp);
        VirtualBalanceEntity b = balanceRepo.findById(p.getId()).orElseThrow();
        System.out.println("[원자성 SELL] " + after.getStatus() + " 매도=" + sells(p.getId()).size() + " 가용=" + b.getAvailableKrw());
        assertThat(after.getStatus()).isEqualTo("OPEN");
        assertThat(sells(p.getId())).isEmpty();
        assertThat(b.getAvailableKrw()).isEqualByComparingTo("200");
        Mockito.verify(telegramService, Mockito.never()).bufferTradeEvent(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        // 실패를 거두면 같은 회차 다시 평가 — 매도 1·알림 1(커밋 뒤 한 번)
        PaperTradingService.alignedRaceHook = n -> { };
        ((Map<?, ?>) ReflectionTestUtils.getField(target(), "lastEvaluatedClosedCandle")).remove(p.getId());
        runPaper(p);
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSED");
        assertThat(sells(p.getId())).hasSize(1);
        Mockito.verify(telegramService, Mockito.times(1)).bufferTradeEvent(Mockito.any(), Mockito.any(), Mockito.eq("SELL"),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }
}
