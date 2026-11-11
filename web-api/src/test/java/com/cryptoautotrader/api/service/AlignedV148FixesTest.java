package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.controller.PaperTradingController;
import com.cryptoautotrader.api.dto.PerformanceSummaryResponse;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 총괄 v1.148 관측 W1~W6 의 보완 확인 (2026-11-11). 총괄 관측 시험({@code AlignedReview864Test})과 같은 입력·호출부에 <b>바라는 동작</b>을
 * 단언한다. 시세 장부는 진짜 빈이고 시험이 값을 넣거나 지운다. sleep·반복 없음. 경합 시험은 훅 안에서 다른 스레드의 완료를 Future 로 기다린다.
 */
@DisplayName("LIVE_ALIGNED v1.148 보완 — 대기 정산 가격·삭제 거절·미정산 보유 노출·커밋 뒤 보고 격리·런타임 상태 열")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=AlignedV148FixesTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AlignedV148FixesTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient ws;
    @Autowired private PaperTradingController controller;

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "v148-fixes";
    }

    private PaperTradingService target() {
        return AopTestUtils.getUltimateTargetObject(paperTradingService);
    }

    @AfterEach
    void resetHooks() {
        PaperTradingService.alignedRaceHook = n -> { };
        markPriceBook.remove(BTC);
        markPriceBook.remove(ETH);
        Mockito.reset(telegramService);
    }

    private VirtualBalanceEntity timed(String coin) {
        VirtualBalanceEntity p = paper(coin);
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

    /** 유효 시세 없이 정지 → CLOSING(FORCED_STOP). */
    private PaperPositionEntity stopIntoPending(VirtualBalanceEntity p) {
        PaperPositionEntity pp = pos(p);
        markPriceBook.remove(p.getCoinPair());
        paperTradingService.stop(p.getId());
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSING");
        return pp;
    }

    private PerformanceSummaryResponse.SessionPerformance perf(Long sid) {
        return paperTradingService.getOverallPerformance().getSessions().stream()
                .filter(x -> x.getSessionId().equals(sid)).findFirst().orElseThrow();
    }

    // ── W1 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("W1 — 대기 중 주기 회차: 신선한 시세가 있으면 그 가격(봉 종가 아님)으로 한 번, 사유 TIME_STOP 유지")
    void w1PeriodicSettlesAtFreshQuoteNotPreDecisionClose() {
        VirtualBalanceEntity p = timed(BTC);
        PaperPositionEntity pp = pos(p);
        ReflectionTestUtils.invokeMethod(target(), "alignedExitsWithoutCandles", p, BTC);   // 캔들·시세 없음 → 대기
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSING");
        assertThat(reload(pp).getExitDecidedAt()).isNotNull();

        List<Candle> cs = uptrend(lastOpen);
        insertCache(BTC, cs);
        BigDecimal oldClose = lastClose(cs);
        BigDecimal fresh = oldClose.multiply(new BigDecimal("1.1"));
        markPriceBook.record(BTC, fresh, Instant.now());
        paperTradingService.runStrategy();

        List<PaperOrderEntity> s = sells(p.getId());
        System.out.println("[W1 보완] 봉 종가=" + oldClose + " 신선 시세=" + fresh + " 체결=" + (s.isEmpty() ? "없음" : s.get(0).getPrice())
                + " 사유=" + reload(pp).getExitReason());
        assertThat(s).hasSize(1);
        assertThat(s.get(0).getPrice()).isEqualByComparingTo(fresh.multiply(new BigDecimal("0.999")));
        assertThat(s.get(0).getPrice()).isNotEqualByComparingTo(oldClose.multiply(new BigDecimal("0.999")));
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSED");
        assertThat(reload(pp).getExitReason()).isEqualTo(ExitReason.TIME_STOP);

        paperTradingService.runStrategy();   // 두 번째 회차 — 추가 매도 없음
        assertThat(sells(p.getId())).hasSize(1);
    }

    @Test
    @DisplayName("W1b — 대기 중 주기 회차: 봉은 신선하지만 시세가 없으면 계속 CLOSING, 매도·정산 0")
    void w1bPeriodicWithFreshCandlesButNoQuoteStaysPending() {
        VirtualBalanceEntity p = timed(BTC);
        PaperPositionEntity pp = pos(p);
        ReflectionTestUtils.invokeMethod(target(), "alignedExitsWithoutCandles", p, BTC);
        insertCache(BTC, uptrend(lastOpen));
        markPriceBook.remove(BTC);
        paperTradingService.runStrategy();
        System.out.println("[W1b 보완] 상태=" + reload(pp).getStatus() + " 매도=" + sells(p.getId()).size()
                + " 가용=" + balanceRepo.findById(p.getId()).orElseThrow().getAvailableKrw());
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSING");
        assertThat(sells(p.getId())).isEmpty();
        assertThat(balanceRepo.findById(p.getId()).orElseThrow().getAvailableKrw()).isEqualByComparingTo("200");
    }

    // ── W2·W3 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("W2 — 청산 대기가 남은 STOPPED 세션 단건 삭제 거절(세션·포지션 보존), 정산 뒤에는 종전대로 삭제")
    void w2SingleDeleteRejectedWhilePending() {
        VirtualBalanceEntity p = paper(BTC);
        PaperPositionEntity pp = stopIntoPending(p);
        assertThatThrownBy(() -> paperTradingService.deleteSession(p.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("청산 대기");
        System.out.println("[W2 보완] 삭제 시도 뒤 세션=" + balanceRepo.existsById(p.getId()) + " 포지션=" + paperPositionRepo.existsById(pp.getId()));
        assertThat(balanceRepo.existsById(p.getId())).isTrue();
        assertThat(paperPositionRepo.existsById(pp.getId())).isTrue();

        target().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, new BigDecimal("101"), Instant.now(), RealtimePriceEvent.WS_TRADE));
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSED");
        paperTradingService.deleteSession(p.getId());
        assertThat(balanceRepo.existsById(p.getId())).isFalse();
    }

    @Test
    @DisplayName("W3 — 일괄 삭제는 청산 대기 세션을 건너뛰고, 다 정산된 정지 세션만 지운다")
    void w3BulkDeleteSkipsPending() {
        VirtualBalanceEntity pending = paper(BTC);
        PaperPositionEntity pp = stopIntoPending(pending);
        VirtualBalanceEntity done = paper(ETH);
        paperTradingService.stop(done.getId());   // 포지션 없음 — 정산 완료 상태
        paperTradingService.bulkDeleteSessions(List.of(pending.getId(), done.getId()));
        System.out.println("[W3 보완] 대기 세션=" + balanceRepo.existsById(pending.getId()) + " 포지션=" + paperPositionRepo.existsById(pp.getId())
                + " 완료 세션=" + balanceRepo.existsById(done.getId()));
        assertThat(balanceRepo.existsById(pending.getId())).isTrue();
        assertThat(paperPositionRepo.existsById(pp.getId())).isTrue();
        assertThat(balanceRepo.existsById(done.getId())).isFalse();
    }

    @Test
    @DisplayName("W2c — 삭제와 정산 경합: 정산 트랜잭션이 커밋 전이면 삭제는 커밋된 CLOSING 을 보고 거절, 정산 커밋 뒤 삭제는 성공")
    void w2cDeleteRacingSettlement() throws Exception {
        VirtualBalanceEntity p = paper(BTC);
        PaperPositionEntity pp = stopIntoPending(p);
        AtomicReference<String> during = new AtomicReference<>("실행 안 됨");
        PaperTradingService.alignedRaceHook = n -> {
            if (("SETTLE_BEFORE_CLOSE#" + pp.getId()).equals(n) || ("WRITE_AFTER_POSITION").equals(n)) {
                if (!"실행 안 됨".equals(during.get())) return;
                try {
                    during.set(CompletableFuture.supplyAsync(() -> {
                        try {
                            paperTradingService.deleteSession(p.getId());
                            return "삭제됨";
                        } catch (IllegalStateException e) {
                            return "거절: " + e.getMessage();
                        }
                    }).get(20, TimeUnit.SECONDS));
                } catch (Exception e) {
                    during.set("오류: " + e.getClass().getSimpleName());
                }
            }
        };
        target().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, new BigDecimal("101"), Instant.now(), RealtimePriceEvent.WS_TRADE));
        PaperTradingService.alignedRaceHook = n -> { };
        System.out.println("[W2c 보완] 정산 중 삭제=" + during.get() + " 정산 뒤 포지션=" + reload(pp).getStatus() + " 매도=" + sells(p.getId()).size());
        assertThat(during.get()).startsWith("거절: 청산 대기");
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSED");
        assertThat(sells(p.getId())).hasSize(1);
        paperTradingService.deleteSession(p.getId());
        assertThat(balanceRepo.existsById(p.getId())).isFalse();
    }

    // ── W4 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("W4 — CLOSING 은 보유 조회에 남고, 성과 요약은 미실현을 0 으로 메우지 않음(평가 불가·대기 수), 완료 거래에 넣지 않음")
    void w4PendingStaysVisibleAsUnsettledHolding() {
        VirtualBalanceEntity p = paper(BTC);
        PaperPositionEntity pp = pos(p);
        pp.setUnrealizedPnl(new BigDecimal("500"));
        paperPositionRepo.save(pp);
        PerformanceSummaryResponse.SessionPerformance before = perf(p.getId());
        markPriceBook.remove(BTC);
        paperTradingService.stop(p.getId());
        PerformanceSummaryResponse.SessionPerformance after = perf(p.getId());
        List<PaperPositionEntity> held = paperTradingService.getOpenPositions(p.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) controller.getPositions(p.getId(), "OPEN").getData().get(0);
        System.out.println("[W4 보완] DB=" + reload(pp).getStatus() + " 보유 조회=" + held.size() + "(" + held.get(0).getStatus() + ")"
                + " 요약 미실현 전=" + before.getUnrealizedPnl() + " 후=" + after.getUnrealizedPnl() + " 대기=" + after.getPendingCloseCount()
                + " 상태=" + after.getValuationStatus() + " 완료 거래=" + after.getTotalTrades() + " 화면=" + view.get("valuationStatus")
                + "/" + view.get("unrealizedPnl") + "/기록값 " + view.get("lastRecordedUnrealizedPnl"));
        assertThat(before.getUnrealizedPnl()).isEqualByComparingTo("500");
        assertThat(before.getPendingCloseCount()).isZero();
        assertThat(held).hasSize(1);
        assertThat(held.get(0).getStatus()).isEqualTo("CLOSING");
        assertThat(after.getUnrealizedPnl()).isNull();
        assertThat(after.getTotalPnl()).isNull();
        assertThat(after.getReturnRatePct()).isNull();
        assertThat(after.getPendingCloseCount()).isEqualTo(1);
        assertThat(after.getValuationStatus()).startsWith("평가 불가");
        assertThat(after.getTotalTrades()).isZero();
        assertThat(paperTradingService.getOverallPerformance().getTotalUnrealizedPnl()).isNull();
        assertThat(view.get("status")).isEqualTo("CLOSING");
        assertThat(view.get("unrealizedPnl")).isNull();
        assertThat((String) view.get("valuationStatus")).startsWith("평가 불가");
        assertThat(view.get("pendingExitReason")).isEqualTo("FORCED_STOP");
        assertThat(view.get("exitDecidedAt")).isNotNull();

        target().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, new BigDecimal("101"), Instant.now(), RealtimePriceEvent.WS_TRADE));
        PerformanceSummaryResponse.SessionPerformance settled = perf(p.getId());
        assertThat(settled.getPendingCloseCount()).isZero();
        assertThat(settled.getTotalTrades()).isEqualTo(1);
        assertThat(settled.getUnrealizedPnl()).isEqualByComparingTo("0");
        assertThat(paperTradingService.getOpenPositions(p.getId())).isEmpty();
    }

    @Test
    @DisplayName("W4b — 정지 응답·정지 알림·일괄 정지가 대기를 반영: 최종 자산 비움, '최종 자산' 알림 대신 청산 대기 알림")
    void w4bStopResponseAndNotificationReflectPending() {
        VirtualBalanceEntity p = paper(BTC);
        p.setTelegramEnabled(true);
        balanceRepo.save(p);
        pos(p);
        markPriceBook.remove(BTC);
        Map<String, Object> body = controller.stop(p.getId()).getData();
        System.out.println("[W4b 보완] 정지 응답=" + body);
        assertThat(body.get("finalAsset")).isNull();
        assertThat(body.get("pendingCloseCount")).isEqualTo(1L);
        assertThat((String) body.get("assetStatus")).startsWith("확정 아님");
        Mockito.verify(telegramService, Mockito.never()).notifyPaperSessionStopped(any(), any(), any(), any(), Mockito.anyDouble());
        Mockito.verify(telegramService, Mockito.times(1)).notifyPaperSessionStoppedPendingClose(eq(p.getId()), any(), eq(BTC), eq(1L));

        VirtualBalanceEntity q = paper(ETH);
        pos(q);
        markPriceBook.remove(ETH);
        Map<String, Object> all = controller.stopAll().getData();
        System.out.println("[W4b 보완] 일괄 정지 응답=" + all);
        assertThat(((Number) all.get("pendingCloseCount")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat((String) all.get("settlement")).startsWith("청산 대기");
    }

    // ── §3-1.5 실패 격리 ───────────────────────────────────────────

    @Test
    @DisplayName("§3-1.5 — 한 포지션의 정산 실패가 다른 세션의 대기 정산을 막지 않음(실패한 쪽은 CLOSING 유지, 다음 시세에 정산)")
    void settleFailureIsIsolatedPerPosition() {
        VirtualBalanceEntity a = paper(BTC);
        PaperPositionEntity pa = stopIntoPending(a);
        VirtualBalanceEntity b = paper(BTC);
        PaperPositionEntity pb = stopIntoPending(b);
        PaperTradingService.alignedRaceHook = n -> {
            if (("SETTLE_BEFORE_CLOSE#" + pa.getId()).equals(n)) throw new IllegalStateException("주입 — A 정산 실패");
        };
        markPriceBook.record(BTC, new BigDecimal("101"), Instant.now());
        paperTradingService.runStrategy();   // 주기 정산(세션 RUNNING 없음도 포함)
        System.out.println("[§3-1.5] A=" + reload(pa).getStatus() + " B=" + reload(pb).getStatus());
        assertThat(reload(pa).getStatus()).isEqualTo("CLOSING");
        assertThat(sells(a.getId())).isEmpty();
        assertThat(reload(pb).getStatus()).isEqualTo("CLOSED");

        PaperTradingService.alignedRaceHook = n -> { };
        target().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, new BigDecimal("102"), Instant.now(), RealtimePriceEvent.WS_TRADE));
        assertThat(reload(pa).getStatus()).isEqualTo("CLOSED");
        assertThat(sells(a.getId())).hasSize(1);
        assertThat(sells(b.getId())).hasSize(1);
    }

    // ── W5 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("W5 — 커밋 뒤 BUY 알림 예외: 확정 매수는 진입으로 보고, 같은 회차 다음 세션도 진입, 보고 실패 수만 증가")
    void w5AfterCommitBuyNotificationFailureDoesNotFailApproval() {
        insertCache(BTC, uptrend(lastOpen));
        VirtualBalanceEntity p = paper(BTC);
        p.setTelegramEnabled(true);
        balanceRepo.save(p);
        VirtualBalanceEntity q = paper(BTC);
        q.setTelegramEnabled(true);
        balanceRepo.save(q);
        Mockito.doThrow(new IllegalStateException("주입 — 커밋 뒤 알림 실패")).when(telegramService)
                .bufferTradeEvent(any(), any(), eq("BUY"), any(), any(), any(), any(), any());
        long failuresBefore = PaperTradingService.alignedReportFailures.get();
        runPaper(p);
        runPaper(q);
        List<EntryPriorityRounds.Outcome> out = approveRound();
        List<String> reasons = out.stream().filter(o -> "PAPER".equals(o.candidate().kind())).map(EntryPriorityRounds.Outcome::reason).toList();
        System.out.println("[W5 보완] 결과=" + reasons + " 포지션 p=" + paperPositions(p).size() + " q=" + paperPositions(q).size()
                + " 보고 실패 증가=" + (PaperTradingService.alignedReportFailures.get() - failuresBefore));
        assertThat(reasons).hasSize(2).containsOnlyNulls();
        assertThat(paperPositions(p)).hasSize(1);
        assertThat(paperPositions(q)).hasSize(1);
        assertThat(balanceRepo.findById(p.getId()).orElseThrow().getAvailableKrw()).isEqualByComparingTo("200");
        assertThat(PaperTradingService.alignedReportFailures.get() - failuresBefore).isEqualTo(2);
        Mockito.verify(telegramService, Mockito.times(2)).bufferTradeEvent(any(), any(), eq("BUY"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("W5b — 커밋 뒤 SELL 알림 예외: 청산은 확정(CLOSED·매도1), 대기 정산 알림 예외도 같다")
    void w5bAfterCommitSellNotificationFailure() {
        VirtualBalanceEntity p = paper(BTC);
        p.setTelegramEnabled(true);
        balanceRepo.save(p);
        PaperPositionEntity pp = stopIntoPending(p);
        Mockito.doThrow(new IllegalStateException("주입 — 커밋 뒤 알림 실패")).when(telegramService)
                .bufferTradeEvent(any(), any(), eq("SELL"), any(), any(), any(), any(), any());
        target().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, new BigDecimal("101"), Instant.now(), RealtimePriceEvent.WS_TRADE));
        System.out.println("[W5b 보완] 포지션=" + reload(pp).getStatus() + " 매도=" + sells(p.getId()).size());
        assertThat(reload(pp).getStatus()).isEqualTo("CLOSED");
        assertThat(sells(p.getId())).hasSize(1);

        VirtualBalanceEntity q = paper(BTC);
        q.setTelegramEnabled(true);
        balanceRepo.save(q);
        PaperPositionEntity qp = pos(q);
        markPriceBook.record(BTC, new BigDecimal("101"), Instant.now());
        paperTradingService.stop(q.getId());   // 유효 시세로 바로 청산 — 알림 예외가 정지를 실패시키지 않는다
        assertThat(reload(qp).getStatus()).isEqualTo("CLOSED");
        assertThat(balanceRepo.findById(q.getId()).orElseThrow().getStatus()).isEqualTo("STOPPED");
    }

    // ── W6·§3-3 ────────────────────────────────────────────────────

    @Test
    @DisplayName("W6 — 미실현 피크 30,000 → 현재 25,000: 메모리를 지워도(같은 DB) 저장 피크로 발동 유지, 피크는 낮아지지 않음")
    void w6MddPeakSurvivesMemoryReset() {
        VirtualBalanceEntity p = paper(BTC);
        p.setTotalKrw(new BigDecimal("30000"));
        p.setAvailableKrw(new BigDecimal("30000"));
        p = balanceRepo.save(p);
        CircuitBreakerResult warm = ReflectionTestUtils.invokeMethod(target(), "alignedCircuitBreaker", p);
        BigDecimal stored = balanceRepo.findMddPeakKrw(p.getId());
        p.setTotalKrw(new BigDecimal("25000"));
        p.setAvailableKrw(new BigDecimal("25000"));
        p = balanceRepo.save(p);   // 묵은 엔티티 저장 — 피크 열은 엔티티로 쓰지 않으므로 그대로여야 한다
        CircuitBreakerResult before = ReflectionTestUtils.invokeMethod(target(), "alignedCircuitBreaker", p);
        ((Map<?, ?>) ReflectionTestUtils.getField(target(), "alignedPeakTotalKrw")).clear();
        ((Map<?, ?>) ReflectionTestUtils.getField(target(), "alignedPeakPersisted")).clear();
        CircuitBreakerResult after = ReflectionTestUtils.invokeMethod(target(), "alignedCircuitBreaker", p);
        BigDecimal storedAfter = balanceRepo.findMddPeakKrw(p.getId());
        System.out.println("[W6 보완] 저장 피크=" + stored + " → " + storedAfter + " 발동 전/초기화 전/초기화 뒤=" + warm.isTriggered() + "/"
                + before.isTriggered() + "/" + after.isTriggered());
        assertThat(stored).isEqualByComparingTo("30000");
        assertThat(warm.isTriggered()).isFalse();
        assertThat(before.isTriggered()).isTrue();
        assertThat(after.isTriggered()).isTrue();
        assertThat(storedAfter).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("§3-3 — 피크 조건부 갱신: 낮은 값·늦은 값은 피크를 내리지 못함, 높은 값만 올림")
    void mddPeakOnlyRises() {
        VirtualBalanceEntity p = paper(BTC);
        assertThat(balanceRepo.findMddPeakKrw(p.getId())).isNull();
        assertThat(balanceRepo.raiseMddPeakKrw(p.getId(), new BigDecimal("30000"))).isEqualTo(1);
        assertThat(balanceRepo.raiseMddPeakKrw(p.getId(), new BigDecimal("25000"))).isZero();
        assertThat(balanceRepo.raiseMddPeakKrw(p.getId(), new BigDecimal("30000"))).isZero();
        assertThat(balanceRepo.findMddPeakKrw(p.getId())).isEqualByComparingTo("30000");
        assertThat(balanceRepo.raiseMddPeakKrw(p.getId(), new BigDecimal("30001"))).isEqualTo(1);
        long version = balanceRepo.findById(p.getId()).orElseThrow().getVersion();
        VirtualBalanceEntity stale = balanceRepo.findById(p.getId()).orElseThrow();
        stale.setRealizedPnl(BigDecimal.ONE);
        balanceRepo.save(stale);   // 엔티티 저장이 피크 열을 덮지 않는다
        System.out.println("[§3-3 피크] 저장=" + balanceRepo.findMddPeakKrw(p.getId()) + " version " + version + "→"
                + balanceRepo.findById(p.getId()).orElseThrow().getVersion());
        assertThat(balanceRepo.findMddPeakKrw(p.getId())).isEqualByComparingTo("30001");
    }

    @Test
    @DisplayName("§3-3 — 호출자 트랜잭션이 롤백돼도 이미 관측한 피크는 내려가지 않음(별도 트랜잭션 조건부 갱신)")
    void mddPeakNotLoweredByCallerRollback() {
        VirtualBalanceEntity p = paper(BTC);
        balanceRepo.raiseMddPeakKrw(p.getId(), new BigDecimal("30000"));
        org.springframework.transaction.support.TransactionTemplate tt = new org.springframework.transaction.support.TransactionTemplate(txManager);
        tt.executeWithoutResult(st -> {
            balanceRepo.raiseMddPeakKrw(p.getId(), new BigDecimal("31000"));
            jdbc.update("UPDATE paper_trading.virtual_balance SET realized_pnl = 7 WHERE id = ?", p.getId());
            st.setRollbackOnly();
        });
        VirtualBalanceEntity after = balanceRepo.findById(p.getId()).orElseThrow();
        System.out.println("[§3-3 롤백] 저장 피크=" + balanceRepo.findMddPeakKrw(p.getId()) + " 롤백된 실현손익=" + after.getRealizedPnl());
        assertThat(balanceRepo.findMddPeakKrw(p.getId())).isEqualByComparingTo("31000");
        assertThat(after.getRealizedPnl()).isNotEqualByComparingTo("7");
    }

    @Test
    @DisplayName("§3-3 — 키 없는 세션(종전 PAPER)은 런타임 열을 쓰지 않음: 피크 NULL, 판단 시각 NULL")
    void legacyPaperLeavesRuntimeColumnsNull() {
        VirtualBalanceEntity legacy = legacyPaper();
        PaperPositionEntity lp = pos(legacy);
        insertCache(BTC, uptrend(lastOpen));
        runPaper(legacy);
        paperTradingService.stop(legacy.getId());
        System.out.println("[§3-3 종전] 피크=" + balanceRepo.findMddPeakKrw(legacy.getId()) + " 판단 시각=" + reload(lp).getExitDecidedAt()
                + " 상태=" + reload(lp).getStatus());
        assertThat(balanceRepo.findMddPeakKrw(legacy.getId())).isNull();
        assertThat(reload(lp).getExitDecidedAt()).isNull();
        assertThat(reload(lp).getStatus()).isNotEqualTo("CLOSING");
    }

    /** 키 없는(종전) PAPER 세션 하나 — 같은 입력이지만 strategy_params 에 실행 모드 키가 없다. */
    private VirtualBalanceEntity legacyPaper() {
        com.cryptoautotrader.api.dto.PaperTradingStartRequest req = new com.cryptoautotrader.api.dto.PaperTradingStartRequest();
        req.setStrategyType(com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy.NAME);
        req.setCoinPair(BTC);
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("20200"));
        req.setInvestRatio(new BigDecimal("0.995"));
        req.setStopLossPct(new BigDecimal("5.00"));
        req.setMaxHoldHours(0);
        req.setStrategyParams(new java.util.HashMap<>(Map.of(PaperMaxInvestment.KEY, 20000)));
        VirtualBalanceEntity s = paperTradingService.start(req);
        paperIds.add(s.getId());
        return s;
    }
}
