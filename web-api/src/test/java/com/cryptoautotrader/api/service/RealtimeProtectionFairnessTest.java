package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.155 §2 — F 전달 경계의 작업 차례 공정성 (2026-11-12, Spring·DB·주문 없음, 처리기는 대역).
 *
 * <p>총괄 탐침(FifoFairnessWitnessTest): 9d92a0f7 의 {@code CoinLane.drain} 은 대기열이 빌 때까지 돈다 — 처리 중 같은 코인이 계속 들어오면
 * 한 작업이 250번 처리하는 동안 이미 접수된 다른 코인 작업이 0번 돌았다. 대기열 100 은 한 작업의 처리 수 상한이 아니었다.</p>
 *
 * <p>계약: 한 작업은 <b>시작 때 이미 있던 유계 묶음</b>까지만 처리하고, 남은(그 사이 들어온) 같은 코인 시세는 같은 순서로 <b>실행기 뒤에 재접수</b>된다.
 * 그래서 이미 대기 중인 다른 코인·다른 작업이 차례를 얻는다. 같은 코인 순서·중복 없음·초과/거절/예외 계수는 그대로다.</p>
 *
 * <p>대기열은 메모리다 — 재기동·새 인스턴스는 앞 인스턴스의 대기 시세를 재생하지 않는다(한계, ④ 로 고정).</p>
 */
@DisplayName("v1.155 §2 F 전달 경계 — 유계 묶음·재접수 공정성 (단위)")
class RealtimeProtectionFairnessTest {

    private static RealtimePriceEvent ev(String coin, int seq) {
        return new RealtimePriceEvent(coin, new BigDecimal(seq), Instant.now(), RealtimePriceEvent.WS_TRADE);
    }

    private static long snap(RealtimeProtectionDispatcher d, String engine, String accessor) {
        Object s = d.snapshot(engine);
        return ((Number) ReflectionTestUtils.invokeMethod(s, accessor)).longValue();
    }

    @Test
    @DisplayName("① FIFO 대역 실행기 — A 처리 중 A 를 계속 넣어도(250건) 이미 접수된 B 는 A 의 두 번째 차례 전에 처리, A 순서·중복 없음, 거절/초과 0")
    void fifoBoundedTurn() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        Executor fifo = tasks::addLast;
        LiveTradingService live = Mockito.mock(LiveTradingService.class);
        DynamicTradingService dyn = Mockito.mock(DynamicTradingService.class);
        List<String> liveCalls = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger(1);
        RealtimeProtectionDispatcher[] ref = new RealtimeProtectionDispatcher[1];
        Mockito.doAnswer(inv -> {
            RealtimePriceEvent e = inv.getArgument(0);
            liveCalls.add(e.getCoinCode() + ":" + e.getPrice().toPlainString());
            if ("KRW-A".equals(e.getCoinCode()) && seq.get() < 250) ref[0].onPrice(ev("KRW-A", seq.incrementAndGet()));
            return null;
        }).when(live).onRealtimePriceEvent(Mockito.any());
        RealtimeProtectionDispatcher d = new RealtimeProtectionDispatcher(fifo, live, dyn);
        ref[0] = d;
        d.onPrice(ev("KRW-A", 1));
        d.onPrice(ev("KRW-B", 1));
        int steps = 0;
        while (!tasks.isEmpty() && steps++ < 100_000) tasks.removeFirst().run();

