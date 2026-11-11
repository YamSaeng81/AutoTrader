package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.support.ExecutorBarrier;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.152 F — 공유 {@code marketDataExecutor}(LIVE·DYNAMIC 실시간 @Async + PAPER 선택 모드 실시간 작업)를 <b>실제 실행기</b>에서
 * 래치로 포화시키고, 실제 완료 신호(Future.get·실행기 장벽)로 해제·회복을 확인한다 (2026-11-12). sleep·activeCount 순간값으로 판정하지 않는다.
 * 실행기 설정(코어·최대·대기열)은 바꾸지 않는다. 로컬 합성 부하이며 운영 부하 실측이 아니다.
 *
 * <ol>
 *   <li>포화(작업자 최대·대기열 가득) 중 운영 발행 경로({@code publishEvent})로 시세 1건 — 발행 쪽 예외·LIVE 수신·PAPER 거절 수·시세 장부를 기록</li>
 *   <li>해제 → 막은 작업 전부 완료 확인 → 시세 1건 → 장벽 뒤 LIVE 수신 확인(회복)</li>
 *   <li>작업자만 바쁘고 대기열 여유 — 시세 1건은 대기열에 들어가 해제 전에는 LIVE 가 처리하지 않고, 해제 뒤 처리(수신 지연 = 앞선 작업 완료까지)</li>
 * </ol>
 */
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=SharedMarketDataExecutorSaturationTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("F 공유 marketDataExecutor 실제 포화 — 포화 중 발행·해제 회복·대기열 지연 (래치·Future·장벽)")
class SharedMarketDataExecutorSaturationTest extends AbstractSma20SameInputFixture {

