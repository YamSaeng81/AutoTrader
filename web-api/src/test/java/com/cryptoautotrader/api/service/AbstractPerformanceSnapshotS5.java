package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PerformanceSummaryResponse;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 총괄 v1.151 S5 — 성과 조회가 세션 금액과 포지션 상태를 <b>한 스냅샷</b>에서 만드는지 (2026-11-11).
 * 총괄 재현(Review19Test)과 같은 순서: 성과 조회가 세션을 읽은 뒤 포지션을 읽기 직전에 <b>다른 스레드</b>(자기 영속성 컨텍스트)가
 * 실제 제품 정산 경로로 청산 대기를 정산·커밋하고, 그 완료(Future.get)를 기다린 뒤 조회를 잇는다. sleep 없음.
 * 기대: 응답은 정산 전 스냅샷 전체(대기1·완료0·현재 자산 null·마지막 기록=정산 전 금액)이고, 정산 뒤 완료 표시와 섞이지 않는다.
 * 정산 커밋 뒤 새 조회는 정산 뒤 스냅샷 전체(대기0·완료1·현재 자산=DB 금액·실현손익=DB)다.
 */
abstract class AbstractPerformanceSnapshotS5 extends AbstractSma20SameInputFixture {

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    private PerformanceSummaryResponse.SessionPerformance perf(PerformanceSummaryResponse r, Long id) {
        return r.getSessions().stream().filter(s -> s.getSessionId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("S5 — 조회 도중 다른 스레드 정산이 커밋돼도 응답은 정산 전 스냅샷 하나(금액·대기·완료·합계 일관)")
    void settlementCommittedMidReadDoesNotMix() throws Exception {
        VirtualBalanceEntity session = paper(BTC);
        PaperPositionEntity pos = paperPos(session, new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"),
                Instant.now().minus(2, ChronoUnit.HOURS));
        markPriceBook.remove(BTC);
        paperTradingService.stop(session.getId());
        assertThat(paperTradingService.pendingCloseCount(session.getId())).isEqualTo(1);
        VirtualBalanceEntity pre = balanceRepo.findById(session.getId()).orElseThrow();
        BigDecimal before = pre.getTotalKrw();
        BigDecimal realizedBefore = pre.getRealizedPnl();

        PaperTradingService target = AopTestUtils.getUltimateTargetObject(paperTradingService);
        PaperPositionRepository original = (PaperPositionRepository) ReflectionTestUtils.getField(target, "positionRepo");
        AtomicBoolean injected = new AtomicBoolean(false);
        PaperPositionRepository decorated = mock(PaperPositionRepository.class, org.mockito.AdditionalAnswers.delegatesTo(original));
        doAnswer(inv -> {
            Long sid = inv.getArgument(0);
            if (sid.equals(session.getId()) && injected.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> target.doOnAlignedRealtimePrice(
                        new RealtimePriceEvent(BTC, new BigDecimal("101"), Instant.now(), RealtimePriceEvent.WS_TRADE)))
                        .get(20, TimeUnit.SECONDS);
            }
            return original.findBySessionId(sid);
        }).when(decorated).findBySessionId(anyLong());
        ReflectionTestUtils.setField(target, "positionRepo", decorated);
        PerformanceSummaryResponse response;
        try {
            response = paperTradingService.getOverallPerformance();
        } finally {
            ReflectionTestUtils.setField(target, "positionRepo", original);
        }
        VirtualBalanceEntity actual = balanceRepo.findById(session.getId()).orElseThrow();
        String stored = original.findById(pos.getId()).orElseThrow().getStatus();
        var p = perf(response, session.getId());
        System.out.println("[S5-fixed] " + reportName() + " status=" + p.getValuationStatus() + " pending=" + p.getPendingCloseCount()
                + " trades=" + p.getTotalTrades() + " currentAsset=" + p.getCurrentAsset() + " lastRecorded=" + p.getLastRecordedTotalKrw()
                + " responseRealized=" + p.getRealizedPnl() + " dbAsset=" + actual.getTotalKrw() + " dbRealized=" + actual.getRealizedPnl()
                + " position=" + stored + " overallPending=" + response.getPendingCloseCount() + " overallTrades=" + response.getTotalTrades());

        // 경합이 실제로 일어났다: 조회 도중 정산이 커밋됨
        assertThat(injected.get()).isTrue();
        assertThat(stored).isEqualTo("CLOSED");
        assertThat(actual.getTotalKrw()).isNotEqualByComparingTo(before);
        // 응답은 정산 전 스냅샷 하나
        assertThat(p.getPendingCloseCount()).isEqualTo(1);
        assertThat(p.getValuationStatus()).isNotEqualTo("평가 완료");
        assertThat(p.getTotalTrades()).isZero();
        assertThat(p.getCurrentAsset()).isNull();
        assertThat(p.getLastRecordedTotalKrw()).isEqualByComparingTo(before);
        assertThat(p.getRealizedPnl()).isEqualByComparingTo(realizedBefore);
        assertThat(response.getPendingCloseCount()).isGreaterThanOrEqualTo(1);
        assertThat(response.getTotalPnl()).isNull();

        // 정산 커밋 뒤 새 조회는 정산 뒤 스냅샷 하나
        var after = perf(paperTradingService.getOverallPerformance(), session.getId());
        assertThat(after.getPendingCloseCount()).isZero();
        assertThat(after.getValuationStatus()).isEqualTo("평가 완료");
        assertThat(after.getTotalTrades()).isEqualTo(1);
        assertThat(after.getCurrentAsset()).isEqualByComparingTo(actual.getTotalKrw());
        assertThat(after.getRealizedPnl()).isEqualByComparingTo(actual.getRealizedPnl());
        assertThat(after.getLastRecordedTotalKrw()).isNull();
    }
}
