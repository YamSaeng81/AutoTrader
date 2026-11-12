package com.cryptoautotrader.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 실시간 시세 → <b>실자금 보호 점검</b>(LIVE·DYNAMIC 실시간 손절) 전달 경계 (2026-11-12, 총괄 v1.153 F · v1.154 §2 보완).
 *
 * <p>종전에는 두 엔진의 처리기가 각각 {@code @EventListener @Async("marketDataExecutor")} 였다. 실행기(코어 2·최대 4·대기열 100·AbortPolicy)가
 * 가득 차면 <b>먼저 불린 리스너의 접수 거절 예외가 발행 쪽으로 올라가</b>, 같은 시세를 나머지 리스너(다른 엔진 손절·시세 장부)가 받지 못했고
 * 발행 쪽(웹소켓 콜백·REST 폴백 묶음·강제 갱신)이 예외로 끊겼다(총괄 v1.152 F 관측).</p>
 *
 * <p><b>전달 계약(v1.154 §2):</b> 처리기에 도달하는 시세 열은 종전과 같아야 한다. 1판(ec7d33ef)은 코인별 최신 값 하나로 덮어
 * 포화 거절이 없을 때도 대기 중 280→300 이면 손절선을 지난 280 이 사라졌다(총괄 탐침). 이 판은 <b>엔진 × 코인별 도착 순서 대기열</b>이다.
 * <ul>
 *   <li>시세는 도착 순서 그대로 쌓고, 덮거나 거르지 않는다(비가격·낡은 값·미래 관측의 판단은 처리 시각에 처리기의 기존 N02/N16 검사가 한다 —
 *       이 경계는 관측 시각을 비교하지 않으므로 원문 관측 시각은 그대로 처리기에 간다). 처리기의 가격 이력·급변 판정·throttle(평상 5초·급변 1초,
 *       처리 시각 기준)은 종전처럼 시세 하나하나에 적용된다.</li>
 *   <li>코인마다 처리 작업은 하나만 접수돼 있다 — 같은 코인은 순서대로(종전 비동기는 같은 코인 두 작업이 다른 작업자에서 동시에 돌 수 있었다),
 *       다른 코인은 서로 다른 작업이라 코인 A 가 행 잠금을 기다려도 작업자가 남아 있으면 코인 B 는 기다리지 않는다.</li>
 *   <li>접수 거절: 그 시세를 버리지 않는다 — 대기열에 남기고 <b>엔진별 거절 수·고정 사유</b>를 남긴다. 그 코인의 다음 시세 때, 그리고 대기열이
 *       비어 있지 않은 동안 1초마다({@link #retryPending}) 다시 접수한다. 발행 쪽으로 예외를 던지지 않으므로 다른 엔진·시세 장부·다른 코인 전달이
 *       끊기지 않는다.</li>
 *   <li>유계: 코인별 대기열은 {@link #LANE_CAPACITY} 건까지다. 넘는 새 시세는 버리고 <b>엔진별 초과 수·고정 사유</b>를 남긴다 — 구판 AbortPolicy 의
 *       손실과 같은 종류지만 그 엔진·그 코인에 갇히고 세어진다. 풀·실행기 대기열 크기는 바꾸지 않는다.</li>
 *   <li>처리기 예외는 처리기가 기존대로 로그로 남긴다(두 처리기 모두 본문을 잡는다). 잡히지 않고 올라온 예외는 이 코인 대기열의 그 시세 하나만
 *       실패로 세고 다음 시세를 계속 처리한다 — 전역으로 삼키지 않는다(다른 비동기 작업·거래소 복구 이벤트의 거절 계약은 그대로).</li>
 *   <li>호출 스레드에서 대신 처리하지 않는다(웹소켓 콜백 스레드를 DB 대기에 묶지 않는다 — 종전 비동기화의 이유).</li>
 * </ul>
 * 처리 내용·잠금 순서는 종전과 같다 — 같은 실행기 스레드에서 같은 처리기 메서드(프록시, {@code @Transactional})를 부른다.</p>
 *
 * <p>한계(새 전달 정책): 대기가 유효기간(120초)을 넘으면 처리기의 N02 검사가 그 시세를 버린다. 포화가 풀린 뒤 재접수까지 최대 1초. 처리 시도 수·대기 수는
 * 보호 완료 수가 아니다.</p>
 */
@Component
public class RealtimeProtectionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RealtimeProtectionDispatcher.class);

    /** 접수 거절의 고정 사유 — 로그·관측 값에 같은 문구를 쓴다(1판과 같은 문구). */
    public static final String REJECT_REASON = "실행기 포화 — 보호 점검 접수 거절, 코인별 최신 시세 대기 유지";
    /** 코인별 대기열 초과의 고정 사유. */
    public static final String OVERFLOW_REASON = "코인별 보호 대기열 초과 — 새 시세 버림";
    /** 엔진 × 코인 대기열 상한(실행기 대기열 100 과 같은 수 — 커지지 않는다). */
    public static final int LANE_CAPACITY = 100;

    public static final String LIVE = "LIVE";
    public static final String DYNAMIC = "DYNAMIC";

    private final Executor executor;
    private final Map<String, Engine> engines = new ConcurrentHashMap<>();

    public RealtimeProtectionDispatcher(@Qualifier("marketDataExecutor") Executor executor,
                                        @Lazy LiveTradingService liveTradingService,
                                        @Lazy DynamicTradingService dynamicTradingService) {
        this.executor = executor;
        engines.put(LIVE, new Engine(LIVE, liveTradingService::onRealtimePriceEvent));
        engines.put(DYNAMIC, new Engine(DYNAMIC, dynamicTradingService::onRealtimePriceEvent));
    }

    @EventListener
    public void onPrice(RealtimePriceEvent event) {
        if (event == null || event.getCoinCode() == null) return;
        for (Engine e : engines.values()) {
            e.offer(event);
        }
    }

    /** 대기열이 남은 코인만 다시 접수한다 — 비어 있으면 아무것도 하지 않는다. 접수 거절이면 거절 수가 늘고 대기열은 그대로다. */
    @Scheduled(fixedDelay = 1_000, initialDelay = 1_000)
    public void retryPending() {
        for (Engine e : engines.values()) {
            for (CoinLane l : e.lanes.values()) {
                if (l.size() > 0) l.schedule();
            }
        }
    }

    /** 관측 — 엔진별 누적 접수 거절·대기열 초과·지금 대기 코인 수·대기 시세 수·처리기 호출 수·처리기 예외 수. 처리 수는 보호 완료 수가 아니다. */
    public Snapshot snapshot(String engine) {
        Engine e = engines.get(engine);
        int coins = 0, events = 0;
        for (CoinLane l : e.lanes.values()) {
            int n = l.size();
            if (n > 0) { coins++; events += n; }
        }
        return new Snapshot(engine, e.rejected.get(), e.overflow.get(), coins, events, e.processed.get(), e.failures.get(),
                e.lastRejectReason, e.lastOverflowReason);
    }

    public record Snapshot(String engine, long rejected, long overflow, int pendingCoins, int pendingEvents, long processed,
                           long failures, String lastRejectReason, String lastOverflowReason) { }

    private final class Engine {
        final String name;
        final Consumer<RealtimePriceEvent> handler;
        final Map<String, CoinLane> lanes = new ConcurrentHashMap<>();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong overflow = new AtomicLong();
        final AtomicLong processed = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        volatile String lastRejectReason;
        volatile String lastOverflowReason;

        Engine(String name, Consumer<RealtimePriceEvent> handler) {
            this.name = name;
            this.handler = handler;
        }

        void offer(RealtimePriceEvent ev) {
            lanes.computeIfAbsent(ev.getCoinCode(), c -> new CoinLane(this, c)).offer(ev);
        }
    }

    private final class CoinLane {
        final Engine engine;
        final String coin;
        private final ArrayDeque<RealtimePriceEvent> queue = new ArrayDeque<>();
        final AtomicBoolean scheduled = new AtomicBoolean();

        CoinLane(Engine engine, String coin) {
            this.engine = engine;
            this.coin = coin;
        }

        synchronized int size() {
            return queue.size();
        }

        void offer(RealtimePriceEvent ev) {
            boolean accepted;
            synchronized (this) {
                accepted = queue.size() < LANE_CAPACITY;
                if (accepted) queue.addLast(ev);
            }
            if (!accepted) {
                long n = engine.overflow.incrementAndGet();
                engine.lastOverflowReason = OVERFLOW_REASON;
                if (n == 1 || n % 100 == 0) {
                    log.warn("[실시간 보호] {} {} (coin={}, 누적 초과 {})", engine.name, OVERFLOW_REASON, coin, n);
                }
            }
            schedule();
        }

        synchronized RealtimePriceEvent poll() {
            return queue.pollFirst();
        }

        void schedule() {
            if (!scheduled.compareAndSet(false, true)) return;
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException ex) {   // TaskRejectedException 도 이 하위 형이다
                scheduled.set(false);
                long n = engine.rejected.incrementAndGet();
                engine.lastRejectReason = REJECT_REASON;
                if (n == 1 || n % 100 == 0) {
                    log.warn("[실시간 보호] {} {} (coin={}, 대기 {}, 누적 거절 {})", engine.name, REJECT_REASON, coin, size(), n);
                }
            }
        }

        void drain() {
            try {
                RealtimePriceEvent ev;
                while ((ev = poll()) != null) {
                    try {
                        engine.handler.accept(ev);
                    } catch (RuntimeException ex) {
                        engine.failures.incrementAndGet();
                        log.error("[실시간 보호] {} 처리기 예외 — coin={}, price={}", engine.name, coin, ev.getPrice(), ex);
                    }
                    engine.processed.incrementAndGet();
                }
            } finally {
                scheduled.set(false);
            }
            // 비운 뒤·플래그를 내리기 전 사이에 들어온 시세 — 다시 접수한다(접수 거절이면 대기열에 남는다).
            if (size() > 0) schedule();
        }
    }
}
