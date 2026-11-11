package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.controller.PaperTradingController;
import com.cryptoautotrader.api.dto.PerformanceSummaryResponse;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 총괄 v1.150 관측 S1~S4 의 보완 확인 (2026-11-11). 총괄 관측 시험({@code Review72Test})과 같은 입력·호출부에 <b>바라는 동작</b>을 단언한다.
 * 알림 실패는 메서드 예외 주입이다(자연 발생·큐 포화 관측 아님). sleep·반복 없음.
 */
@DisplayName("LIVE_ALIGNED v1.150 보완 — 정지 결과 DB 확정·일괄 정지 수·보유 한 스냅샷·잔고/목록/성과 평가 상태")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=AlignedV150FixesTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AlignedV150FixesTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient ws;
    @Autowired private PaperTradingController controller;

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "v150-fixes";
    }

    private PaperTradingService target() {
        return AopTestUtils.getUltimateTargetObject(paperTradingService);
    }

    @AfterEach
    void reset() {
        markPriceBook.remove(BTC);
        markPriceBook.remove(ETH);
        Mockito.reset(telegramService);
    }

    private PaperPositionEntity pos(VirtualBalanceEntity p) {
        return paperPos(p, new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"), Instant.now().minus(2, ChronoUnit.HOURS));
    }

    private VirtualBalanceEntity notifying(String coin) {
        VirtualBalanceEntity s = paper(coin);
        s.setTelegramEnabled(true);
        return balanceRepo.save(s);
    }

    private PaperPositionEntity reload(PaperPositionEntity p) {
        return paperPositionRepo.findById(p.getId()).orElseThrow();
    }

    private VirtualBalanceEntity reload(VirtualBalanceEntity s) {
        return balanceRepo.findById(s.getId()).orElseThrow();
    }

    /** 다른 시험이 남긴 RUNNING 세션을 먼저 정지 — 일괄 정지의 수를 이 시험의 세션으로만 센다. */
    private void stopLeftovers() {
        paperTradingService.stopAll();
        Mockito.reset(telegramService);
    }

    // ── S1 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("S1 — 대기 정지 알림이 실패해도 stop() 은 커밋된 결과(STOPPED·CLOSING 1)를 돌려주고, 알림은 1회, 실패 수 +1, 정지 재실행 없음")
    void s1PendingStopNotificationFailureKeepsCommittedResult() {
        VirtualBalanceEntity s = notifying(BTC);
        PaperPositionEntity p = pos(s);
        markPriceBook.remove(BTC);
        Mockito.doThrow(new IllegalStateException("LOCAL stop notification failure"))
                .when(telegramService).notifyPaperSessionStoppedPendingClose(eq(s.getId()), any(), any(), eq(1L));
        long failuresBefore = PaperTradingService.alignedReportFailures.get();

        Map<String, Object> body = controller.stop(s.getId()).getData();
        System.out.println("[S1 보완] 응답=" + body + " DB=" + reload(s).getStatus() + "/" + reload(p).getStatus()
                + " 보고 실패 수 +" + (PaperTradingService.alignedReportFailures.get() - failuresBefore));

        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("STOPPED");
        assertThat(body.get("pendingCloseCount")).isEqualTo(1L);
        assertThat(body.get("finalAsset")).isNull();
        assertThat(reload(s).getStatus()).isEqualTo("STOPPED");
        assertThat(reload(p).getStatus()).isEqualTo("CLOSING");
        assertThat(PaperTradingService.alignedReportFailures.get() - failuresBefore).isEqualTo(1);
        Mockito.verify(telegramService, Mockito.times(1)).notifyPaperSessionStoppedPendingClose(eq(s.getId()), any(), any(), eq(1L));
        Mockito.verify(telegramService, Mockito.never()).notifyPaperSessionStopped(any(), any(), any(), any(), anyDouble());
    }

    @Test
    @DisplayName("S1b — 완료 정지 알림(유효 시세로 즉시 정산)이 실패해도 정지·정산 확정, 응답은 최종 자산·대기 0")
    void s1bCompletedStopNotificationFailureKeepsCommittedResult() {
        VirtualBalanceEntity s = notifying(BTC);
        PaperPositionEntity p = pos(s);
        markPriceBook.record(BTC, new BigDecimal("101"), Instant.now());
        Mockito.doThrow(new IllegalStateException("LOCAL stop notification failure"))
                .when(telegramService).notifyPaperSessionStopped(eq(s.getId()), any(), any(), any(), anyDouble());

        Map<String, Object> body = controller.stop(s.getId()).getData();
        System.out.println("[S1b 보완] 응답=" + body + " DB=" + reload(s).getStatus() + "/" + reload(p).getStatus());

        assertThat(body.get("status")).isEqualTo("STOPPED");
        assertThat(body.get("pendingCloseCount")).isEqualTo(0L);
        assertThat(body.get("finalAsset")).isNotNull();
        assertThat(reload(p).getStatus()).isEqualTo("CLOSED");
        Mockito.verify(telegramService, Mockito.times(1)).notifyPaperSessionStopped(eq(s.getId()), any(), any(), any(), anyDouble());
    }

    @Test
    @DisplayName("S1c — 정지 커밋 뒤 응답 전에 정산이 끝나도 응답은 커밋 시점 대기 1·최종 자산 비움(옛 엔티티 총액을 최종으로 내지 않음)")
    void s1cStopResponseUsesCommitTimePendingCount() {
        VirtualBalanceEntity s = notifying(BTC);
        PaperPositionEntity p = pos(s);
        markPriceBook.remove(BTC);
        // 커밋 뒤 보고(대기 알림) 안에서 첫 유효 시세가 들어와 정산까지 끝나는 순서 — 응답 직전에 대기 수를 다시 세던 종전 코드는 0 을 읽었다.
        Mockito.doAnswer(inv -> {
            target().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, new BigDecimal("101"), Instant.now(), RealtimePriceEvent.WS_TRADE));
            return null;
        }).when(telegramService).notifyPaperSessionStoppedPendingClose(eq(s.getId()), any(), any(), eq(1L));

        Map<String, Object> body = controller.stop(s.getId()).getData();
        System.out.println("[S1c 보완] 응답=" + body + " 응답 시점 DB 포지션=" + reload(p).getStatus() + " 대기=" + paperTradingService.pendingCloseCount(s.getId()));

        assertThat(reload(p).getStatus()).isEqualTo("CLOSED");          // 응답 전에 정산은 끝났다
        assertThat(body.get("pendingCloseCount")).isEqualTo(1L);          // 응답은 정지 커밋 시점의 값
        assertThat(body.get("finalAsset")).isNull();                      // 정산 전 엔티티 총액을 최종 자산으로 내지 않는다
        assertThat(body.get("lastRecordedTotalKrw")).isNotNull();
    }

    // ── S2 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("S2 — 일괄 정지: 한 세션의 알림 실패가 그 세션을 결과에서 빼지 않고 다음 세션도 정지, 수·대기·정산 표시가 DB 와 같음")
    void s2BulkStopKeepsCommittedSessionsAndCounts() {
        stopLeftovers();
        VirtualBalanceEntity a = notifying(BTC);
        pos(a);
        VirtualBalanceEntity b = notifying(ETH);
        pos(b);
        markPriceBook.remove(BTC);
        markPriceBook.remove(ETH);
        Mockito.doThrow(new IllegalStateException("LOCAL stop notification failure"))
                .when(telegramService).notifyPaperSessionStoppedPendingClose(anyLong(), any(), any(), anyLong());

        Map<String, Object> all = controller.stopAll().getData();
        System.out.println("[S2 보완] 일괄 정지 응답=" + all + " DB=" + reload(a).getStatus() + "/" + reload(b).getStatus()
                + " 대기=" + paperTradingService.pendingCloseCount(a.getId()) + "/" + paperTradingService.pendingCloseCount(b.getId()));

        assertThat(all.get("stoppedCount")).isEqualTo(2);
        assertThat(all.get("pendingCloseCount")).isEqualTo(2L);
        assertThat((String) all.get("settlement")).startsWith("청산 대기 2건");
        assertThat(reload(a).getStatus()).isEqualTo("STOPPED");
        assertThat(reload(b).getStatus()).isEqualTo("STOPPED");
        Mockito.verify(telegramService, Mockito.times(2)).notifyPaperSessionStoppedPendingClose(anyLong(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("S2b — 정지 호출이 커밋 뒤 예외로 끝나도(종전 PAPER 알림 예외) DB 에 STOPPED 로 확정됐으면 일괄 결과에 넣음, 정지 재실행 없음")
    void s2bBulkStopFallsBackToCommittedDbState() {
        stopLeftovers();
        VirtualBalanceEntity legacy = notifying(BTC);
        Map<String, Object> params = new HashMap<>(legacy.getStrategyParams());
        params.remove(PaperExecutionMode.KEY);
        legacy.setStrategyParams(params);
        balanceRepo.save(legacy);
        VirtualBalanceEntity aligned = notifying(ETH);
        Mockito.doThrow(new IllegalStateException("LOCAL legacy stop notification failure"))
                .when(telegramService).notifyPaperSessionStopped(eq(legacy.getId()), any(), any(), any(), anyDouble());

        List<PaperTradingService.SessionValuation> out = paperTradingService.stopAll();
        System.out.println("[S2b 보완] 결과 id=" + out.stream().map(v -> v.session().getId()).toList()
                + " DB=" + reload(legacy).getStatus() + "/" + reload(aligned).getStatus());

        assertThat(out).extracting(v -> v.session().getId()).containsExactlyInAnyOrder(legacy.getId(), aligned.getId());
        assertThat(out).allSatisfy(v -> assertThat(v.pendingCloseCount()).isZero());
        assertThat(reload(legacy).getStatus()).isEqualTo("STOPPED");
        Mockito.verify(telegramService, Mockito.times(1)).notifyPaperSessionStopped(eq(legacy.getId()), any(), any(), any(), anyDouble());
    }

    // ── S3 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("S3 — 보유 조회는 한 SQL(OPEN·CLOSING) 1회: 상태별 두 조회를 쓰지 않음(총괄 목 스냅샷 입력에서 같은 ID 중복 0)")
    void s3HeldPositionsUseSingleQuery() {
        PaperTradingService t = target();
        PaperPositionRepository repo = Mockito.mock(PaperPositionRepository.class);
        Object old = ReflectionTestUtils.getField(t, "positionRepo");
        PaperPositionEntity first = PaperPositionEntity.builder().id(101L).sessionId(10L).status("OPEN").build();
        PaperPositionEntity second = PaperPositionEntity.builder().id(101L).sessionId(10L).status("CLOSING").build();
        Mockito.when(repo.findBySessionIdAndStatus(10L, "OPEN")).thenReturn(List.of(first));
        Mockito.when(repo.findBySessionIdAndStatus(10L, "CLOSING")).thenReturn(List.of(second));
        Mockito.when(repo.findHeldBySessionId(10L)).thenReturn(List.of(second));
        ReflectionTestUtils.setField(t, "positionRepo", repo);
        try {
            List<PaperPositionEntity> out = t.getOpenPositions(10L);
            System.out.println("[S3 보완] 반환=" + out.size() + " 고유 ID=" + out.stream().map(PaperPositionEntity::getId).distinct().count());
            assertThat(out).hasSize(1);
            Mockito.verify(repo, Mockito.times(1)).findHeldBySessionId(10L);
            Mockito.verify(repo, Mockito.never()).findBySessionIdAndStatus(any(), anyString());
        } finally {
            ReflectionTestUtils.setField(t, "positionRepo", old);
        }
    }

    @Test
    @DisplayName("S3b — 실제 H2: OPEN·CLOSING 은 보유로 id 순 1번씩, CLOSED 는 제외(OPEN 의 뜻 유지)")
    void s3bHeldPositionsOnRealRepository() {
        VirtualBalanceEntity s = paper(BTC);
        PaperPositionEntity open = pos(s);
        PaperPositionEntity closing = pos(s);
        closing.setStatus("CLOSING");
        paperPositionRepo.save(closing);
        PaperPositionEntity closed = pos(s);
        closed.setStatus("CLOSED");
        closed.setClosedAt(Instant.now());
        paperPositionRepo.save(closed);

        List<PaperPositionEntity> held = paperTradingService.getOpenPositions(s.getId());
        System.out.println("[S3b 보완] 보유=" + held.stream().map(x -> x.getId() + ":" + x.getStatus()).toList());
        assertThat(held).extracting(PaperPositionEntity::getId).containsExactly(open.getId(), closing.getId());
        assertThat(held).extracting(PaperPositionEntity::getStatus).containsExactly("OPEN", "CLOSING");
    }

    // ── S4 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("S4 — 잔고·목록·성과: 대기 중엔 총자산·평가액·손익·수익률을 비우고 기록값을 따로, 현금·실현손익 유지; 정산 뒤 평가 완료")
    void s4BalanceListAndPerformanceCarryValuationStatus() {
        VirtualBalanceEntity s = paper(BTC);
        pos(s);
        markPriceBook.remove(BTC);
        paperTradingService.stop(s.getId());

        Map<String, Object> bal = controller.getSessionBalance(s.getId()).getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> row = controller.listSessions().getData().stream()
                .filter(m -> s.getId().equals(m.get("id"))).findFirst().orElseThrow();
        PerformanceSummaryResponse.SessionPerformance perf = paperTradingService.getOverallPerformance().getSessions().stream()
                .filter(x -> s.getId().equals(x.getSessionId())).findFirst().orElseThrow();
        System.out.println("[S4 보완] 잔고=" + bal);
        System.out.println("[S4 보완] 목록=" + row);
        System.out.println("[S4 보완] 성과 currentAsset=" + perf.getCurrentAsset() + " 기록=" + perf.getLastRecordedTotalKrw()
                + " 대기=" + perf.getPendingCloseCount());

        for (String k : List.of("totalAssetKrw", "positionValueKrw", "unrealizedPnl", "positionPnlPct", "totalReturnPct")) {
            assertThat(bal.get(k)).as(k).isNull();
        }
        assertThat(bal.get("pendingCloseCount")).isEqualTo(1L);
        assertThat((String) bal.get("valuationStatus")).startsWith("평가 불가");
        assertThat(bal.get("lastRecordedTotalKrw")).isNotNull();
        assertThat((String) bal.get("lastRecordedNote")).contains("현재 평가 아님");
        assertThat(bal.get("availableKrw")).isNotNull();
        assertThat(bal.get("realizedPnl")).isNotNull();
        assertThat(row.get("totalAssetKrw")).isNull();
        assertThat(row.get("totalReturnPct")).isNull();
        assertThat(row.get("pendingCloseCount")).isEqualTo(1L);
        assertThat(row.get("lastRecordedTotalKrw")).isNotNull();
        assertThat(perf.getCurrentAsset()).isNull();
        assertThat(perf.getLastRecordedTotalKrw()).isNotNull();

        target().doOnAlignedRealtimePrice(new RealtimePriceEvent(BTC, new BigDecimal("101"), Instant.now(), RealtimePriceEvent.WS_TRADE));
        Map<String, Object> after = controller.getSessionBalance(s.getId()).getData();
        PerformanceSummaryResponse.SessionPerformance perfAfter = paperTradingService.getOverallPerformance().getSessions().stream()
                .filter(x -> s.getId().equals(x.getSessionId())).findFirst().orElseThrow();
        System.out.println("[S4 보완] 정산 뒤 잔고=" + after + " 성과 currentAsset=" + perfAfter.getCurrentAsset());
        assertThat(after.get("pendingCloseCount")).isEqualTo(0L);
        assertThat(after.get("valuationStatus")).isEqualTo("평가 완료");
        assertThat(after.get("totalAssetKrw")).isNotNull();
        assertThat(after.get("totalReturnPct")).isNotNull();
        assertThat(after).doesNotContainKey("lastRecordedTotalKrw");
        assertThat(perfAfter.getCurrentAsset()).isNotNull();
        assertThat(perfAfter.getLastRecordedTotalKrw()).isNull();
    }
}
