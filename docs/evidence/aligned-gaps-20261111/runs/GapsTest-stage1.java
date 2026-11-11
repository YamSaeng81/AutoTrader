package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE_ALIGNED 에 남은 판단 차이·경계 (2026-11-11, 총괄 v1.145 §3-2 / v1.146 순서 3-2).
 *
 * <p>{@link Sma20LiveAlignedPaperSameInputTest} 의 14 시나리오는 바꾸지 않는다. 여기는 그 뒤에 남은 항목을 같은 대역·같은 운영
 * 호출부로 본다. 경합은 latch 로 고정한다 — sleep·반복·기대 완화 없음. 시간 초과는 실패다.</p>
 */
@DisplayName("SMA20 LIVE_ALIGNED 남은 차이 — 평균가·급변 간격·위험 관문·가격 없는 시간 청산·정지/청산 경합")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=Sma20LiveAlignedGapsTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class Sma20LiveAlignedGapsTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient upbitWebSocketClient;

    private static final BigDecimal FEE = new BigDecimal("0.0005");
    private static final BigDecimal SLIP = new BigDecimal("0.001");

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "aligned-gaps";
    }

    private PaperTradingService paperTarget() {
        return AopTestUtils.getUltimateTargetObject(paperTradingService);
    }

    private void refreshAligned() {
        ReflectionTestUtils.invokeMethod(paperTarget(), "refreshAlignedCoins",
                balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING"));
    }

    /** 운영 스케줄러 {@code runStrategy} 의 세션 블록과 같은 경로 — 세션 잠금 안에서 다시 읽은 행으로 평가한다. */
    private void runPaperLocked(Long sessionId) {
        Object lock = ReflectionTestUtils.invokeMethod(paperTarget(), "alignedLock", sessionId);
        synchronized (lock) {
            VirtualBalanceEntity fresh = balanceRepo.findById(sessionId).orElse(null);
            if (fresh != null && "RUNNING".equals(fresh.getStatus())) {
                ReflectionTestUtils.invokeMethod(paperTarget(), "runSessionStrategy", fresh);
            }
        }
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError(what + " — 15초 안에 도달하지 않았다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(what + " — 인터럽트", e);
        }
    }

    private static <T> T get(Future<T> f, String what) {
        try {
            return f.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            throw new AssertionError(what + " — 20초 안에 끝나지 않았다(미완료를 정상 종료로 보지 않는다)", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError(what + " — 예외로 끝났다: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(what + " — 인터럽트", e);
        }
    }

    private List<PaperOrderEntity> paperOrders(Long sessionId, String side) {
        return paperOrderRepo.findBySessionIdOrderByCreatedAtDesc(sessionId, org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream().filter(o -> side.equals(o.getSide())).toList();
    }

    @AfterEach
    void resetHook() {
        PaperTradingService.alignedRaceHook = name -> { };
    }

    // ── 비동기 경계 ───────────────────────────────────────────────────

    @Test
    @DisplayName("B05 승인 E검사 통과 뒤·매수 저장 전에 정지 커밋 — 정지된 세션에 열린 포지션·부분 반영이 남지 않는다")
    void b05StopBetweenApprovalCheckAndBuy() {
        insertCache(BTC, uptrend(lastOpen));
        VirtualBalanceEntity p = paper(BTC);
        runPaper(p);   // 회차 접수(LIVE 와 같은 회차 — 승인 차례에 체결)

        CountDownLatch atBuy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PaperTradingService.alignedRaceHook = name -> {
            if ("APPROVE_BEFORE_BUY".equals(name)) {
                atBuy.countDown();
                await(release, "승인 재개 신호");
            }
        };
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<List<EntryPriorityRounds.Outcome>> approval = ex.submit(this::approveRound);
            await(atBuy, "승인이 E검사를 지나 매수 직전에 도달");

            Future<VirtualBalanceEntity> stop = ex.submit(() -> paperTradingService.stop(p.getId()));
            // 정지가 승인과 직렬화되는지 — 승인이 멈춰 있는 동안 정지가 끝나면(아래 false) 매수 저장이 정지 뒤에 들어간다.
            boolean stopFinishedWhileApprovalPaused;
            try {
                stop.get(2, TimeUnit.SECONDS);
                stopFinishedWhileApprovalPaused = true;
            } catch (java.util.concurrent.TimeoutException e) {
                stopFinishedWhileApprovalPaused = false;
            } catch (Exception e) {
                throw new AssertionError("정지가 예외로 끝났다: " + e.getCause(), e);
            }
            release.countDown();
            List<EntryPriorityRounds.Outcome> out = get(approval, "승인");
            VirtualBalanceEntity stopped = get(stop, "정지");

            VirtualBalanceEntity after = balanceRepo.findById(p.getId()).orElseThrow();
            List<PaperPositionEntity> positions = paperPositions(p);
            List<PaperOrderEntity> buys = paperOrders(p.getId(), "BUY");
            List<PaperOrderEntity> sells = paperOrders(p.getId(), "SELL");
            String approvalReason = out.stream().filter(o -> "PAPER".equals(o.candidate().kind())).findFirst()
                    .map(o -> o.reason() == null ? "진입" : o.reason()).orElse("후보 없음");
            System.out.println("[B05] 정지가 승인 중단 중에 끝남=" + stopFinishedWhileApprovalPaused + " 승인 결과=" + approvalReason
                    + " 상태=" + after.getStatus() + " 포지션=" + positions.stream().map(PaperPositionEntity::getStatus).toList()
                    + " 매수=" + buys.size() + " 매도=" + sells.size() + " 가용=" + after.getAvailableKrw() + " 총=" + after.getTotalKrw());
            row("B05 정지 경합", "0 정지가 승인 중단 중에 끝남", "—", stopFinishedWhileApprovalPaused, false);
            row("B05 정지 경합", "1 승인 결과", "—", approvalReason, false);
            row("B05 정지 경합", "2 정지 뒤 상태", "STOPPED", after.getStatus(), true);
            row("B05 정지 경합", "3 열린 포지션 수", 0, positions.stream().filter(x -> "OPEN".equals(x.getStatus())).count(), true);

            assertThat(stopped.getStatus()).isEqualTo("STOPPED");
            assertThat(after.getStatus()).isEqualTo("STOPPED");
            assertThat(positions).as("🔴 정지된 세션에 열린 포지션이 남으면 아무도 청산하지 않는다")
                    .noneMatch(x -> "OPEN".equals(x.getStatus()));
            // 매수가 있었으면 정지의 강제 청산 매도가 짝으로 있고, 잔액은 초기 − 투입 + 매도 순수익으로 정확히 맞는다.
            assertThat(sells.size()).as("매수 %d건에 매도 %d건", buys.size(), sells.size()).isEqualTo(buys.size());
            BigDecimal expected = new BigDecimal("20200");
            for (PaperPositionEntity pos : positions) {
                BigDecimal proceeds = pos.getSize().multiply(sells.get(0).getPrice());
                expected = expected.subtract(pos.getInvestedKrw()).add(proceeds.subtract(proceeds.multiply(FEE)));
            }
            assertThat(after.getAvailableKrw().setScale(2, RoundingMode.HALF_UP))
                    .as("부분 반영(포지션·주문은 있는데 차감이 없거나, 차감만 있는 상태)이 없다")
                    .isEqualByComparingTo(expected.setScale(2, RoundingMode.HALF_UP));
            assertThat(after.getTotalKrw()).isEqualByComparingTo(after.getAvailableKrw());
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("B06 실시간 손절과 평가 회차 청산이 같은 포지션에 동시에 — 매도 1회, 잔액 1회 반영")
    void b06RealtimeVersusEvaluationExitRace() {
        List<Candle> cs = downtrend(lastOpen);
        insertCache(BTC, cs);
        BigDecimal close = lastClose(cs);
        BigDecimal entry = close.multiply(new BigDecimal("1.05")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal sl = entry.multiply(new BigDecimal("0.97")).setScale(0, RoundingMode.HALF_UP);   // 현재가 < 손절가
        BigDecimal tp = entry.multiply(new BigDecimal("1.10"));
        VirtualBalanceEntity p = paper(BTC);
        PaperPositionEntity pos = paperPos(p, entry, sl, tp, Instant.now().minus(20, ChronoUnit.HOURS));
        refreshAligned();

        CountDownLatch atClose = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PaperTradingService.alignedRaceHook = name -> {
            if ("RT_BEFORE_CLOSE".equals(name)) {
                atClose.countDown();
                await(release, "실시간 처리기 재개 신호");
            }
        };
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<?> rt = ex.submit(() -> paperTarget().doOnAlignedRealtimePrice(
                    new RealtimePriceEvent(BTC, close, Instant.now(), RealtimePriceEvent.WS_TRADE)));
            await(atClose, "실시간 손절이 청산 직전에 도달");
            Future<?> tick = ex.submit(() -> runPaperLocked(p.getId()));
            boolean tickFinishedWhileRtPaused;
            try {
                tick.get(2, TimeUnit.SECONDS);
                tickFinishedWhileRtPaused = true;
            } catch (java.util.concurrent.TimeoutException e) {
                tickFinishedWhileRtPaused = false;
            } catch (Exception e) {
                throw new AssertionError("평가 회차가 예외로 끝났다: " + e.getCause(), e);
            }
            release.countDown();
            get(rt, "실시간 처리기");
            get(tick, "평가 회차");

            PaperPositionEntity after = paperPositionRepo.findById(pos.getId()).orElseThrow();
            VirtualBalanceEntity b = balanceRepo.findById(p.getId()).orElseThrow();
            List<PaperOrderEntity> sells = paperOrders(p.getId(), "SELL");
            BigDecimal fill = close.multiply(BigDecimal.ONE.subtract(SLIP)).setScale(8, RoundingMode.HALF_DOWN);
            BigDecimal proceeds = pos.getSize().multiply(fill);
            BigDecimal expectedAvail = new BigDecimal("200").add(proceeds.subtract(proceeds.multiply(FEE)));
            System.out.println("[B06] 평가가 RT 중단 중에 끝남=" + tickFinishedWhileRtPaused + " 상태=" + after.getStatus()
                    + " 사유=" + after.getExitReason() + " 매도=" + sells.size() + " 가용=" + b.getAvailableKrw() + " 기대=" + expectedAvail);
            row("B06 청산 경합", "0 평가가 RT 중단 중에 끝남", "—", tickFinishedWhileRtPaused, false);
            row("B06 청산 경합", "1 매도 주문 수", 1, sells.size(), true);
            assertThat(tickFinishedWhileRtPaused).as("평가 회차는 실시간 처리기와 같은 세션 잠금을 기다린다").isFalse();
            assertThat(after.getStatus()).isEqualTo("CLOSED");
            assertThat(sells).as("같은 포지션에 매도가 두 번 나가면 잔액이 두 번 늘어난다").hasSize(1);
            assertThat(b.getAvailableKrw().setScale(2, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(expectedAvail.setScale(2, RoundingMode.HALF_UP));
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }
}