        int bIndex = liveCalls.indexOf("KRW-B:1");
        List<Integer> aSeq = liveCalls.stream().filter(c -> c.startsWith("KRW-A:")).map(c -> Integer.parseInt(c.substring(6))).toList();
        System.out.println("[F155] ① FIFO: LIVE 호출 " + liveCalls.size() + " · B 위치 " + bIndex + " · A " + aSeq.size()
                + " · 단계 " + steps + " | " + d.snapshot("LIVE") + " | " + d.snapshot("DYNAMIC"));
        assertThat(tasks).as("작업 소진").isEmpty();
        assertThat(bIndex).as("이미 접수된 B 는 A 첫 묶음(1건) 바로 뒤 차례").isEqualTo(1);
        assertThat(aSeq).as("A 같은 코인 순서·중복 없음·250건 모두").isEqualTo(java.util.stream.IntStream.rangeClosed(1, 250).boxed().toList());
        assertThat(snap(d, "LIVE", "rejected") + snap(d, "DYNAMIC", "rejected")).isZero();
        assertThat(snap(d, "LIVE", "overflow")).isZero();
        assertThat(snap(d, "LIVE", "pendingEvents") + snap(d, "DYNAMIC", "pendingEvents")).isZero();
    }

    @Test
    @DisplayName("② 실제 스레드 풀(2/4/100·AbortPolicy) 작업자 전부 사용 — 느린 C 가 한 작업자를, 계속 들어오는 A 가 다른 작업자를 쓰는 동안 이미 접수된 B 와 다른 실행기 작업이 진행")
    void realPoolAllWorkersBusy() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch cRunning = new CountDownLatch(1);
        CountDownLatch releaseC = new CountDownLatch(1);
        CountDownLatch aWarm = new CountDownLatch(10);
        CountDownLatch bDone = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        AtomicBoolean feedA = new AtomicBoolean(true);
        AtomicInteger aSeq = new AtomicInteger(1);
        AtomicInteger aCalls = new AtomicInteger();
        AtomicInteger aCallsAtB = new AtomicInteger(-1);
        AtomicInteger aCallsAtOther = new AtomicInteger(-1);
        List<Integer> aSeen = Collections.synchronizedList(new ArrayList<>());
        RealtimeProtectionDispatcher[] ref = new RealtimeProtectionDispatcher[1];
        LiveTradingService live = Mockito.mock(LiveTradingService.class);
        Mockito.doAnswer(inv -> {
            RealtimePriceEvent e = inv.getArgument(0);
            switch (e.getCoinCode()) {
                case "KRW-C" -> {
                    cRunning.countDown();
                    releaseC.await(30, TimeUnit.SECONDS);   // 느린 코인(행 잠금 대기 대역)
                }
                case "KRW-A" -> {
                    aSeen.add(e.getPrice().intValue());
                    aCalls.incrementAndGet();
                    aWarm.countDown();
                    if (feedA.get() && aSeq.get() < 200_000) ref[0].onPrice(ev("KRW-A", aSeq.incrementAndGet()));
                }
                case "KRW-B" -> {
                    aCallsAtB.set(aCalls.get());
                    bDone.countDown();
                }
                default -> { }
            }
            return null;
        }).when(live).onRealtimePriceEvent(Mockito.any());
        RealtimeProtectionDispatcher d = new RealtimeProtectionDispatcher(pool, live, Mockito.mock(DynamicTradingService.class));
        ref[0] = d;
        boolean bOk, otherOk;
        try {
            d.onPrice(ev("KRW-C", 1));
            assertThat(cRunning.await(10, TimeUnit.SECONDS)).as("C 가 작업자 하나를 잡음").isTrue();
            d.onPrice(ev("KRW-A", 1));
            assertThat(aWarm.await(10, TimeUnit.SECONDS)).as("A 가 다른 작업자에서 계속 처리 중").isTrue();
            d.onPrice(ev("KRW-B", 1));
            pool.execute(() -> {   // 다른 실행기 작업(거래소 복구 이벤트 같은 @Async 대역)
                aCallsAtOther.set(aCalls.get());
                otherDone.countDown();
            });
            bOk = bDone.await(10, TimeUnit.SECONDS);
            otherOk = otherDone.await(10, TimeUnit.SECONDS);
        } finally {
            feedA.set(false);
            releaseC.countDown();
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        List<Integer> seen = new ArrayList<>(aSeen);
        System.out.println("[F155] ② 실제 풀: B 처리 " + bOk + "(그때 A " + aCallsAtB.get() + "건) · 다른 작업 " + otherOk + "(그때 A " + aCallsAtOther.get()
                + "건) · A 전체 " + seen.size() + " · 작업자 최대 " + pool.getLargestPoolSize() + " | " + d.snapshot("LIVE"));
        assertThat(bOk).as("A 가 계속 들어오고 C 가 작업자를 잡은 동안에도 이미 접수된 B 처리").isTrue();
        assertThat(otherOk).as("같은 실행기의 다른 작업도 진행").isTrue();
        // 1회차: 위 두 단언만으로는 9d92 도 통과했다 — A 공급 상한(200,000)이 10초 안에 소진된 뒤에야 B 가 돌았다(시험 결함, 원문 보존).
        //   B·다른 작업이 A 공급이 끝나기 전에 돌았는지를 직접 본다.
        assertThat(aCallsAtB.get()).as("B 는 A 공급이 끝나기 전에 처리").isLessThan(200_000);
        assertThat(aCallsAtOther.get()).as("다른 작업도 A 공급이 끝나기 전에").isLessThan(200_000);
        assertThat(seen).as("A 같은 코인 순서·중복 없음").isEqualTo(java.util.stream.IntStream.rangeClosed(1, seen.size()).boxed().toList());
        assertThat(snap(d, "LIVE", "rejected")).isZero();
        assertThat(snap(d, "LIVE", "overflow")).isZero();
        assertThat(snap(d, "LIVE", "failures")).isZero();
    }

    @Test
    @DisplayName("③ 묶음 상한 — 한 작업이 처리하는 수는 시작 때 대기 수(≤ 대기열 상한 100) 이하, 나머지는 같은 순서로 다음 작업")
    void batchBoundedByPendingAtStart() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        LiveTradingService live = Mockito.mock(LiveTradingService.class);
        List<Integer> seen = new ArrayList<>();
        List<Integer> perTask = new ArrayList<>();
        RealtimeProtectionDispatcher[] ref = new RealtimeProtectionDispatcher[1];
        AtomicInteger next = new AtomicInteger(101);
        Mockito.doAnswer(inv -> {
            RealtimePriceEvent e = inv.getArgument(0);
            if ("KRW-A".equals(e.getCoinCode())) {
                seen.add(e.getPrice().intValue());
                if (next.get() <= 150) ref[0].onPrice(ev("KRW-A", next.getAndIncrement()));   // 처리 중 50건 더 도착
            }
            return null;
        }).when(live).onRealtimePriceEvent(Mockito.any());
        RealtimeProtectionDispatcher d = new RealtimeProtectionDispatcher(tasks::addLast, live, Mockito.mock(DynamicTradingService.class));
        ref[0] = d;
        for (int i = 1; i <= 100; i++) d.onPrice(ev("KRW-A", i));
        int steps = 0;
        while (!tasks.isEmpty() && steps++ < 10_000) {   // 엔진 순서는 정하지 않는다 — LIVE 호출이 있은 작업만 센다
            int before = seen.size();
            tasks.removeFirst().run();
            if (seen.size() > before) perTask.add(seen.size() - before);
        }
        System.out.println("[F155] ③ 묶음: 작업별 처리 " + perTask + " · 전체 " + seen.size() + " | " + d.snapshot("LIVE"));
        assertThat(perTask.get(0)).as("첫 작업 = 시작 때 대기 100건").isEqualTo(100);
        assertThat(perTask).allMatch(n -> n <= RealtimeProtectionDispatcher.LANE_CAPACITY);
        assertThat(seen).isEqualTo(java.util.stream.IntStream.rangeClosed(1, 150).boxed().toList());
        assertThat(snap(d, "LIVE", "overflow")).isZero();
    }

    @Test
    @DisplayName("④ 종료 접수 거절과 새 인스턴스 — 거절 수·고정 사유·대기 유지, 새 인스턴스(재기동 대역)는 앞 대기를 재생하지 않는다(메모리 한계)")
    void shutdownRejectAndNewInstanceDoesNotReplay() {
        ThreadPoolExecutor dead = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1));
        dead.shutdown();
        LiveTradingService live = Mockito.mock(LiveTradingService.class);
        RealtimeProtectionDispatcher d1 = new RealtimeProtectionDispatcher(dead, live, Mockito.mock(DynamicTradingService.class));
        d1.onPrice(ev("KRW-R", 280));
        d1.onPrice(ev("KRW-R", 281));
        d1.retryPending();
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        RealtimeProtectionDispatcher d2 = new RealtimeProtectionDispatcher(tasks::addLast, live, Mockito.mock(DynamicTradingService.class));
        d2.retryPending();
        System.out.println("[F155] ④ 종료/새 인스턴스: 앞 " + d1.snapshot("LIVE") + " | 새 " + d2.snapshot("LIVE") + " · 새 작업 " + tasks.size());
        assertThat(snap(d1, "LIVE", "rejected")).isEqualTo(3);
        assertThat(snap(d1, "LIVE", "pendingEvents")).isEqualTo(2);
        assertThat(d1.snapshot("LIVE").lastRejectReason()).isEqualTo(RealtimeProtectionDispatcher.REJECT_REASON);
        assertThat(snap(d2, "LIVE", "pendingEvents")).as("새 인스턴스는 앞 대기를 모른다 — 재생 없음(한계)").isZero();
        assertThat(tasks).isEmpty();
        Mockito.verify(live, Mockito.never()).onRealtimePriceEvent(Mockito.any());
    }
}