    @MockBean private UpbitWebSocketClient upbitWebSocketClient;
    @Autowired @Qualifier("marketDataExecutor") private java.util.concurrent.Executor marketDataExecutor;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @Override
    protected Map<String, Object> paperParams() {
        return Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED);
    }

    @Override
    protected String reportName() {
        return "md-saturation";
    }

    @SuppressWarnings("unchecked")
    private int liveHistory(String coin) {
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        Deque<?> d = ((Map<String, Deque<?>>) ReflectionTestUtils.getField(target, "priceHistory")).get(coin);
        return d == null ? 0 : d.size();
    }

    private Throwable publish(BigDecimal price) {
        try {
            eventPublisher.publishEvent(new RealtimePriceEvent(BTC, price, Instant.now(), RealtimePriceEvent.WS_TRADE));
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Test
    void saturateReleaseRecoverAndQueueDelay() throws Exception {
        ThreadPoolExecutor pool = ((ThreadPoolTaskExecutor) marketDataExecutor).getThreadPoolExecutor();
        int core = pool.getCorePoolSize(), max = pool.getMaximumPoolSize();
        int qcap = pool.getQueue().size() + pool.getQueue().remainingCapacity();
        insertCache(BTC, uptrend(lastOpen));
        VirtualBalanceEntity p = paper(BTC);
        PaperTradingService paperTarget = AopTestUtils.getUltimateTargetObject(paperTradingService);
        ReflectionTestUtils.invokeMethod(paperTarget, "refreshAlignedCoins", balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING"));
        ExecutorBarrier.awaitPriorTasks(pool, 15_000);   // 시작 전 앞선 작업 없음(확정 신호)
        clearRealtimeThrottle();
        List<String> log = new ArrayList<>();
        log.add("설정 core=" + core + " max=" + max + " queue=" + qcap + " 거절 정책=" + pool.getRejectedExecutionHandler().getClass().getSimpleName());

        // ── ① 포화 ──
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(max);
        List<Future<?>> blocked = new ArrayList<>();
        Runnable blocker = () -> {
            running.countDown();
            try { release.await(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        for (int i = 0; i < core; i++) blocked.add(pool.submit(blocker));
        for (int i = 0; i < qcap; i++) blocked.add(pool.submit(() -> { }));   // 대기열 채움(해제 뒤 바로 끝남)
        for (int i = core; i < max; i++) blocked.add(pool.submit(blocker));    // 대기열이 차야 작업자가 최대까지 는다
        assertThat(running.await(20, TimeUnit.SECONDS)).as("작업자 " + max + "개 모두 막힘(실제 실행 신호)").isTrue();
        assertThat(pool.getQueue().remainingCapacity()).as("대기열 가득").isZero();

        int liveBefore = liveHistory(BTC);
        long paperRejBefore = paperTarget.alignedRtRejected.get();
        Throwable thrown = publish(new BigDecimal("12000000"));
        BigDecimal mark1 = markPriceBook.freshPrice(BTC, Instant.now());
        if (thrown != null) {
            StringBuilder sb = new StringBuilder("① 거절 스택(서비스·멀티캐스터 프레임):");
            for (StackTraceElement e : thrown.getStackTrace()) {
                if (e.getClassName().contains("cryptoautotrader") || e.getClassName().contains("Multicaster") || e.getClassName().contains("ApplicationListenerMethodAdapter")) {
                    sb.append(" | ").append(e.getClassName().replaceAll(".*[.]", "")).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
                }
            }
            log.add(sb.toString());
        }
        log.add("① 포화 중 발행: 발행 쪽 예외=" + (thrown == null ? "없음" : thrown.getClass().getSimpleName() + ": " + thrown.getMessage())
                + " · PAPER 거절 +" + (paperTarget.alignedRtRejected.get() - paperRejBefore)
                + " · LIVE 이력 " + liveBefore + "→" + liveHistory(BTC) + " · 시세 장부 BTC=" + mark1);

        // ── ② 해제·회복 ──
        release.countDown();
        for (Future<?> f : blocked) f.get(20, TimeUnit.SECONDS);   // 막은 작업 전부 실제 완료
        ExecutorBarrier.awaitPriorTasks(pool, 15_000);
        int liveAfterReleaseRaw = liveHistory(BTC);
        clearRealtimeThrottle();
        int liveAfterRelease = liveHistory(BTC);
        Throwable thrown2 = publish(new BigDecimal("12000100"));
        ExecutorBarrier.awaitPriorTasks(pool, 15_000);
        int liveRecovered = liveHistory(BTC);
        log.add("② 해제 뒤 시세 장부 BTC=" + markPriceBook.freshPrice(BTC, Instant.now()));
        log.add("② 해제 뒤: 해제 직후 LIVE 이력=" + liveAfterReleaseRaw + "(비운 뒤 " + liveAfterRelease + ")" + " · 새 시세 발행 예외=" + (thrown2 == null ? "없음" : thrown2.getClass().getSimpleName())
                + " · 장벽 뒤 LIVE 이력=" + liveRecovered + " · PAPER 누적 거절=" + paperTarget.alignedRtRejected.get());

        // ── ③ 작업자만 바쁨(대기열 여유) — 지연 ──
        clearRealtimeThrottle();
        CountDownLatch release3 = new CountDownLatch(1);
        CountDownLatch running3 = new CountDownLatch(core);
        List<Future<?>> blocked3 = new ArrayList<>();
        for (int i = 0; i < core; i++) blocked3.add(pool.submit(() -> {
            running3.countDown();
            try { release3.await(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        assertThat(running3.await(20, TimeUnit.SECONDS)).isTrue();
        Throwable thrown3 = publish(new BigDecimal("12000200"));
        int liveWhileBusy = liveHistory(BTC);   // 작업자가 모두 막혀 있으므로 대기열의 LIVE 작업은 아직 실행될 수 없다
        long t0 = System.nanoTime();
        release3.countDown();
        for (Future<?> f : blocked3) f.get(20, TimeUnit.SECONDS);
        ExecutorBarrier.awaitPriorTasks(pool, 15_000);
        long afterReleaseMs = (System.nanoTime() - t0) / 1_000_000;
        int liveAfterBusy = liveHistory(BTC);
        log.add("③ 바쁨(대기열 여유): 발행 예외=" + (thrown3 == null ? "없음" : thrown3.getClass().getSimpleName())
                + " · 해제 전 LIVE 이력=" + liveWhileBusy + " · 해제→장벽 " + afterReleaseMs + "ms 뒤 LIVE 이력=" + liveAfterBusy);

        log.forEach(s -> System.out.println("[F] " + s));
        row("F 포화", "① 포화 중 발행 쪽 예외", thrown == null ? "없음" : thrown.getClass().getSimpleName(), "—", false);

        // 확정 단언(관측된 결함을 고정하지 않는 부분만): 회복·지연 순서
        assertThat(thrown2).as("해제 뒤 발행 예외 없음").isNull();
        assertThat(liveRecovered).as("해제 뒤 LIVE 수신 회복").isGreaterThan(liveAfterRelease);
        assertThat(thrown3).as("대기열 여유면 거절 없음").isNull();
        assertThat(liveAfterBusy).as("해제 뒤 대기열의 LIVE 작업 처리").isGreaterThan(liveWhileBusy);
    }
}
